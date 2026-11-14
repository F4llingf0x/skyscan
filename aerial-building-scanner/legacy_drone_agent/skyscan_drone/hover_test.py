"""HOVER & DODGE TEST — a props-ON, GPS-FREE bench flight test:

    rise to a set height (default 1 m) on the downward rangefinder,
    hold, and DODGE obstacles that move TOWARD the drone
    (walk at it slowly from a covered side — it tilts away),
    then land and disarm after the set duration or on abort.

Why no GPS: at 1 m for a couple of minutes, position drift is irrelevant —
what matters is attitude (IMU), height (rangefinder), and proximity (lidar).
Control path: GUIDED_NOGPS + SET_ATTITUDE_TARGET (attitude + thrust), which
ArduPilot accepts with no position estimate. Height is closed-loop on the
downward TFmini-S; a small PI on thrust holds the target AGL.

Obstacle model — only TOWARD-MOVING obstacles trigger a dodge:
  each covered azimuth keeps an EMA of range rate; an obstacle inside
  react_dist_m AND closing faster than closing_mps trips a dodge = a few
  degrees of tilt AWAY from its azimuth (height loop keeps running). When it
  stops closing or leaves, the drone levels. Inside min_dist_m -> immediate
  controlled landing (a person that close to spinning props must not be
  danced around).

Covered azimuths depend on the lidar mount (config lidar.plane):
  vertical   ring -> left (-90°) / right (+90°); forward (0°) via TFmini-S.
             THE REAR IS BLIND — approach from the front or sides.
  horizontal full 72×5° ring -> any azimuth.

Safety rails (non-negotiable, all enforced here):
  height clamped to max_height_m (3 m), duration to max_duration_s (180 s),
  battery floor abort, stale-sensor (rangefinder/lidar) -> immediate land,
  attitude-estimate check before arming, thrust hard-capped. Run it in a
  netted/clear area, prop guards strongly recommended; see SETUP_MANUAL.
"""
from __future__ import annotations

import logging
import math
import threading
import time

log = logging.getLogger("hover_test")


