"""Camera module catalog — the single source of truth for per-module optics.

The mounted camera module is SELECTABLE, at two levels:
  1. airframe default: config `camera.module`
  2. per-mission override: the mission plan's `cameraModule` (picked by the
     operator in the SkyScan planner before launching the scan) — this wins,
     because the operator on site knows which module is actually mounted.

The FOV here drives PlantAggregator's frame->world projection, so a wrong
module selection puts every detected plant in the wrong place. The catalog ids
MUST stay in sync with the frontend planner's dropdown (planner.component.ts).

Wide/fisheye variants come with an honest warning: the nadir pinhole model the
aggregator uses degrades toward the frame edges as distortion grows. The 120°
module is usable if detections near the frame edge are down-weighted or the
image is undistorted first; the 160° fisheye is NOT suitable for mapping
without a proper fisheye camera model — selectable, but flagged.
"""
from __future__ import annotations

import logging

log = logging.getLogger("camera_modules")

# id -> (hfov_deg, vfov_deg, mapping_grade, note)
#   mapping_grade: "good" | "degraded" | "unsuitable" (for the nadir projection)
CAMERA_MODULES: dict[str, tuple[float, float, str, str]] = {
    "imx219-std": (62.2, 48.8, "good",
                   "stock lens (Raspberry Pi Camera v2 optics)"),
    "imx219-77":  (64.9, 51.0, "good",
                   "77° diagonal variant (project default)"),
    "imx219-120": (108.4, 92.2, "degraded",
                   "120° diagonal wide lens — edge distortion: undistort frames "
                   "or ignore detections near the border for accurate mapping"),
    "imx219-160": (155.1, 147.2, "unsuitable",
                   "160° fisheye — nadir pinhole projection invalid; do not use "
                   "for plant mapping without a fisheye camera model"),
}

DEFAULT_MODULE = "imx219-77"


def resolve_fov(module_id: str | None, hfov_override=None, vfov_override=None
                ) -> tuple[float, float, str]:
    """(hfov_deg, vfov_deg, module_id) for a module, with optional explicit
    overrides (custom lens). Unknown ids fall back to the default, loudly."""
    if hfov_override is not None and vfov_override is not None:
        return float(hfov_override), float(vfov_override), module_id or "custom"
    mid = (module_id or DEFAULT_MODULE).lower()
    if mid not in CAMERA_MODULES:
        log.warning("unknown camera module %r — using %s", module_id, DEFAULT_MODULE)
        mid = DEFAULT_MODULE
    h, v, grade, note = CAMERA_MODULES[mid]
    if grade == "degraded":
        log.warning("camera module %s: %s", mid, note)
    elif grade == "unsuitable":
        log.error("camera module %s: %s", mid, note)
    return h, v, mid
