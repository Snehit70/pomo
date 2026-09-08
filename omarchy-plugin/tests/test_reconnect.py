import os
import queue
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))

from pomo_link.client import PomoClient
from pomo_link.constants import CONNECT_RETRY_MAX
from pomo_link.queue import SessionQueue
from pomo_link.store import ConfigStore
from pomo_link.timer import TimerModel


class FakeWs:
    def __init__(self, fail=True):
        self.fail = fail
        self.connected = False
        self.sock = None
        self.sent = []

    def connect(self, *args, **kwargs):
        del args, kwargs
        if self.fail:
            raise TimeoutError("timed out")
        self.connected = True
        return True

    def send_text(self, text):
        self.sent.append(text)

    def try_send_text(self, text):
        self.sent.append(text)

    def close(self):
        self.connected = False

    def recv_ready(self, timeout):
        del timeout
        return False

    def try_send_ping(self):
        self.sent.append("ping")

    def read_texts(self):
        return []


class FakeRest:
    def __init__(self, code=200, body="{}"):
        self.code = code
        self.body = body

    def configure(self, host, port, token):
        del host, port, token

    def get_status(self, host=None, port=None, token=None, timeout=None):
        del host, port, token, timeout
        return self.code, self.body

    def request(self, method, path, body=None, timeout=None, host=None, port=None, token=None):
        del method, path, body, timeout, host, port, token
        return self.code, self.body

    def get_config(self):
        return 0, ""

    def post(self, path, body=None, timeout=None):
        del path, body, timeout
        return 0, ""


class DeterministicWorker:
    """Queue worker jobs and apply each result only when the test advances it."""

    def __init__(self):
        self.jobs = []
        self.results = queue.Queue()

    def submit(self, tag, func):
        self.jobs.append((tag, func))

    def run_next(self, client):
        tag, func = self.jobs.pop(0)
        try:
            result = func()
        except Exception as exc:
            result = exc
        client.apply_result(tag, result)
        return tag

    def stop(self):
        pass


class ReconnectTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = ConfigStore(self.tmp.name)
        self.store.set_pairing(host="10.0.0.1", port=9876, token="tok")
        self.model = TimerModel()
        self.queue = SessionQueue(os.path.join(self.tmp.name, "sessions.json"))
        self.ws = FakeWs(fail=True)
        self.rest = FakeRest()
        self.worker = DeterministicWorker()
        self.client = PomoClient(
            self.model,
            self.queue,
            self.store,
            rest=self.rest,
            ws=self.ws,
            worker=self.worker,
        )

    def tearDown(self):
        self.tmp.cleanup()

    def test_boot_ws_fail_stays_discovering_without_fake_heartbeat(self):
        self.client.mode = "DISCOVERING"
        self.client.probe_active = True
        self.client.ever_synced = False
        before = self.client.last_socket_contact_at
        ok = self.client.begin_websocket("discovery")
        self.assertTrue(ok)
        self.assertEqual([tag for tag, _func in self.worker.jobs], ["connect"])
        self.worker.run_next(self.client)
        self.assertEqual(self.client.mode, "DISCOVERING")
        self.assertEqual(self.client.last_socket_contact_at, before)
        self.assertGreater(self.client.retry_delay_s, 0)
        self.assertNotEqual(self.client.mode, "CONNECTING")

    def test_offline_ws_fail_returns_offline_keeps_local_owner(self):
        self.client.ever_synced = True
        self.client.set_mode("DISCOVERING")
        self.client.connect_failures = CONNECT_RETRY_MAX - 1
        self.model.set_local_owner(True)
        self.assertTrue(self.model.local_owner)
        before = self.client.last_socket_contact_at
        ok = self.client.begin_websocket("discovery")
        self.assertTrue(ok)
        self.worker.run_next(self.client)
        self.assertEqual(self.client.mode, "OFFLINE")
        self.assertTrue(self.model.local_owner)
        self.assertEqual(self.client.last_socket_contact_at, before)

    def test_successful_connect_enters_connecting_and_stamps_contact(self):
        self.ws.fail = False
        self.client.mode = "DISCOVERING"
        before = self.client.last_socket_contact_at
        ok = self.client.begin_websocket("discovery")
        self.assertTrue(ok)
        self.assertEqual(self.client.mode, "DISCOVERING")
        self.assertEqual(self.client.last_socket_contact_at, before)
        self.worker.run_next(self.client)
        self.assertEqual(self.client.mode, "CONNECTING")
        self.assertGreater(self.client.last_socket_contact_at, 0)
        self.assertTrue(self.ws.sent)

    def test_connecting_stale_with_local_clock_goes_offline(self):
        self.client.ever_synced = True
        self.client.enter_offline("stale socket")
        self.client.set_mode("CONNECTING")
        self.assertTrue(self.model.local_owner)
        self.client.last_socket_contact_at = time.monotonic() - 30
        self.client.tick_heartbeat()
        self.assertEqual(self.client.mode, "OFFLINE")
        self.assertTrue(self.model.local_owner)

    def test_soft_resync_keeps_local_owner(self):
        self.client.ever_synced = True
        self.client.enter_offline("stale socket")
        self.assertTrue(self.model.local_owner)
        self.rest.code = 200
        self.ws.fail = True
        self.assertTrue(self.client.soft_resync("reconnect connect stale"))
        self.assertEqual([tag for tag, _func in self.worker.jobs], ["soft_resync"])
        self.worker.run_next(self.client)
        self.assertTrue(self.model.local_owner)
        self.assertEqual(self.client.mode, "OFFLINE")
        self.assertEqual([tag for tag, _func in self.worker.jobs], ["connect"])
        self.worker.run_next(self.client)
        self.assertTrue(self.model.local_owner)
        self.assertEqual(self.client.mode, "OFFLINE")

    def test_soft_resync_phone_owned_clears_owner_and_reconnects(self):
        self.client.ever_synced = True
        self.client.set_mode("CONNECTING")
        self.assertFalse(self.model.local_owner)
        self.rest.code = 200
        self.ws.fail = False
        self.assertTrue(self.client.soft_resync("stale socket"))
        self.assertEqual([tag for tag, _func in self.worker.jobs], ["soft_resync"])
        self.worker.run_next(self.client)
        self.assertFalse(self.model.local_owner)
        self.assertEqual(self.client.mode, "CONNECTING")
        self.assertEqual([tag for tag, _func in self.worker.jobs], ["connect"])
        self.worker.run_next(self.client)
        self.assertFalse(self.model.local_owner)
        self.assertEqual(self.client.mode, "CONNECTING")
        self.assertGreater(self.client.last_socket_contact_at, 0)

    def test_gesture_transport_failure_recovers_to_local_clock(self):
        self.client.ever_synced = True
        self.client.set_mode("SYNCED")
        self.client.busy = True

        self.client._apply_gesture_result("toggle", (0, ""))

        self.assertFalse(self.client.busy)
        self.assertEqual([tag for tag, _func in self.worker.jobs], ["soft_resync"])

        self.rest.code = 0
        self.worker.run_next(self.client)

        self.assertEqual(self.client.mode, "OFFLINE")
        self.assertTrue(self.model.local_owner)


if __name__ == "__main__":
    unittest.main()
