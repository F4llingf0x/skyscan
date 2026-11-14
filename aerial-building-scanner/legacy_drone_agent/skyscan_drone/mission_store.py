"""Persistent mission progress — the battery-swap / multi-flight backbone.

Progress (plan id, next waypoint index, leg count, partial-scan flag) is synced
to disk after every waypoint, so a battery swap, agent restart, or Jetson power
cycle resumes exactly where the survey stopped. Partial lidar segments are also
flushed per-leg so no captured data is ever lost with the battery.
"""
from __future__ import annotations

import json
import os
from pathlib import Path


class MissionStore:
    def __init__(self, path: str):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def load(self) -> dict | None:
        if not self.path.exists():
            return None
        try:
            return json.loads(self.path.read_text())
        except Exception:
            return None

    def save(self, state: dict) -> None:
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(state, indent=2))
        os.replace(tmp, self.path)                       # atomic

    def clear(self) -> None:
        self.path.unlink(missing_ok=True)
