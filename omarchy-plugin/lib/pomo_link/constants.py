"""Timing and product defaults copied from firmware PomoLink."""

QUEUE_CAPACITY = 32

DEFAULT_WORK_MINUTES = 25
DEFAULT_SHORT_MINUTES = 5
DEFAULT_LONG_MINUTES = 15
DEFAULT_LONG_AFTER = 4
DEFAULT_GOAL = 8
DEFAULT_PORT = 9876

EXTEND_SECONDS = 300
# ADR 0002: shorter skipped work records nothing.
MIN_PARTIAL_WORK_S = 60

OFFLINE_PROBE_S = 5.0
STALE_AFTER_S = 20.0
BOOT_PROBE_S = 45.0
RECONNECT_INTERVAL_S = 5.0
UNPAIRED_RETRY_S = 300.0
CONFIG_REFRESH_S = 300.0
CONFIG_RETRY_S = 60.0
SOFT_RESYNC_MAX = 8
TIMER_SNAP_INTERVAL_S = 30.0
# Client-side WS keepalive: paused/stopped phones send no frames, so without
# our own pings the 20s stale check would kill a healthy idle socket.
WS_PING_S = 10.0
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
