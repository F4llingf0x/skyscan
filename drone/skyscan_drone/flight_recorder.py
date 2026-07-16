"""Flight recorder — maximum-detail structured logging during flight.

Everything goes to a per-mission JSONL file on the Jetson (NVMe recommended):
  * vehicle state at 10 Hz (pose, battery, mode, EKF, GPS, forward min-range,
    measured tilt) — far denser than the 1 Hz backend telemetry,
  * every agent event (commands, waypoints, legs, tilt moves, obstacles,
    battery swaps, self-tests) with component tags,
  * every flight-controller STATUSTEXT (the FC's own words: "PreArm: ...",
    "EKF variance", failsafe messages) with severity,
  * every error with component + specific message.

After each flight the log is uploaded to the backend and appears on the
mission page for download, so post-flight analysis never depends on shell
access to the drone. Local files rotate but are retained (crash forensics).
"""
from __future__ import annotations

import json
import logging
import threading
import time
from pathlib import Path

log = logging.getLogger("recorder")


class FlightRecorder:
    def __init__(self, log_dir: str, state_hz: float = 10.0):
        self.dir = Path(log_dir)
        self.dir.mkdir(parents=True, exist_ok=True)
        self.state_hz = state_hz
        self._fh = None
        self._lock = threading.Lock()
        self._running = False
        self._state_provider = None
        self.current_path: Path | None = None

    # ------------------------------------------------------------ lifecycle

    def start_mission(self, mission_id: str, state_provider) -> None:
        self.stop()
        self.current_path = self.dir / f"flight-{mission_id}-{int(time.time())}.jsonl"
        self._fh = open(self.current_path, "a", buffering=1)   # line-buffered
        self._state_provider = state_provider
        self._running = True
        threading.Thread(target=self._state_loop, daemon=True, name="recorder").start()
        self.event("recorder", "MISSION_LOG_START", {"missionId": mission_id})
        log.info("flight recorder -> %s", self.current_path)

    def stop(self) -> None:
        self._running = False
        if self._fh:
            self.event("recorder", "MISSION_LOG_END", {})
            self._fh.close()
            self._fh = None

    # ------------------------------------------------------------ writers

    def event(self, component: str, event_type: str, payload: dict) -> None:
        self._write({"t": time.time(), "kind": "event", "component": component,
                     "type": event_type, "data": payload})

    def error(self, component: str, message: str, detail: str = "") -> None:
        self._write({"t": time.time(), "kind": "error", "component": component,
                     "message": message, "detail": detail})
        log.error("[%s] %s %s", component, message, detail)

    def fc_statustext(self, severity: int, severity_name: str, text: str) -> None:
        self._write({"t": time.time(), "kind": "fc_statustext",
                     "severity": severity, "severityName": severity_name, "text": text})

    def _state_loop(self) -> None:
        period = 1.0 / self.state_hz
        while self._running:
            try:
                if self._state_provider:
                    self._write({"t": time.time(), "kind": "state", **self._state_provider()})
            except Exception as e:
                log.debug("state record failed: %s", e)
            time.sleep(period)

    def _write(self, obj: dict) -> None:
        with self._lock:
            if self._fh:
                self._fh.write(json.dumps(obj, separators=(",", ":")) + "\n")
