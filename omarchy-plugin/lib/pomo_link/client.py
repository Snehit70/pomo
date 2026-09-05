"""PomoClient state machine copied from firmware, adapted to unix time."""

from __future__ import annotations

import json
import queue as job_queue
import time
from datetime import date as date_type
from urllib.parse import urlparse

from .constants import (
    BOOT_PROBE_S,
    CONFIG_REFRESH_S,
    CONFIG_RETRY_S,
    CONNECT_RETRY_MAX,
    DEFAULT_PORT,
    EXTEND_SECONDS,
    HTTP_FLUSH_TIMEOUT_S,
    IMPORT_RETRY_MAX,
    OFFLINE_PROBE_S,
    RECONNECT_INTERVAL_S,
    SOFT_RESYNC_MAX,
    STALE_AFTER_S,
    UNPAIRED_RETRY_S,
    WS_PING_S,
)
from .discovery import browse_pomo
from .paths import ensure_data_dir_lock
from .rest import RestClient
from .worker import RestWorker
from .ws import Rfc6455Client, WebSocketError


def marker_for(mode):
    if mode == "SYNCED":
        return " "
    if mode == "OFFLINE":
        return "~"
    if mode == "UNPAIRED":
        return "?"
    return "."


def _safe_float(value, default=0.0):
    try:
        out = float(value)
    except (TypeError, ValueError):
        return default
    if out != out or out in (float("inf"), float("-inf")):
        # json.loads accepts Infinity/NaN literals; inf remaining would
        # OverflowError in displayed_seconds() later.
        return default
    return out


def _safe_int(value, default=None):
    try:
        return int(value)
    except (TypeError, ValueError, OverflowError):
        # OverflowError: int(float("inf")) — json.loads accepts Infinity.
        return default


def _safe_date(value):
    if not isinstance(value, str):
        return ""
    value = value.strip()
    try:
        parsed = date_type.fromisoformat(value)
    except ValueError:
        return ""
    return value if parsed.isoformat() == value else ""


def parse_pairing_payload(value):
    """Accept pasted {url, token} JSON from Android Settings.

    Host/port from `url` pin the phone. Empty discrete host/port must not
    clobber that. A non-empty host (optional port) overrides the URL. An
    explicit empty host with no URL means mDNS.
    """
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return {}
        try:
            value = json.loads(text)
        except json.JSONDecodeError:
            return {}
    if not isinstance(value, dict):
        return {}
    out = {}
    token = value.get("token")
    if token is None:
        token = ""
    token = str(token).strip()
    if token:
        out["token"] = token
    url_host = None
    url = value.get("url")
    if isinstance(url, str) and url.strip():
        parsed = urlparse(url.strip())
        host = parsed.hostname or ""
        if host:
            url_host = host
            out["host"] = host
            try:
                out["port"] = int(parsed.port or DEFAULT_PORT)
            except ValueError:
                # urlparse raises on out-of-range ports; pair input must not
                # take the engine down.
                out["port"] = DEFAULT_PORT
    host_override = ""
    if "host" in value:
        host_override = str(value.get("host") or "").strip()
        if host_override:
            out["host"] = host_override
        elif url_host is None:
            out["host"] = ""
    if "port" in value and value["port"] not in ("", None):
        try:
            port = int(value["port"])
        except (TypeError, ValueError):
            port = None
        if port is not None and 1 <= port <= 65535:
            # Discrete port applies for mDNS-off host pin, not as a blank
            # default sitting next to a pasted url.
            if url_host is None or host_override:
                out["port"] = port
    return out


