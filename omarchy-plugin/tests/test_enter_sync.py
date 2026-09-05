import json
import os
import shutil
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))

import pomo_link.client as client_module
from pomo_link.adopt import can_adopt
from pomo_link.main import Engine

from test_stdin import FakeWorker, StubRest, StubWS, run_pending_jobs


def state_frame(server_time, remaining, start, status="running", phase="work", duration=1500.0):
    return json.dumps(
        {
            "type": "state",
            "data": {
                "status": status,
                "phase": phase,
                "remaining": remaining,
                "duration": duration,
                "completed": 0,
                "daily_goal": 8,
                "start_time": start,
                "server_time": server_time,
            },
        }
    )


class EnterSyncBase(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="pomo-sync-")
        self.engine = Engine(directory=self.dir)
        self.client = self.engine.client
        self.client.ws = StubWS()
        self.client.rest = StubRest()
        self.client.worker = FakeWorker()
        self.client.host = "h"
        self.client.port = 9876
        self.client.token = "t"

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def connect_ready(self):
        self.client.set_mode("CONNECTING")


class NewestFrameWinsTest(EnterSyncBase):
    def test_newer_state_frame_during_import_is_snap_target(self):
        self.client.rest = StubRest(results=[(200, '{"accepted": ["id1"], "rejected": []}')])
        self.client.queue.enqueue("id1", "work", 60, int(time.time()) - 60, "")
        now = int(time.time())
        self.connect_ready()
        self.client.on_websocket_text(state_frame(now - 5, 100.0, 1111.0))
        self.assertTrue(self.client.entering_sync)
        self.assertEqual(len(self.client.worker.jobs), 1, "import job queued")
        # A fresher frame arrives while the import is "in flight".
        self.client.on_websocket_text(state_frame(now - 3, 90.0, 2222.0))
        run_pending_jobs(self.client)  # import ok -> desk idle -> snap
        self.assertEqual(self.client.mode, "SYNCED")
        self.assertEqual(self.client.model.start_time, 2222.0)
        self.assertFalse(self.client.entering_sync)

    def test_older_duplicate_frame_does_not_replace_pending(self):
        self.client.rest = StubRest(results=[(200, '{"accepted": ["id1"], "rejected": []}')])
        self.client.queue.enqueue("id1", "work", 60, int(time.time()) - 60, "")
        now = int(time.time())
        self.connect_ready()
        self.client.on_websocket_text(state_frame(now - 3, 90.0, 2222.0))
        self.client.on_websocket_text(state_frame(now - 5, 100.0, 1111.0))
        run_pending_jobs(self.client)
        self.assertEqual(self.client.model.start_time, 2222.0)


class ImportRetryTest(EnterSyncBase):
    def test_import_fails_three_times_then_snaps_and_syncs(self):
        self.client.queue.enqueue("id1", "work", 60, int(time.time()) - 60, "")
        now = int(time.time())
        self.connect_ready()
        self.client.on_websocket_text(state_frame(now - 3, 90.0, 2222.0))
        for _ in range(3):
            run_pending_jobs(self.client)
            if self.client.import_retry_at:
                self.client.import_retry_at -= 10.0
                self.client.tick_enter_sync()
        self.assertEqual(self.client.mode, "SYNCED")
        self.assertTrue(any("syncing anyway" in e for e in self.client.error_lines))
        self.assertEqual(self.client.queue.count(), 1, "rows retained for next reconnect")

    def test_import_success_drops_rows(self):
        self.client.rest = StubRest(results=[(200, '{"accepted": ["id1"], "rejected": []}')])
        self.client.queue.enqueue("id1", "work", 60, int(time.time()) - 60, "")
        now = int(time.time())
        self.connect_ready()
        self.client.on_websocket_text(state_frame(now - 3, 90.0, 2222.0))
        run_pending_jobs(self.client)
        self.assertEqual(self.client.mode, "SYNCED")
        self.assertTrue(self.client.queue.empty())


