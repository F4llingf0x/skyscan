"""Pre-flight self-test — remote-triggerable diagnostics with a specific verdict
per component, so "something is wrong" is never the error message.

Each check returns: component, PASS/WARN/FAIL/SKIP, the measured value, a
plain-language message, and a fix hint. Two profiles:
  * full  — initial setup / operator-requested: includes actuation (tilt sweep
            with encoder tracking, camera test capture). Ground only, props off.
  * quick — automatic before every flight leg: read-only checks (link, FC
            sensor health bits, GPS, EKF, battery, lidar stream, rangefinder,
            disk). A FAIL here blocks takeoff and reports exactly why.

The report is posted to the backend and rendered as a checklist in the console.
"""
from __future__ import annotations

import json
import logging
import os
import shutil
import time
from dataclasses import asdict, dataclass, field

log = logging.getLogger("selftest")

# MAV_SYS_STATUS_SENSOR bits worth naming individually
FC_SENSORS = [
    (0x01, "gyro_3d", "3D gyroscope"),
    (0x02, "accel_3d", "3D accelerometer"),
    (0x04, "mag_3d", "3D magnetometer (compass)"),
    (0x08, "baro", "absolute pressure (baro)"),
    (0x20, "gps", "GPS"),
    (0x10000, "rc_receiver", "RC receiver"),
    (0x2000000, "battery_monitor", "battery monitor"),
]


@dataclass
class CheckResult:
    component: str
    status: str          # PASS | WARN | FAIL | SKIP
    value: str
    message: str
    hint: str = ""


@dataclass
class SelfTestReport:
    droneId: str
    mode: str
    at: float = field(default_factory=time.time)
    overall: str = "PASS"
    checks: list = field(default_factory=list)

    def add(self, r: CheckResult) -> None:
        self.checks.append(asdict(r))
        order = {"PASS": 0, "SKIP": 0, "WARN": 1, "FAIL": 2}
        if order[r.status] > order[self.overall if self.overall != "PASS" else "PASS"]:
            self.overall = r.status

    def json(self) -> dict:
        return asdict(self)


