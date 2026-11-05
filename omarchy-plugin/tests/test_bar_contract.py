"""Bar-facing behaviour: repeat clicks, failed clicks while reconnecting,
skip partials (ADR 0002), and the follower's freshness check."""

import json
import os
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))

from pomo_link.client import PomoClient
from pomo_link.main import Engine
from pomo_link.queue import SessionQueue
from pomo_link.store import ConfigStore
from pomo_link.timer import TimerModel
sys.path.insert(0, os.path.dirname(__file__))
from test_reconnect import DeterministicWorker, FakeRest, FakeWs


def _synced_engine(tmp):
    engine = Engine(directory=tmp, stdout_status=False)
    engine.client.host = "phone"
    engine.client.port = 9876
    engine.client.token = "token"
    engine.client.set_mode("SYNCED")
    return engine


class RepeatClickTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.engine = _synced_engine(self.tmp.name)
        self.sent = []

        def send(command):
            self.sent.append(command)
            self.engine.client.busy = True
            return True  # async: phone POST outstanding

        self.engine.client.send_gesture = send

    def tearDown(self):
        self.engine.client.worker.stop()
        self.tmp.cleanup()

    def test_second_toggle_while_first_in_flight_is_dropped(self):
        self.engine._handle_ipc_line('{"cmd":"toggle"}')
        self.engine.drain_pending_gesture()
        self.engine._handle_ipc_line('{"cmd":"toggle"}')
        self.assertEqual(self.engine.pending_ipc_gestures, [])
        self.engine.client.busy = False
        self.engine.drain_pending_gesture()
        self.assertEqual(self.sent, ["toggle"])

    def test_queued_repeats_collapse_but_distinct_commands_keep_order(self):
        self.engine.client.busy = True
        self.engine.inflight_gesture = "extend"
        for cmd in ("toggle", "toggle", "skip", "skip", "toggle"):
            self.engine._handle_ipc_line('{"cmd":"%s"}' % cmd)
        self.assertEqual(self.engine.pending_ipc_gestures, ["toggle", "skip", "toggle"])

    def test_same_command_after_reply_is_a_new_press(self):
        self.engine._handle_ipc_line('{"cmd":"toggle"}')
        self.engine.drain_pending_gesture()
        self.engine.client.busy = False  # phone answered
        self.engine._handle_ipc_line('{"cmd":"toggle"}')
        self.assertEqual(self.engine.pending_ipc_gestures, ["toggle"])


class FailedClickReplayTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        store = ConfigStore(self.tmp.name)
        store.set_pairing(host="10.0.0.1", port=9876, token="tok")
        self.model = TimerModel()
        self.model.apply_state("running", "work", 1200, 1500, 0, 12, start_time=1700000000)
        self.client = PomoClient(
            self.model,
            SessionQueue(os.path.join(self.tmp.name, "sessions.json")),
            store,
            rest=FakeRest(code=0, body=""),
            ws=FakeWs(fail=True),
            worker=DeterministicWorker(),
        )
        self.client.ever_synced = True

    def tearDown(self):
        self.tmp.cleanup()

    def test_click_failing_while_connecting_goes_local(self):
        self.client.mode = "CONNECTING"
        self.client.busy = True
        self.client.apply_result("toggle", (0, ""))
        self.assertEqual(self.client.mode, "OFFLINE")
        self.assertTrue(self.model.local_owner)
        self.assertEqual(self.client.replay_gesture, "toggle")
        self.assertFalse(self.client.busy)

    def test_click_failing_while_synced_resyncs_without_replay(self):
        self.client.mode = "SYNCED"
        self.client.apply_result("toggle", (0, ""))
        self.assertIsNone(self.client.replay_gesture)
        self.assertTrue(self.client.soft_resyncing)

    def test_engine_applies_replayed_click_locally(self):
        engine = Engine(directory=self.tmp.name, stdout_status=False)
        try:
            engine.client.set_mode("OFFLINE")
            self.assertEqual(engine.model.status, "stopped")
            engine.pending_ipc_gestures.append("skip")
            engine.client.replay_gesture = "toggle"
            engine._queue_replay_gesture()
            self.assertEqual(engine.pending_ipc_gestures, ["toggle", "skip"])
            engine.drain_pending_gesture()
            self.assertEqual(engine.model.status, "running")
        finally:
            engine.client.worker.stop()


class SkipPartialTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.engine = Engine(directory=self.tmp.name, stdout_status=False)
        self.engine.client.set_mode("OFFLINE")
        self.model = self.engine.model

    def tearDown(self):
        self.engine.client.worker.stop()
        self.tmp.cleanup()

    def _start_with_elapsed(self, elapsed):
        self.model.toggle()
        self.model.remaining = self.model.duration - elapsed
        self.model.arm_running_baseline()

    def test_skip_after_a_minute_queues_partial_without_block_credit(self):
        self._start_with_elapsed(600)
        start = self.model.start_time
        self.model.skip()
        rows = self.engine.queue.items
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["type"], "work")
        self.assertFalse(rows[0]["completed"])
        self.assertIn(rows[0]["duration"], (599, 600))
        self.assertEqual(rows[0]["start"], int(start))
        self.assertEqual(self.model.completed, 0)
        self.assertEqual(self.model.phase, "short")

    def test_short_skip_and_reset_record_nothing(self):
        self._start_with_elapsed(30)
        self.model.skip()
        self.model.skip()  # break -> work, never a partial
        self._start_with_elapsed(900)
        self.model.reset()
        self.assertEqual(self.engine.queue.items, [])

    def test_partial_survives_queue_reload(self):
        self._start_with_elapsed(300)
        self.model.skip()
        reloaded = SessionQueue(self.engine.store.sessions_path)
        self.assertFalse(reloaded.items[0]["completed"])
        with open(self.engine.store.sessions_path, encoding="utf-8") as handle:
            self.assertFalse(json.load(handle)["sessions"][0]["completed"])


class FollowerTest(unittest.TestCase):
    """Runs the real `pomo-link waybar` follower against a fake status file."""

    def _follow(self, runtime, state, seconds=0.8):
        env = dict(os.environ, XDG_STATE_HOME=state, XDG_RUNTIME_DIR=runtime)
        env.pop("POMO_LINK_STATUS", None)
        bin_path = os.path.join(os.path.dirname(__file__), "..", "bin", "pomo-link")
        proc = subprocess.Popen(
            [sys.executable, bin_path, "waybar"],
            env=env,
            stdout=subprocess.PIPE,
            text=True,
        )
        time.sleep(seconds)
        proc.terminate()
        out, _ = proc.communicate(timeout=5)
        return [json.loads(line) for line in out.splitlines() if line.strip()]

    def _status_file(self, state):
        directory = os.path.join(state, "pomo")
        os.makedirs(directory, exist_ok=True)
        return os.path.join(directory, "waybar.json")

    def test_fresh_status_file_renders_without_socket(self):
        with tempfile.TemporaryDirectory() as runtime, tempfile.TemporaryDirectory() as state:
            path = self._status_file(state)
            with open(path, "w", encoding="utf-8") as handle:
                json.dump({"mode": "OFFLINE", "status": "running", "phase": "work",
                           "remaining": 90, "has_token": True, "local_owner": True}, handle)
            lines = self._follow(runtime, state)
            self.assertEqual(lines[-1]["class"], "running work local")

    def test_status_file_left_by_dead_daemon_is_dead(self):
        with tempfile.TemporaryDirectory() as runtime, tempfile.TemporaryDirectory() as state:
            path = self._status_file(state)
            with open(path, "w", encoding="utf-8") as handle:
                json.dump({"mode": "SYNCED", "status": "running", "phase": "work",
                           "remaining": 90, "has_token": True}, handle)
            old = time.time() - 60
            os.utime(path, (old, old))
            lines = self._follow(runtime, state)
            self.assertEqual(lines[-1]["class"], "stopped work dead")


if __name__ == "__main__":
    unittest.main()
