"""SkyScan drone companion agent — main entry point (Jetson Orin Nano).

Wires everything together and mirrors the StubDroneConnector's contract for
real hardware:

  poll commands  <- backend JetsonDroneConnector queue
                    UPLOAD_MISSION {plan}, START, RESUME, ABORT
  push events    -> ACCEPTED, TAKEOFF, TELEMETRY (1 Hz), BATTERY_SWAP,
                    OBSTACLE_STOP/CLEAR, LANDED-with-scan, ABORTED, FAILURE
  upload scans   -> multipart LAS + preview + geotagged photos

Run:  python3 -m skyscan_drone.agent --config /etc/skyscan/config.yaml
SITL: SKYSCAN_MOCK_SENSORS=1 + mavlink.device: udp:127.0.0.1:14550
"""
from __future__ import annotations

import argparse
import json
import logging
import math
import threading
import time
from pathlib import Path

import yaml

from .backend_client import BackendClient
from .flight_recorder import FlightRecorder
from .mavlink_controller import MavlinkController
from .mission_store import MissionStore
from .selftest import SelfTest, report_to_json
from .route_executor import AbortedError, RouteExecutor
from .scan_assembler import ScanAssembler
from .safety.avoidance import ForwardAvoidance
from .safety.monitor import SafetyMonitor
from .sensors.camera import Imx219Camera
from .sensors.i2c_mux import I2CMux
from .sensors.rangefinder import DownwardRangefinder
from .sensors.rplidar_c1 import RpLidarC1
from .sensors.tilt_servo import TiltServo

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)-7s [%(name)s] %(message)s")
log = logging.getLogger("agent")