class SelfTest:
    def __init__(self, cfg, mav, lidar, tilt, rangefinder_last, camera, backend):
        self.cfg, self.mav, self.lidar, self.tilt = cfg, mav, lidar, tilt
        self.rangefinder_last = rangefinder_last     # () -> (age_s, dist_m) | None
        self.camera, self.backend = camera, backend

    # ------------------------------------------------------------ suite

    def run(self, mode: str = "full") -> SelfTestReport:
        rep = SelfTestReport(droneId=self.cfg["drone_id"], mode=mode)
        rep.add(self._backend_link())
        rep.add(self._fc_link())
        for r in self._fc_sensor_bits():
            rep.add(r)
        rep.add(self._gps())
        rep.add(self._ekf())
        rep.add(self._battery())
        for r in self._fc_recent_errors():
            rep.add(r)
        rep.add(self._lidar())
        rep.add(self._rangefinder())
        rep.add(self._disk())
        if mode == "full":
            rep.add(self._tilt_sweep())
            rep.add(self._camera())
        else:
            rep.add(self._tilt_quick())
        log.info("self-test (%s): %s — %d checks", mode, rep.overall, len(rep.checks))
        return rep

    # ------------------------------------------------------------ checks

    def _backend_link(self) -> CheckResult:
        t0 = time.time()
        cmds = self.backend.poll_commands()
        dt = (time.time() - t0) * 1000
        if cmds is not None and dt < 5000:
            return CheckResult("backend_link", "PASS", f"{dt:.0f} ms",
                               "SkyScan backend reachable and authenticated")
        return CheckResult("backend_link", "WARN", "timeout",
                           "Backend not reachable — flight can proceed, events will queue",
                           "Check LTE/WiFi and internal token in config.yaml")

    def _fc_link(self) -> CheckResult:
        p = self.mav.state.pose
        age = time.time() - p.t if p.t else 1e9
        if age < 2:
            return CheckResult("fc_link", "PASS", f"pose age {age*1000:.0f} ms",
                               "Flight controller streaming (MAVLink healthy)")
        return CheckResult("fc_link", "FAIL", f"pose age {age:.0f} s",
                           "No recent position stream from the Pixhawk",
                           "Check TELEM2 wiring, SERIAL2_PROTOCOL=2, SERIAL2_BAUD=921")

    def _fc_sensor_bits(self) -> list[CheckResult]:
        present, enabled, health = self.mav.state.fc_sensors
        out = []
        for bit, comp, label in FC_SENSORS:
            if not present & bit:
                out.append(CheckResult(f"fc.{comp}", "WARN", "not present",
                                       f"{label}: not reported by the FC",
                                       "Expected on 6C Pro — check wiring/params" if comp != "rc_receiver"
                                       else "Bind the RC receiver — manual override is required"))
            elif not enabled & bit:
                out.append(CheckResult(f"fc.{comp}", "WARN", "disabled",
                                       f"{label}: present but disabled in FC params"))
            elif not health & bit:
                out.append(CheckResult(f"fc.{comp}", "FAIL", "unhealthy",
                                       f"{label}: PRESENT BUT UNHEALTHY",
                                       "See FC messages below; recalibrate or check wiring"))
            else:
                out.append(CheckResult(f"fc.{comp}", "PASS", "healthy", f"{label}: healthy"))
        return out

    def _gps(self) -> CheckResult:
        s = self.mav.state
        fixes = {0: "no GPS", 1: "no fix", 2: "2D", 3: "3D", 4: "DGPS", 5: "RTK float", 6: "RTK fixed"}
        v = f"{fixes.get(s.gps_fix, s.gps_fix)}, {s.satellites} sats"
        if s.gps_fix >= 5:
            return CheckResult("gps", "PASS", v, "RTK solution — survey-grade positioning")
        if s.gps_fix >= 3 and s.satellites >= 8:
            return CheckResult("gps", "WARN", v,
                               "3D fix but no RTK — cloud accuracy will be metre-class",
                               "Check NTRIP corrections / F9P; acceptable for test flights")
        if s.gps_fix >= 3:
            return CheckResult("gps", "WARN", v, "3D fix with few satellites",
                               "Move away from obstructions; wait for more satellites")
        return CheckResult("gps", "FAIL", v, "No 3D GPS fix — cannot fly autonomously",
                           "GNSS module missing or no sky view (see hardware gap list)")

    def _ekf(self) -> CheckResult:
        if self.mav.state.ekf_ok:
            return CheckResult("ekf", "PASS", "healthy", "EKF position/velocity estimates healthy")
        return CheckResult("ekf", "FAIL", "not converged",
                           "EKF not healthy — position estimate unreliable",
                           "Wait after boot; check compass calibration and GPS")

    def _battery(self) -> CheckResult:
        s = self.mav.state
        minimum = self.cfg["flight"]["battery"]["leg_start_min_pct"]
        v = f"{s.battery_pct:.0f}% / {s.battery_v:.1f} V"
        if s.battery_v < 5:
            return CheckResult("battery", "FAIL", v, "No battery telemetry",
                               "Power module (PM02D) not detected — see hardware gaps")
        if s.battery_pct >= minimum:
            return CheckResult("battery", "PASS", v, "Battery ready for a full leg")
        return CheckResult("battery", "FAIL", v,
                           f"Battery below leg-start minimum ({minimum}%)", "Swap battery")

    def _fc_recent_errors(self) -> list[CheckResult]:
        msgs = self.mav.recent_statustext(max_age_s=120, min_severity=4)  # <= WARNING
        if not msgs:
            return [CheckResult("fc.messages", "PASS", "none",
                                "No warnings/errors from the flight controller (last 2 min)")]
        return [CheckResult("fc.messages", "FAIL" if m["severity"] <= 3 else "WARN",
                            m["severityName"], f"FC says: {m['text']}",
                            "This is the flight controller's own diagnosis — fix it verbatim")
                for m in msgs[-5:]]

    def _lidar(self) -> CheckResult:
        age, rate = self.lidar.stream_stats()
        v = f"{rate:.1f} scans/s, last {age*1000:.0f} ms ago"
        if age < 1.5 and rate >= self.cfg["lidar"]["scan_hz"] * 0.6:
            return CheckResult("lidar", "PASS", v, "RPLidar C1 streaming")
        if age < 5:
            return CheckResult("lidar", "WARN", v, "Lidar streaming below expected rate",
                               "Check USB power; C1 wants a stable 5 V")
        return CheckResult("lidar", "FAIL", v, "No lidar data",
                           "Check /dev/ttyUSB0, cable, and that the motor spins")

    def _rangefinder(self) -> CheckResult:
        last = self.rangefinder_last()
        if last is None:
            return CheckResult("rangefinder", "FAIL", "no data",
                               "Downward TFmini-S not reporting",
                               "UART mode: check port/baud (115200) and 5 V supply; "
                               "I2C mode: check TCA9548A channel and address 0x10")
        age, dist = last
        v = f"{dist:.2f} m, {age*1000:.0f} ms ago"
        if age < 2 and 0.1 < dist < 12.0:
            return CheckResult("rangefinder", "PASS", v, "Downward TFmini-S healthy")
        return CheckResult("rangefinder", "WARN", v, "Rangefinder reading stale or implausible",
                           "IR sensor: bright sunlight on dark surfaces shortens range")

    def _disk(self) -> CheckResult:
        free_gb = shutil.disk_usage(self.cfg["output"]["scan_dir"]
                                    if os.path.isdir(self.cfg["output"]["scan_dir"]) else "/").free / 1e9
        v = f"{free_gb:.1f} GB free"
        if free_gb > 5:
            return CheckResult("storage", "PASS", v, "Enough space for scan sessions")
        if free_gb > 2:
            return CheckResult("storage", "WARN", v, "Storage getting low", "Offload old sessions")
        return CheckResult("storage", "FAIL", v, "Not enough space for a scan session")

    def _tilt_sweep(self) -> CheckResult:
        cfg = self.cfg["tilt"]
        worst = 0.0
        for target in (cfg["min_deg"], 0.0, cfg["max_deg"], cfg["structure_deg"]):
            measured = self.tilt.move_to(target)
            worst = max(worst, abs(measured - max(cfg["min_deg"], min(cfg["max_deg"], target))))
        v = f"max tracking error {worst:.2f}°"
        if worst <= cfg["settle_tolerance_deg"]:
            return CheckResult("tilt", "PASS", v, "Tilt axis tracks across full range (encoder verified)")
        if worst <= 2.0:
            return CheckResult("tilt", "WARN", v, "Tilt settles but outside tolerance",
                               "Re-run servo pulse calibration; check linkage backlash")
        return CheckResult("tilt", "FAIL", v, "Tilt axis not tracking commanded angles",
                           "Check DS3218 power (separate BEC!), PCA9685 channel, AS5600 magnet gap")

    def _tilt_quick(self) -> CheckResult:
        deg = self.tilt.read_angle_deg()
        if self.cfg["tilt"]["min_deg"] - 5 <= deg <= self.cfg["tilt"]["max_deg"] + 5:
            return CheckResult("tilt", "PASS", f"{deg:.2f}°", "Tilt encoder reading plausible")
        return CheckResult("tilt", "FAIL", f"{deg:.2f}°", "Tilt encoder reading out of range",
                           "AS5600 magnet misaligned or mux channel wrong")

    def _camera(self) -> CheckResult:
        try:
            self.camera.start_session("selftest")
            p = self.camera.capture()
            size = os.path.getsize(p) if p else 0
            if size > 1000 or (size > 0 and os.environ.get("SKYSCAN_MOCK_SENSORS")):
                return CheckResult("camera", "PASS", f"{size} bytes", "IMX219 test capture OK")
            return CheckResult("camera", "WARN", f"{size} bytes", "Capture produced a suspicious file")
        except Exception as e:
            return CheckResult("camera", "FAIL", "exception", f"Camera capture failed: {e}",
                               "Check CSI ribbon seating and nvargus-daemon")


def report_to_json(rep: SelfTestReport) -> str:
    return json.dumps(rep.json())
