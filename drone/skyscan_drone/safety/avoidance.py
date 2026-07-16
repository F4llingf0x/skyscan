"""Object avoidance — layered and fully autonomous (no operator in the loop).

Layer 1 (flight controller): the full 360° lidar ring goes to the FC as
OBSTACLE_DISTANCE; with ArduPilot path planning enabled (OA_TYPE=1 BendyRuler,
AVOID_ENABLE=7, PRX1_TYPE=2) the FC itself curves GUIDED paths around
obstacles — most encounters never reach layer 2.

Layer 2 (companion, this class): stop-and-hold with hysteresis. If the path
clears (BendyRuler slid past, or the obstacle moved), flight resumes on its
own after 3 s of clearance.

Layer 3 (companion escalation): if the hold PERSISTS beyond detour_after_s,
the obstacle is treated as a static unexpected object: this class computes its
world position from the nearest-return bearing/distance + vehicle pose and
raises detour_request; the route executor then flies an arc around it — with
capture running, so THE OBSTACLE IS SCANNED into the cloud — and rejoins the
route. Every step is reported as events; none of it waits for an operator.
"""
from __future__ import annotations

import logging
import math
import time

log = logging.getLogger("avoidance")


class ForwardAvoidance:
    def __init__(self, cfg: dict, forward_min_provider, nearest_provider, pose_provider,
                 mav, on_event):
        self.cfg = cfg
        self.forward_min = forward_min_provider
        self.nearest = nearest_provider          # () -> (bearing_deg, dist_m) | None
        self.pose = pose_provider                # () -> Pose
        self.mav = mav
        self.on_event = on_event
        self.holding = False
        self.detour_request: dict | None = None  # {"lat","lon","radiusM"} set on escalation
        self._hold_since: float | None = None
        self._clear_since: float | None = None

    def check_and_act(self) -> bool:
        """Called from the route loop. Returns True while movement is inhibited."""
        d = self.forward_min()
        if not self.holding:
            if d < self.cfg["stop_distance_m"]:
                log.warning("OBSTACLE at %.1f m — braking", d)
                self.mav.hold()
                self.holding = True
                self._hold_since = time.time()
                self._clear_since = None
                self.on_event("OBSTACLE_STOP", {"distanceM": round(d, 2)})
            return self.holding

        # holding: either clear and resume, or escalate to an autonomous detour
        if d > self.cfg["resume_clear_m"]:
            if self._clear_since is None:
                self._clear_since = time.time()
            elif time.time() - self._clear_since > 3.0:
                log.info("path clear (%.1f m) — resuming", d)
                self.mav.resume_guided()
                self.holding = False
                self.on_event("OBSTACLE_CLEAR", {"distanceM": round(d, 2)})
        else:
            self._clear_since = None
            if (time.time() - self._hold_since > self.cfg.get("detour_after_s", 8)
                    and self.detour_request is None):
                self.detour_request = self._locate_obstacle()
                if self.detour_request:
                    self.on_event("OBSTACLE_DETOUR", self.detour_request)
        return self.holding

    def _locate_obstacle(self) -> dict | None:
        near = self.nearest()
        if near is None:
            return None
        bearing_deg, dist = near
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