class PomoClient:
    def __init__(self, model, queue, store, rest=None, ws=None, worker=None):
        self.model = model
        self.queue = queue
        self.store = store
        self.rest = rest or RestClient()
        self.ws = ws or Rfc6455Client()
        self.worker = worker or RestWorker()

        self.mode = "BOOT"
        self.host = store.host
        self.port = store.port or DEFAULT_PORT
        self.token = store.token
        self.rest.configure(self.host, self.port, self.token)

        # Inter-process data-dir lock: single writer for config/timer state.
        # Best-effort only — None (held elsewhere / cannot lock) never raises.
        # The handle must stay alive on self for the process lifetime.
        self._data_lock = None
        try:
            self._data_lock = ensure_data_dir_lock(self.store.directory)
        except Exception:
            self._data_lock = None

        self.ever_synced = False
        self.entering_sync = False
        self.ws_dropped_during_enter = False
        self.pending_sync_state = None
        self.prefer_known_host = False
        self.soft_resync_count = 0
        self.soft_resyncing = False
        self.soft_resync_reason = ""
        self.probe_started_at = time.monotonic()
        self.probe_active = True
        self.last_contact_at = 0.0
        self.last_socket_contact_at = 0.0
        self.last_poll_at = 0.0
        self.retry_started_at = 0.0
        self.retry_delay_s = 0.0
        self.last_config_fetch_at = 0.0
        self.config_fetch_failed = False
        self.message = ""
        self.last_event = None
        self._ignore_disconnect = False
        self.log_lines = []
        self.error_lines = []
        # True while a phone gesture is outstanding; status exposes it and
        # further gestures wait instead of stacking.
        self.busy = False
        self.resync_after_command = False
        self.last_ping_at = 0.0
        self.connect_failures = 0
        # Async job bookkeeping (Phase 3): at most one job runs on the worker
        # at a time; per-tag in-flight guards stop duplicate submissions.
        self.connecting = False
        self.status_inflight = False
        self.wsdrop_inflight = False
        self.config_inflight = False
        self.discover_inflight = False
        self.discover_generation = 0
        self.discover_job_generation = None
        self.pinned_failures = 0
        self.pinned_fallback_inflight = False
        self.import_inflight = False
        self.adopt_inflight = False
        self.import_failures = 0
        self.import_retry_at = 0.0
        self.zero_observed_at = 0.0
        self.zero_refresh_at = 0.0
        self.zero_session = None
        self.zero_refresh_session = None

    def log(self, text):
        self.log_lines.append(text)
        if len(self.log_lines) > 50:
            self.log_lines = self.log_lines[-50:]

    def drain_logs(self):
        lines = self.log_lines
        self.log_lines = []
        return lines

    def note_error(self, text):
        self.log(text)
        self.error_lines.append(text)
        if len(self.error_lines) > 10:
            self.error_lines = self.error_lines[-10:]

    def drain_errors(self):
        lines = self.error_lines
        self.error_lines = []
        return lines

    # --- Phase 3: async phone I/O -------------------------------------
    # Every blocking call (REST, avahi, WS handshake) runs on the worker
    # thread; the select loop only submits jobs and applies results.

    def submit_rest(self, tag, method, path, body=None, timeout=None, host=None, port=None, token=None):
        h = host if host is not None else self.host
        p = int(port if port is not None else self.port)
        tok = token if token is not None else self.token
        rest = self.rest

        def job():
            return rest.request(method, path, body=body, timeout=timeout, host=h, port=p, token=tok)

        self.worker.submit(tag, job)

    def drain_worker_results(self):
        while True:
            try:
                tag, result = self.worker.results.get_nowait()
            except job_queue.Empty:
                return
            try:
                self.apply_result(tag, result)
            except Exception as exc:
                self.note_error("result %s error: %s" % (tag, exc))

    def apply_result(self, tag, result):
        if tag == "connect":
            self._apply_connect_result(result)
        elif tag in ("toggle", "skip", "reset", "extend"):
            self._apply_gesture_result(tag, result)
        elif tag == "soft_resync":
            self._apply_soft_resync_result(result)
        elif tag == "status":
            self._apply_status_result(result)
        elif tag == "wsdrop":
            self._apply_wsdrop_result(result)
        elif tag == "import":
            self._apply_import_result(result)
        elif tag == "adopt":
            self._apply_adopt_result(result)
        elif tag == "config":
            self._apply_config_result(result)
        elif tag == "discover":
            self._apply_discover_result(result)
        else:
            self.log("unknown job result tag %s" % tag)

    @staticmethod
    def _result_tuple(result):
        """REST jobs yield (code, text); unexpected raises yield the exception."""
        if isinstance(result, Exception):
            return 0, ""
        if isinstance(result, tuple) and len(result) == 2:
            return result
        return 0, ""

    def set_mode(self, next_mode):
        if self.mode == next_mode:
            return
        prev = self.mode
        self.mode = next_mode
        self.log("mode %s -> %s" % (prev, next_mode))
        if next_mode in ("OFFLINE", "UNPAIRED"):
            self.model.date = ""
            self.model.set_local_owner(True)
        elif next_mode == "SYNCED":
            self.model.set_local_owner(False)
            self.store.clear_timer_snapshot()

    def marker(self):
        return marker_for(self.mode)

    def phone_commands_active(self):
        if not self.host:
            return False
        if self.mode == "SYNCED":
            return True
        if self.ever_synced and not self.model.local_owner:
            return self.mode in ("CONNECTING", "DISCOVERING")
        return False

    def in_boot_probe(self):
        return self.probe_active and not self.ever_synced

    def enter_offline(self, reason):
        if self.mode == "OFFLINE":
            return
        self.log("leave SYNC/probe -> OFFLINE: %s" % reason)
        self.probe_active = False
        self.entering_sync = False
        self.ws_dropped_during_enter = False
        self.soft_resync_count = 0
        self.pending_sync_state = None
        self.prefer_known_host = False
        self.connect_failures = 0
        self.import_inflight = False
        self.adopt_inflight = False
        self.import_failures = 0
        self.import_retry_at = 0.0
        self.last_poll_at = 0.0
        self.message = reason
        self.schedule_rediscover()
        self.set_mode("OFFLINE")
        self._disconnect_ws()

    def enter_unpaired(self, reason):
        if self.mode == "UNPAIRED":
            return
        self.log("token rejected -> UNPAIRED: %s" % reason)
        self.probe_active = False
        self.entering_sync = False
        self.ws_dropped_during_enter = False
        self.soft_resync_count = 0
        self.pending_sync_state = None
        self.import_inflight = False
        self.adopt_inflight = False
        self.import_failures = 0
        self.import_retry_at = 0.0
        self.retry_started_at = time.monotonic()
        self.retry_delay_s = UNPAIRED_RETRY_S
        self.message = reason
        self.set_mode("UNPAIRED")
        self._disconnect_ws()

    def schedule_rediscover(self):
        self.retry_started_at = time.monotonic()
        self.retry_delay_s = RECONNECT_INTERVAL_S
        self.log("schedule rediscover in %s ms" % int(self.retry_delay_s * 1000))

    def _disconnect_ws(self):
        self._ignore_disconnect = True
        try:
            self.ws.close()
        finally:
            self._ignore_disconnect = False

    def apply_pairing(self, payload):
        parsed = parse_pairing_payload(payload)
        if not parsed:
            return False
        host = parsed.get("host", self.host)
        port = parsed.get("port", self.port)
        token = parsed.get("token", self.token)
        # Settings writes re-send identical pairing on every settings change;
        # tearing down a healthy socket for that re-opens the dead-button
        # window, so an unchanged triple is a no-op.
        if (
            host == self.host
            and _safe_int(port, 0) == _safe_int(self.port, 0)
            and token == self.token
        ):
            return False
        self._invalidate_discovery()
        self.store.set_pairing(host=host, port=port, token=token)
        self.host = self.store.host
        self.port = self.store.port
        self.token = self.store.token
        self.rest.configure(self.host, self.port, self.token)
        self.message = ""
        if self.token:
            self.retry_delay_s = 0
            if self.mode in ("UNPAIRED", "OFFLINE", "BOOT"):
                self.probe_active = True
                self.probe_started_at = time.monotonic()
                self.set_mode("DISCOVERING")
            elif self.mode in ("SYNCED", "CONNECTING"):
                self.ever_synced = False
                self.begin_websocket("pairing changed")
        else:
            self.enter_unpaired("empty token")
        return True

    def apply_phone_object(self, data, force=True):
        if not isinstance(data, dict):
            return False
        start_time = _safe_float(data.get("start_time"))
        remaining = _safe_float(data.get("remaining"))
        duration = _safe_float(data.get("duration"))
        completed = _safe_int(data.get("completed"))
        phone_date = _safe_date(data.get("date"))
        server_time = _safe_int(data.get("server_time"))
        if server_time is None or server_time < 0:
            server_time = 0
        epoch_now = int(time.time())
        # Missing or malformed goal keeps the store-cached goal (via the None
        # path in TimerModel.apply_state), never a hardcoded default.
        goal = None
        if data.get("daily_goal") is not None:
            goal = _safe_int(data.get("daily_goal"))
            if goal is not None and goal < 0:
                goal = 0
        ok = self.model.apply_state(
            data.get("status") or "stopped",
            data.get("phase") or "work",
            remaining,
            duration,
            completed,
            goal,
            start_time,
            server_time,
            epoch_now,
            force,
        )
        if ok:
            self.model.date = phone_date
            session = (self.model.start_time, self.model.phase)
            if (
                not self.model.is_running()
                or self.model.displayed_seconds() > 0
                or (self.zero_session is not None and session != self.zero_session)
            ):
                self.zero_observed_at = 0.0
                self.zero_session = None
        if not ok:
            self.log("state frame ignored (stale/out-of-order)")
        return ok

    def begin_websocket(self, reason):
        """Submit the WS handshake to the worker; completes in
        _apply_connect_result. Returns True when a connect job was queued."""
        if self.connecting:
            self.log("connect already in flight; ignoring %s" % reason)
            return False
        self.log("begin WebSocket %s:%s (%s)" % (self.host, self.port, reason))
        self._disconnect_ws()
        if not self.host or not self.port or not self.token:
            self.enter_unpaired("missing host/token")
            return False
        self.rest.configure(self.host, self.port, self.token)
        self.connecting = True
        ws_obj = self.ws
        host, port = self.host, self.port

        def job():
            return ws_obj.connect(host, port, path="/ws", timeout=5.0)

        self.worker.submit("connect", job)
        return True

    def _apply_connect_result(self, result):
        self.connecting = False
        if self.mode in ("UNPAIRED", "OFFLINE"):
            # The pipeline was aborted while the handshake was in flight;
            # a late success must not resurrect CONNECTING.
            self._disconnect_ws()
            self.log("connect result discarded (mode %s)" % self.mode)
            return
        if isinstance(result, Exception) or result is None:
            self.log("WS connect failed: %s" % result)
            self.connect_failures += 1
            self._note_pinned_failure()
            # A failed open proves nothing about the socket; stamping contact
            # here made the engine wait a phantom 20s stale window before the
            # first retry. Schedule a short retry instead.
            self.last_socket_contact_at = 0.0
            self.retry_started_at = time.monotonic()
            self.retry_delay_s = RECONNECT_INTERVAL_S
            if self.in_boot_probe():
                self.set_mode("DISCOVERING")
                return
            self.set_mode("CONNECTING")
            if self.connect_failures >= CONNECT_RETRY_MAX:
                self.log("connect failed %sx -> OFFLINE" % self.connect_failures)
                self.enter_offline("connect retries exhausted")
            return
        try:
            # Tick-path hello: non-blocking send, never a 5s stall on the loop.
            self.ws.try_send_text(json.dumps({"type": "hello", "token": self.token}))
        except WebSocketError as exc:
            if "would block" in str(exc).lower():
                # Send budget hit: soft disconnect, not a crash. Count it like
                # a connect failure, park on CONNECTING, then take the shared
                # disconnect path (wsdrop probe) outside the boot probe.
                self.log("hello send would block -> soft disconnect")
                self.connect_failures += 1
                self._note_pinned_failure()
                self.last_socket_contact_at = 0.0
                self.retry_started_at = time.monotonic()
                self.retry_delay_s = RECONNECT_INTERVAL_S
                if self.in_boot_probe():
                    self.set_mode("DISCOVERING")
                    return
                self.set_mode("CONNECTING")
                if self.connect_failures >= CONNECT_RETRY_MAX:
                    self.enter_offline("connect retries exhausted")
                    return
                self.on_websocket_disconnected()
                return
            self.log("hello send failed: %s" % exc)
            self.connect_failures += 1
            self._note_pinned_failure()
            self.last_socket_contact_at = 0.0
            self.retry_started_at = time.monotonic()
            self.retry_delay_s = RECONNECT_INTERVAL_S
            if self.in_boot_probe():
                self.set_mode("DISCOVERING")
                return
            self.set_mode("CONNECTING")
            if self.connect_failures >= CONNECT_RETRY_MAX:
                self.enter_offline("connect retries exhausted")
            return
        self.log("WS connected, hello sent")
        now = time.monotonic()
        self.last_contact_at = now
        self.last_socket_contact_at = now
        self.last_poll_at = now
        self.last_ping_at = 0.0
        self.connect_failures = 0
        self._reset_pinned_fallback_state()
        self.retry_delay_s = 0
        self.set_mode("CONNECTING")

    def soft_resync(self, reason):
        """Submit the reachability probe; completion continues in
        _apply_soft_resync_result. Never blocks the select loop."""
        if self.soft_resyncing:
            return False
        if not self.host or not self.port:
            self.enter_offline(reason or "soft resync no host")
            return False
        if self.soft_resync_count >= SOFT_RESYNC_MAX:
            self.log("soft resync budget exhausted -> OFFLINE")
            self.enter_offline("soft resync budget")
            return False
        self.soft_resyncing = True
        self.soft_resync_reason = reason or ""
        self.submit_rest("soft_resync", "GET", "/api/status")
        return True

    def _apply_soft_resync_result(self, result):
        self.soft_resyncing = False
        code, _body = self._result_tuple(result)
        if self.mode == "SYNCED":
            # The old socket delivered a state frame while we probed; the
            # light path already re-synced us.
            return
        if code == 401:
            self.enter_unpaired("soft resync 401")
            return
        if code != 200:
            self.log("soft resync REST code=%s -> OFFLINE" % code)
            self.enter_offline(self.soft_resync_reason or "soft resync unreachable")
            return
        self.soft_resync_count += 1
        self.entering_sync = False
        self.ws_dropped_during_enter = False
        if self.model.local_owner:
            # OFFLINE already took the clock. Keep it so the first state frame
            # runs import+adopt instead of the light snap-to-phone path.
            self.log(
                "soft resync #%s: %s (keep local clock, full enter-SYNC)"
                % (self.soft_resync_count, self.soft_resync_reason)
            )
            ok = self.begin_websocket("local-owner reconnect")
            self.soft_resyncing = False
            return ok
        self.model.set_local_owner(False)
        self.log("soft resync #%s: %s (phone still owns clock)" % (self.soft_resync_count, self.soft_resync_reason))
        self.begin_websocket("soft resync")

    def tick_offline(self):
        now = time.monotonic()
        if self.host and not self.status_inflight:
            if self.last_poll_at == 0 or now - self.last_poll_at >= OFFLINE_PROBE_S:
                self.last_poll_at = now
                self.status_inflight = True
                self.submit_rest("status", "GET", "/api/status")
        if self.retry_delay_s and now - self.retry_started_at >= self.retry_delay_s:
            self.retry_delay_s = 0
            self.log("rediscover timer elapsed -> DISCOVERING")
            self.set_mode("DISCOVERING")

    def _apply_status_result(self, result):
        self.status_inflight = False
        zero_refresh_session = self.zero_refresh_session
        self.zero_refresh_session = None
        code, body = self._result_tuple(result)
        if self.mode == "OFFLINE":
            if code == 401:
                self.enter_unpaired("GET /api/status")
                return
            if code == 200:
                self._invalidate_pinned_fallback()
                self._reset_pinned_fallback_state()
                self.log("phone reachable while OFFLINE -> reconnect known host")
                self.retry_delay_s = 0
                self.prefer_known_host = True
                self.set_mode("DISCOVERING")
            else:
                self._note_pinned_failure()
            return
        if self.mode == "SYNCED":
            # Never REST-promote to SYNCED; only refresh an existing sync.
            try:
                data = json.loads(body)
            except json.JSONDecodeError:
                data = None
            if isinstance(data, dict):
                if (
                    zero_refresh_session is not None
                    and (self.model.start_time, self.model.phase) != zero_refresh_session
                ):
                    self.log("zero refresh ignored (session changed)")
                    return
                self.apply_phone_object(data, force=False)
            return

    def tick_discovery(self):
        now = time.monotonic()
        if self.retry_delay_s and now - self.retry_started_at < self.retry_delay_s:
            return
        if not self.token:
            self.enter_unpaired("no token")
            return
        if self.store.host:
            self.prefer_known_host = False
            self.host = self.store.host
            self.port = self.store.port or DEFAULT_PORT
            self.log("using configured host %s:%s (mDNS not queried)" % (self.host, self.port))
            self.rest.configure(self.host, self.port, self.token)
            self.begin_websocket("discovery")
            return
        if self.prefer_known_host and self.host and self.port:
            self.prefer_known_host = False
            self.log("reusing known host %s:%s (REST-proven)" % (self.host, self.port))
            self.rest.configure(self.host, self.port, self.token)
            self.begin_websocket("discovery")
            return
        if self.discover_inflight:
            return
        self.prefer_known_host = False
        self._queue_discovery_job()

    def _note_pinned_failure(self):
        if not self.store.host:
            return
        self.pinned_failures += 1
        if self.pinned_failures >= CONNECT_RETRY_MAX and not self.pinned_fallback_inflight:
            self._queue_discovery_job(pinned_fallback=True)

    def _reset_pinned_fallback_state(self):
        self.pinned_failures = 0
        self.pinned_fallback_inflight = False

    def _invalidate_discovery(self):
        self.discover_generation += 1
        self.discover_inflight = False
        self.discover_job_generation = None
        self.pinned_fallback_inflight = False

    def _invalidate_pinned_fallback(self):
        if self.pinned_fallback_inflight:
            self.discover_generation += 1
            self.discover_inflight = False
            self.discover_job_generation = None
            self.pinned_fallback_inflight = False

    def _queue_discovery_job(self, pinned_fallback=False):
        if self.discover_inflight:
            return False
        self.discover_inflight = True
        self.pinned_fallback_inflight = pinned_fallback
        generation = self.discover_generation
        self.discover_job_generation = generation
        token = self.token
        rest = self.rest

        def job():
            try:
                try:
                    candidates = browse_pomo()
                except Exception:
                    candidates = []
                if not isinstance(candidates, (list, tuple)):
                    candidates = []
                valid = []
                for cand in candidates:
                    if not isinstance(cand, dict):
                        continue
                    host = cand.get("host")
                    try:
                        port = int(cand.get("port"))
                    except (TypeError, ValueError):
                        continue
                    if not isinstance(host, str) or not host.strip() or not 1 <= port <= 65535:
                        continue
                    valid.append({"host": host.strip(), "port": port, "proto": cand.get("proto", "")})
                unauthorized = 0
                picked = None
                for cand in valid:
                    try:
                        code, _text = rest.request(
                            "GET", "/api/status", host=cand["host"], port=cand["port"], token=token
                        )
                    except Exception:
                        continue
                    if code == 200:
                        picked = cand
                        break
                    if code == 401:
                        unauthorized += 1
                payload = {
                    "picked": picked,
                    "unauthorized": unauthorized,
                    "responders": len(valid),
                }
            except Exception:
                payload = {"picked": None, "unauthorized": 0, "responders": 0}
            return {
                "generation": generation,
                "pinned_fallback": pinned_fallback,
                "payload": payload,
            }

        self.worker.submit("discover", job)
        return True

    def _apply_discover_result(self, result):
        if not isinstance(result, dict) or result.get("generation") != self.discover_generation:
            return
        if result.get("generation") != self.discover_job_generation:
            return
        self.discover_inflight = False
        self.discover_job_generation = None
        was_pinned_fallback = bool(result.get("pinned_fallback"))
        self.pinned_fallback_inflight = False
        payload = result.get("payload")
        if not isinstance(payload, dict):
            payload = {"picked": None, "unauthorized": 0, "responders": 0}
        now = time.monotonic()
        picked = payload.get("picked")
        if picked is None:
            if (
                not was_pinned_fallback
                and payload.get("responders")
                and payload.get("unauthorized") == payload.get("responders")
            ):
                self.log("all mDNS responders rejected token")
                self.enter_unpaired("mDNS all 401")
                return
            if payload.get("responders"):
                self.log("mDNS had %s responders but none authed" % payload.get("responders"))
            else:
                self.log("mDNS miss, no configured host")
            if was_pinned_fallback:
                self.pinned_failures = 0
                self.pinned_fallback_inflight = False
                self.retry_started_at = now
                self.retry_delay_s = RECONNECT_INTERVAL_S
                self.set_mode("OFFLINE")
            elif self.in_boot_probe():
                self.retry_started_at = now
                self.retry_delay_s = 1.0
            else:
                self.enter_offline("mDNS miss on rediscover")
            return
        if not isinstance(picked, dict):
            self.retry_started_at = now
            self.retry_delay_s = RECONNECT_INTERVAL_S
            self.set_mode("OFFLINE")
            return
        host = picked.get("host")
        port = _safe_int(picked.get("port"), 0)
        if not isinstance(host, str) or not host.strip() or not 1 <= port <= 65535:
            self.retry_started_at = now
            self.retry_delay_s = RECONNECT_INTERVAL_S
            self.set_mode("OFFLINE")
            return
        self.host = host.strip()
        self.port = port
        self.store.set_pairing(host=self.host, port=self.port, token=self.token)
        self._reset_pinned_fallback_state()
        self.log("discovered %s:%s via mDNS" % (self.host, self.port))
        self.rest.configure(self.host, self.port, self.token)
        if was_pinned_fallback:
            self.set_mode("DISCOVERING")
        self.begin_websocket("discovery")

    def on_websocket_text(self, payload):
        if self.mode == "UNPAIRED":
            return
        try:
            doc = json.loads(payload)
        except json.JSONDecodeError:
            self.log("bad frame")
            return
        if not isinstance(doc, dict):
            return
        frame_type = doc.get("type") or ""
        now = time.monotonic()
        self.last_contact_at = now
        self.last_socket_contact_at = now

        if frame_type == "state":
            data = doc.get("data")
            if not isinstance(data, dict):
                return
            if self.mode == "SYNCED":
                self.apply_phone_object(data, force=False)
                return
            if self.mode == "CONNECTING":
                if self.entering_sync:
                    # A fresher frame arrived mid-pipeline; it becomes the
                    # snap target instead of the frame that started it.
                    self.update_pending_sync_state(data)
                    return
                self.pending_sync_state = data
                if self.ever_synced and not self.model.local_owner:
                    self.apply_phone_object(data, force=True)
                    self.pending_sync_state = None
                    self.soft_resync_count = 0
                    self.last_contact_at = time.monotonic()
                    self.set_mode("SYNCED")
                    self.log("soft resync complete -> SYNCED (light path)")
                    return
                self.begin_enter_sync()
            return

        if frame_type == "event":
            if self.mode != "SYNCED":
                return
            event = doc.get("event") or ""
            if event == "phase_complete":
                phase = doc.get("phase") or "work"
                self.last_event = {"event": "phase_complete", "phase": phase}
            return
        # Unknown types ignored by contract.

    def on_websocket_disconnected(self):
        if self._ignore_disconnect or self.soft_resyncing:
            return
        if self.mode in ("UNPAIRED", "OFFLINE"):
            return
        if self.entering_sync:
            self.ws_dropped_during_enter = True
            self.log("WS drop during enter-SYNC pipeline (deferred)")
            return
        if self.mode == "SYNCED":
            self.log("WS drop while SYNCED -> soft resync")
            self.soft_resync("ws disconnected")
            return
        if self.mode != "CONNECTING":
            return
        if self.wsdrop_inflight:
            return
        self.log("WS drop while CONNECTING — token/reachability probe")
        self.wsdrop_inflight = True
        self.submit_rest("wsdrop", "GET", "/api/status")

    def _apply_wsdrop_result(self, result):
        self.wsdrop_inflight = False
        code, _body = self._result_tuple(result)
        if code == 401:
            self.enter_unpaired("ws drop 401")
            return
        if code == 200:
            if self.ever_synced and not self.model.local_owner:
                self.soft_resync("ws drop phone up")
            elif self.soft_resync_count < SOFT_RESYNC_MAX:
                self.soft_resync_count += 1
                self.begin_websocket("ws drop retry")
            else:
                self.enter_offline("ws connect failed")
            return
        self.log("WS drop CONNECTING REST code=%s" % code)

    def refresh_socket_contact(self):
        """A pong, ping, or any data frame proves the socket is alive.

        The phone sends no frames while paused/stopped, so without this the
        stale check would soft-resync a healthy idle socket every 20s.
        """
        activity = getattr(self.ws, "last_peer_activity_mono", 0.0)
        # 0.0 means "no peer frame yet" (fresh/teardown stub), not a real
        # timestamp. Adopting it would stamp a falsy contact that disables
        # the stale watchdog below (and on fresh CI runners with a small
        # monotonic clock it even overwrites now-100).
        if activity and activity > self.last_socket_contact_at:
            self.last_socket_contact_at = activity
            self.last_contact_at = activity

    def tick_ws_ping(self):
        if self.mode not in ("CONNECTING", "SYNCED"):
            return
        if not self.ws.connected:
            return
        now = time.monotonic()
        if self.last_ping_at and now - self.last_ping_at < WS_PING_S:
            return
        self.last_ping_at = now
        try:
            # Non-blocking tick ping: WouldBlock surfaces as WebSocketError
            # and takes the soft-disconnect path, never a loop stall/crash.
            self.ws.try_send_ping()
        except WebSocketError:
            self.on_websocket_disconnected()

    def tick_connect_retry(self):
        if self.mode != "CONNECTING" or self.ws.connected:
            return
        if not self.retry_delay_s:
            return
        if time.monotonic() - self.retry_started_at < self.retry_delay_s:
            return
        self.retry_delay_s = 0
        self.log("connect retry timer elapsed")
        self.begin_websocket("connect retry")

    def pump_websocket(self):
        if self.mode not in ("CONNECTING", "SYNCED"):
            return
        if not self.ws.connected:
            # Never-connected / already closed: ~20s stale heartbeat owns retry.
            return
        try:
            if not self.ws.recv_ready(0.0):
                return
            texts = self.ws.read_texts()
        except WebSocketError:
            self.on_websocket_disconnected()
            return
        for text in texts:
            self.on_websocket_text(text)

    # --- Stepped enter-SYNC pipeline -----------------------------------
    # state frame → begin_enter_sync → import job → on_import_done →
    #   adopt job (desk live) or snap → finish_enter_sync → SYNCED.
    # Newer state frames refresh pending_sync_state while the pipeline
    # runs; stdin and the WS keep pumping throughout.

    def update_pending_sync_state(self, data):
        """Newest-wins by server_time so a delayed duplicate can never
        become the snap target and bypass the stale-frame contract."""
        if self.pending_sync_state is None:
            self.pending_sync_state = data
            return
        new_st = _safe_int(data.get("server_time"))
        old_st = _safe_int(self.pending_sync_state.get("server_time"))
        if new_st is not None and old_st is not None:
            if new_st >= old_st:
                self.pending_sync_state = data
        else:
            self.pending_sync_state = data

    def begin_enter_sync(self):
        self.entering_sync = True
        self.import_failures = 0
        self.import_retry_at = 0.0
        self.log("enter SYNC pipeline start")
        self.start_import()

    def start_import(self):
        if self.queue.empty():
            self.log("flush skip: empty queue")
            self.import_inflight = False
            self.on_import_done(True)
            return
        if not self.host:
            self.log("flush failed: no host")
            self.on_import_done(False)
            return
        self.queue.strip_implausible_starts(int(time.time()))
        body = {
            "source": "omarchy",
            "sessions": [],
        }
        for item in self.queue.items:
            row = {
                "client_id": item["client_id"],
                "type": item["type"],
                "duration": int(item["duration"]),
                "completed": True,
            }
            if item.get("start"):
                row["start"] = int(item["start"])
            if item.get("tag"):
                row["tag"] = item["tag"]
            body["sessions"].append(row)
        self.log("POST /api/sessions/import count=%s" % self.queue.count())
        self.import_inflight = True
        self.submit_rest("import", "POST", "/api/sessions/import", body=body, timeout=HTTP_FLUSH_TIMEOUT_S)

    def _apply_import_result(self, result):
        self.import_inflight = False
        if not self.entering_sync or self.mode in ("UNPAIRED", "OFFLINE"):
            return
        code, response = self._result_tuple(result)
        if code == 401:
            self.enter_unpaired("/api/sessions/import")
            return
        ok = False
        if code == 200:
            try:
                resp = json.loads(response)
            except json.JSONDecodeError:
                resp = None
            if isinstance(resp, dict) and isinstance(resp.get("accepted"), list):
                terminal = []
                for item in resp["accepted"]:
                    if isinstance(item, str) and item:
                        terminal.append(item)
                rejected = resp.get("rejected")
                if isinstance(rejected, list):
                    for row in rejected:
                        if not isinstance(row, dict):
                            continue
                        cid = str(row.get("client_id") or "")
                        self.log("flush row rejected id=%s err=%s" % (cid, row.get("error") or ""))
                        if cid:
                            terminal.append(cid)
                self.queue.drop_by_client_id(terminal)
                ok = self.queue.empty()
                if not ok:
                    self.log("flush incomplete; retryable rows remain queued")
            else:
                self.log("flush rejected: response parse failed")
        else:
            self.log("flush rejected: http %s" % code)
        if ok:
            self.on_import_done(True)
            return
        self.import_failures += 1
        if self.import_failures >= IMPORT_RETRY_MAX:
            self.note_error("session import failed %sx; syncing anyway" % self.import_failures)
            self.on_import_done(False)
            return
        self.log("session import retry #%s in %ss" % (self.import_failures, int(RECONNECT_INTERVAL_S)))
        self.import_retry_at = time.monotonic() + RECONNECT_INTERVAL_S

    def on_import_done(self, imported):
        del imported
        data = self.pending_sync_state
        if data is None:
            self.log("enter SYNC aborted: no phone state")
            self.entering_sync = False
            return
        desk_live = self.model.is_live() and (self.model.local_owner or not self.ever_synced)

        if desk_live:
            # Always POST when live. Phone canAdopt / 409 decides same-session
            # vs least-remaining. Do not pre-filter on remaining (that skips
            # same-session refresh).
            self.log("desk live -> try adopt")
            self.adopt_inflight = True
            self.submit_rest("adopt", "POST", "/api/timer/adopt", body=self.adopt_payload(), timeout=HTTP_FLUSH_TIMEOUT_S)
            return

        self.log("adopt result=skip desk_idle -> snap latest pending")
        self.finish_enter_sync(snap=True)

    def adopt_payload(self):
        remaining = float(self.model.displayed_seconds())
        duration = self.model.duration
        if duration <= 0.0:
            duration = remaining if remaining > 0.0 else 1.0
        rem = remaining
        if rem < 0.0:
            rem = 0.0
        if rem > duration:
            rem = duration
        self._ensure_live_start_time()
        return {
            "status": self.model.status,
            "phase": self.model.phase,
            "remaining": rem,
            "duration": duration,
            "start_time": self.model.start_time,
            "completed": self.model.completed,
            "daily_goal": self.model.goal,
            "tag": "",
        }

    def _apply_adopt_result(self, result):
        self.adopt_inflight = False
        if not self.entering_sync or self.mode in ("UNPAIRED", "OFFLINE"):
            return
        code, response = self._result_tuple(result)
        if code == 401:
            self.enter_unpaired("/api/timer/adopt")
            return
        if code == 0:
            data = self.pending_sync_state
            phone_stopped = data is None or str(data.get("status") or "stopped") == "stopped"
            if phone_stopped:
                self.entering_sync = False
                self.log("adopt result=transport_fail (keep local)")
                self.enter_offline("adopt transport fail")
                return
            self.log("adopt result=transport_fail phone_active -> snap latest pending")
            self.finish_enter_sync(snap=True)
            return
        if code == 409:
            self.log("adopt result=409 timer_busy")
            try:
                resp = json.loads(response)
            except json.JSONDecodeError:
                resp = None
            if isinstance(resp, dict) and isinstance(resp.get("state"), dict):
                self.apply_phone_object(resp["state"], force=True)
                self.log("adopt 409 applied phone state")
                self.finish_enter_sync(snap=False)
                return
            self.finish_enter_sync(snap=True)
            return
        if code != 200:
            self.log("adopt result=http_%s (snap latest pending)" % code)
            self.finish_enter_sync(snap=True)
            return
        try:
            resp = json.loads(response)
        except json.JSONDecodeError:
            self.log("adopt response parse failed (snap latest pending)")
            self.finish_enter_sync(snap=True)
            return
        if not resp.get("success"):
            self.log("adopt result=success_false (snap latest pending)")
            self.finish_enter_sync(snap=True)
            return
        state = resp.get("state")
        if isinstance(state, dict):
            self.apply_phone_object(state, force=True)
        self.log("adopt result=ok (phone owns clock)")
        self.finish_enter_sync(snap=False)

    def finish_enter_sync(self, snap=True):
        if snap and self.pending_sync_state is not None:
            self.apply_phone_object(self.pending_sync_state, force=True)
        self.pending_sync_state = None
        self.config_fetch_failed = False
        self.last_config_fetch_at = time.monotonic()
        self.log("config fetch deferred until SYNC is stable")
        self.store.save()
        self.probe_active = False
        self.ever_synced = True
        self.entering_sync = False
        self.import_failures = 0
        self.import_retry_at = 0.0
        self.soft_resync_count = 0
        self.last_contact_at = time.monotonic()
        self.set_mode("SYNCED")
        self.message = ""
        self.log("enter SYNC pipeline done -> SYNCED")
        if self.ws_dropped_during_enter:
            self.ws_dropped_during_enter = False
            self.log("WS died during enter-SYNC pipeline -> soft resync")
            self.soft_resync("ws drop during enter")

    def tick_enter_sync(self):
        if not self.entering_sync:
            return
        if self.import_inflight or self.adopt_inflight:
            return
        if self.import_retry_at and time.monotonic() >= self.import_retry_at:
            self.import_retry_at = 0.0
            self.start_import()

    def _ensure_live_start_time(self):
        """Ensure a live adopt has the firmware's stable epoch identity."""
        if not self.model.is_live() or self.model.start_time > 0.0:
            return
        now = int(time.time())
        if now <= 0:
            return
        displayed = self.model.displayed_seconds()
        elapsed = max(0.0, self.model.duration - displayed)
        start_time = now - elapsed
        if start_time > 0.0:
            self.model.set_start_time(start_time)

    def tick_zero_refresh(self):
        if self.mode != "SYNCED" or not self.model.is_running():
            return
        if self.model.displayed_seconds() > 0:
            self.zero_observed_at = 0.0
            self.zero_session = None
            return
        session = (self.model.start_time, self.model.phase)
        if self.zero_session != session:
            self.zero_session = session
            self.zero_observed_at = time.monotonic()
            return
        now = time.monotonic()
        if now - self.zero_observed_at < 2.0:
            return
        if self.status_inflight or now - self.zero_refresh_at < 3.0:
            return
        self.zero_refresh_at = now
        self.zero_refresh_session = session
        self.status_inflight = True
        self.submit_rest("status", "GET", "/api/status")

    def tick_config_refresh(self):
        now = time.monotonic()
        every = CONFIG_RETRY_S if self.config_fetch_failed else CONFIG_REFRESH_S
        if self.last_config_fetch_at and now - self.last_config_fetch_at < every:
            return
        if self.config_inflight:
            return
        self.last_config_fetch_at = now
        self.config_inflight = True
        self.submit_rest("config", "GET", "/api/config")

    def _apply_config_result(self, result):
        self.config_inflight = False
        code, response = self._result_tuple(result)
        if code == 401:
            self.enter_unpaired("GET /api/config")
            self.config_fetch_failed = True
            return
        if code != 200:
            self.config_fetch_failed = True
            self.log("config refresh failed; will retry")
            return
        try:
            doc = json.loads(response)
        except json.JSONDecodeError:
            self.config_fetch_failed = True
            return
        durations = doc.get("durations") if isinstance(doc.get("durations"), dict) else {}
        work = durations.get("work", self.store.work_minutes)
        short_m = durations.get("short_break", self.store.short_minutes)
        long_m = durations.get("long_break", self.store.long_minutes)
        long_after = doc.get("long_break_after", self.store.long_after)
        goal = self.store.goal
        if doc.get("daily_goal") is not None:
            parsed_goal = _safe_int(doc.get("daily_goal"))
            if parsed_goal is not None:
                goal = max(0, parsed_goal)
        self.store.set_durations(work, short_m, long_m, long_after, goal)
        self.store.save()
        self.model.set_config(work, short_m, long_m, long_after, goal)
        self.config_fetch_failed = False
        self.log("config cached %s/%s/%s after=%s goal=%s" % (work, short_m, long_m, long_after, goal))

    def tick_heartbeat(self):
        now = time.monotonic()
        if self.mode == "CONNECTING":
            self.tick_enter_sync()
        if self.mode == "SYNCED" and not self.entering_sync:
            self.tick_config_refresh()
        if self.entering_sync or self.mode == "UNPAIRED":
            return
        if self.last_socket_contact_at and (now - self.last_socket_contact_at) >= STALE_AFTER_S:
            if self.mode == "SYNCED":
                self.log("heartbeat stale: SYNCED socket -> soft resync")
                self.soft_resync("stale socket")
            elif self.mode == "CONNECTING":
                if self.model.local_owner:
                    self.log("heartbeat stale: CONNECTING with local clock -> OFFLINE")
                    self.enter_offline("reconnect connect stale")
                else:
                    self.log("heartbeat stale: CONNECTING socket -> soft resync/offline")
                    self.soft_resync("reconnect connect stale")

    def tick_probe_watchdog(self):
        if self.mode in ("SYNCED", "OFFLINE", "UNPAIRED"):
            return
        if self.entering_sync or self.mode == "CONNECTING":
            return
        if self.mode == "BOOT":
            self.set_mode("DISCOVERING")
            self.probe_started_at = time.monotonic()
            self.probe_active = True
            return
        if self.mode != "DISCOVERING":
            return
        if not self.in_boot_probe():
            return
        if time.monotonic() - self.probe_started_at < BOOT_PROBE_S:
            return
        self.log("boot probe timeout (DISCOVERING window elapsed)")
        self.enter_offline("boot probe timeout")

    def tick(self):
        self.tick_probe_watchdog()
        if self.resync_after_command and self.mode == "SYNCED" and not self.busy:
            self.resync_after_command = False
            self.soft_resync("command response missing state")
            return
        if self.mode in ("CONNECTING", "SYNCED"):
            self.pump_websocket()
            self.refresh_socket_contact()
            self.tick_ws_ping()
        if self.mode == "CONNECTING":
            self.tick_connect_retry()
        if self.mode in ("BOOT",):
            if not self.token:
                self.enter_unpaired("no token")
            else:
                self.set_mode("DISCOVERING")
                self.probe_started_at = time.monotonic()
                self.probe_active = True
            return
        if self.mode == "DISCOVERING":
            self.tick_discovery()
            return
        if self.mode == "OFFLINE":
            self.tick_offline()
            return
        if self.mode in ("CONNECTING", "SYNCED"):
            self.tick_heartbeat()
            if self.mode == "SYNCED":
                self.tick_zero_refresh()
            return
        if self.mode == "UNPAIRED":
            if time.monotonic() - self.retry_started_at >= self.retry_delay_s:
                if self.token:
                    self.log("unpaired cooldown over, re-discovering")
                    self.retry_delay_s = 0
                    self.set_mode("DISCOVERING")

    def post_command(self, tag, path, body=None):
        """Submit the gesture POST; completion in _apply_gesture_result."""
        self.submit_rest(tag, "POST", path, body=body if body is not None else "")

    def _apply_gesture_result(self, tag, result):
        self.busy = False
        code, response = self._result_tuple(result)
        if code == 401:
            # Token rejected: unpaired keeps local_owner True via set_mode.
            self.enter_unpaired(tag)
            return
        if code == 200:
            try:
                doc = json.loads(response) if response else {}
            except json.JSONDecodeError:
                doc = {}
            if isinstance(doc, dict) and doc.get("success") and isinstance(doc.get("state"), dict):
                self.apply_phone_object(doc["state"], force=True)
            else:
                # Applied on the phone but no state came back: reconcile via a
                # soft resync on the next tick so the UI cannot sit stale.
                self.resync_after_command = True
            return
        if code == 0:
            # Transport failure: never stay dead-SYNCED after a failed toggle.
            # busy is already cleared above. Soft-resync once when the socket
            # was live (SYNCED/CONNECTING); otherwise go OFFLINE fast when
            # clearly unreachable. Other HTTP codes below are log-only.
            self.note_error("%s failed: phone unreachable" % tag)
            if self.mode in ("SYNCED", "CONNECTING"):
                # soft_resync submits the reachability probe; on budget
                # exhaustion / missing host it enters OFFLINE itself.
                # Already-resyncing keeps the in-flight probe (once).
                self.soft_resync("gesture transport fail")
            else:
                self.enter_offline("gesture transport fail")
            return
        self.note_error("%s failed: http %s" % (tag, code))

    def send_gesture(self, gesture):
        """Returns True when the gesture went async on the phone path (busy
        stays set until the result lands), False when applied locally."""
        if self.phone_commands_active():
            self.busy = True
            if gesture == "toggle":
                self.post_command("toggle", "/api/toggle", "")
            elif gesture == "skip":
                self.post_command("skip", "/api/skip", "")
            elif gesture == "reset":
                self.post_command("reset", "/api/reset", "")
            elif gesture == "extend":
                self.post_command("extend", "/api/extend", {"seconds_delta": EXTEND_SECONDS})
            return True
        if not self.model.local_owner:
            return False
        if gesture == "toggle":
            self.model.toggle()
        elif gesture == "skip":
            self.model.skip()
        elif gesture == "reset":
            self.model.reset()
        elif gesture == "extend":
            self.model.extend(EXTEND_SECONDS)
        return False
