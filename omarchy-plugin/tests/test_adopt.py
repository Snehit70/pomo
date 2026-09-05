import os
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))

from pomo_link.adopt import can_adopt, is_same_session
from pomo_link.main import Engine


def phone(**kwargs):
    state = {
        "status": "stopped",
        "phase": "work",
        "remaining": 1500.0,
        "duration": 1500.0,
        "start_time": 0.0,
    }
    state.update(kwargs)
    return state


def payload(**kwargs):
    state = {
        "status": "running",
        "phase": "work",
        "remaining": 1200.0,
        "duration": 1500.0,
        "start_time": 1710000000.0,
    }
    state.update(kwargs)
    return state


class LeastRemainingAdoptTest(unittest.TestCase):
    def test_phone_stopped_always_adopts(self):
        self.assertTrue(
            can_adopt(
                phone(status="stopped", remaining=1500.0, start_time=0.0),
                payload(remaining=1400.0, start_time=1710000000.0),
            )
        )

    def test_same_session_always_adopts_even_if_payload_remaining_is_larger(self):
        current = phone(
            status="running",
            phase="work",
            remaining=1100.0,
            start_time=1710000000.0,
        )
        desk = payload(
            status="running",
            phase="work",
            remaining=1300.0,
            start_time=1710000000.0,
        )
        self.assertTrue(is_same_session(current, desk))
        self.assertTrue(can_adopt(current, desk))

    def test_example_laptop_20m_beats_phone_23m(self):
        # 25m timer: laptop 20m remaining, phone 23m remaining -> laptop wins.
        current = phone(
            status="running",
            phase="work",
            remaining=23 * 60,
            start_time=100.0,
        )
        desk = payload(
            status="running",
            phase="work",
            remaining=20 * 60,
            duration=25 * 60,
            start_time=200.0,
        )
        self.assertFalse(is_same_session(current, desk))
        self.assertTrue(can_adopt(current, desk))

    def test_both_live_different_session_desk_strictly_less(self):
        current = phone(status="running", remaining=900.0, start_time=1.0, phase="work")
        desk = payload(status="paused", remaining=899.0, start_time=2.0, phase="work")
        self.assertTrue(can_adopt(current, desk))

    def test_equal_remaining_on_different_sessions_is_busy(self):
        current = phone(status="running", remaining=800.0, start_time=1.0, phase="work")
        desk = payload(status="running", remaining=800.0, start_time=2.0, phase="work")
        self.assertFalse(can_adopt(current, desk))

    def test_desk_longer_remaining_on_different_session_is_busy(self):
        current = phone(status="running", remaining=700.0, start_time=1.0, phase="work")
        desk = payload(status="running", remaining=701.0, start_time=2.0, phase="work")
        self.assertFalse(can_adopt(current, desk))

    def test_zero_start_time_is_not_same_session(self):
        current = phone(status="running", remaining=500.0, start_time=0.0, phase="work")
        desk = payload(status="running", remaining=100.0, start_time=0.0, phase="work")
        self.assertFalse(is_same_session(current, desk))
        self.assertTrue(can_adopt(current, desk))  # desk remaining strictly less

    def test_non_live_payload_while_phone_live_is_busy(self):
        current = phone(status="running", remaining=500.0, start_time=1.0, phase="work")
        desk = payload(status="stopped", remaining=0.0, start_time=2.0, phase="work")
        self.assertFalse(can_adopt(current, desk))

    def test_equal_start_time_requires_equal_phase(self):
        current = phone(status="running", remaining=500.0, start_time=42.0, phase="work")
        desk = payload(status="running", remaining=100.0, start_time=42.0, phase="short")
        self.assertFalse(is_same_session(current, desk))

    def test_focus_overrides_break_any_remaining(self):
        # Desk work vs phone break adopts even when desk has MORE remaining.
        current = phone(
            status="running", phase="short", remaining=300.0, start_time=1.0,
        )
        desk = payload(
            status="running", phase="work", remaining=1400.0,
            duration=1500.0, start_time=2.0,
        )
        self.assertFalse(is_same_session(current, desk))
        self.assertTrue(can_adopt(current, desk))

    def test_focus_overrides_long_break_any_remaining(self):
        current = phone(
            status="paused", phase="long", remaining=200.0, start_time=1.0,
        )
        desk = payload(
            status="paused", phase="work", remaining=1400.0,
            duration=1500.0, start_time=2.0,
        )
        self.assertTrue(can_adopt(current, desk))

    def test_break_does_not_override_work(self):
        # Desk break vs phone work is 409 busy even with LESS remaining.
        current = phone(
            status="running", phase="work", remaining=1400.0, start_time=1.0,
        )
        desk = payload(
            status="running", phase="short", remaining=100.0,
            duration=300.0, start_time=2.0,
        )
        self.assertFalse(can_adopt(current, desk))

    def test_long_break_does_not_override_work(self):
        current = phone(
            status="paused", phase="work", remaining=1400.0, start_time=1.0,
        )
        desk = payload(
            status="paused", phase="long", remaining=100.0,
            duration=900.0, start_time=2.0,
        )
        self.assertFalse(can_adopt(current, desk))

    def test_paused_counts_as_live_focus_wins(self):
        current = phone(
            status="paused", phase="short", remaining=250.0, start_time=1.0,
        )
        desk = payload(
            status="paused", phase="work", remaining=1400.0,
            duration=1500.0, start_time=2.0,
        )
        self.assertTrue(can_adopt(current, desk))
        flipped = phone(
            status="paused", phase="work", remaining=1400.0, start_time=1.0,
        )
        flipped_desk = payload(
            status="paused", phase="short", remaining=100.0,
            duration=300.0, start_time=2.0,
        )
        self.assertFalse(can_adopt(flipped, flipped_desk))

    def test_break_vs_break_short_vs_long_least_remaining(self):
        # Same class (both break, incl. short-vs-long) -> strict least-remaining.
        current = phone(
            status="running", phase="long", remaining=600.0, start_time=1.0,
        )
        self.assertTrue(
            can_adopt(current, payload(
                status="running", phase="short", remaining=599.0,
                duration=300.0, start_time=2.0,
            ))
        )
        self.assertFalse(
            can_adopt(current, payload(
                status="running", phase="short", remaining=600.0,
                duration=300.0, start_time=2.0,
            ))
        )
        self.assertFalse(
            can_adopt(current, payload(
                status="running", phase="short", remaining=601.0,
                duration=300.0, start_time=2.0,
            ))
        )

    def test_work_vs_work_least_remaining(self):
        current = phone(status="running", phase="work", remaining=900.0, start_time=1.0)
        self.assertTrue(
            can_adopt(current, payload(
                status="running", phase="work", remaining=899.0, start_time=2.0,
            ))
        )
        self.assertFalse(
            can_adopt(current, payload(
                status="running", phase="work", remaining=900.0, start_time=2.0,
            ))
        )

    def test_adopt_reconstructs_missing_start_time_from_firmware_semantics(self):
        with tempfile.TemporaryDirectory() as directory, patch("pomo_link.client.time.time", return_value=2000):
            engine = Engine(directory=directory)
            engine.client.enter_offline("test")
            engine.model.status = "running"
            engine.model.duration = 1500.0
            engine.model.remaining = 1200.0
            engine.model.start_time = 0.0
            engine.model.arm_running_baseline()
            adopted = engine.client.adopt_payload()
            self.assertEqual(adopted["start_time"], 1700.0)
            self.assertEqual(engine.model.start_time, 1700.0)
            engine.client.worker.stop()


if __name__ == "__main__":
    unittest.main()
