"""Waybar custom-module JSON for a pomo-link status snapshot."""

from __future__ import annotations

import json

WORK_ICON = "\uf252"
BREAK_ICON = "\uf0f4"
OFFLINE_ICON = "\uf071"
SEP = "│"
DIM = "#6a6a6a"

CONTROL_ICONS = {"skip": "⏭", "extend": "+5", "reset": "⟳"}


def format_mmss(seconds):
    try:
        n = int(seconds)
    except (TypeError, ValueError):
        n = 0
    if n < 0:
        n = 0
    return "%d:%02d" % (n // 60, n % 60)


def _sep():
    return "<span foreground='%s'>%s</span>" % (DIM, SEP)


def _dump(doc):
    return json.dumps(doc, ensure_ascii=False, separators=(",", ":"))


def link_state(status):
    """Who owns the clock, as one CSS class.

    online      SYNCED, the phone is the clock
    local       OFFLINE, this daemon is the clock (a normal, working state)
    connecting  BOOT / DISCOVERING / CONNECTING, transient
    unpaired    no token or token rejected; needs the user
    dead        no daemon status at all
    """
    if not isinstance(status, dict):
        return "dead"
    mode = str(status.get("mode") or "")
    if mode == "SYNCED":
        return "online"
    if mode == "UNPAIRED" or status.get("has_token") is not True:
        return "unpaired"
    if mode == "OFFLINE":
        return "local"
    return "connecting"


def format_waybar(status):
    if not isinstance(status, dict):
        return _dump(
            {
                "text": "%s %s --:--" % (OFFLINE_ICON, _sep()),
                "class": "stopped work dead",
                "tooltip": "pomo-link is not running",
            }
        )

    mode = str(status.get("mode") or "")
    st = str(status.get("status") or "stopped")
    phase = str(status.get("phase") or "work")
    remaining = status.get("remaining") or 0
    completed = status.get("completed") or 0
    goal = status.get("goal") or 0
    local_owner = status.get("local_owner") is True
    busy = status.get("busy") is True
    message = str(status.get("message") or "").strip()
    net = link_state(status)

    if net == "unpaired":
        icon = OFFLINE_ICON
    elif phase in ("short", "long"):
        icon = BREAK_ICON
    else:
        icon = WORK_ICON

    text = "%s %s %s" % (icon, _sep(), format_mmss(remaining))
    tooltip = "%s %s - %s/%s sessions" % (st, phase, completed, goal)
    if net != "online":
        if local_owner:
            tooltip += " (local)"
        elif mode:
            tooltip += " (%s)" % mode.lower()
    if busy:
        tooltip += "\nsending to phone…"
    if message:
        tooltip += "\n%s" % message

    classes = [st, phase, net]
    if busy:
        classes.append("busy")
    return _dump({"text": text, "class": " ".join(classes), "tooltip": tooltip})


def format_control(status, kind):
    """One drawer button (skip / extend / reset) for themes that group them."""
    icon = CONTROL_ICONS.get(kind, "?")
    if not isinstance(status, dict):
        return _dump({"text": icon, "class": "inactive", "tooltip": "pomo-link is not running"})
    st = str(status.get("status") or "stopped")
    phase = str(status.get("phase") or "work")
    if kind == "extend":
        active = st == "running"
        tooltip = "Add 5 min" if active else "Timer not running"
    elif kind == "reset":
        active = st in ("running", "paused")
        tooltip = "Reset this phase" if active else "Nothing to reset"
    else:
        active = True
        tooltip = "Skip to break" if phase == "work" else "Skip to work"
    if status.get("busy") is True:
        active = False
        tooltip = "sending to phone…"
    return _dump({"text": icon, "class": "active" if active else "inactive", "tooltip": tooltip})
