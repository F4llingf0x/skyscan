"""RPLidar C1 acquisition.

The C1 is a 2D scanner mounted with its plane HORIZONTAL: a full 360° ring
around the aircraft at flight altitude, tiltable ±30° about the lateral axis.
3D comes from motion + tilt: orbits/passes sweep the ring across the scene,
and the tilt sweep widens the captured band. Every measurement is
georeferenced with the interpolated vehicle pose + the MEASURED encoder tilt.

Because the ring is horizontal, the same sensor is the 360° obstacle map at
flight altitude: per-revolution we maintain 72 five-degree sectors (min range
each) for the flight controller's OBSTACLE_DISTANCE path planning, plus the
nearest-return bearing/distance for the companion detour logic. Blind axes
remain up/down (see README gaps: add an upward ToF before flying under
overhangs).

Timestamps: each measurement is stamped on receipt (host clock). For survey-
grade accuracy add GNSS PPS discipline on the Jetson (see README).
"""
from __future__ import annotations

import logging
import math
import os
import threading
import time
from dataclasses import dataclass

log = logging.getLogger("lidar")
MOCK = os.environ.get("SKYSCAN_MOCK_SENSORS") == "1"


@dataclass
class LidarPoint:
    t: float
    angle_deg: float     # horizontal ring: 0 = nose, +90 = right, ±180 = tail
    dist_m: float
    quality: int


class RpLidarC1:
    def __init__(self, cfg: dict, on_points):
        self.cfg = cfg
        self.on_points = on_points     # callback(list[LidarPoint])
        self._running = False
        self.forward_min_m = float("inf")   # rolling min in the forward sector (avoidance)
        self.sectors_m = [float("inf")] * 72     # 5° bins, index 0 at the nose, clockwise
        self.nearest: tuple[float, float] | None = None   # (bearing_deg, dist_m)
        self._last_scan_t = 0.0
        self._scan_times: list[float] = []

    def start(self) -> None:
        self._running = True
        threading.Thread(target=self._loop, daemon=True, name="rplidar").start()

    def _loop(self) -> None:
        if MOCK:
            self._mock_loop()
            return
        from rplidar import RPLidar
        while self._running:
            try:
                lidar = RPLidar(self.cfg["port"], baudrate=460800)
                log.info("RPLidar C1: %s", lidar.get_info())
                for scan in lidar.iter_scans(max_buf_meas=4000):
                    if not self._running:
                        break
                    t = time.time()
                    pts, fwd = [], float("inf")
                    sectors = [float("inf")] * 72
                    near_b, near_d = 0.0, float("inf")
                    for quality, angle, dist_mm in scan:
                        d = dist_mm / 1000.0
                        if d <= 0.05 or d > self.cfg["max_range_m"]:
                            continue
                        a = (angle + 180) % 360 - 180          # -> [-180, 180], 0 = nose
                        pts.append(LidarPoint(t, a, d, quality))
                        sec = int(((a + 180) % 360) / 5) % 72
                        if d < sectors[sec]:
                            sectors[sec] = d
                        if abs(a) <= 20:
                            fwd = min(fwd, d)
                        if d < near_d:
                            near_b, near_d = a, d
                    self.forward_min_m = fwd
                    self.sectors_m = sectors
                    self.nearest = None if near_d == float("inf") else (near_b, near_d)
                    self._mark_scan(t)
                    if pts:
                        self.on_points(pts)
                lidar.stop(); lidar.disconnect()
            except Exception as e:
                log.warning("lidar restart after error: %s", e)
                time.sleep(2)

    def _mock_loop(self) -> None:
        """SITL: synthesize a wall 8 m off the nose so the pipeline has data."""
        while self._running:
            t = time.time()
            pts = []
            sectors = [float("inf")] * 72
            for a in range(-180, 180, 2):
                if abs(a) < 60:
                    d = 8.0 / max(0.5, math.cos(math.radians(a)))
                else:
                    d = self.cfg["max_range_m"] * 0.9
                pts.append(LidarPoint(t, a, d, 47))
                sec = int(((a + 180) % 360) / 5) % 72
                sectors[sec] = min(sectors[sec], d)
            self.forward_min_m = 8.0
            self.sectors_m = sectors
            self.nearest = (0.0, 8.0)
            self._mark_scan(t)
            self.on_points(pts)
            time.sleep(1.0 / self.cfg["scan_hz"])

    def _mark_scan(self, t: float) -> None:
        self._last_scan_t = t
        self._scan_times.append(t)
        if len(self._scan_times) > 100:
            del self._scan_times[:50]

    def stream_stats(self) -> tuple[float, float]:
        """(age of last scan in s, scan rate Hz over the recent window)."""
        age = time.time() - self._last_scan_t if self._last_scan_t else 1e9
        recent = [t for t in self._scan_times if t > time.time() - 5]
        rate = len(recent) / 5.0
        return age, rate

    def stop(self) -> None:
        self._running = False