class AdoptPipelineTest(EnterSyncBase):
    def _desk_live(self):
        self.client.enter_offline("test")
        self.client.model.set_config(45, 5, 15, 4, 8)
        self.client.model.toggle()
        self.client.model.set_start_time(1710000000.0)

    def test_desk_idle_snaps_latest_pending(self):
        now = int(time.time())
        self.connect_ready()
        self.client.on_websocket_text(state_frame(now - 3, 90.0, 2222.0))
        run_pending_jobs(self.client)
        self.assertEqual(self.client.mode, "SYNCED")
        self.assertEqual(self.client.model.start_time, 2222.0)

    def test_adopt_409_applies_phone_state_without_pending_snap(self):
        self._desk_live()
        now = int(time.time())
        phone_state = {
            "status": "running", "phase": "work", "remaining": 500.0,
            "duration": 1500.0, "completed": 3, "daily_goal": 8,
            "start_time": 9999.0, "server_time": now,
        }
        self.client.rest = StubRest(results=[(409, json.dumps({"state": phone_state}))])
        self.connect_ready()
        self.client.on_websocket_text(state_frame(now - 3, 90.0, 2222.0))
        # queue empty -> import skipped -> adopt job queued
        self.assertEqual(len(self.client.worker.jobs), 1)
        self.assertEqual(self.client.worker.jobs[0][0], "adopt")
        run_pending_jobs(self.client)
        self.assertEqual(self.client.mode, "SYNCED")
        self.assertEqual(self.client.model.start_time, 9999.0)
        self.assertEqual(self.client.model.remaining, 500.0)
        self.assertEqual(self.client.model.completed, 3)

    def test_adopt_transport_fail_phone_stopped_goes_offline(self):
        self._desk_live()
        now = int(time.time())
        self.connect_ready()
        self.client.on_websocket_text(state_frame(now - 3, 90.0, 2222.0, status="stopped"))
        self.client.rest = StubRest(results=[(0, "")])
        run_pending_jobs(self.client)
        self.assertEqual(self.client.mode, "OFFLINE")

    def test_adopt_transport_fail_phone_active_snaps(self):
        self._desk_live()
        now = int(time.time())
        self.connect_ready()
        self.client.on_websocket_text(state_frame(now - 3, 90.0, 2222.0))
        self.client.rest = StubRest(results=[(0, "")])
        run_pending_jobs(self.client)
        self.assertEqual(self.client.mode, "SYNCED")
        self.assertEqual(self.client.model.start_time, 2222.0)


class AdoptFocusPrecedenceTest(EnterSyncBase):
    """Pipeline always POSTs when desk is live; the phone's focus-over-break
    rule (adopt.can_adopt, mirrored in Kotlin) decides 200 vs 409."""

    def _desk_live_work(self):
        self.client.enter_offline("test")
        self.client.model.set_config(45, 5, 15, 4, 8)
        self.client.model.toggle()
        self.client.model.set_start_time(1710000000.0)
        self.assertEqual(self.client.model.phase, "work")

    def _desk_live_break(self):
        self._desk_live_work()
        # work running -> skip to break idle -> toggle to break running.
        self.client.model.skip()
        self.assertEqual(self.client.model.phase, "short")
        self.client.model.toggle()
        self.client.model.set_start_time(1720000000.0)
        self.assertEqual(self.client.model.phase, "short")
        self.assertTrue(self.client.model.is_live())

    def test_can_adopt_work_vs_break_both_directions(self):
        phone_break = {
            "status": "running", "phase": "short", "remaining": 300.0,
            "start_time": 1.0,
        }
        desk_work = {
            "status": "running", "phase": "work", "remaining": 1400.0,
            "start_time": 2.0,
        }
        self.assertTrue(can_adopt(phone_break, desk_work))
        phone_work = {
            "status": "running", "phase": "work", "remaining": 1400.0,
            "start_time": 1.0,
        }
        desk_break = {
            "status": "running", "phase": "short", "remaining": 100.0,
            "start_time": 2.0,
        }
        self.assertFalse(can_adopt(phone_work, desk_break))

    def test_can_adopt_same_class_least_remaining(self):
        phone_work = {"status": "running", "phase": "work", "remaining": 900.0, "start_time": 1.0}
        self.assertTrue(can_adopt(phone_work, {
            "status": "paused", "phase": "work", "remaining": 899.0, "start_time": 2.0,
        }))
        self.assertFalse(can_adopt(phone_work, {
            "status": "running", "phase": "work", "remaining": 900.0, "start_time": 2.0,
        }))
        phone_long = {"status": "running", "phase": "long", "remaining": 600.0, "start_time": 1.0}
        self.assertTrue(can_adopt(phone_long, {
            "status": "running", "phase": "short", "remaining": 599.0, "start_time": 2.0,
        }))
        self.assertFalse(can_adopt(phone_long, {
            "status": "paused", "phase": "short", "remaining": 600.0, "start_time": 2.0,
        }))

    def test_desk_work_vs_phone_break_posts_adopt_and_200_wins(self):
        self._desk_live_work()
        now = int(time.time())
        desk_start = 1710000000.0
        adopted_state = {
            "status": "running", "phase": "work", "remaining": 1400.0,
            "duration": 2700.0, "completed": 0, "daily_goal": 8,
            "start_time": desk_start, "server_time": now,
        }
        self.client.rest = StubRest(results=[(200, json.dumps({
            "success": True, "state": adopted_state,
        }))])
        self.connect_ready()
        # Phone is on a break; desk work must still POST (focus wins on phone).
        self.client.on_websocket_text(state_frame(now - 3, 250.0, 1111.0, status="running", phase="short", duration=300.0))
        self.assertEqual(len(self.client.worker.jobs), 1)
        self.assertEqual(self.client.worker.jobs[0][0], "adopt")
        run_pending_jobs(self.client)
        self.assertEqual(self.client.mode, "SYNCED")
        self.assertEqual(self.client.model.phase, "work")
        self.assertEqual(self.client.model.start_time, desk_start)

    def test_desk_break_vs_phone_work_409_snaps_to_phone(self):
        self._desk_live_break()
        now = int(time.time())
        phone_state = {
            "status": "running", "phase": "work", "remaining": 1400.0,
            "duration": 2700.0, "completed": 2, "daily_goal": 8,
            "start_time": 9999.0, "server_time": now,
        }
        self.client.rest = StubRest(results=[(409, json.dumps({"state": phone_state}))])
        self.connect_ready()
        self.client.on_websocket_text(state_frame(now - 3, 1400.0, 9999.0, status="running", phase="work", duration=2700.0))
        self.assertEqual(self.client.worker.jobs[0][0], "adopt")
        run_pending_jobs(self.client)
        self.assertEqual(self.client.mode, "SYNCED")
        self.assertEqual(self.client.model.phase, "work")
        self.assertEqual(self.client.model.start_time, 9999.0)

    def test_data_dir_lock_wired_best_effort(self):
        # paths.ensure_data_dir_lock handle lives on the client; None (held
        # elsewhere) never raises and never breaks the pipeline.
        self.assertTrue(hasattr(self.client, "_data_lock"))


