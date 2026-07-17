"""Pixhawk 6C Pro interface (ArduPilot Copter) over MAVLink via pymavlink.

Responsibilities: connection + heartbeat, mode/arm/takeoff/RTL, guided waypoint
flight, pose + battery streams for the scan assembler and leg planner, and
injecting companion sensor data (downward TFmini-S, forward obstacle distance)
into the flight controller's EKF/avoidance.
"""
from __future__ import annotations

import logging
import math
import threading
import time
from dataclasses import dataclass, field

from pymavlink import mavutil

log = logging.getLogger("mavlink")


@dataclass
class Pose:
    t: float = 0.0
    lat: float = 0.0
    lon: float = 0.0
    alt_rel_m: float = 0.0
    roll: float = 0.0
    pitch: float = 0.0
    yaw: float = 0.0


SEVERITY_NAMES = {0: "EMERGENCY", 1: "ALERT", 2: "CRITICAL", 3: "ERROR",
                  4: "WARNING", 5: "NOTICE", 6: "INFO", 7: "DEBUG"}


@dataclass
class VehicleState:
    pose: Pose = field(default_factory=Pose)
    battery_pct: float = 100.0
    battery_v: float = 0.0
    mode: str = "UNKNOWN"
    armed: bool = False
    ekf_ok: bool = False
    gps_fix: int = 0
    satellites: int = 0
    fc_sensors: tuple = (0, 0, 0)     # SYS_STATUS present/enabled/health bitmasks


