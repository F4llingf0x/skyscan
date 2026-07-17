"""Object avoidance — layered and fully autonomous (no operator in the loop),
reworked for the real sensor geometry: the C1's 360° ring is VERTICAL, so it
covers up / down / left / right but is BLIND along the nose. Forward sensing
comes from the dedicated forward TFmini-S; the ring's horizontal lateral rays
(tilt-immune, on the tilt axis) guard the corridor sides.

Layer 1 (flight controller): forward rangefinder + ring wedge minima stream
to the FC as per-orientation DISTANCE_SENSOR; with ArduPilot avoidance enabled
(AVOID_ENABLE=7, PRX1_TYPE=2) the FC brakes/backs off on its own — this works
even if the companion process dies.

Layer 2 (companion, this class): stop-and-hold with hysteresis on the forward
range, plus a lateral corridor check on the ring. If the path clears (the
obstacle moved, or the FC slid past), flight resumes on its own after 3 s.

Layer 3 (companion escalation): if the hold PERSISTS beyond detour_after_s,
the obstacle is treated as a static unexpected object. Located along the
current heading at the measured forward distance (or at the ring's nearest
lateral return), it raises detour_request; the route executor then flies an
arc around it — nose along the arc tangent, so the object sits abeam, inside
the vertical scan plane, and IS SCANNED into the delivered cloud — then
rejoins the route. Every step is reported as events.
"""
from __future__ import annotations

import logging
import math
import time

log = logging.getLogger("avoidance")


class ForwardAvoidance:
    def __init__(self, cfg: dict, forward_min_provider, lateral_provider, pose_provider,
                 mav, on_event):
        self.cfg = cfg
        self.forward_min = forward_min_provider  # () -> dist_m (forward TFmini-S, inf if none)
        self.lateral = lateral_provider          # () -> (left_m, right_m) ring minima
        self.pose = pose_provider                # () -> Pose
        self.mav = mav
        self.on_event = on_event
        self.holding = False
        self.detour_request: dict | None = None  # {"lat","lon","radiusM"} set on escalation
        self._hold_since: float | None = None
        self._clear_since: float | None = None
        self._hold_bearing_deg = 0.0             # body-relative bearing of the blocker

    def check_and_act(self) -> bool:
        """Called from the route loop. Returns True while movement is inhibited."""
        fwd = self.forward_min()
        left, right = self.lateral()
        lat_stop = self.cfg.get("lateral_stop_m", 0.0)
        blocked, bearing = None, 0.0
        if fwd < self.cfg["stop_distance_m"]:
            blocked, bearing = fwd, 0.0
        elif lat_stop and right < lat_stop:
            blocked, bearing = right, 90.0
        elif lat_stop and left < lat_stop:
            blocked, bearing = left, -90.0

        if not self.holding:
            if blocked is not None:
                log.warning("OBSTACLE at %.1f m (bearing %+.0f°) — braking", blocked, bearing)
                self.mav.hold()
                self.holding = True
                self._hold_since = time.time()
                self._hold_bearing_deg = bearing
                self._clear_since = None
                self.on_event("OBSTACLE_STOP", {"distanceM": round(blocked, 2),
                                                "bearingDeg": bearing})
            return self.holding

        # holding: either clear and resume, or escalate to an autonomous detour
        clear = (fwd > self.cfg["resume_clear_m"]
                 and (not lat_stop or min(left, right) > lat_stop * 1.5))
        if clear:
            if self._clear_since is None:
                self._clear_since = time.time()
            elif time.time() - self._clear_since > 3.0:
                log.info("path clear (fwd %.1f m) — resuming", fwd)
                self.mav.resume_guided()
                self.holding = False
                self.on_event("OBSTACLE_CLEAR", {"distanceM": round(fwd, 2)})
        else:
            self._clear_since = None
            if (time.time() - self._hold_since > self.cfg.get("detour_after_s", 8)
                    and self.detour_request is None):
                self.detour_request = self._locate_obstacle(fwd, left, right)
                if self.detour_request:
                    self.on_event("OBSTACLE_DETOUR", self.detour_request)
        return self.holding

    def _locate_obstacle(self, fwd: float, left: float, right: float) -> dict | None:
        """World position of the blocker from body bearing + range + pose.
        Forward range from the TFmini-S; lateral ranges from the ring's
        horizontal rays (tilt-immune)."""
        cands = [(0.0, fwd), (90.0, right), (-90.0, left)]
        bearing_deg, dist = min(cands, key=lambda c: c[1])
        if not math.isfinite(dist):
            return None
        p = self.pose()
        heading = p.yaw + math.radians(bearing_deg)
        m_lat = 110540.0
        m_lon = 111320.0 * math.cos(math.radians(p.lat))
        return {"lat": p.lat + dist * math.cos(heading) / m_lat,
                "lon": p.lon + dist * math.sin(heading) / m_lon,
                "radiusM": self.cfg["stop_distance_m"] + self.cfg.get("detour_margin_m", 3.0),
                "distanceM": round(dist, 2)}

    def clear_detour(self) -> None:
        self.detour_request = None
        self.holding = False
        self._hold_since = None
