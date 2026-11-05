"""Bounded offline session history for POST /api/sessions/import."""

from __future__ import annotations

from .constants import IMPORT_MAX_AGE_S, IMPORT_MAX_FUTURE_S, PHASES, QUEUE_CAPACITY
from .persist import atomic_write, load_json


def _valid_type(value):
    return value in PHASES


class SessionQueue:
    def __init__(self, path, capacity=QUEUE_CAPACITY):
        self.path = path
        self.capacity = capacity
        self.items = []
        self.load()

    def count(self):
        return len(self.items)

    def empty(self):
        return not self.items

    def at(self, index):
        return self.items[index]

    def load(self):
        self.items = []
        data = load_json(self.path)
        if not isinstance(data, dict):
            return
        rows = data.get("sessions")
        if not isinstance(rows, list):
            return
        skipped = 0
        for row in rows:
            if not isinstance(row, dict):
                skipped += 1
                continue
            if len(self.items) >= self.capacity:
                skipped += 1
                continue
            client_id = str(row.get("client_id") or "")
            typ = str(row.get("type") or "")
            try:
                duration = int(row.get("duration") or 0)
            except (TypeError, ValueError):
                skipped += 1
                continue
            if not client_id or not _valid_type(typ) or duration <= 0:
                skipped += 1
                continue
            item = {
                "client_id": client_id,
                "type": typ,
                "duration": duration,
                # Only an explicit false is a partial (ADR 0002); older files
                # wrote True for everything.
                "completed": row.get("completed") is not False,
            }
            if "start" in row and row["start"] is not None:
                try:
                    start = int(row["start"])
                except (TypeError, ValueError):
                    start = 0
                if start > 0:
                    item["start"] = start
            tag = str(row.get("tag") or "")
            if tag:
                item["tag"] = tag
            self.items.append(item)
        _ = skipped

    def save(self):
        rows = []
        for item in self.items:
            row = {
                "client_id": item["client_id"],
                "type": item["type"],
                "duration": int(item["duration"]),
                "completed": item.get("completed") is not False,
            }
            if item.get("start"):
                row["start"] = int(item["start"])
            if item.get("tag"):
                row["tag"] = item["tag"]
            rows.append(row)
        atomic_write(self.path, {"sessions": rows}, mode=0o600)
        return True

    def enqueue(self, client_id, typ, duration_sec, start_epoch, tag="", completed=True):
        if not client_id:
            return False
        if not _valid_type(typ):
            return False
        duration_sec = int(duration_sec)
        if duration_sec <= 0:
            return False
        if len(self.items) >= self.capacity:
            self.drop_oldest()
        item = {
            "client_id": str(client_id),
            "type": typ,
            "duration": duration_sec,
            "completed": bool(completed),
        }
        if start_epoch is not None and int(start_epoch) >= 0:
            item["start"] = int(start_epoch)
        if tag:
            item["tag"] = str(tag)
        self.items.append(item)
        self.save()
        return True

    def drop_oldest(self):
        if self.items:
            self.items.pop(0)

    def drop_by_client_id(self, client_ids):
        if not client_ids or not self.items:
            return 0
        drop = set(client_ids)
        kept = []
        dropped = 0
        for item in self.items:
            if item.get("client_id") in drop:
                dropped += 1
                continue
            kept.append(item)
        self.items = kept
        if dropped:
            self.save()
        return dropped

    def strip_implausible_starts(self, now_epoch):
        now_epoch = int(now_epoch or 0)
        if now_epoch <= 0 or not self.items:
            return 0
        max_future = now_epoch + IMPORT_MAX_FUTURE_S
        min_start = now_epoch - IMPORT_MAX_AGE_S
        stripped = 0
        for item in self.items:
            start = item.get("start")
            if start is None:
                continue
            if start > max_future or start < min_start:
                item.pop("start", None)
                stripped += 1
        if stripped:
            self.save()
        return stripped

    def clear(self):
        self.items = []
        self.save()
