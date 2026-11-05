"""Hybrid timer model copied from firmware TimerModel.

SYNCED: phone is the sole live clock. apply_state() snaps the baseline so
error cannot accumulate. Remaining is projected with server_time.

OFFLINE / UNPAIRED: this process owns the live clock. Tick uses wall-clock
elapsed from a stored remaining + monotonic anchor (an end point), not
accumulated 1s ticks.
"""

from __future__ import annotations

import time

from .constants import (
    DEFAULT_GOAL,
    DEFAULT_LONG_AFTER,
    DEFAULT_LONG_MINUTES,
    DEFAULT_SHORT_MINUTES,
    DEFAULT_WORK_MINUTES,
    MIN_PARTIAL_WORK_S,
    PHASES,
    STATUSES,
)


class TimerModel:
    def __init__(self):
        self.status = "stopped"
        self.phase = "work"
        self.remaining = 0.0
        self.duration = 0.0
        self.start_time = 0.0
        self.completed = 0
        self.date = ""
        self.completed_date = ""
        self.goal = DEFAULT_GOAL
        self.received_at_mono = time.monotonic()
        self.received_at_epoch = time.time()
        self.last_server_time = 0
        self.has_state = False
        self.local_owner = False
        self.work_minutes = DEFAULT_WORK_MINUTES
        self.short_minutes = DEFAULT_SHORT_MINUTES
        self.long_minutes = DEFAULT_LONG_MINUTES
        self.long_after = DEFAULT_LONG_AFTER
        self.phase_complete_handler = None
        self.session_complete_handler = None
        # (elapsed_seconds, start_time) for a skipped work block (ADR 0002).
        self.partial_work_handler = None

    def set_config(self, work_minutes, short_minutes, long_minutes, long_after, goal):
        if work_minutes > 0:
            self.work_minutes = int(work_minutes)
        if short_minutes > 0:
            self.short_minutes = int(short_minutes)
        if long_minutes > 0:
            self.long_minutes = int(long_minutes)
        if long_after > 0:
            self.long_after = int(long_after)
        if goal is not None and int(goal) >= 0:
            self.goal = int(goal)

    def is_running(self):
        return self.status == "running"

    def is_paused(self):
        return self.status == "paused"

    def is_stopped(self):
        return self.status == "stopped"

    def is_live(self):
        return self.is_running() or self.is_paused()

    def duration_seconds_for_phase(self, phase):
        if phase == "short":
            return float(self.short_minutes * 60)
        if phase == "long":
            return float(self.long_minutes * 60)
        return float(self.work_minutes * 60)

    def displayed_seconds(self):
        if not self.is_running():
            return 0 if self.remaining < 0 else int(self.remaining)
        if self.local_owner:
            elapsed = time.time() - self.received_at_epoch
            if elapsed < 0.0:
                elapsed = 0.0
        else:
            elapsed = time.monotonic() - self.received_at_mono
        value = int(self.remaining) - int(elapsed)
        return 0 if value < 0 else value

    def snap_remaining(self):
        self.remaining = float(self.displayed_seconds())
        self._arm_projection_baseline()

    def snap_for_persist(self):
        if not self.has_state:
            return
        if self.is_running():
            self.snap_remaining()

    def arm_running_baseline(self):
        self._arm_projection_baseline()

    def _arm_projection_baseline(self):
        self.received_at_mono = time.monotonic()
        self.received_at_epoch = time.time()

    def set_start_time(self, start_time):
        self.start_time = float(start_time) if start_time and start_time > 0 else 0.0

    def apply_state(
        self,
        status,
        phase,
        remaining,
        duration,
        completed,
        goal,
        start_time=0.0,
        server_time=0,
        epoch_now=0,
        force=True,
    ):
        status = status if status in STATUSES else "stopped"
        phase = phase if phase in PHASES else "work"
        remaining = float(remaining or 0.0)
        duration = float(duration or 0.0)
        start_time = float(start_time or 0.0)
        server_time = int(server_time or 0)
        epoch_now = int(epoch_now or 0)
        completed = int(completed or 0)
        if goal is None:
            goal_value = self.goal
        else:
            goal_value = int(goal)
            if goal_value < 0:
                goal_value = 0

        same_session = (
            self.has_state
            and start_time > 0.0
            and self.start_time == start_time
            and self.phase == phase
        )

        rem = remaining
        if status == "running" and server_time > 0 and epoch_now > server_time:
            rem -= float(epoch_now - server_time)
            if rem < 0.0:
                rem = 0.0

        if not force and self.has_state and not self.local_owner and same_session:
            if server_time > 0 and self.last_server_time > 0 and server_time < self.last_server_time:
                return False
            if self.is_running() and status == "running":
                cur = self.displayed_seconds()
                if rem > float(cur) + 1.0:
                    likely_extend = duration > self.duration + 0.5
                    if not likely_extend:
                        return False

        self.local_owner = False
        self.status = status
        self.phase = phase
        self.remaining = rem
        self.duration = duration
        self.completed = completed
        if goal_value >= 0:
            self.goal = goal_value
        self.start_time = start_time
        self.received_at_mono = time.monotonic()
        self.has_state = True
        if server_time > 0:
            self.last_server_time = server_time
        elif not same_session:
            self.last_server_time = 0
        return True

    def set_local_owner(self, owns):
        if owns == self.local_owner:
            if owns and not self.has_state:
                self._init_local_idle()
            return
        if owns:
            if self.has_state:
                self.snap_remaining()
            else:
                self._init_local_idle()
            self.local_owner = True
            if self.is_running():
                self.arm_running_baseline()
        else:
            if self.is_running():
                self.snap_remaining()
            self.local_owner = False

    def _init_local_idle(self):
        self.status = "stopped"
        self.phase = "work"
        self.duration = self.duration_seconds_for_phase("work")
        self.remaining = self.duration
        self.start_time = 0.0
        self.last_server_time = 0
        self._arm_projection_baseline()
        self.has_state = True

    def restore_live_state(self, status, phase, remaining, duration, completed, start_time):
        if status not in ("running", "paused"):
            return False
        if phase not in PHASES:
            return False
        remaining = float(remaining)
        duration = float(duration)
        if remaining < 0.0 or duration <= 0.0:
            return False
        self.status = status
        self.phase = phase
        self.remaining = remaining
        self.duration = duration
        self.completed = 0 if completed is None or int(completed) < 0 else int(completed)
        self.start_time = float(start_time) if start_time and start_time > 0 else 0.0
        self.last_server_time = 0
        self._arm_projection_baseline()
        self.has_state = True
        return True

    def toggle(self):
        if not self.local_owner:
            return
        if self.is_running():
            self.snap_remaining()
            self.status = "paused"
            return
        if self.remaining <= 0.0:
            self.remaining = self.duration_seconds_for_phase(self.phase)
            self.duration = self.remaining
        if self.status == "stopped":
            # Stamp unix start_time on live start so adopt identity is exact.
            self.start_time = float(int(time.time()))
        self.status = "running"
        self.arm_running_baseline()

    def skip(self):
        if not self.local_owner:
            return
        if self.phase == "work":
            self._record_partial_work()
        if self.phase == "work":
            self._advance_after_work_skip()
        else:
            self.phase = "work"
            self.duration = self.duration_seconds_for_phase("work")
        self.remaining = self.duration
        self.start_time = 0.0
        self.status = "stopped"
        self._arm_projection_baseline()

    def _record_partial_work(self):
        """ADR 0002: a skipped work block keeps its focus time (no block
        credit) once at least MIN_PARTIAL_WORK_S ran. Reset keeps nothing."""
        if not self.is_live():
            return
        if self.is_running():
            self.snap_remaining()
        elapsed = int(self.duration - self.remaining)
        if elapsed < MIN_PARTIAL_WORK_S:
            return
        if self.partial_work_handler:
            self.partial_work_handler(elapsed, self.start_time)

    def reset(self):
        if not self.local_owner:
            return
        self.duration = self.duration_seconds_for_phase(self.phase)
        self.remaining = self.duration
        self.start_time = 0.0
        self.status = "stopped"
        self._arm_projection_baseline()

    def extend(self, seconds_delta=300):
        if not self.local_owner:
            return
        if not self.is_running():
            return
        if seconds_delta < 1:
            seconds_delta = 1
        self.snap_remaining()
        self.duration += float(seconds_delta)
        self.remaining += float(seconds_delta)
        self.arm_running_baseline()

    def tick(self):
        if not self.local_owner:
            return None
        if not self.is_running():
            return None
        if self.displayed_seconds() > 0:
            return None
        return self._handle_local_complete()

    def _advance_after_work_complete(self):
        self.completed += 1
        if self.completed > 0 and (self.completed % self.long_after) == 0:
            self.phase = "long"
            self.duration = self.duration_seconds_for_phase("long")
        else:
            self.phase = "short"
            self.duration = self.duration_seconds_for_phase("short")

    def _advance_after_work_skip(self):
        self.phase = "short"
        self.duration = self.duration_seconds_for_phase("short")

    def _advance_after_break_complete(self):
        self.phase = "work"
        self.duration = self.duration_seconds_for_phase("work")

    def _handle_local_complete(self):
        finished_phase = self.phase
        finished_duration = 0 if self.duration < 0 else int(self.duration)
        completed_work = finished_phase == "work"
        finished_start = self.start_time

        self.remaining = 0.0
        self.start_time = 0.0
        self.status = "stopped"

        if completed_work:
            self._advance_after_work_complete()
        else:
            self._advance_after_break_complete()

        self.remaining = self.duration
        self._arm_projection_baseline()

        if self.phase_complete_handler:
            self.phase_complete_handler(finished_phase)
        if self.session_complete_handler:
            self.session_complete_handler(
                finished_phase, finished_duration, completed_work, finished_start
            )
        return finished_phase
