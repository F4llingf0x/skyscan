"""HTTP link to the SkyScan backend.

The agent always dials OUT (poll for commands, POST events/scans), so the drone
works behind NAT/LTE with no inbound ports. Counterpart of the backend's
JetsonDroneConnector, which queues commands and ingests these events.
"""
from __future__ import annotations

import logging
import time
from typing import Any

import requests

log = logging.getLogger("backend")


class BackendClient:
    def __init__(self, base_url: str, internal_token: str, drone_id: str):
        self.base = base_url.rstrip("/")
        self.drone_id = drone_id
        self.session = requests.Session()
        self.session.headers["X-Internal-Token"] = internal_token

    # ---------------------------------------------------------- commands (poll)

    def poll_commands(self) -> list[dict[str, Any]]:
        try:
            r = self.session.get(f"{self.base}/internal/drone/commands",
                                 params={"droneId": self.drone_id}, timeout=10)
            r.raise_for_status()
            return r.json()
        except Exception as e:  # link loss is normal in the field — the agent keeps flying
            log.debug("command poll failed: %s", e)
            return []

    # ---------------------------------------------------------- events (push)

    def post_event(self, event_type: str, mission_id: str, payload: dict | None = None) -> bool:
        body = {"droneId": self.drone_id, "type": event_type,
                "missionId": mission_id, "payload": payload or {}}
        try:
            r = self.session.post(f"{self.base}/internal/drone/events", json=body, timeout=10)
            r.raise_for_status()
            return True
        except Exception as e:
            log.warning("event %s not delivered: %s", event_type, e)
            return False

    def post_event_retrying(self, event_type: str, mission_id: str,
                            payload: dict | None = None, attempts: int = 30) -> None:
        """For lifecycle-critical events (LANDED, FAILURE): retry until delivered."""
        for _ in range(attempts):
            if self.post_event(event_type, mission_id, payload):
                return
            time.sleep(2)
        log.error("giving up delivering %s for %s", event_type, mission_id)

    # ---------------------------------------------------------- scan + photo upload

    def upload_scan(self, mission_id: str, pass_type: str, sensor_type: str,
                    scan_path: str, preview_path: str | None,
                    photo_paths: list[str], gnss_meta: dict) -> None:
        files = [("scan", ("scan.las", open(scan_path, "rb"), "application/octet-stream"))]
        if preview_path:
            files.append(("preview", ("preview.png", open(preview_path, "rb"), "image/png")))
        for i, p in enumerate(photo_paths):
            files.append(("photos", (f"photo_{i:04d}.jpg", open(p, "rb"), "image/jpeg")))
        data = {"droneId": self.drone_id, "missionId": mission_id,
                "passType": pass_type, "sensorType": sensor_type,
                "gnssImuMetaJson": __import__("json").dumps(gnss_meta)}
        # Scans are the product — retry hard.
        for attempt in range(30):
            try:
                r = self.session.post(f"{self.base}/internal/drone/scans",
                                      data=data, files=files, timeout=300)
                r.raise_for_status()
                log.info("scan uploaded for mission %s (%d photos)", mission_id, len(photo_paths))
                return
            except Exception as e:
                log.warning("scan upload attempt %d failed: %s", attempt + 1, e)
                time.sleep(min(60, 2 ** attempt))
                for _, (name, fh, _t) in files:
                    fh.seek(0)
        raise RuntimeError("scan upload failed permanently")

    def upload_flight_log(self, mission_id: str, log_path: str) -> None:
        """Full-detail JSONL flight log -> mission page download."""
        for attempt in range(10):
            try:
                with open(log_path, "rb") as fh:
                    r = self.session.post(f"{self.base}/internal/drone/logs",
                                          data={"droneId": self.drone_id, "missionId": mission_id},
                                          files={"log": ("flight.jsonl", fh, "application/x-ndjson")},
                                          timeout=120)
                r.raise_for_status()
                log.info("flight log uploaded for %s", mission_id)
                return
            except Exception as e:
                log.warning("log upload attempt %d failed: %s", attempt + 1, e)
                time.sleep(min(30, 2 ** attempt))
        log.error("flight log not uploaded — retained locally at %s", log_path)
