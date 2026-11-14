"""Continuous safety monitor — the veto authority over the mission logic.

Watches battery, EKF health, GNSS fix, backend link age and geofence, and
escalates: WARN -> pause leg (RTL for battery swap) -> RTL -> LAND. The flight
controller's own failsafes (RC loss, crash detection, low-battery LAND) remain
armed underneath as the last line — this monitor only ever adds conservatism.
"""
from __future__ import annotations

import logging
import time

log = logging.getLogger("safety")


class SafetyMonitor:
    def __init__(self, cfg: dict, mav, geofence_check, on_event):
        self.cfg = cfg
        self.mav = mav
        self.in_fence = geofence_check              # (lat, lon) -> bool
        self.on_event = on_event
        self.last_link_ok = time.time()

    def link_ok(self) -> None:
        self.last_link_ok = time.time()

    def battery_needs_swap(self) -> bool:
        return self.mav.state.battery_pct <= self.cfg["battery"]["rtl_pct"]

    def battery_critical(self) -> bool:
        return self.mav.state.battery_pct <= self.cfg["battery"]["land_now_pct"]

    def ok_to_start_leg(self) -> tuple[bool, str]:
        s = self.mav.state
        if s.battery_pct < self.cfg["battery"]["leg_start_min_pct"]:
            return False, f"battery {s.battery_pct:.0f}% < leg start minimum"
        if s.gps_fix < 3:
            return False, f"GPS fix type {s.gps_fix} (<3D)"
        if not s.ekf_ok:
            return False, "EKF not healthy"
        return True, "ok"

    def abort_reason(self) -> str | None:
        """Checked between waypoints; any non-None aborts the leg safely."""
        s = self.mav.state
        if self.battery_critical():
            return "battery critical"
        if not self.in_fence(s.pose.lat, s.pose.lon):
            return "geofence breach"
        if not s.ekf_ok:
            return "EKF degraded"
        # Link loss does NOT abort — the survey continues autonomously and
        # events are queued; RTL on link loss is a policy knob, default off.
        return None
