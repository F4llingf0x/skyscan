"""Executes a SkyScan route (3D GeoJSON LineString) with multi-flight support
and fully autonomous obstacle handling: persistent obstructions are detoured
around AND scanned (capture keeps running on the detour arc, so the unexpected
object ends up in the delivered cloud), then the planned route is rejoined —
no operator action at any point.

Leg planning: the route is cut into battery legs by conservative energy
estimate; each leg is takeoff -> resume waypoint -> fly -> (battery threshold)
-> save progress -> RTL -> disarm -> WAIT for battery swap -> next leg starts
on the operator's resume command. Progress persists across power cycles.
Capturing (lidar + photos) runs only along survey waypoints, pauses during
transit/RTL, and partial data survives every interruption.
"""
from __future__ import annotations

import logging
import math
import time

log = logging.getLogger("route")


class AbortedError(Exception):
    pass


class RouteExecutor:
    def __init__(self, cfg, mav, safety, avoidance, assembler, tilt, camera, store, on_event,
                 preflight_check=None, on_plant_images=None, on_crude_frame=None):
        self.cfg, self.mav, self.safety = cfg, mav, safety
        self.avoidance, self.assembler = avoidance, assembler
        self.tilt, self.camera, self.store = tilt, camera, store
        self.on_event = on_event
        self.preflight_check = preflight_check   # () -> (ok, [specific failures])
        self.on_plant_images = on_plant_images   # (idx, [paths]) -> None (disease classifier feed)
        self.on_crude_frame = on_crude_frame     # (path, pose, agl_m) -> None (plant detector feed)
        self.abort_requested = False
        self.resume_requested = False

    # ------------------------------------------------------------ main entry

    def run(self, mission: dict) -> None:
        """mission: {missionId, passType, sensorType, routeJson, altitudeM, speedMps, ...}"""
        wps = self._waypoints(mission)
        state = self.store.load()
        start_idx = 0
        if state and state.get("missionId") == mission["missionId"] and state.get("passType") == mission["passType"]:
            start_idx = state.get("nextWp", 0)
            log.info("resuming mission %s at waypoint %d/%d", mission["missionId"], start_idx, len(wps))
            self.on_event("RESUMING", {"waypoint": start_idx, "of": len(wps)})

        # payload mode via the tilt axis
        # Tilt is FIXED for the whole flight (set on the ground, verified by encoder).
        # The mission plan carries the operator-chosen angle; presets are the fallback.
        structure = mission["sensorType"] == "FRONT_360_VERTICAL"
        self._inspect = mission["sensorType"] == "PLANT_INSPECT"
        preset = self.cfg["tilt"]["structure_deg"] if structure else self.cfg["tilt"]["landscape_deg"]
        tilt_target = float(mission.get("tiltDeg", preset))
        measured = self.tilt.move_to(tilt_target)
        self.on_event("TILT_SET", {"targetDeg": tilt_target, "measuredDeg": round(measured, 2),
                                   "fixedForFlight": True})

        self.camera.start_session(mission["missionId"])
        if start_idx == 0:
            self.assembler.start()
        else:
            self.assembler.resume()

        leg = (state or {}).get("leg", 0)
        idx = start_idx
        speed = float(mission.get("speedMps", self.cfg["flight"]["cruise_speed_mps"]))

        while idx < len(wps):
            leg += 1
            idx = self._fly_leg(mission, wps, idx, leg, speed)
            if idx < len(wps):
                # battery swap: persist, land, wait for resume
                self.store.save({"missionId": mission["missionId"], "passType": mission["passType"],
                                 "nextWp": idx, "leg": leg})
                self.assembler.pause()
                self.on_event("BATTERY_SWAP", {"nextWaypoint": idx, "of": len(wps), "leg": leg})
                self.mav.rtl()
                self.mav.wait_disarmed()
                self._wait_for_resume()

        # route complete: land at home
        self.mav.rtl()
        self.mav.wait_disarmed()
        self.store.clear()
        self.on_event("ROUTE_COMPLETE", {"waypoints": len(wps), "legs": leg})

    # ------------------------------------------------------------ one battery leg

    def _fly_leg(self, mission: dict, wps: list, idx: int, leg: int, speed: float) -> int:
        # Automated pre-flight self-test: takeoff is blocked while any check FAILs,
        # and the console shows exactly which component and why.
        while self.preflight_check is not None:
            ok, fails = self.preflight_check()
            if ok:
                self.on_event("PREFLIGHT_PASS", {"leg": leg})
                break
            self.on_event("PREFLIGHT_FAIL", {"leg": leg, "failures": fails})
            log.warning("pre-flight blocked: %s", "; ".join(fails))
            if self.abort_requested:
                raise AbortedError("abort during pre-flight: " + "; ".join(fails))
            time.sleep(10)

        ok, why = self.safety.ok_to_start_leg()
        while not ok:
            self.on_event("LEG_HOLD", {"reason": why})
            if self.abort_requested:
                raise AbortedError("abort while waiting to start leg")
            time.sleep(5)
            ok, why = self.safety.ok_to_start_leg()

        self.on_event("LEG_START", {"leg": leg, "fromWaypoint": idx})
        self.mav.arm_and_takeoff(self.cfg["flight"]["takeoff_alt_m"])
        last_photo = 0.0
        while idx < len(wps):
            lat, lon, alt = wps[idx]
            reached = self.mav.goto(lat, lon, alt, speed,
                                    self.cfg["flight"]["waypoint_accept_radius_m"],
                                    abort_check=self._abort_or_hold)
            if self.abort_requested:
                raise AbortedError("operator abort")
            reason = self.safety.abort_reason()
            if reason:
                raise AbortedError(reason)
            if self.avoidance.detour_request is not None:
                self._detour_and_scan(self.avoidance.detour_request, alt, speed)
                self.avoidance.clear_detour()
                self.mav.resume_guided()
                continue                      # retry the same waypoint from the new position
            if reached:
                if getattr(self, "_inspect", False):
                    # CROP_HEALTH: each waypoint IS a detected plant — hold and
                    # shoot close-ups for the disease classifier. Battery swaps
                    # between plants are handled by the leg loop below, so a
                    # long plant list simply spans multiple packs.
                    self._inspect_plant(idx)
                idx += 1
                self.store.save({"missionId": mission["missionId"], "passType": mission["passType"],
                                 "nextWp": idx, "leg": leg})
                now = time.time()
                if not getattr(self, "_inspect", False) and \
                        now - last_photo >= self.cfg["camera"]["interval_s"]:
                    p = self.camera.capture()
                    last_photo = now
                    if p:
                        log.debug("photo %s", p)
                        if self.on_crude_frame:
                            try:
                                agl = self._agl_m()
                                self.on_crude_frame(p, self.mav.state.pose, agl)  # -> plant detector
                            except Exception as e:
                                log.warning("crude frame hook failed: %s", e)
                if self.safety.battery_needs_swap():
                    return idx                       # leg ends here; caller handles swap
        return idx

    def _inspect_plant(self, idx: int) -> None:
        """Hold over a detected plant and capture a small burst of close-ups.
        The lidar keeps assembling (canopy profile) throughout."""
        cfg = self.cfg.get("inspection", {})
        shots = int(cfg.get("shots_per_plant", 3))
        dwell = float(cfg.get("dwell_s", 2.0))
        self.on_event("PLANT_INSPECT", {"waypoint": idx, "shots": shots})
        self.mav.hold()
        t0 = time.time()
        burst = []
        while time.time() - t0 < dwell:
            if self.abort_requested or self.safety.abort_reason():
                break
            if len(burst) < shots:
                p = self.camera.capture()
                if p:
                    burst.append(p)
                    log.debug("plant %d photo %s", idx, p)
            time.sleep(max(0.2, dwell / max(1, shots)))
        self.mav.resume_guided()
        if self.on_plant_images and burst:
            try:
                self.on_plant_images(idx, burst)      # -> disease classifier
            except Exception as e:
                log.warning("plant %d classify hook failed: %s", idx, e)

    def _agl_m(self) -> float:
        """Above-ground height for projection: downward rangefinder if fresh,
        else the FC's relative altitude."""
        r = getattr(self, "agl_provider", None)
        if r is not None:
            v = r()
            if v is not None:
                return v
        return self.mav.state.pose.alt_rel_m

    def _abort_or_hold(self) -> bool:
        """goto() poll: obstacle hold pauses translation; abort ends the leg."""
        if self.abort_requested or self.safety.abort_reason():
            return True
        self.avoidance.check_and_act()               # BRAKE/resume handled inside
        return False

    def _detour_and_scan(self, obstacle: dict, alt: float, speed: float) -> None:
        """Autonomous obstacle handling: arc around the located obstacle at a safe
        radius — capture running, so the object is scanned into the cloud — and
        end positioned past it, ready to rejoin the planned route."""
        import math as _m
        lat0, lon0, r = obstacle["lat"], obstacle["lon"], obstacle["radiusM"]
        m_lat = 110540.0
        m_lon = 111320.0 * _m.cos(_m.radians(lat0))
        self.on_event("OBSTACLE_SCAN_START", {"lat": lat0, "lon": lon0, "radiusM": r})
        p = self.mav.state.pose
        start = _m.atan2((p.lon - lon0) * m_lon, (p.lat - lat0) * m_lat)
        # full loop (obstacle fully scanned) + a further half-turn to exit on the far side
        for k in range(0, 13):
            a = start + k * (2 * _m.pi / 8)
            wlat = lat0 + (r * _m.cos(a)) / m_lat
            wlon = lon0 + (r * _m.sin(a)) / m_lon
            ok = self.mav.goto(wlat, wlon, alt, min(speed, 2.5),
                               self.cfg["flight"]["waypoint_accept_radius_m"],
                               abort_check=lambda: self.abort_requested
                                                   or self.safety.abort_reason() is not None)
            if not ok:
                break
        self.on_event("OBSTACLE_SCAN_DONE", {"lat": lat0, "lon": lon0})

    def _wait_for_resume(self) -> None:
        self.resume_requested = False
        log.info("waiting for battery swap + resume command")
        while not self.resume_requested:
            if self.abort_requested:
                raise AbortedError("abort during battery swap")
            time.sleep(1)
        self.assembler.resume()

    # ------------------------------------------------------------ helpers

    def _waypoints(self, mission: dict) -> list[tuple[float, float, float]]:
        import json
        route = mission.get("routeJson")
        default_alt = float(mission.get("altitudeM", 30))
        if route:
            coords = json.loads(route)["coordinates"]
            return [(c[1], c[0], c[2] if len(c) > 2 else default_alt) for c in coords]
        # No planned route (shouldn't happen): orbit the polygon centroid once.
        poly = json.loads(mission["polygonGeoJson"])["coordinates"][0]
        clat = sum(p[1] for p in poly) / len(poly)
        clon = sum(p[0] for p in poly) / len(poly)
        return [(clat + 0.0002 * math.sin(a), clon + 0.0003 * math.cos(a), default_alt)
                for a in [i * math.pi / 8 for i in range(17)]]
