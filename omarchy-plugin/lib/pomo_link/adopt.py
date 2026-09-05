"""Phone POST /api/timer/adopt decision (TimerAdoptPayloads.canAdopt)."""

from .constants import LIVE_STATUSES


def is_live_status(status):
    return status in LIVE_STATUSES


def is_same_session(phone, payload):
    """Exact start_time + phase. Both start_time values must be > 0.

    Matches Kotlin `current.start_time == payload.start_time` (Double ==).
    Do not recompute start from now-(duration-remaining) at adopt time.
    """
    phone_start = float(phone.get("start_time") or 0.0)
    payload_start = float(payload.get("start_time") or 0.0)
    if phone_start <= 0.0 or payload_start <= 0.0:
        return False
    return phone_start == payload_start and phone.get("phase") == payload.get("phase")


def can_adopt(phone, payload):
    """Whether the phone should take `payload` as the sole live clock.

    Focus-over-break precedence (identical to Kotlin `canAdopt`):

    1. Phone STOPPED → always adopt.
    2. Same session (start_time + phase, both > 0) → always adopt.
    3. Both live (running/paused) on different sessions, different classes
       (work vs short/long break) → work side wins: desk work vs phone
       break adopts regardless of remaining; desk break vs phone work is
       409 busy (client snaps to phone).
    4. Both live, same class (both work, or both break incl. short-vs-long)
       → strict least-remaining: payload.remaining < phone.remaining.
    5. Otherwise 409 timer_busy; client snaps to phone.
    """
    if phone.get("status") == "stopped":
        return True
    if is_same_session(phone, payload):
        return True
    if not is_live_status(phone.get("status")) or not is_live_status(payload.get("status")):
        return False
    # Phase classes: work vs break (short/long). Non-work counts as break so
    # short-vs-long stays in the same class and falls to least-remaining.
    phone_work = phone.get("phase") == "work"
    payload_work = payload.get("phase") == "work"
    if phone_work != payload_work:
        # Focus overrides break in either direction: only the work side wins.
        return payload_work
    return float(payload.get("remaining") or 0.0) < float(phone.get("remaining") or 0.0)
