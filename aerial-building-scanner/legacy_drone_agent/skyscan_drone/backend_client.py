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
                    scan_path: str | None, preview_path: str | None,
                    photo_paths: list[str], gnss_meta: dict) -> None:
        files = []
        if scan_path is not None:
            files.append(("scan", ("scan.las", open(scan_path, "rb"), "application/octet-stream")))
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

    def upload_plants(self, mission_id: str, plants: list) -> bool:
        """Detected plants (crude pass) -> backend, which stores them instead of
        running its own stub detector. Best-effort with a few retries."""
        return self._post_json(f"/internal/drone/missions/{mission_id}/plants",
                               {"droneId": self.drone_id, "plants": plants},
                               what="plants")

    def upload_findings(self, mission_id: str, findings: list) -> bool:
        """Per-plant disease findings (inspection pass) -> backend."""
        return self._post_json(f"/internal/drone/missions/{mission_id}/findings",
                               {"droneId": self.drone_id, "findings": findings},
                               what="findings")

    def upload_sightings(self, mission_id: str, sightings: list) -> bool:
        """Full tagged-animal list (WILDLIFE_SURVEY) at landing."""
        return self._post_json(f"/internal/drone/missions/{mission_id}/sightings",
                               {"droneId": self.drone_id, "sightings": sightings},
                               what="sightings")

    def post_live_sighting(self, mission_id: str, sighting: dict) -> None:
        """One sighting streamed DURING the pass — best effort, never blocks
        the flight loop (single attempt, short timeout)."""
        try:
            self.session.post(f"{self.base}/internal/drone/missions/{mission_id}/sightings/live",
                              json=sighting, timeout=5)
        except Exception as e:
            log.debug("live sighting post failed (will be in the landing upload): %s", e)

    def upload_plant_photo(self, mission_id: str, plant_index: int, path: str) -> None:
        self._post_file(f"/internal/drone/missions/{mission_id}/plants/by-index/{plant_index}/photo",
                        path, "plant photo")

    def upload_sighting_photo(self, mission_id: str, label: str, path: str) -> None:
        self._post_file(f"/internal/drone/missions/{mission_id}/sightings/{label}/photo",
                        path, "sighting photo")

    def _post_file(self, url_path: str, file_path: str, what: str) -> None:
        """Best-effort multipart upload (photos are evidence, not flight-critical)."""
        try:
            with open(file_path, "rb") as f:
                r = self.session.post(f"{self.base}{url_path}",
                                      files={"file": ("photo.jpg", f, "image/jpeg")}, timeout=60)
            r.raise_for_status()
        except Exception as e:
            log.warning("%s upload failed (%s): %s", what, url_path, e)

    def _post_json(self, path: str, body: dict, what: str) -> bool:
        for attempt in range(10):
            try:
                r = self.session.post(f"{self.base}{path}", json=body, timeout=60)
                r.raise_for_status()
                log.info("%s uploaded for %s", what, path)
                return True
            except Exception as e:
                log.warning("%s upload attempt %d failed: %s", what, attempt + 1, e)
                time.sleep(min(30, 2 ** attempt))
        log.error("%s upload failed permanently", what)
        return False

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