class GestureDuringPipelineTest(EnterSyncBase):
    def test_stdin_toggle_during_import_is_held_then_applied(self):
        self.client.rest = StubRest(results=[(200, '{"accepted": ["id1"], "rejected": []}')])
        self.client.queue.enqueue("id1", "work", 60, int(time.time()) - 60, "")
        now = int(time.time())
        self.connect_ready()
        self.engine.handle_line('{"cmd":"toggle"}')
        self.engine.handle_line('{"cmd":"toggle"}')
        self.client.on_websocket_text(state_frame(now - 3, 90.0, 2222.0))
        self.engine.drain_pending_gesture()
        self.assertEqual(self.engine.pending_gesture, "toggle", "held during pipeline")
        run_pending_jobs(self.client)
        self.assertEqual(self.client.mode, "SYNCED")
        self.engine.drain_pending_gesture()
        self.assertIsNone(self.engine.pending_gesture)
        self.assertTrue(self.client.busy, "phone-path gesture in flight")
        self.assertEqual(self.client.worker.jobs[-1][0], "toggle")
        run_pending_jobs(self.client)
        self.assertFalse(self.client.busy)


class DiscoveryAsyncTest(EnterSyncBase):
    def test_discovery_job_probes_candidates_and_picks(self):
        self.client.store.host = ""
        self.client.rest = StubRest(results=[(200, "")])
        self.client.set_mode("DISCOVERING")
        self.client.retry_delay_s = 0
        orig_browse = client_module.browse_pomo
        # The patch must stay active while the deferred job executes, not
        # just while it is queued.
        client_module.browse_pomo = lambda timeout=4.0: [
            {"host": "1.2.3.4", "port": 9876, "proto": "IPv4"}
        ]
        try:
            self.client.tick_discovery()
            run_pending_jobs(self.client)
        finally:
            client_module.browse_pomo = orig_browse
        self.assertEqual(self.client.host, "1.2.3.4")
        self.assertEqual(self.client.worker.jobs[-1][0], "connect", "handshake queued off-loop")

    def test_pinned_host_skips_mdns(self):
        self.client.store.host = "5.6.7.8"
        self.client.set_mode("DISCOVERING")
        self.client.retry_delay_s = 0
        self.client.tick_discovery()
        tags = [tag for tag, _func in self.client.worker.jobs]
        self.assertEqual(tags, ["connect"], "no discover job for pinned host")

    def _pin_old_host(self):
        self.client.store.set_pairing(host="old-host", port=9876, token="t")
        self.client.host = "old-host"
        self.client.port = 9876
        self.client.token = "t"
        self.client.set_mode("OFFLINE")

    def test_three_failed_pinned_rest_probes_queue_one_fallback(self):
        self._pin_old_host()
        self.client.rest = StubRest(results=[(0, ""), (0, ""), (0, "")])
        for _ in range(3):
            self.client.last_poll_at = 0
            self.client.tick_offline()
            run_pending_jobs(self.client)
        self.assertEqual([tag for tag, _func in self.client.worker.jobs], ["discover"])
        self.client.tick_offline()
        self.assertEqual([tag for tag, _func in self.client.worker.jobs], ["discover"])

    def test_pinned_fallback_persists_only_authenticated_candidate(self):
        self._pin_old_host()
        self.client.pinned_failures = 2
        self.client.rest = StubRest(results=[(200, "")])
        original = client_module.browse_pomo
        client_module.browse_pomo = lambda timeout=4.0: [
            {"host": "new-host", "port": 9999, "proto": "IPv4"}
        ]
        try:
            self.client._note_pinned_failure()
            tag, job = self.client.worker.jobs.pop(0)
            result = job()
            self.assertEqual(self.client.store.host, "old-host")
            self.assertEqual(self.client.store.port, 9876)
            self.client.apply_result(tag, result)
        finally:
            client_module.browse_pomo = original
        self.assertEqual(self.client.store.host, "new-host")
        self.assertEqual(self.client.store.port, 9999)
        self.assertEqual(self.client.mode, "DISCOVERING")
        self.assertEqual([tag for tag, _func in self.client.worker.jobs], ["connect"])

    def test_pinned_fallback_miss_keeps_pairing_and_retry(self):
        self._pin_old_host()
        original = client_module.browse_pomo
        client_module.browse_pomo = lambda timeout=4.0: []
        try:
            self.client.pinned_failures = 2
            self.client._note_pinned_failure()
            run_pending_jobs(self.client)
        finally:
            client_module.browse_pomo = original
        self.assertEqual(self.client.mode, "OFFLINE")
        self.assertEqual(self.client.store.host, "old-host")
        self.assertEqual(self.client.store.port, 9876)
        self.assertEqual(self.client.retry_delay_s, 5.0)
        self.assertFalse(self.client.discover_inflight)

    def test_stale_discovery_result_cannot_replace_new_pairing(self):
        self.client.store.set_pairing(host="", port=9876, token="old-token")
        self.client.host = ""
        self.client.port = 9876
        self.client.token = "old-token"
        self.client.set_mode("DISCOVERING")
        self.client.retry_delay_s = 0
        original = client_module.browse_pomo
        client_module.browse_pomo = lambda timeout=4.0: [
            {"host": "stale-host", "port": 9999, "proto": "IPv4"}
        ]
        try:
            self.client.tick_discovery()
            tag, old_job = self.client.worker.jobs.pop(0)
            self.client.apply_pairing({"host": "new-host", "port": 8888, "token": "new-token"})
            old_result = old_job()
            self.client.apply_result(tag, old_result)
        finally:
            client_module.browse_pomo = original
        self.assertEqual(self.client.host, "new-host")
        self.assertEqual(self.client.port, 8888)
        self.assertEqual(self.client.token, "new-token")
        self.assertEqual(self.client.store.host, "new-host")
        self.assertEqual(self.client.store.port, 8888)
        self.assertFalse(self.client.discover_inflight)
        self.client.tick_discovery()
        self.assertEqual(self.client.worker.jobs[-1][0], "connect")

    def test_stale_fallback_result_cannot_break_pinned_recovery(self):
        self._pin_old_host()
        self.client.pinned_failures = 2
        original = client_module.browse_pomo
        client_module.browse_pomo = lambda timeout=4.0: [
            {"host": "stale-host", "port": 9999, "proto": "IPv4"}
        ]
        try:
            self.client._note_pinned_failure()
            tag, old_job = self.client.worker.jobs.pop(0)
            self.client._apply_status_result((200, ""))
            self.assertEqual(self.client.mode, "DISCOVERING")
            self.assertEqual(self.client.host, "old-host")
            self.assertEqual(self.client.port, 9876)
            self.client.tick_discovery()
            old_result = old_job()
            self.client.apply_result(tag, old_result)
        finally:
            client_module.browse_pomo = original
        self.assertEqual(self.client.mode, "DISCOVERING")
        self.assertEqual(self.client.host, "old-host")
        self.assertEqual(self.client.port, 9876)
        self.assertEqual(self.client.store.host, "old-host")
        self.assertEqual(self.client.store.port, 9876)
        self.assertFalse(self.client.pinned_fallback_inflight)
        self.assertEqual([tag for tag, _func in self.client.worker.jobs], ["connect"])


