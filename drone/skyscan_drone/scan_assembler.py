"""Turns the 2D lidar stream into a georeferenced 3D cloud.

For each measurement: sensor frame -> tilt rotation (measured encoder angle) ->
body frame (mount extrinsics) -> NED via the time-interpolated attitude ->
local UTM via the time-interpolated position. Written out as LAS in the
mission's target CRS, ready for the SkyScan processing engine.

Frames:
  scan plane (HORIZONTAL ring): x' forward, y' right (angle 0 = nose, +90 = right)
  tilt (FIXED per flight, measured by encoder): rotation about a build-time axis —
    axis: "roll"  (recommended): + tilt lifts the RIGHT side of the ring; the
          fore/aft rays stay horizontal, so forward avoidance is tilt-immune
    axis: "pitch": + tilt lifts the nose ray (legacy mounting)
  body: x fwd, y right, z down; NED via ZYX (yaw-pitch-roll)
"""
from __future__ import annotations

import logging
import math
import threading

from .las_writer import write_las
from .sensors.rplidar_c1 import LidarPoint

log = logging.getLogger("assembler")


def _utm34n(lon: float, lat: float) -> tuple[float, float]:
    a = 6378137.0; f = 1 / 298.257223563; k0 = 0.9996
    e2 = f * (2 - f); ep2 = e2 / (1 - e2)
    lam, phi, lam0 = math.radians(lon), math.radians(lat), math.radians(21)
    n = a / math.sqrt(1 - e2 * math.sin(phi) ** 2)
    t = math.tan(phi) ** 2; c = ep2 * math.cos(phi) ** 2
    A = math.cos(phi) * (lam - lam0)
    m = a * ((1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * phi
             - (3 * e2 / 8 + 3 * e2 ** 2 / 32 + 45 * e2 ** 3 / 1024) * math.sin(2 * phi)
             + (15 * e2 ** 2 / 256 + 45 * e2 ** 3 / 1024) * math.sin(4 * phi)
             - (35 * e2 ** 3 / 3072) * math.sin(6 * phi))
    e_ = k0 * n * (A + (1 - t + c) * A ** 3 / 6 + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * A ** 5 / 120) + 500000.0
    n_ = k0 * (m + n * math.tan(phi) * (A * A / 2 + (5 - t + 9 * c + 4 * c * c) * A ** 4 / 24
             + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * A ** 6 / 720))
    return e_, n_


class ScanAssembler:
    def __init__(self, mount_cfg: dict, pose_at, tilt_deg_provider, ground_alt_amsl_provider):
        self.mount = mount_cfg
        self.pose_at = pose_at
        self.tilt_deg = tilt_deg_provider
        self.ground_alt = ground_alt_amsl_provider
        self.points: list[tuple[float, float, float, int]] = []
        self._lock = threading.Lock()
        self.capturing = False

    def on_lidar(self, pts: list[LidarPoint]) -> None:
        if not self.capturing or not pts:
            return
        pose = self.pose_at(pts[0].t)
        if pose.lat == 0.0 and pose.lon == 0.0:
            return                                        # no EKF position yet
        tilt = math.radians(self.tilt_deg())
        e0, n0 = _utm34n(pose.lon, pose.lat)
        z0 = self.ground_alt() + pose.alt_rel_m
        ox, oy, oz = self.mount["offset_m"]
        cr, sr = math.cos(pose.roll), math.sin(pose.roll)
        cp, sp = math.cos(pose.pitch), math.sin(pose.pitch)
        cy, sy = math.cos(pose.yaw), math.sin(pose.yaw)
        ct, st = math.cos(tilt), math.sin(tilt)
        out = []
        roll_axis = self.mount.get("tilt_axis", "roll") == "roll"
        for p in pts:
            a = math.radians(p.angle_deg)
            # horizontal ring (x' fwd, y' right, z'=0), tilted about the mount axis:
            xs_, ys_ = p.dist_m * math.cos(a), p.dist_m * math.sin(a)
            if roll_axis:
                # tilt about body X (+ = right side of ring up, z-down frame)
                xb = xs_ + ox
                yb = ys_ * ct + oy
                zb = -(ys_ * st) + oz
            else:
                # tilt about body Y (+ = nose of ring up)
                xb = xs_ * ct + ox
                yb = ys_ + oy
                zb = -(xs_ * st) + oz
            # body -> NED (ZYX)
            xn = (cy * cp) * xb + (cy * sp * sr - sy * cr) * yb + (cy * sp * cr + sy * sr) * zb
            yn = (sy * cp) * xb + (sy * sp * sr + cy * cr) * yb + (sy * sp * cr - cy * sr) * zb
            zn = (-sp) * xb + (cp * sr) * yb + (cp * cr) * zb
            out.append((e0 + yn, n0 + xn, z0 - zn, min(65535, p.quality * 300)))
        with self._lock:
            self.points.extend(out)

    def start(self) -> None:
        with self._lock:
            self.points.clear()
        self.capturing = True

    def pause(self) -> None:
        self.capturing = False

    def resume(self) -> None:
        self.capturing = True

    def finish(self, path: str) -> int:
        self.capturing = False
        with self._lock:
            n = len(self.points)
            log.info("assembling %d points -> %s", n, path)
            write_las(path, self.points)
        return n
