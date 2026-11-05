import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))

from pomo_link.waybar import format_control, format_waybar


class WaybarFormatTest(unittest.TestCase):
    def test_missing_status_is_offline_placeholder(self):
        doc = json.loads(format_waybar(None))
        self.assertEqual(doc["class"], "stopped work dead")
        self.assertEqual(doc["tooltip"], "pomo-link is not running")
        self.assertIn("--:--", doc["text"])

    def test_synced_running_work(self):
        doc = json.loads(
            format_waybar(
                {
                    "mode": "SYNCED",
                    "status": "running",
                    "phase": "work",
                    "remaining": 1499,
                    "completed": 2,
                    "goal": 8,
                    "has_token": True,
                    "local_owner": False,
                }
            )
        )
        self.assertEqual(doc["class"], "running work online")
        self.assertIn("24:59", doc["text"])
        self.assertEqual(doc["tooltip"], "running work - 2/8 sessions")

    def test_offline_local_timer_keeps_time(self):
        doc = json.loads(
            format_waybar(
                {
                    "mode": "OFFLINE",
                    "status": "running",
                    "phase": "work",
                    "remaining": 60,
                    "completed": 0,
                    "goal": 8,
                    "has_token": True,
                    "local_owner": True,
                }
            )
        )
        self.assertEqual(doc["class"], "running work local")
        self.assertIn("1:00", doc["text"])
        self.assertIn("(local)", doc["tooltip"])

    def test_unpaired_uses_warning_icon_class(self):
        doc = json.loads(
            format_waybar(
                {
                    "mode": "UNPAIRED",
                    "status": "stopped",
                    "phase": "work",
                    "remaining": 1500,
                    "completed": 0,
                    "goal": 8,
                    "has_token": False,
                    "local_owner": True,
                }
            )
        )
        self.assertEqual(doc["class"], "stopped work unpaired")
        self.assertIn("", doc["text"])


    def _status(self, **over):
        base = {
            "mode": "SYNCED",
            "status": "running",
            "phase": "work",
            "remaining": 600,
            "completed": 1,
            "goal": 12,
            "has_token": True,
            "local_owner": False,
            "busy": False,
            "message": "",
        }
        base.update(over)
        return base

    def test_reconnect_is_connecting_not_an_error(self):
        for mode in ("BOOT", "DISCOVERING", "CONNECTING"):
            doc = json.loads(format_waybar(self._status(mode=mode)))
            self.assertEqual(doc["class"], "running work connecting", mode)

    def test_busy_click_is_visible(self):
        doc = json.loads(format_waybar(self._status(busy=True)))
        self.assertEqual(doc["class"], "running work online busy")
        self.assertIn("sending to phone", doc["tooltip"])

    def test_message_reaches_tooltip(self):
        doc = json.loads(
            format_waybar(self._status(mode="OFFLINE", local_owner=True, message="phone unreachable"))
        )
        self.assertTrue(doc["tooltip"].endswith("\nphone unreachable"))


class WaybarControlTest(unittest.TestCase):
    def test_extend_only_while_running(self):
        running = json.loads(format_control({"status": "running", "phase": "work"}, "extend"))
        paused = json.loads(format_control({"status": "paused", "phase": "work"}, "extend"))
        self.assertEqual(running["class"], "active")
        self.assertEqual(paused["class"], "inactive")
        self.assertEqual(running["text"], "+5")

    def test_reset_needs_live_timer(self):
        stopped = json.loads(format_control({"status": "stopped", "phase": "work"}, "reset"))
        self.assertEqual(stopped["class"], "inactive")

    def test_skip_tooltip_and_dead_daemon(self):
        brk = json.loads(format_control({"status": "stopped", "phase": "short"}, "skip"))
        self.assertEqual(brk["tooltip"], "Skip to work")
        dead = json.loads(format_control(None, "skip"))
        self.assertEqual(dead["class"], "inactive")

    def test_busy_disables_controls(self):
        doc = json.loads(format_control({"status": "running", "phase": "work", "busy": True}, "extend"))
        self.assertEqual(doc["class"], "inactive")


if __name__ == "__main__":
    unittest.main()
