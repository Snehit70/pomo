"""Timing and product defaults copied from firmware PomoLink."""

QUEUE_CAPACITY = 32

DEFAULT_WORK_MINUTES = 25
DEFAULT_SHORT_MINUTES = 5
DEFAULT_LONG_MINUTES = 15
DEFAULT_LONG_AFTER = 4
DEFAULT_GOAL = 8
DEFAULT_PORT = 9876

EXTEND_SECONDS = 300

# Interval between OFFLINE-mode REST probes; kept at 5s — matches the
# steady reconnect tick without hot-looping the phone radio.
OFFLINE_PROBE_S = 5.0
# No socket contact for this long -> treat the WS as dead and reconnect.
# Was 20s: idle phones only answer pings, and a 20s window kept a dead
# socket (stale pinned IP, avahi missing) frozen before retry. 12s keeps
# one missed ping + margin while cutting permanent-fail tails.
STALE_AFTER_S = 12.0
# Boot-time window where failures prefer DISCOVERING (mDNS) over OFFLINE.
# Was 45s: pinned-host retries hid behind a 45s probe before mDNS could
# correct a changed IP. 15s is enough for the first avahi sweep + connect.
BOOT_PROBE_S = 15.0
# Steady-state tick between reconnect attempts; unchanged — 5s balances
# phone-radio wakeups against user-visible reconnect latency.
RECONNECT_INTERVAL_S = 5.0
# Idle time in UNPAIRED before re-probing for a newly paired phone.
# Was 300s: a phone paired elsewhere stayed invisible for 5min. 60s
# re-checks cheaply (single worker job) without hot-looping mDNS.
UNPAIRED_RETRY_S = 60.0
CONFIG_REFRESH_S = 300.0
CONFIG_RETRY_S = 60.0
SOFT_RESYNC_MAX = 8
TIMER_SNAP_INTERVAL_S = 30.0
# Client-side WS keepalive: paused/stopped phones send no frames, so without
# our own pings the stale check would kill a healthy idle socket.
WS_PING_S = 10.0
# Pinned-host connect failures before falling back to mDNS/OFFLINE; kept
# at 3 — enough to ride out one transient refusal, then let discovery
# correct a changed IP instead of hammering a stale address.
CONNECT_RETRY_MAX = 3

HTTP_TIMEOUT_S = 2.0
HTTP_FLUSH_TIMEOUT_S = 5.0

IMPORT_MAX_FUTURE_S = 5 * 60
IMPORT_MAX_AGE_S = 14 * 24 * 60 * 60
IMPORT_RETRY_MAX = 3

STATUSES = ("stopped", "running", "paused")
PHASES = ("work", "short", "long")
LIVE_STATUSES = ("running", "paused")

MODES = (
    "BOOT",
    "DISCOVERING",
    "CONNECTING",
    "SYNCED",
    "OFFLINE",
    "UNPAIRED",
)