class Agent:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.backend = BackendClient(cfg["backend"]["base_url"],
                                     cfg["backend"]["internal_token"], cfg["drone_id"])
        self.mav = MavlinkController(cfg["mavlink"]["device"], cfg["mavlink"]["baud"])
        self.mux = I2CMux(cfg["tilt"]["i2c_bus"])
        self.tilt = TiltServo(cfg["tilt"], self.mux)
        self.rangefinder = DownwardRangefinder(cfg["rangefinder"], self.mux,
                                               publish=self.mav.send_downward_range)
        self.assembler = ScanAssembler(cfg["lidar"]["mount"], self.mav.pose_at,
                                       self.tilt.read_angle_deg, lambda: 0.0)
        self.lidar = RpLidarC1(cfg["lidar"], on_points=self._on_lidar)
        self.camera = Imx219Camera(cfg["camera"], lambda: self.mav.state.pose)
        self.store = MissionStore(cfg["flight"]["battery"].get("resume_file",
                                  cfg["flight"].get("resume_file", "/var/lib/skyscan/progress.json")))
        self.recorder = FlightRecorder(cfg.get("logging", {}).get("dir", "/var/lib/skyscan/logs"),
                                       cfg.get("logging", {}).get("state_hz", 10.0))
        self.mav.on_statustext = self._on_fc_statustext
        self.selftest = SelfTest(cfg, self.mav, self.lidar, self.tilt,
                                 lambda: (None if self.rangefinder.last is None else
                                          (time.time() - self.rangefinder.last[0], self.rangefinder.last[1])),
                                 self.camera, self.backend)
        self.safety = SafetyMonitor(cfg["flight"], self.mav, self._in_geofence, self._event)
        self.avoidance = ForwardAvoidance(cfg["avoidance"], lambda: self.lidar.forward_min_m,
                                          lambda: self.lidar.nearest,
                                          lambda: self.mav.state.pose,
                                          self.mav, self._event)
        self.executor = RouteExecutor(cfg, self.mav, self.safety, self.avoidance,
                                      self.assembler, self.tilt, self.camera, self.store,
                                      self._event, preflight_check=self._preflight_check)
        self.mission: dict | None = None
        self._fence: list[tuple[float, float]] = []
        self._flight_thread: threading.Thread | None = None

    # ---------------------------------------------------------------- startup

    def start(self) -> None:
        self.mav.connect()
        self.rangefinder.start()
        self.lidar.start()
        threading.Thread(target=self._telemetry_loop, daemon=True, name="telemetry").start()
        log.info("agent up — drone %s polling %s", self.cfg["drone_id"],
                 self.cfg["backend"]["base_url"])
        self._command_loop()

    # ---------------------------------------------------------------- backend command loop

    def _command_loop(self) -> None:
        interval = self.cfg["backend"]["poll_interval_s"]
        while True:
            for cmd in self.backend.poll_commands():
                self.safety.link_ok()
                try:
                    self._handle(cmd)
                except Exception as e:
                    log.exception("command failed")
                    mid = (self.mission or {}).get("missionId", cmd.get("missionId", "?"))
                    self.backend.post_event_retrying("FAILURE", mid, {"error": str(e)})
            time.sleep(interval)

    def _handle(self, cmd: dict) -> None:
        ctype = cmd.get("type")
        log.info("command: %s", ctype)
        if ctype == "UPLOAD_MISSION":
            self.mission = cmd["plan"]
            self._fence = self._build_geofence(self.mission)
            self.backend.post_event("ACCEPTED", self.mission["missionId"])
        elif ctype == "START":
            if self.mission is None:
                raise RuntimeError("no uploaded mission on vehicle")
            if self._flight_thread and self._flight_thread.is_alive():
                self.executor.resume_requested = True     # START during swap = resume leg
            else:
                self._flight_thread = threading.Thread(target=self._fly, daemon=True, name="flight")
                self._flight_thread.start()
        elif ctype == "RESUME":
            self.executor.resume_requested = True
        elif ctype == "SELF_TEST":
            mid = cmd.get("missionId", (self.mission or {}).get("missionId", "none"))
            threading.Thread(target=self._run_selftest, args=(mid, "full"),
                             daemon=True, name="selftest").start()
        elif ctype == "ABORT":
            self.executor.abort_requested = True
            self.mav.rtl()

    # ---------------------------------------------------------------- flight

    def _fly(self) -> None:
        m = self.mission
        mid = m["missionId"]
        try:
            self.executor.abort_requested = False
            self.recorder.start_mission(mid, self._recorder_state)
            self.backend.post_event("TAKEOFF", mid)
            self.executor.run(m)

            # assemble + upload the product
            scan_dir = Path(self.cfg["output"]["scan_dir"]) / mid
            scan_dir.mkdir(parents=True, exist_ok=True)
            scan_path = str(scan_dir / f"{m['passType'].lower()}.las")
            n = self.assembler.finish(scan_path)
            gnss_meta = {"gnss": {"fixType": self.mav.state.gps_fix,
                                  "satellites": self.mav.state.satellites},
                         "tiltDeg": self.tilt.read_angle_deg(),
                         "pointCount": n, "source": "rplidar-c1 + pixhawk-ekf"}
            self.backend.upload_scan(mid, m["passType"], m["sensorType"], scan_path,
                                     preview_path=None,
                                     photo_paths=self.camera.all_photos(),
                                     gnss_meta=gnss_meta)
            self.backend.post_event_retrying("LANDED", mid, {"pointCount": n})
            self._finish_log(mid)
        except AbortedError as e:
            log.warning("mission aborted: %s", e)
            self.mav.rtl()
            self.recorder.error("mission", "aborted", str(e))
            self.backend.post_event_retrying("ABORTED", mid, {"reason": str(e)})
            self._finish_log(mid)
        except Exception as e:
            log.exception("flight failed")
            self.mav.rtl()
            self.recorder.error("mission", "failure", str(e))
            self.backend.post_event_retrying("FAILURE", mid, {"error": str(e)})
            self._finish_log(mid)

    # ---------------------------------------------------------------- observability

    def _event(self, event_type: str, payload: dict) -> None:
        """Single funnel for agent events: full detail to the flight log,
        and mission-routed delivery to the backend/console."""
        self.recorder.event("agent", event_type, payload)
        mid = (self.mission or {}).get("missionId")
        if mid:
            self.backend.post_event(event_type, mid, payload)

    def _on_fc_statustext(self, severity: int, name: str, text: str) -> None:
        self.recorder.fc_statustext(severity, name, text)
        if severity <= 4:      # WARNING and worse: surface in the console live
            self._event("FC_MESSAGE", {"severity": name, "text": text})

    def _recorder_state(self) -> dict:
        s = self.mav.state
        return {"lat": s.pose.lat, "lon": s.pose.lon, "altM": s.pose.alt_rel_m,
                "roll": round(s.pose.roll, 4), "pitch": round(s.pose.pitch, 4),
                "yaw": round(s.pose.yaw, 4), "batteryPct": s.battery_pct,
                "batteryV": s.battery_v, "mode": s.mode, "armed": s.armed,
                "ekfOk": s.ekf_ok, "gpsFix": s.gps_fix, "sats": s.satellites,
                "fwdMinM": None if not math.isfinite(self.lidar.forward_min_m)
                           else round(self.lidar.forward_min_m, 2),
                "tiltDeg": round(self.tilt.read_angle_deg(), 2)}

    def _run_selftest(self, mission_id: str, mode: str) -> None:
        rep = self.selftest.run(mode)
        self.recorder.event("selftest", "REPORT", rep.json())
        self.backend.post_event_retrying("SELFTEST_REPORT", mission_id, rep.json())

    def _preflight_check(self) -> tuple[bool, list[str]]:
        """Quick self-test before every leg; FAILs block takeoff with specifics."""
        rep = self.selftest.run("quick")
        mid = (self.mission or {}).get("missionId", "none")
        self.backend.post_event("SELFTEST_REPORT", mid, rep.json())
        fails = [f"{c['component']}: {c['message']}" for c in rep.checks if c["status"] == "FAIL"]
        return len(fails) == 0, fails

    def _finish_log(self, mission_id: str) -> None:
        self.recorder.stop()
        if self.recorder.current_path:
            try:
                self.backend.upload_flight_log(mission_id, str(self.recorder.current_path))
            except Exception as e:
                log.warning("log upload failed: %s", e)

    # ---------------------------------------------------------------- streams

    def _on_lidar(self, pts) -> None:
        self.assembler.on_lidar(pts)
        # keep the FC's own avoidance fed regardless of companion state
        try:
            if math.isfinite(self.lidar.forward_min_m):
                self.mav.send_forward_obstacle(self.lidar.forward_min_m,
                                               self.cfg["lidar"]["max_range_m"])
            # full ring -> FC path planning (BendyRuler routes around obstacles on its own)
            self.mav.send_obstacle_ring(self.lidar.sectors_m, self.cfg["lidar"]["max_range_m"])
        except Exception:
            pass

    def _telemetry_loop(self) -> None:
        while True:
            if self.mission and self.mav.state.armed:
                s = self.mav.state
                self.backend.post_event("TELEMETRY", self.mission["missionId"], {
                    "lat": s.pose.lat, "lon": s.pose.lon, "altM": s.pose.alt_rel_m,
                    "speedMps": self.cfg["flight"]["cruise_speed_mps"],
                    "batteryPct": s.battery_pct, "flightMode": s.mode,
                    "progressPct": self._progress_pct(),
                })
            time.sleep(1.0)

    def _progress_pct(self) -> float:
        st = self.store.load()
        if not st or not self.mission:
            return 0.0
        try:
            total = len(json.loads(self.mission["routeJson"])["coordinates"])
            return min(100.0, 100.0 * st.get("nextWp", 0) / max(1, total))
        except Exception:
            return 0.0

    # ---------------------------------------------------------------- geofence

    def _build_geofence(self, mission: dict) -> list[tuple[float, float]]:
        """Mission polygon inflated by the configured margin (simple radial buffer)."""
        ring = json.loads(mission["polygonGeoJson"])["coordinates"][0]
        clat = sum(p[1] for p in ring) / len(ring)
        clon = sum(p[0] for p in ring) / len(ring)
        m_lat, m_lon = 110540.0, 111320.0 * math.cos(math.radians(clat))
        margin = self.cfg["flight"]["geofence_margin_m"]
        out = []
        for lon, lat in [(p[0], p[1]) for p in ring]:
            dx, dy = (lon - clon) * m_lon, (lat - clat) * m_lat
            d = math.hypot(dx, dy) or 1.0
            k = (d + margin) / d
            out.append((clat + dy * k / m_lat, clon + dx * k / m_lon))
        return out

    def _in_geofence(self, lat: float, lon: float) -> bool:
        if not self._fence or (lat == 0 and lon == 0):
            return True
        inside = False
        v = self._fence
        for i in range(len(v)):
            (y1, x1), (y2, x2) = v[i], v[(i + 1) % len(v)]
            if (y1 > lat) != (y2 > lat) and lon < (x2 - x1) * (lat - y1) / (y2 - y1) + x1:
                inside = not inside
        return inside


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="config.yaml")
    args = ap.parse_args()
    cfg = yaml.safe_load(open(args.config))
    Agent(cfg).start()


if __name__ == "__main__":
    main()
