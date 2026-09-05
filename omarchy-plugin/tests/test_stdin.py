import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))

from pomo_link.main import Engine


class FakeStdin:
    def __init__(self, fd):
        self._fd = fd
        self.closed = False

    def fileno(self):
        return self._fd

    def close(self):
        self.closed = True


class StubWS:
    connected = False
    sock = None

    def __init__(self):
        # Instance-level so would-block tests do not leak across engines.
        self.connected = False
        self.sock = None
        self.sent = []
        self.would_block_on_send = False

    def connect(self, *args, **kwargs):
        raise OSError("no network in test")

    def close(self):
        pass

    def send_text(self, text):
        self.sent.append(text)

    def try_send_text(self, text):
        # Tick-path hello uses the non-blocking send.
        from pomo_link.ws import WebSocketError

        if self.would_block_on_send:
            raise WebSocketError("send would block")
        self.sent.append(text)

    def try_send_ping(self):
        from pomo_link.ws import WebSocketError

        if self.would_block_on_send:
            raise WebSocketError("send would block")
        self.sent.append("ping")

    def send_ping(self):
        self.try_send_ping()

    def recv_ready(self, timeout=0.0):
        return False


class ConnectedStubWS(StubWS):
    def __init__(self):
        super().__init__()
        self.connected = False
        self.sock = None
        self.close_calls = 0

    def connect(self, *args, **kwargs):
        self.connected = True
        self.sock = object()
        return True

    def close(self):
        self.close_calls += 1
        self.connected = False
        self.sock = None


class StubRest:
    def __init__(self, results=None):
        self.results = list(results or [])
        self.calls = []

    def configure(self, *args):
        pass

    def request(self, method, path, **kwargs):
        self.calls.append((method, path))
        if self.results:
            return self.results.pop(0)
        return 0, ""

    def get_status(self, **kwargs):
        return self.request("GET", "/api/status")

    def get_config(self):
        return self.request("GET", "/api/config")

    def post(self, path, body=None, **kwargs):
        return self.request("POST", path)


class StdinDrainTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="pomo-stdin-")
        self._orig_stdin = sys.stdin
        self.read_fd, self.write_fd = os.pipe()
        sys.stdin = FakeStdin(self.read_fd)
        self.engines = []

    def tearDown(self):
        # Engine.__init__ starts a real RestWorker thread; every engine a
        # test created must be stopped or the suite leaks one per test.
        for engine in self.engines:
            engine.client.worker.stop()
        sys.stdin = self._orig_stdin
        os.close(self.read_fd)
        if self.write_fd is not None:
            os.close(self.write_fd)
        shutil.rmtree(self.dir, ignore_errors=True)

    def _engine(self):
        engine = Engine(directory=self.dir)
        self.engines.append(engine)
        return engine

    def test_two_lines_in_one_read_both_process(self):
        engine = self._engine()
        seen = []
        engine.handle_line = seen.append
        os.write(self.write_fd, b'{"cmd":"ping"}\n{"cmd":"ping"}\n')
        engine._drain_stdin()
        self.assertEqual(len(seen), 2)

    def test_partial_line_then_rest(self):
        engine = self._engine()
        os.write(self.write_fd, b'{"cmd":"tog')
        engine._drain_stdin()
        self.assertIsNone(engine.pending_gesture)
        os.write(self.write_fd, b'gle"}\n')
        engine._drain_stdin()
        self.assertEqual(engine.pending_gesture, "toggle")

    def test_eof_stops_engine(self):
        engine = self._engine()
        engine.handle_line = lambda line: self.fail("no line expected")
        os.close(self.write_fd)
        self.write_fd = None
        engine._drain_stdin()
        self.assertFalse(engine.running)

    def test_multiple_reads_keep_remainder_intact(self):
        engine = self._engine()
        seen = []
        engine.handle_line = seen.append
        os.write(self.write_fd, b'{"cmd":"a"}\n{"cmd":"b"}\n{"cmd":"c"}\n{"cmd":"d')
        engine._drain_stdin()
        os.write(self.write_fd, b'"}\n{"cmd":"e"}\n')
        engine._drain_stdin()
        self.assertEqual(
            [line for line in seen if line.strip()],
            ['{"cmd":"a"}', '{"cmd":"b"}', '{"cmd":"c"}', '{"cmd":"d"}', '{"cmd":"e"}'],
        )


class GestureQueueTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="pomo-gesture-")
        self.engine = Engine(directory=self.dir)
        self.engine.client.ws = StubWS()

    def tearDown(self):
        self.engine.client.worker.stop()
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_three_toggles_coalesce_to_one(self):
        # Fresh boot owns the local clock, so force the neither-owned hold to
        # exercise stdin last-wins coalescing (boot gestures run locally).
        self.engine.model.set_local_owner(False)
        for _ in range(3):
            self.engine.handle_line('{"cmd":"toggle"}')
        self.assertEqual(self.engine.pending_gesture, "toggle")
        applied = []
        self.engine.client.send_gesture = applied.append
        self.engine.client.host = "h"
        self.engine.client.port = 9876
        self.engine.client.token = "t"
        self.engine.client.set_mode("SYNCED")
        self.engine.drain_pending_gesture()
        self.assertEqual(applied, ["toggle"])
        self.assertIsNone(self.engine.pending_gesture)

    def test_gesture_held_while_busy(self):
        # Hold via busy: queue while the phone POST is in flight.
        self.engine.client.busy = True
        self.engine.handle_line('{"cmd":"skip"}')
        applied = []
        self.engine.client.send_gesture = applied.append
        self.engine.client.host = "h"
        self.engine.client.set_mode("SYNCED")
        self.engine.drain_pending_gesture()
        self.assertEqual(applied, [])
        self.assertEqual(self.engine.pending_gesture, "skip")
        self.engine.client.busy = False
        self.engine.drain_pending_gesture()
        self.assertEqual(applied, ["skip"])

    def test_boot_gesture_executes_locally_no_waiting_hint(self):
        # Wave-1 boot-local: fresh boot owns the clock, so a toggle runs
        # immediately instead of parking on "waiting to connect".
        engine = self.engine
        self.assertTrue(engine.model.local_owner)
        self.assertIsNone(engine.pending_gesture)
        engine.handle_line('{"cmd":"toggle"}')
        self.assertIsNone(engine.pending_gesture)
        self.assertEqual(engine.model.status, "running")
        self.assertNotEqual(engine.client.message, "waiting to connect")
        self.assertFalse(engine.client.busy)

    def test_neither_owned_gesture_held_then_applies_offline(self):
        # Residual neither-owned hold (e.g. restored live snapshot before
        # OFFLINE flips ownership): parks with the bounded waiting hint.
        engine = self.engine
        engine.model.set_local_owner(False)
        self.assertIsNone(engine.pending_gesture)
        engine.handle_line('{"cmd":"toggle"}')
        self.assertEqual(engine.client.message, "waiting to connect")
        self.engine.drain_pending_gesture()
        self.assertEqual(engine.pending_gesture, "toggle")
        engine.client.enter_offline("test")
        self.assertTrue(engine.model.local_owner)
        engine.drain_pending_gesture()
        self.assertIsNone(engine.pending_gesture)
        self.assertEqual(engine.model.status, "running")
        self.assertNotEqual(engine.client.message, "waiting to connect")

    def test_local_path_gesture_clears_busy_and_second_gesture_applies(self):
        engine = self.engine
        engine.handle_line('{"cmd":"toggle"}')
        engine.client.enter_offline("test")
        engine.drain_pending_gesture()
        self.assertFalse(engine.client.busy)
        self.assertEqual(engine.model.status, "running")
        engine.handle_line('{"cmd":"toggle"}')
        engine.drain_pending_gesture()
        self.assertFalse(engine.client.busy)
        self.assertEqual(engine.model.status, "paused")
        self.assertIsNone(engine.pending_gesture)

    def test_replaced_gesture_wins(self):
        engine = self.engine
        # Force the hold so last-wins is observable (boot runs locally).
        engine.model.set_local_owner(False)
        engine.handle_line('{"cmd":"toggle"}')
        engine.handle_line('{"cmd":"reset"}')
        self.assertEqual(engine.pending_gesture, "reset")

    def test_busy_exposed_in_status_payload(self):
        self.assertFalse(self.engine.status_payload()["busy"])
        self.engine.client.busy = True
        self.assertTrue(self.engine.status_payload()["busy"])


class FakeWorker:
    """Deterministic stand-in for RestWorker: jobs queue up; the test runs
    them and feeds results back explicitly."""

    def __init__(self):
        self.jobs = []
        self.results = __import__("queue").Queue()

    def submit(self, tag, func):
        self.jobs.append((tag, func))

    def stop(self):
        pass


def run_pending_jobs(client, count=1):
    """Execute up to `count` queued jobs synchronously and apply results."""
    ran = 0
    while client.worker.jobs and ran < count:
        tag, func = client.worker.jobs.pop(0)
        try:
            result = func()
        except Exception as exc:
            result = exc
        client.apply_result(tag, result)
        ran += 1
    return ran