class OfflineProbeTest(EnterSyncBase):
    def test_offline_poll_result_200_moves_to_discovering(self):
        self.client.rest = StubRest(results=[(200, "")])
        self.client.enter_offline("test")
        self.client.tick_offline()
        run_pending_jobs(self.client)
        self.assertEqual(self.client.mode, "DISCOVERING")
        self.assertTrue(self.client.prefer_known_host)


class ZeroRefreshTest(EnterSyncBase):
    def test_zero_running_state_refreshes_after_delay_without_local_completion(self):
        self.client.rest = StubRest(results=[(200, json.dumps({
            "status": "running", "phase": "work", "remaining": 0,
            "duration": 1500, "completed": 0, "start_time": 1710000000,
        }))])
        self.client.set_mode("SYNCED")
        self.client.apply_phone_object({
            "status": "running", "phase": "work", "remaining": 0,
            "duration": 1500, "completed": 0, "start_time": 1710000000,
        })
        self.client.model.received_at_mono = 10.0
        clock = [10.0]
        with patch.object(client_module.time, "monotonic", side_effect=lambda: clock[0]):
            self.client.tick_zero_refresh()
            clock[0] = 12.1
            self.client.tick_zero_refresh()
            self.assertEqual([tag for tag, _func in self.client.worker.jobs], ["status"])
            self.assertTrue(self.client.status_inflight)
            self.client.tick_zero_refresh()
            self.assertEqual(len(self.client.worker.jobs), 1, "in-flight refresh is reused")

            tag, job = self.client.worker.jobs.pop(0)
            self.client.apply_result(tag, job())
            self.assertFalse(self.client.status_inflight)
            self.assertEqual(self.client.model.status, "running")
            self.assertEqual(self.engine.pending_events, [])
            self.assertEqual(self.client.queue.count(), 0)

            clock[0] = 13.0
            self.client.tick_zero_refresh()
            self.assertEqual(self.client.worker.jobs, [], "zero refresh is throttled")

            self.client.apply_phone_object({
                "status": "running", "phase": "work", "remaining": 5,
                "duration": 1500, "completed": 0, "start_time": 1710000000,
            })
            self.assertEqual(self.client.zero_observed_at, 0.0)
            self.assertIsNone(self.client.zero_session)

            self.client.zero_session = (1710000000.0, "work")
            self.client.zero_observed_at = 12.1
            self.client.apply_phone_object({
                "status": "running", "phase": "short", "remaining": 0,
                "duration": 300, "completed": 1, "start_time": 1710000001,
            })
            self.assertEqual(self.client.zero_observed_at, 0.0)
            self.assertIsNone(self.client.zero_session)

    def test_zero_refresh_result_cannot_overwrite_new_websocket_session(self):
        old_state = {
            "status": "running", "phase": "work", "remaining": 0,
            "duration": 1500, "completed": 0, "start_time": 1111,
            "server_time": 100,
        }
        self.client.rest = StubRest(results=[(200, json.dumps(old_state))])
        self.client.set_mode("SYNCED")
        self.client.apply_phone_object(old_state)
        self.client.model.received_at_mono = 10.0
        self.client.zero_session = (1111.0, "work")
        self.client.zero_observed_at = 10.0
        with patch.object(client_module.time, "monotonic", return_value=12.1):
            self.client.tick_zero_refresh()
            self.assertTrue(self.client.status_inflight)
            tag, job = self.client.worker.jobs.pop(0)
            old_result = job()

            self.client.on_websocket_text(state_frame(int(time.time()), 90.0, 2222.0))
            self.client.apply_result(tag, old_result)

        self.assertFalse(self.client.status_inflight)
        self.assertEqual(self.client.model.start_time, 2222.0)
        self.assertEqual(self.client.model.remaining, 90.0)


if __name__ == "__main__":
    unittest.main()