class MavlinkController:
    def __init__(self, device: str, baud: int):
        self.device, self.baud = device, baud
        self.master: mavutil.mavfile | None = None
        self.state = VehicleState()
        self._pose_buffer: list[Pose] = []          # ring buffer for scan/pose time sync
        self._statustext: list[dict] = []           # the FC's own messages (errors verbatim)
        self.on_statustext = None                   # callback(severity, name, text)
        self._lock = threading.Lock()
        self._running = False

    # ---------------------------------------------------------- lifecycle

    def connect(self) -> None:
        log.info("connecting to flight controller at %s @ %d", self.device, self.baud)
        self.master = mavutil.mavlink_connection(self.device, baud=self.baud, autoreconnect=True)
        self.master.wait_heartbeat(timeout=30)
        log.info("heartbeat from system %d component %d", self.master.target_system,
                 self.master.target_component)
        # ask for the streams we live on
        for msg_id, hz in ((mavutil.mavlink.MAVLINK_MSG_ID_GLOBAL_POSITION_INT, 10),
                           (mavutil.mavlink.MAVLINK_MSG_ID_ATTITUDE, 20),
                           (mavutil.mavlink.MAVLINK_MSG_ID_SYS_STATUS, 2),
                           (mavutil.mavlink.MAVLINK_MSG_ID_GPS_RAW_INT, 2)):
            self.master.mav.command_long_send(
                self.master.target_system, self.master.target_component,
                mavutil.mavlink.MAV_CMD_SET_MESSAGE_INTERVAL, 0,
                msg_id, int(1e6 / hz), 0, 0, 0, 0, 0)
        self._running = True
        threading.Thread(target=self._rx_loop, daemon=True, name="mavlink-rx").start()

    def _rx_loop(self) -> None:
        while self._running:
            msg = self.master.recv_match(blocking=True, timeout=1)
            if msg is None:
                continue
            t = time.time()
            mt = msg.get_type()
            if mt == "GLOBAL_POSITION_INT":
                with self._lock:
                    p = self.state.pose
                    p.t, p.lat, p.lon = t, msg.lat / 1e7, msg.lon / 1e7
                    p.alt_rel_m = msg.relative_alt / 1000.0
                    self._pose_buffer.append(Pose(t, p.lat, p.lon, p.alt_rel_m,
                                                  p.roll, p.pitch, p.yaw))
                    if len(self._pose_buffer) > 4000:
                        del self._pose_buffer[:2000]
            elif mt == "ATTITUDE":
                with self._lock:
                    p = self.state.pose
                    p.roll, p.pitch, p.yaw = msg.roll, msg.pitch, msg.yaw
            elif mt == "SYS_STATUS":
                self.state.battery_pct = max(0, msg.battery_remaining)
                self.state.battery_v = msg.voltage_battery / 1000.0
                self.state.fc_sensors = (msg.onboard_control_sensors_present,
                                         msg.onboard_control_sensors_enabled,
                                         msg.onboard_control_sensors_health)
            elif mt == "STATUSTEXT":
                name = SEVERITY_NAMES.get(msg.severity, str(msg.severity))
                entry = {"t": t, "severity": msg.severity, "severityName": name, "text": msg.text}
                with self._lock:
                    self._statustext.append(entry)
                    if len(self._statustext) > 500:
                        del self._statustext[:250]
                (log.warning if msg.severity <= 4 else log.info)("FC[%s]: %s", name, msg.text)
                if self.on_statustext:
                    try: self.on_statustext(msg.severity, name, msg.text)
                    except Exception: pass
            elif mt == "GPS_RAW_INT":
                self.state.gps_fix, self.state.satellites = msg.fix_type, msg.satellites_visible
            elif mt == "HEARTBEAT" and msg.type != mavutil.mavlink.MAV_TYPE_GCS:
                self.state.armed = bool(msg.base_mode & mavutil.mavlink.MAV_MODE_FLAG_SAFETY_ARMED)
                self.state.mode = mavutil.mode_string_v10(msg)
            elif mt == "EKF_STATUS_REPORT":
                self.state.ekf_ok = bool(msg.flags & 0x1F)

    def pose_at(self, t: float) -> Pose:
        """Interpolated pose for lidar timestamp sync (the georeferencing backbone)."""
        with self._lock:
            buf = self._pose_buffer
            if not buf:
                return self.state.pose
            for i in range(len(buf) - 1, 0, -1):
                if buf[i - 1].t <= t <= buf[i].t:
                    a, b = buf[i - 1], buf[i]
                    u = 0 if b.t == a.t else (t - a.t) / (b.t - a.t)
                    yaw = a.yaw + u * self._ang(b.yaw - a.yaw)
                    return Pose(t, a.lat + u * (b.lat - a.lat), a.lon + u * (b.lon - a.lon),
                                a.alt_rel_m + u * (b.alt_rel_m - a.alt_rel_m),
                                a.roll + u * self._ang(b.roll - a.roll),
                                a.pitch + u * self._ang(b.pitch - a.pitch), yaw)
            return buf[-1]

    def recent_statustext(self, max_age_s: float = 120, min_severity: int = 7) -> list[dict]:
        """FC messages at or below the given severity number (lower = worse)."""
        cutoff = time.time() - max_age_s
        with self._lock:
            return [m for m in self._statustext if m["t"] >= cutoff and m["severity"] <= min_severity]

    @staticmethod
    def _ang(d: float) -> float:
        while d > math.pi: d -= 2 * math.pi
        while d < -math.pi: d += 2 * math.pi
        return d

    # ---------------------------------------------------------- commands

    def set_mode(self, mode: str) -> None:
        self.master.set_mode(self.master.mode_mapping()[mode])

    def arm_and_takeoff(self, alt_m: float, timeout_s: float = 60) -> None:
        self.set_mode("GUIDED")
        self.master.arducopter_arm()
        self.master.motors_armed_wait()
        self.master.mav.command_long_send(
            self.master.target_system, self.master.target_component,
            mavutil.mavlink.MAV_CMD_NAV_TAKEOFF, 0, 0, 0, 0, 0, 0, 0, alt_m)
        t0 = time.time()
        while time.time() - t0 < timeout_s:
            if self.state.pose.alt_rel_m >= alt_m * 0.95:
                return
            time.sleep(0.3)
        raise RuntimeError("takeoff timeout")

    def goto(self, lat: float, lon: float, alt_m: float, speed: float,
             accept_radius_m: float, abort_check) -> bool:
        """Fly to a waypoint in GUIDED; returns False if abort_check() fired."""
        self.master.mav.command_long_send(
            self.master.target_system, self.master.target_component,
            mavutil.mavlink.MAV_CMD_DO_CHANGE_SPEED, 0, 1, speed, -1, 0, 0, 0, 0)
        self.master.mav.set_position_target_global_int_send(
            0, self.master.target_system, self.master.target_component,
            mavutil.mavlink.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT,
            0b0000111111111000,                       # position only
            int(lat * 1e7), int(lon * 1e7), alt_m, 0, 0, 0, 0, 0, 0, 0, 0)
        while True:
            if abort_check():
                return False
            d = self._dist_m(self.state.pose.lat, self.state.pose.lon, lat, lon)
            dz = abs(self.state.pose.alt_rel_m - alt_m)
            if d < accept_radius_m and dz < max(1.0, accept_radius_m):
                return True
            time.sleep(0.2)

    def arm(self, timeout_s: float = 20) -> None:
        """Arm WITHOUT takeoff (no GPS/position estimate required in
        GUIDED_NOGPS / ALT_HOLD class modes)."""
        self.conn.mav.command_long_send(self.conn.target_system, self.conn.target_component,
                                        mavutil.mavlink.MAV_CMD_COMPONENT_ARM_DISARM, 0,
                                        1, 0, 0, 0, 0, 0, 0)
        t0 = time.time()
        while time.time() - t0 < timeout_s:
            if self.state.armed:
                return
            time.sleep(0.2)
        raise TimeoutError("vehicle did not arm")

    def disarm(self, force: bool = False) -> None:
        self.conn.mav.command_long_send(self.conn.target_system, self.conn.target_component,
                                        mavutil.mavlink.MAV_CMD_COMPONENT_ARM_DISARM, 0,
                                        0, 21196 if force else 0, 0, 0, 0, 0, 0)

    def set_attitude_target(self, roll_deg: float, pitch_deg: float,
                            yaw_rate_dps: float, thrust01: float) -> None:
        """SET_ATTITUDE_TARGET — the GPS-free control path (GUIDED_NOGPS):
        level-ish attitude + collective thrust, no position estimate needed.
        type_mask 0b00000011 ignores body roll/pitch rates (we command the
        attitude quaternion + yaw rate + thrust)."""
        import math as _m
        cr, sr = _m.cos(_m.radians(roll_deg) / 2), _m.sin(_m.radians(roll_deg) / 2)
        cp, sp = _m.cos(_m.radians(pitch_deg) / 2), _m.sin(_m.radians(pitch_deg) / 2)
        q = [cr * cp, sr * cp, cr * sp, -sr * sp]     # w,x,y,z (yaw 0 in body frame)
        self.conn.mav.set_attitude_target_send(
            0, self.conn.target_system, self.conn.target_component,
            0b00000011,                                # ignore roll/pitch body rates
            q, 0.0, 0.0, _m.radians(yaw_rate_dps),
            max(0.0, min(1.0, thrust01)))

    def hold(self) -> None:
        """Brake to a hover (obstacle stop)."""
        self.set_mode("BRAKE")

    def resume_guided(self) -> None:
        self.set_mode("GUIDED")

    def rtl(self) -> None:
        self.set_mode("RTL")

    def wait_disarmed(self, timeout_s: float = 300) -> None:
        t0 = time.time()
        while time.time() - t0 < timeout_s and self.state.armed:
            time.sleep(1)

    # ---------------------------------------------------------- companion sensor injection

    def send_downward_range(self, dist_m: float) -> None:
        """Downward TFmini-S -> DISTANCE_SENSOR: precise AGL + landing (10 cm..12 m)."""
        self.master.mav.distance_sensor_send(
            0, 10, 1200, int(dist_m * 100), mavutil.mavlink.MAV_DISTANCE_SENSOR_LASER,
            0, mavutil.mavlink.MAV_SENSOR_ROTATION_PITCH_270, 255)

    def send_forward_range(self, dist_m: float, max_m: float) -> None:
        """Forward TFmini-S -> DISTANCE_SENSOR (the C1's vertical ring is blind
        along the nose; this sensor is the forward avoidance input)."""
        self.master.mav.distance_sensor_send(
            0, 30, int(max_m * 100), int(dist_m * 100),
            mavutil.mavlink.MAV_DISTANCE_SENSOR_LASER, 1,
            mavutil.mavlink.MAV_SENSOR_ROTATION_NONE, 255)

    def send_ring_proximity(self, left_m: float, right_m: float, up_m: float,
                            max_m: float) -> None:
        """Vertical-ring wedge minima -> per-orientation DISTANCE_SENSOR for the
        FC's proximity/simple-avoidance (PRX1_TYPE=2 consumes these). The
        lateral rays are horizontal and tilt-immune (they lie on the tilt
        axis); up covers climbs under overhangs. Fore/aft remain the forward
        rangefinder's job."""
        cap = int(max_m * 100)
        for dist, orient, sid in (
                (right_m, mavutil.mavlink.MAV_SENSOR_ROTATION_YAW_90, 2),
                (left_m, mavutil.mavlink.MAV_SENSOR_ROTATION_YAW_270, 3),
                (up_m, mavutil.mavlink.MAV_SENSOR_ROTATION_PITCH_90, 4)):
            d = cap + 1 if dist == float("inf") else int(min(dist, max_m) * 100)
            self.master.mav.distance_sensor_send(
                0, 20, cap, d, mavutil.mavlink.MAV_DISTANCE_SENSOR_LASER,
                sid, orient, 255)

    def send_obstacle_ring(self, sectors_m: list, max_m: float) -> None:
        """HORIZONTAL mount only: the full 360° ring (72 x 5° sectors) ->
        OBSTACLE_DISTANCE, enabling the FC's own path planning (ArduPilot
        BendyRuler, OA_TYPE=1) to route around obstacles autonomously,
        underneath the companion's detour logic."""
        dists = [int(min(d, max_m) * 100) if d != float("inf") else int(max_m * 100) + 1
                 for d in sectors_m]
        self.master.mav.obstacle_distance_send(
            int(time.time() * 1e6), mavutil.mavlink.MAV_DISTANCE_SENSOR_LASER,
            dists, 5, 5, int(max_m * 100) + 1,
            5.0, 0.0, mavutil.mavlink.MAV_FRAME_BODY_FRD)

    @staticmethod
    def _dist_m(lat1, lon1, lat2, lon2) -> float:
        m_lat = 110540.0
        m_lon = 111320.0 * math.cos(math.radians(lat1))
        return math.hypot((lat2 - lat1) * m_lat, (lon2 - lon1) * m_lon)

    def close(self) -> None:
        self._running = False