class WorkerPipelineTest(StdinDrainTest):
    def test_loop_advances_boot_and_queues_connect(self):
        engine = self._engine()
        engine.client.worker.stop()
        engine.client.worker = FakeWorker()
        engine.client.ws = StubWS()
        engine.client.host = "phone"
        engine.client.port = 9876
        engine.client.token = "token"
        # A configured host takes the deterministic pinned-host path instead
        # of submitting an mDNS job.
        engine.client.store.host = "phone"
        engine.client.store.port = 9876

        # Keep select's normal 0.2s timeout in use, but make both iterations
        # immediately readable through the real stdin pipe.
        os.write(self.write_fd, b"\n")
        engine._loop_once()
        self.assertEqual(engine.client.mode, "DISCOVERING")
        self.assertEqual(
            [tag for tag, _func in engine.client.worker.jobs],
            ["connect"],
        )

        os.write(self.write_fd, b"\n")
        engine._loop_once()
        # The second tick sees the handshake already in flight and must not
        # submit a duplicate connect job.
        self.assertEqual(
            [tag for tag, _func in engine.client.worker.jobs],
            ["connect"],
        )

        # Exercise the same worker handoff without touching the network.
        self.assertEqual(run_pending_jobs(engine.client), 1)


class LateConnectResultTest(StdinDrainTest):
    def test_discarded_connect_result_closes_socket(self):
        for mode in ("OFFLINE", "UNPAIRED"):
            with self.subTest(mode=mode):
                engine = self._engine()
                client = engine.client
                client.worker.stop()
                client.worker = FakeWorker()
                client.ws = ConnectedStubWS()
                client.host = "phone"
                client.port = 9876
                client.token = "token"

                self.assertTrue(client.begin_websocket("test"))
                tag, job = client.worker.jobs.pop(0)
                result = job()
                self.assertTrue(client.ws.connected)
                close_calls_before_result = client.ws.close_calls

                client.set_mode(mode)
                client.apply_result(tag, result)

                self.assertEqual(client.mode, mode)
                self.assertFalse(client.ws.connected)
                self.assertIsNone(client.ws.sock)
                self.assertEqual(client.ws.close_calls, close_calls_before_result + 1)


class GestureFailureTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="pomo-fail-")
        self.engine = Engine(directory=self.dir)
        self.engine.client.ws = StubWS()
        self.engine.client.rest = StubRest()
        self.engine.client.worker.stop()
        self.engine.client.worker = FakeWorker()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def _synced(self, rest=None):
        engine = self.engine
        engine.client.host = "h"
        engine.client.token = "t"
        if rest is not None:
            engine.client.rest = rest
        engine.client.set_mode("SYNCED")

    def test_unreachable_phone_gesture_emits_error(self):
        self._synced()
        engine = self.engine
        engine.client.send_gesture("toggle")
        self.assertTrue(engine.client.busy)
        run_pending_jobs(engine.client)
        self.assertFalse(engine.client.busy)
        errors = engine.client.drain_errors()
        self.assertEqual(len(errors), 1)
        self.assertIn("unreachable", errors[0])

    def test_unreachable_gesture_while_synced_soft_resyncs(self):
        self._synced()
        engine = self.engine
        engine.client.send_gesture("toggle")
        run_pending_jobs(engine.client)
        # Fast-offline: never dead-SYNCED; a reachability probe is in flight
        # with busy cleared and a concise error surfaced.
        self.assertFalse(engine.client.busy)
        self.assertEqual(engine.client.mode, "SYNCED")
        self.assertEqual([tag for tag, _func in engine.client.worker.jobs], ["soft_resync"])

    def test_unreachable_gesture_when_clearly_unreachable_goes_offline(self):
        engine = self.engine
        engine.client.host = "h"
        engine.client.token = "t"
        engine.client.set_mode("DISCOVERING")
        engine.client.model.set_local_owner(False)
        engine.client.busy = True
        engine.client._apply_gesture_result("toggle", (0, ""))
        self.assertFalse(engine.client.busy)
        self.assertEqual(engine.client.mode, "OFFLINE")
        self.assertTrue(engine.model.local_owner)
        errors = engine.client.drain_errors()
        self.assertEqual(len(errors), 1)
        self.assertIn("unreachable", errors[0])

    def test_gesture_401_goes_unpaired_keep_local_owner(self):
        self._synced()
        engine = self.engine
        engine.client.rest = StubRest(results=[(401, "")])
        engine.client.send_gesture("toggle")
        run_pending_jobs(engine.client)
        self.assertFalse(engine.client.busy)
        self.assertEqual(engine.client.mode, "UNPAIRED")
        self.assertTrue(engine.model.local_owner)

    def test_gesture_other_http_logs_only(self):
        self._synced(rest=StubRest(results=[(500, "oops")]))
        engine = self.engine
        engine.client.send_gesture("toggle")
        run_pending_jobs(engine.client)
        self.assertFalse(engine.client.busy)
        self.assertEqual(engine.client.mode, "SYNCED")
        errors = engine.client.drain_errors()
        self.assertEqual(len(errors), 1)
        self.assertIn("http 500", errors[0])

    def test_success_without_state_schedules_resync(self):
        self._synced(rest=StubRest(results=[(200, '{"success": true}')]))
        engine = self.engine
        engine.client.send_gesture("toggle")
        run_pending_jobs(engine.client)
        self.assertTrue(engine.client.resync_after_command)
        engine.client.tick()
        self.assertFalse(engine.client.resync_after_command)

    def test_failed_gesture_emits_ndjson_error_event(self):
        import io
        import pomo_link.main as main_mod

        self._synced()
        engine = self.engine
        engine.handle_line('{"cmd":"toggle"}')
        captured = io.StringIO()
        orig_stdout = sys.stdout
        main_mod._last_error_message = ""
        main_mod._last_error_at = 0.0
        try:
            sys.stdout = captured
            engine.drain_pending_gesture()
            run_pending_jobs(engine.client)
            for msg in engine.client.drain_errors():
                main_mod._emit_error(msg)
        finally:
            sys.stdout = orig_stdout
        self.assertIn('"type":"error"', captured.getvalue())
        self.assertIn("unreachable", captured.getvalue())
        self.assertIsNone(engine.pending_gesture)
        # Fast-offline: the failed toggle also kicked the reachability probe
        # instead of leaving a dead SYNCED behind.
        self.assertEqual([tag for tag, _func in engine.client.worker.jobs], ["soft_resync"])

    def test_busy_true_emitted_before_slow_post(self):
        import io
        import json as json_mod
        import time as _t

        class SlowThenOkRest(StubRest):
            def request(self, method, path, **kwargs):
                self.calls.append((method, path))
                _t.sleep(0.05)
                return 200, '{"success": true, "state": {"status": "running", "phase": "work", "remaining": 100.0, "duration": 1500.0, "completed": 0, "daily_goal": 8, "start_time": 1.0}}'

        self._synced(rest=SlowThenOkRest())
        engine = self.engine
        engine.handle_line('{"cmd":"toggle"}')
        captured = io.StringIO()
        orig_stdout = sys.stdout
        try:
            sys.stdout = captured
            engine.drain_pending_gesture()
            run_pending_jobs(engine.client)
            engine.emit_status(force=True)
        finally:
            sys.stdout = orig_stdout
        payload = [
            json_mod.loads(line)
            for line in captured.getvalue().splitlines()
            if line.strip().startswith("{")
        ]
        busy_flags = [p.get("busy") for p in payload if p.get("type") == "status"]
        self.assertIn(True, busy_flags)
        self.assertEqual(busy_flags[-1], False)


class PairingNoopTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="pomo-pair-")
        self.engine = Engine(directory=self.dir)
        self.engine.client.ws = StubWS()

    def tearDown(self):
        self.engine.client.worker.stop()
        shutil.rmtree(self.dir, ignore_errors=True)

    def _pair(self):
        self.engine.client.apply_pairing({"host": "h", "port": 9876, "token": "tok"})

    def test_identical_pairing_is_noop(self):
        engine = self.engine
        self._pair()
        engine.client.set_mode("CONNECTING")
        calls = []
        engine.client.begin_websocket = lambda reason: calls.append(reason) or True
        result = engine.client.apply_pairing({"host": "h", "port": 9876, "token": "tok"})
        self.assertFalse(result)
        self.assertEqual(calls, [])

    def test_changed_pairing_reconnects(self):
        engine = self.engine
        self._pair()
        engine.client.set_mode("CONNECTING")
        calls = []
        engine.client.begin_websocket = lambda reason: calls.append(reason) or True
        result = engine.client.apply_pairing({"host": "h", "port": 9876, "token": "tok2"})
        self.assertTrue(result)
        self.assertEqual(calls, ["pairing changed"])


if __name__ == "__main__":
    unittest.main()
