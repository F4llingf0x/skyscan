"""Thermal camera for WILDLIFE_SURVEY — FLIR Lepton 3.5 on a PureThermal
(USB/UVC) carrier, the most practical radiometric option on a Jetson Orin Nano
(no SPI bit-banging, shows up as a V4L2 device with Y16 radiometric frames).

Interface: capture() -> ThermalFrame(hotspots, max_temp_c, frame_path)
  hotspots: warm blobs above the animal-band threshold, each with a
  normalized image position (cx, cy in 0..1) + estimated temperature, i.e.
  the same coordinate convention the RGB detector uses, so the fusion step
  in vision/animal_detector.py can match them directly.

Real capture is a marked TODO (needs the device + libuvc/flirpy on the
bench); the MOCK produces deterministic hotspots so the wildlife chain runs
end-to-end in SITL. This mirrors the TensorRT model stubs: interface + mock
are real and tested, the hardware call is the one seam left open.
"""
from __future__ import annotations

import logging
import math
import os
from dataclasses import dataclass, field

log = logging.getLogger("thermal")
MOCK = os.environ.get("SKYSCAN_MOCK_SENSORS") == "1"


@dataclass
class Hotspot:
    cx: float                 # normalized image x (0..1), same convention as RGB
    cy: float
    temp_c: float
    size_px: int = 0


@dataclass
class ThermalFrame:
    hotspots: list[Hotspot] = field(default_factory=list)
    max_temp_c: float = 0.0
    frame_path: str | None = None


class ThermalCamera:
    """Lepton 3.5 / PureThermal via UVC. Warm-blob extraction happens here so
    downstream code never touches raw radiometric frames."""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.enabled = bool(cfg.get("enabled", False))
        self.device = cfg.get("device", "/dev/video1")
        # Animal body-temperature band: birds/mammals sit well above ambient
        # ground in most conditions; both bounds configurable per season.
        self.min_temp_c = float(cfg.get("min_temp_c", 28.0))
        self.max_temp_c = float(cfg.get("max_temp_c", 42.0))
        self.min_blob_px = int(cfg.get("min_blob_px", 4))
        self._mock_i = 0
        if self.enabled and not MOCK:
            self._open()

    def _open(self) -> None:
        # TODO(hardware): open the PureThermal UVC device (Y16 mode) via
        # v4l2/libuvc or flirpy.camera.Lepton; verify radiometric mode (TLinear).
        raise NotImplementedError(
            f"thermal capture not wired yet — connect the PureThermal board at "
            f"{self.device} and drop in the UVC open here (or set thermal.enabled: false)")

    def capture(self, pose=None) -> ThermalFrame | None:
        """One thermal frame -> warm blobs in the animal band."""
        if not self.enabled:
            return None
        if MOCK:
            return self._mock_frame(pose)
        # TODO(hardware): grab Y16 frame, convert counts->°C (TLinear: c/100-273.15),
        # threshold to the band, connected-components -> blob centroids -> Hotspots.
        raise NotImplementedError

    def _mock_frame(self, pose) -> ThermalFrame:
        """Deterministic hotspots keyed to the pose, so overlapping frames see
        the same 'animal' — exercises fusion + dedup exactly like the field."""
        import random
        self._mock_i += 1
        seed = 0 if pose is None else (hash((round(pose.lat, 4), round(pose.lon, 4))) & 0xFFFF)
        rnd = random.Random(seed)
        spots = []
        if rnd.random() < 0.5:                      # half the frames have a warm body
            for _ in range(rnd.randint(1, 2)):
                spots.append(Hotspot(cx=rnd.uniform(0.15, 0.85),
                                     cy=rnd.uniform(0.15, 0.85),
                                     temp_c=rnd.uniform(33.0, 39.5),
                                     size_px=rnd.randint(6, 40)))
        return ThermalFrame(hotspots=spots,
                            max_temp_c=max((s.temp_c for s in spots), default=18.0))
