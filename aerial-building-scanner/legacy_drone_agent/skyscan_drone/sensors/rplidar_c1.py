"""RPLidar C1 acquisition — supports BOTH mount orientations, selected by
config `lidar.plane`:

vertical (survey-optimal, default)
    Sensor at the nose, spin axis along body X: the 360° plane is VERTICAL
    (body Y-Z at zero tilt), covering up / right / down / left. Tilt = pitch
    of the plane about the lateral (Y) axis; + leans the TOP of the ring
    toward the nose. Pushbroom scanning: one pass profiles ground + both
    sides; tangent orbits paint a facade floor-to-roof each sweep. BLIND
    fore/aft — the forward TFmini-S is REQUIRED for avoidance.
    Angle convention: 0° = up, +90° = body right, ±180° = down, −90° = left.
    The lateral rays lie ON the tilt axis → left/right ranging is tilt-immune.

horizontal (avoidance-optimal)
    Sensor at the drone's center, spin axis vertical: the 360° plane is
    HORIZONTAL at flight altitude — a full obstacle ring (72 × 5° sectors ->
    OBSTACLE_DISTANCE, BendyRuler-capable). Tilt = pitch about the lateral
    (Y) axis; + lifts the NOSE ray. Scanning coverage per pass is a narrow
    band at flight altitude: expect several× more orbit levels / lines than
    the vertical mount for the same coverage. Blind axes: up and down.
    Angle convention: 0° = nose, +90° = right, ±180° = tail, −90° = left.

Per revolution, both modes publish wedge minima (up/down/left/right for
vertical; forward + left/right for horizontal), the 72-sector ring
(horizontal only) and the nearest in-plane return for detour geometry.

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
    angle_deg: float     # in-plane angle; meaning depends on the mount plane (see module doc)
    dist_m: float
    quality: int


class RpLidarC1:
    def __init__(self, cfg: dict, on_points):
        self.cfg = cfg
        self.plane = cfg.get("plane", "vertical")          # vertical | horizontal
        if self.plane not in ("vertical", "horizontal"):
            raise ValueError(f"lidar.plane must be vertical|horizontal, got {self.plane!r}")
        self.on_points = on_points     # callback(list[LidarPoint])
        self._running = False
        # wedge minima (inf where the mount doesn't cover the direction)
        self.up_min_m = float("inf")       # vertical plane only
        self.down_min_m = float("inf")     # vertical plane only
        self.left_min_m = float("inf")
        self.right_min_m = float("inf")
        self.forward_min_m = float("inf")  # horizontal plane only (vertical: use fwd TFmini)
        self.sectors_m = [float("inf")] * 72   # horizontal plane only: 5° bins, 0 at nose
        self.nearest: tuple[float, float] | None = None   # (in-plane angle_deg, dist_m)
        self._wedge_half = float(cfg.get("wedge_half_deg", 25.0))
        self._last_scan_t = 0.0
        self._scan_times: list[float] = []

    def start(self) -> None:
        self._running = True
        threading.Thread(target=self._loop, daemon=True, name="rplidar").start()

    # ------------------------------------------------------------ acquisition

    def _loop(self) -> None:
        if MOCK:
            self._mock_loop()
            return
        from rplidar import RPLidar
        zero = float(self.cfg.get("angle_zero_deg", 0.0))
        while self._running:
            try:
                lidar = RPLidar(self.cfg["port"], baudrate=460800)
                log.info("RPLidar C1 (%s plane): %s", self.plane, lidar.get_info())
                for scan in lidar.iter_scans(max_buf_meas=4000):
                    if not self._running:
                        break
                    t = time.time()
                    pts = []
                    for quality, angle, dist_mm in scan:
                        d = dist_mm / 1000.0
                        if d <= 0.05 or d > self.cfg["max_range_m"]:
                            continue
                        a = (angle - zero + 180) % 360 - 180   # -> [-180, 180]
                        pts.append(LidarPoint(t, a, d, quality))
                    self._digest(pts)
                    self._mark_scan(t)
                    if pts:
                        self.on_points(pts)
                lidar.stop(); lidar.disconnect()
            except Exception as e:
                log.warning("lidar restart after error: %s", e)
                time.sleep(2)

    def _digest(self, pts: list[LidarPoint]) -> None:
        """Per-revolution obstacle summaries for the FC + companion avoidance."""
        w = self._wedge_half
        up = down = left = right = fwd = float("inf")
        sectors = [float("inf")] * 72
        near_a, near_d = 0.0, float("inf")
        for p in pts:
            a, d = p.angle_deg, p.dist_m
            if self.plane == "vertical":
                # 0 = up, ±180 = down, ±90 lateral (horizontal + tilt-immune)
                if abs(a) <= w:
                    up = min(up, d)
                elif abs(abs(a) - 180) <= w:
                    down = min(down, d)
                elif abs(a - 90) <= w:
                    right = min(right, d)
                elif abs(a + 90) <= w:
                    left = min(left, d)
            else:
                # 0 = nose: full horizontal ring -> sectors + forward/lateral wedges
                sectors[int(((a + 180) % 360) / 5) % 72] = min(
                    sectors[int(((a + 180) % 360) / 5) % 72], d)
                if abs(a) <= w:
                    fwd = min(fwd, d)
                elif abs(a - 90) <= w:
                    right = min(right, d)
                elif abs(a + 90) <= w:
                    left = min(left, d)
            if d < near_d:
                near_a, near_d = a, d
        self.up_min_m, self.down_min_m = up, down
        self.left_min_m, self.right_min_m = left, right
        self.forward_min_m = fwd
        self.sectors_m = sectors
        self.nearest = None if near_d == float("inf") else (near_a, near_d)

    # ------------------------------------------------------------ SITL mock

    def _mock_loop(self) -> None:
        """SITL synthetic scene. vertical: flat ground 5 m below + facade 8 m to
        the RIGHT. horizontal: wall 8 m off the NOSE."""
        while self._running:
            t = time.time()
            pts = []
            for a in range(-180, 180, 2):
                ar = math.radians(a)
                if self.plane == "vertical":
                    cands = []
                    if math.cos(ar) < -0.1:                 # downward hemisphere: ground
                        cands.append(5.0 / -math.cos(ar))
                    if math.sin(ar) > 0.1:                  # right hemisphere: facade
                        cands.append(8.0 / math.sin(ar))
                    d = min([c for c in cands if c <= self.cfg["max_range_m"]],
                            default=self.cfg["max_range_m"] * 0.95)
                else:
                    d = (8.0 / max(0.5, math.cos(ar)) if abs(a) < 60
                         else self.cfg["max_range_m"] * 0.9)
                pts.append(LidarPoint(t, a, d, 47))
            self._digest(pts)
            self._mark_scan(t)
            self.on_points(pts)
            time.sleep(1.0 / self.cfg["scan_hz"])

    # ------------------------------------------------------------ health

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