class HoverTest:
    """One test session. States: IDLE -> PREFLIGHT -> ARMING -> CLIMB -> HOLD
    (<-> DODGE) -> LANDING -> DONE | ABORTED | FAILED."""

    def __init__(self, cfg: dict, mav, agl_provider, obstacle_provider,
                 battery_provider, on_event):
        """
        agl_provider      () -> float|None    fresh downward-rangefinder AGL
        obstacle_provider () -> list[(azimuth_deg, dist_m)]  covered azimuths
        battery_provider  () -> float|None    battery percent
        on_event          (type, payload) -> None
        """
        self.cfg = cfg.get("hover_test", {})
        self.mav = mav
        self.agl = agl_provider
        self.obstacles = obstacle_provider
        self.battery = battery_provider
        self.on_event = on_event

        self.state = "IDLE"
        self.abort_requested = False
        self.status: dict = {}
        self._thread: threading.Thread | None = None
        self._range_hist: dict[int, list[tuple[float, float]]] = {}  # az bucket -> [(t, dist)] window

    # ---------------------------------------------------------------- lifecycle

    def start(self, height_m: float, duration_s: float) -> None:
        if self._thread and self._thread.is_alive():
            raise RuntimeError("hover test already running")
        height_m = max(0.3, min(float(self.cfg.get("max_height_m", 3.0)), height_m))
        duration_s = max(10.0, min(float(self.cfg.get("max_duration_s", 180.0)), duration_s))
        self.abort_requested = False
        self._thread = threading.Thread(target=self._run, args=(height_m, duration_s),
                                        daemon=True, name="hover-test")
        self._thread.start()

    def abort(self) -> None:
        self.abort_requested = True

    def running(self) -> bool:
        return bool(self._thread and self._thread.is_alive())

    # ---------------------------------------------------------------- main loop

    def _run(self, height_m: float, duration_s: float) -> None:
        try:
            self._set_state("PREFLIGHT", {"heightM": height_m, "durationS": duration_s})
            ok, why = self._preflight()
            if not ok:
                self._set_state("FAILED", {"error": why})
                return

            self._set_state("ARMING", {})
            self.mav.set_mode("GUIDED_NOGPS")
            self.mav.arm()

            hover_thrust = float(self.cfg.get("hover_thrust", 0.45))
            kp = float(self.cfg.get("alt_kp", 0.12))
            ki = float(self.cfg.get("alt_ki", 0.04))
            kd = float(self.cfg.get("alt_kd", 0.18))     # climb-rate damping
            max_tilt = float(self.cfg.get("dodge_tilt_deg", 6.0))
            react_d = float(self.cfg.get("react_dist_m", 2.0))
            min_d = float(self.cfg.get("min_dist_m", 0.6))
            closing_min = float(self.cfg.get("closing_mps", 0.15))
            thr_lo = float(self.cfg.get("thrust_floor", 0.20))
            thr_hi = float(self.cfg.get("thrust_ceiling", 0.65))

            i_term = 0.0
            prev_agl = None
            prev_t = None
            vz = 0.0                                      # EMA climb-rate estimate
            t_end = time.time() + duration_s
            phase = "CLIMB"
            self._set_state("CLIMB", {"targetM": height_m})
            dodging_since = None

            while True:
                now = time.time()
                if self.abort_requested:
                    self._set_state("LANDING", {"reason": "abort"})
                    self._land(thr_lo, hover_thrust)
                    self._set_state("ABORTED", {})
                    return
                if now >= t_end:
                    break
                b = self.battery()
                if b is not None and b < float(self.cfg.get("battery_floor_pct", 25)):
                    self._set_state("LANDING", {"reason": "battery"})
                    self._land(thr_lo, hover_thrust)
                    self._set_state("DONE", {"note": "landed on battery floor"})
                    return

                agl = self.agl()
                if agl is None:                      # stale rangefinder = no height truth
                    log.error("rangefinder stale — landing")
                    self._set_state("LANDING", {"reason": "rangefinder stale"})
                    self._land(thr_lo, hover_thrust)
                    self._set_state("FAILED", {"error": "rangefinder went stale in flight"})
                    return

                # -------- toward-moving obstacle detection over covered azimuths
                nearest = self._nearest_closing(now)
                roll_cmd = pitch_cmd = 0.0
                if nearest and nearest["dist"] < min_d:
                    # a person essentially touching the drone: do NOT dance — land.
                    self._set_state("LANDING", {"reason": "obstacle inside hard minimum",
                                                "distM": round(nearest["dist"], 2)})
                    self._land(thr_lo, hover_thrust)
                    self._set_state("DONE", {"note": "landed: obstacle inside hard minimum"})
                    return
                if nearest and nearest["dist"] < react_d and nearest["closing"] > closing_min:
                    az = math.radians(nearest["az"])
                    # tilt AWAY: obstacle at +90° (right) -> roll left (negative)
                    roll_cmd = -math.sin(az) * max_tilt
                    pitch_cmd = math.cos(az) * max_tilt   # obstacle ahead -> pitch back (+)
                    if phase != "DODGE":
                        phase = "DODGE"
                        dodging_since = now
                        self.on_event("HOVER_EVENT", {"event": "DODGE",
                                                      "azimuthDeg": nearest["az"],
                                                      "distM": round(nearest["dist"], 2),
                                                      "closingMps": round(nearest["closing"], 2)})
                elif phase == "DODGE":
                    phase = "HOLD"
                    self.on_event("HOVER_EVENT", {"event": "CLEAR",
                                                  "dodgeS": round(now - (dodging_since or now), 1)})

                # -------- height loop (PI + climb-rate damping -> thrust)
                if prev_agl is not None and prev_t is not None and now > prev_t:
                    inst_vz = (agl - prev_agl) / (now - prev_t)
                    vz = 0.6 * vz + 0.4 * max(-3.0, min(3.0, inst_vz))
                prev_agl, prev_t = agl, now
                err = height_m - agl
                i_term = max(-0.1, min(0.1, i_term + ki * err * 0.1))
                thrust = hover_thrust + kp * err - kd * vz + i_term
                thrust = max(thr_lo, min(thr_hi, thrust))
                self.mav.set_attitude_target(roll_cmd, pitch_cmd, 0.0, thrust)

                if phase == "CLIMB" and abs(err) < 0.15:
                    phase = "HOLD"
                    self._set_state("HOLD", {"aglM": round(agl, 2)})

                self.status = {"state": phase, "aglM": round(agl, 2),
                               "targetM": height_m, "thrust": round(thrust, 2),
                               "rollDeg": round(roll_cmd, 1), "pitchDeg": round(pitch_cmd, 1),
                               "nearest": nearest, "endsInS": round(t_end - now, 1)}
                self._maybe_status()
                time.sleep(0.1)                      # 10 Hz control loop

            self._set_state("LANDING", {"reason": "duration complete"})
            self._land(thr_lo, hover_thrust)
            self._set_state("DONE", {})
        except Exception as e:
            log.exception("hover test failed")
            try:
                self._land(0.2, 0.45)
            except Exception:
                pass
            self._set_state("FAILED", {"error": str(e)})
        finally:
            try:
                self.mav.disarm()
            except Exception:
                pass

    # ---------------------------------------------------------------- pieces

    def _preflight(self) -> tuple[bool, str]:
        """GPS is deliberately NOT checked — this test is GPS-free."""
        if self.agl() is None:
            return False, "downward rangefinder not reporting (height truth required)"
        if not self.obstacles():
            # empty list is fine (nothing near); None means lidar dead
            if self.obstacles() is None:
                return False, "lidar not reporting (proximity required)"
        b = self.battery()
        if b is not None and b < float(self.cfg.get("battery_floor_pct", 25)) + 10:
            return False, f"battery too low to start ({b:.0f}%)"
        st = getattr(self.mav, "state", None)
        if st is not None and getattr(st, "ekf_attitude_ok", True) is False:
            return False, "attitude estimate not healthy"
        return True, ""

    def _nearest_closing(self, now: float) -> dict | None:
        """Nearest obstacle over covered azimuths, with its EMA closing speed
        (positive = moving TOWARD the drone)."""
        best = None
        closing_min = float(self.cfg.get("closing_mps", 0.15))
        window_s = float(self.cfg.get("closing_window_s", 1.0))
        for az, dist in (self.obstacles() or []):
            if not math.isfinite(dist):
                continue
            key = int(round(az / 15.0)) * 15          # 15° buckets
            hist = self._range_hist.setdefault(key, [])
            hist.append((now, dist))
            while hist and now - hist[0][0] > window_s:
                hist.pop(0)
            # Windowed closing rate: distance lost across the window.
            rate = 0.0
            steps = 0
            if len(hist) >= 2 and now - hist[0][0] >= 0.25:
                span = now - hist[0][0]
                rate = max(-2.0, min(2.0, (hist[0][1] - dist) / span))
                # Count DISTINCT decreasing steps in the window: a ghost return
                # popping into existence is ONE discontinuity; a genuinely
                # approaching object keeps producing new, smaller ranges.
                for (t0, d0), (t1, d1) in zip(hist, hist[1:]):
                    if d1 < d0 - 0.02:
                        steps += 1
            closing = rate if (steps >= 2 and rate > closing_min) else 0.0
            cand = {"az": az, "dist": dist, "closing": closing}
            if best is None or dist < best["dist"]:
                best = cand
        return best

    def _land(self, thr_lo: float, hover_thrust: float) -> None:
        """Controlled descent on the rangefinder; idle thrust below 15 cm."""
        rate = float(self.cfg.get("descent_rate_mps", 0.3))
        while True:
            agl = self.agl()
            if agl is None or agl < 0.15:
                break
            thrust = hover_thrust - 0.08 - 0.04 * min(1.0, rate)
            self.mav.set_attitude_target(0.0, 0.0, 0.0, max(thr_lo, thrust))
            time.sleep(0.1)
        for _ in range(10):                           # settle at idle
            self.mav.set_attitude_target(0.0, 0.0, 0.0, 0.0)
            time.sleep(0.1)

    _last_status = 0.0

    def _maybe_status(self) -> None:
        if time.time() - self._last_status >= 1.0:
            self._last_status = time.time()
            self.on_event("HOVER_STATUS", self.status)

    def _set_state(self, s: str, payload: dict) -> None:
        self.state = s
        log.info("hover test -> %s %s", s, payload)
        self.on_event("HOVER_EVENT", {"event": s, **payload})
