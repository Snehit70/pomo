import os
import shutil
import socket
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))

from pomo_link.client import PomoClient
from pomo_link.queue import SessionQueue
from pomo_link.store import ConfigStore
from pomo_link.timer import TimerModel
from pomo_link.ws import Rfc6455Client, WebSocketError, _decode_frames

from test_stdin import FakeWorker, StubRest, run_pending_jobs


class FailingConnectWS:
    connected = False
    sock = None
    last_peer_activity_mono = 0.0

    def connect(self, *args, **kwargs):
        raise OSError("no network in test")

    def close(self):
        pass

    def send_text(self, text):
        pass

    def try_send_text(self, text):
        return self.send_text(text)

    def try_send_ping(self):
        pass


class PingStubWS:
    connected = True
    sock = object()

    def __init__(self):
        self.pings = 0
        self.last_peer_activity_mono = 0.0

    def send_ping(self):
        self.pings += 1

    def try_send_ping(self):
        return self.send_ping()

    def try_send_text(self, text):
        pass

    def recv_ready(self, timeout=0.0):
        return False


def unmasked_frame(opcode, fin, payload):
    b0 = (0x80 if fin else 0) | opcode
    return bytes([b0, len(payload)]) + payload


def make_client(directory):
    store = ConfigStore(directory)
    model = TimerModel()
    return PomoClient(model, SessionQueue(store.sessions_path), store, rest=StubRest())


class FailedConnectTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="pomo-ws-")
        self.client = make_client(self.dir)
        self.client.worker = FakeWorker()
        self.client.ws = FailingConnectWS()
        self.client.host = "h"
        self.client.port = 9876
        self.client.token = "t"
        # These cases exercise reconnect behavior after the initial boot
        # probe. Boot failures intentionally return to DISCOVERING instead.
        self.client.ever_synced = True
        self.client.probe_active = False

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_failed_open_does_not_stamp_contact(self):
        self.assertTrue(self.client.begin_websocket("test"))
        run_pending_jobs(self.client)
        self.assertEqual(self.client.mode, "CONNECTING")
        self.assertEqual(self.client.last_socket_contact_at, 0.0)

    def test_failed_open_schedules_short_retry(self):
        self.client.begin_websocket("test")
        run_pending_jobs(self.client)
        self.assertEqual(self.client.retry_delay_s, 5.0)
        self.assertGreater(self.client.retry_started_at, 0)

    def test_connect_retry_timer_fires(self):
        self.client.begin_websocket("test")
        run_pending_jobs(self.client)
        self.client.retry_started_at -= 10.0  # timer long elapsed
        calls = []
        self.client.begin_websocket = lambda reason: calls.append(reason) or False
        self.client.tick_connect_retry()
        self.assertEqual(calls, ["connect retry"])
        self.assertEqual(self.client.retry_delay_s, 0)

    def test_repeated_failures_reach_offline(self):
        self.client.begin_websocket("test")  # failure 1, retry scheduled
        run_pending_jobs(self.client)
        for _ in range(2):  # retries 2 and 3; third failure -> OFFLINE
            self.client.retry_started_at -= 10.0
            self.client.tick_connect_retry()
            run_pending_jobs(self.client)
        self.assertEqual(self.client.mode, "OFFLINE")

    def test_hello_sent_on_success(self):
        class OkWS:
            def __init__(self):
                self.connected = False
                self.sent = []

            def connect(self, *args, **kwargs):
                self.connected = True
                return True

            def send_text(self, text):
                self.sent.append(text)

            def try_send_text(self, text):
                return self.send_text(text)

            def try_send_ping(self):
                pass

            def close(self):
                pass

        self.client.ws = OkWS()
        self.client.begin_websocket("test")
        run_pending_jobs(self.client)
        self.assertEqual(self.client.mode, "CONNECTING")
        self.assertTrue(any("hello" in text for text in self.client.ws.sent))
        self.assertGreater(self.client.last_socket_contact_at, 0)
        self.assertEqual(self.client.connect_failures, 0)


class PingTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="pomo-ping-")
        self.client = make_client(self.dir)
        self.client.ws = PingStubWS()
        self.client.set_mode("SYNCED")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_ping_sent_and_throttled(self):
        self.client.tick_ws_ping()
        self.assertEqual(self.client.ws.pings, 1)
        self.client.tick_ws_ping()
        self.assertEqual(self.client.ws.pings, 1, "second ping within interval must throttle")
        self.client.last_ping_at -= 11.0
        self.client.tick_ws_ping()
        self.assertEqual(self.client.ws.pings, 2)

    def test_ping_not_sent_without_connected_socket(self):
        self.client.ws.connected = False
        self.client.tick_ws_ping()
        self.assertEqual(self.client.ws.pings, 0)

    def test_ping_send_failure_treated_as_disconnect(self):
        events = []

        def boom():
            raise WebSocketError("send timeout")

        self.client.ws.send_ping = boom
        self.client.ws.try_send_ping = boom
        self.client.on_websocket_disconnected = lambda: events.append("dc")
        self.client.tick_ws_ping()
        self.assertEqual(events, ["dc"])


class ContactTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="pomo-contact-")
        self.client = make_client(self.dir)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_idle_stale_with_fresh_pong_does_not_resync(self):
        now = time.monotonic()
        self.client.ws = PingStubWS()
        self.client.ws.last_peer_activity_mono = now
        self.client.set_mode("SYNCED")
        self.client.last_socket_contact_at = now - 100.0
        calls = []
        self.client.soft_resync = lambda reason: calls.append(reason) or True
        self.client.refresh_socket_contact()
        self.client.tick_heartbeat()
        self.assertEqual(calls, [])
        self.assertEqual(self.client.last_socket_contact_at, now)

    def test_idle_stale_without_activity_resyncs(self):
        now = time.monotonic()
        self.client.ws = PingStubWS()
        self.client.set_mode("SYNCED")
        self.client.last_socket_contact_at = now - 100.0
        calls = []
        self.client.soft_resync = lambda reason: calls.append(reason) or True
        self.client.refresh_socket_contact()
        self.client.tick_heartbeat()
        self.assertEqual(calls, ["stale socket"])


class FragmentTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="pomo-frag-")
        self.client = Rfc6455Client()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_fragmented_message_reassembles(self):
        buf = (
            unmasked_frame(0x1, False, b'{"a":')
            + unmasked_frame(0x0, False, b'12')
            + unmasked_frame(0x0, True, b'3}')
        )
        frames, rest, pings, pong, close = _decode_frames(bytearray(buf))
        self.assertFalse(close)
        texts = self.client._assemble(frames)
        self.assertEqual(texts, ['{"a":123}'])

    def test_fragmented_then_complete_message(self):
        buf = (
            unmasked_frame(0x1, False, b'{"a":')
            + unmasked_frame(0x0, True, b'1}')
            + unmasked_frame(0x1, True, b'{"b":2}')
        )
        frames, rest, pings, pong, close = _decode_frames(bytearray(buf))
        texts = self.client._assemble(frames)
        self.assertEqual(texts, ['{"a":1}', '{"b":2}'])

    def test_stray_continuation_dropped(self):
        self.assertEqual(self.client._assemble([(0x0, True, b"x")]), [])

    def test_pong_tracked_as_activity(self):
        buf = unmasked_frame(0xA, True, b"")
        frames, rest, pings, pong, close = _decode_frames(bytearray(buf))
        self.assertTrue(pong)
        self.assertEqual(frames, [])

    def test_ping_interleaved_between_fragments(self):
        buf = (
            unmasked_frame(0x1, False, b'{"a":')
            + unmasked_frame(0x9, True, b"keepalive")
            + unmasked_frame(0x0, True, b'1}')
        )
        frames, rest, pings, pong, close = _decode_frames(bytearray(buf))
        self.assertEqual(pings, [b"keepalive"])
        texts = self.client._assemble(frames)
        self.assertEqual(texts, ['{"a":1}'])


class SendTimeoutTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="pomo-send-")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_send_timeout_tears_down_socket(self):
        class TimeoutSock:
            def settimeout(self, value):
                self.timeout = value

            def send(self, data):
                raise socket.timeout("stalled peer")

            def close(self):
                self.closed = True

        rc = Rfc6455Client()
        sock = TimeoutSock()
        rc.sock = sock
        rc.connected = True
        with self.assertRaises(WebSocketError):
            rc.send_text("hello")
        self.assertFalse(rc.connected)
        self.assertIsNone(rc.sock)

    def test_send_would_block_maps_to_web_socket_error(self):
        class WouldBlockSock:
            def settimeout(self, value):
                pass

            def send(self, data):
                raise BlockingIOError("would block")

            def fileno(self):
                return -1

            def close(self):
                pass

        rc = Rfc6455Client()
        rc.sock = WouldBlockSock()
        rc.connected = True
        with self.assertRaises(WebSocketError):
            rc.send_text("hello")

    def test_send_oserror_tears_down_socket(self):
        class DeadSock:
            def settimeout(self, value):
                pass

            def send(self, data):
                raise OSError("broken pipe")

            def close(self):
                pass

        rc = Rfc6455Client()
        rc.sock = DeadSock()
        rc.connected = True
        with self.assertRaises(WebSocketError):
            rc.send_text("hello")
        self.assertFalse(rc.connected)

    def test_pong_failure_is_swallowed(self):
        class DeadSock:
            def settimeout(self, value):
                pass

            def send(self, data):
                raise OSError("broken pipe")

            def close(self):
                pass

        rc = Rfc6455Client()
        rc.sock = DeadSock()
        rc.connected = True
        rc.send_pong(b"x")
        self.assertFalse(rc.connected)

    def test_teardown_resets_peer_activity(self):
        class DeadSock:
            def settimeout(self, value):
                pass

            def send(self, data):
                raise OSError("broken pipe")

            def close(self):
                pass

        rc = Rfc6455Client()
        rc.sock = DeadSock()
        rc.connected = True
        rc.last_peer_activity_mono = 123.0
        with self.assertRaises(WebSocketError):
            rc.send_text("hello")
        self.assertEqual(rc.last_peer_activity_mono, 0.0)
        rc.close()
        self.assertEqual(rc.last_peer_activity_mono, 0.0)

    def test_failed_pong_during_read_raises_immediately(self):
        rc = Rfc6455Client()
        server_sock, client_sock = socket.socketpair()
        rc.sock = server_sock
        rc.connected = True

        # Server pings us; the pong send path is dead (as after a teardown),
        # so read_texts must raise now instead of returning texts and letting
        # the 20s stale watchdog reconnect.
        client_sock.sendall(unmasked_frame(0x9, True, b"ping"))

        def dead_pong(payload=b""):
            rc.connected = False
            rc.sock = None

        rc.send_pong = dead_pong
        try:
            with self.assertRaises(WebSocketError):
                rc.read_texts()
        finally:
            server_sock.close()
            client_sock.close()
        self.assertFalse(rc.connected)


if __name__ == "__main__":
    unittest.main()
