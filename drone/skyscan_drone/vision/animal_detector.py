"""Animal detection for WILDLIFE_SURVEY — YOLO-class detector on the RGB
frames, fused with thermal hotspots, every confirmed animal tagged with
TIME + LOCATION of the finding.

    RGB frame  --AnimalDetector.detect-->  [AnimalDetection]
    thermal frame --ThermalCamera.capture--> [Hotspot]
                 \\-- SightingTagger.add(dets, hotspots, pose, agl, t) --/
                        |  fuse: RGB det near a hotspot -> FUSED (+conf, temp)
                        |  lone hotspot (warm but no RGB class) -> THERMAL
                        |  project to world (same math as plant detection)
                        |  dedup MOVING targets: same species within
                        |    merge_dist_m AND merge_window_s = one animal
                        v
                 tagged sightings: species, confidence, lat, lon,
                                   sightedAt (time of finding), source, temp

Backends via vision.animal_detector.backend:
  * "tensorrt" — YOLO trained/fine-tuned on aerial wildlife (implementation
    stub; load/infer marked TODO until an engine exists)
  * "mock"     — deterministic synthetic animals so the whole mission type
    runs end-to-end in SITL

The fusion, projection and space-time dedup are REAL and backend-agnostic —
they are the wildlife-specific logic, and identical for mock or TensorRT.
"""
from __future__ import annotations

import logging
import math
import os
import time
from dataclasses import dataclass, field

from .detector import Detection  # reuse the normalized-box dataclass shape

log = logging.getLogger("animal_detector")
MOCK = os.environ.get("SKYSCAN_MOCK_SENSORS") == "1"


@dataclass
class AnimalDetection:
    species: str
    cx: float
    cy: float
    w: float
    h: float
    confidence: float


@dataclass
class Sighting:
    """One tagged animal — the deliverable row."""
    label: str
    species: str
    confidence: float
    lat: float
    lon: float
    sighted_at: float                    # epoch seconds — time of finding
    source: str                          # RGB | THERMAL | FUSED
    body_temp_c: float | None = None
    hits: int = 1
    frame_path: str | None = None        # the frame that triggered the tag (evidence)

    def as_dict(self) -> dict:
        import datetime
        return {"label": self.label, "species": self.species,
                "confidence": round(self.confidence, 3),
                "lat": self.lat, "lon": self.lon,
                "sightedAt": datetime.datetime.fromtimestamp(
                    self.sighted_at, datetime.timezone.utc).isoformat(),
                "source": self.source,
                "bodyTempC": None if self.body_temp_c is None else round(self.body_temp_c, 1)}


# ---------------------------------------------------------------- detector interface


class AnimalDetector:
    """Interface: detect(frame_path, pose, agl_m) -> list[AnimalDetection]."""

    def detect(self, frame_path: str, pose, agl_m: float) -> list[AnimalDetection]:
        raise NotImplementedError

    @staticmethod
    def create(cfg: dict) -> "AnimalDetector":
        backend = cfg.get("backend", "mock" if MOCK else "tensorrt")
        if backend == "tensorrt":
            return TensorRTAnimalDetector(cfg)
        if backend == "mock":
            return MockAnimalDetector(cfg)
        raise ValueError(f"unknown vision.animal_detector.backend: {backend}")


class TensorRTAnimalDetector(AnimalDetector):
    """YOLO exported to TensorRT, trained on aerial/nadir wildlife imagery
    (e.g. fine-tuned on aerial wildlife datasets — a COCO checkpoint knows
    'bird/dog/horse/cow/sheep' style classes and is a weak starting point for
    nadir views; fine-tuning is what makes this reliable)."""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.conf_thresh = float(cfg.get("conf_threshold", 0.4))
        self.engine_path = cfg.get("engine_path", "/opt/skyscan/models/animals.engine")
        self.classes = cfg.get("classes", {})   # id -> species name
        if not os.path.exists(self.engine_path):
            raise FileNotFoundError(
                f"animal detector engine not found: {self.engine_path} — export a "
                f"wildlife YOLO engine, or set vision.animal_detector.backend: mock")
        # TODO(model): load the TensorRT engine.
        raise NotImplementedError(
            "TensorRT animal detector not wired yet — drop in the engine loader here")

    def detect(self, frame_path: str, pose, agl_m: float) -> list[AnimalDetection]:
        # TODO(model): preprocess -> infer -> NMS -> [AnimalDetection].
        raise NotImplementedError


class MockAnimalDetector(AnimalDetector):
    """A few deterministic 'animals' placed in the world near the survey start,
    re-observed by whichever frames overlap them (same fixed-truth pattern as
    the plant mock) — so fusion + moving-target dedup are genuinely exercised."""

    SPECIES = ["deer", "wild boar", "fox", "hare", "roe deer"]

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.hfov = math.radians(float(cfg.get("hfov_deg", 64.9)))
        self.vfov = math.radians(float(cfg.get("vfov_deg", 51.0)))
        self._truth: list[tuple[float, float, str]] | None = None

    def _ensure_truth(self, pose) -> None:
        if self._truth is not None:
            return
        import random
        rnd = random.Random(7)
        self._truth = []
        for i in range(rnd.randint(3, 6)):
            self._truth.append((pose.lat + (rnd.random() - 0.3) * 0.0012,
                                pose.lon + (rnd.random() - 0.3) * 0.0016,
                                self.SPECIES[rnd.randrange(len(self.SPECIES))]))

    def detect(self, frame_path: str, pose, agl_m: float) -> list[AnimalDetection]:
        self._ensure_truth(pose)
        half_x = agl_m * math.tan(self.hfov / 2)
        half_y = agl_m * math.tan(self.vfov / 2)
        m_lat = 110540.0
        m_lon = 111320.0 * math.cos(math.radians(pose.lat))
        cy, sy = math.cos(pose.yaw), math.sin(pose.yaw)
        out = []
        for lat, lon, species in self._truth:
            north = (lat - pose.lat) * m_lat
            east = (lon - pose.lon) * m_lon
            fwd = north * cy + east * sy
            right = -north * sy + east * cy
            if abs(right) <= half_x and abs(fwd) <= half_y:
                out.append(AnimalDetection(
                    species=species,
                    cx=0.5 + right / (2 * half_x), cy=0.5 - fwd / (2 * half_y),
                    w=0.04, h=0.04, confidence=0.7))
        return out


# ---------------------------------------------------------------- fusion + tagging


class SightingTagger:
    """Fuses RGB detections with thermal hotspots, projects to world, and
    deduplicates MOVING targets with a space+time window.

    Fusion rules (per frame):
      * RGB detection with a hotspot within fuse_dist (normalized image
        units) -> FUSED: confidence boosted, body temperature attached.
      * RGB detection without a hotspot -> RGB (could be a cold-looking or
        occluded animal; kept at model confidence).
      * hotspot with no RGB detection -> THERMAL, species "unidentified
        (warm body)" at thermal_only_conf — a real warm body the RGB model
        missed (dusk, canopy) is exactly what the thermal camera is FOR.

    Dedup: unlike plants, an animal re-seen 30 s later two frames over may
    have moved — same species within merge_dist_m AND within merge_window_s
    of an existing sighting is treated as the same animal (position updated
    to the latest fix, first sighting time kept as the time-of-finding).
    Outside the window it is tagged as a new sighting, which is honest: we
    cannot know it is the same individual.
    """

    def __init__(self, cfg: dict):
        self.hfov = math.radians(float(cfg.get("hfov_deg", 64.9)))
        self.vfov = math.radians(float(cfg.get("vfov_deg", 51.0)))
        self.merge_dist_m = float(cfg.get("merge_dist_m", 12.0))
        self.merge_window_s = float(cfg.get("merge_window_s", 90.0))
        self.fuse_dist = float(cfg.get("fuse_dist", 0.08))      # normalized image units
        self.thermal_only_conf = float(cfg.get("thermal_only_conf", 0.45))
        self.min_conf = float(cfg.get("min_conf", 0.4))
        self.sightings: list[Sighting] = []
        self._n = 0
        self.on_new_sighting = None      # callback(Sighting) -> live stream hook

    def add(self, dets: list[AnimalDetection], hotspots: list, pose, agl_m: float,
            t: float | None = None, frame_path: str | None = None) -> list[Sighting]:
        """Process one synchronized RGB+thermal frame pair; returns NEW sightings."""
        if agl_m <= 0.2:
            return []
        t = time.time() if t is None else t
        hotspots = list(hotspots or [])
        new: list[Sighting] = []

        for d in dets:
            temp = None
            source = "RGB"
            conf = d.confidence
            best = None
            best_dd = self.fuse_dist
            for hs in hotspots:
                dd = math.hypot(hs.cx - d.cx, hs.cy - d.cy)
                if dd <= best_dd:
                    best, best_dd = hs, dd
            if best is not None:
                hotspots.remove(best)
                source = "FUSED"
                temp = best.temp_c
                conf = min(0.99, conf + 0.2)          # two independent sensors agree
            if conf < self.min_conf:
                continue
            s = self._tag(d.species, conf, d.cx, d.cy, pose, agl_m, t, source, temp, frame_path)
            if s:
                new.append(s)

        for hs in hotspots:                           # warm bodies the RGB model missed
            s = self._tag("unidentified (warm body)", self.thermal_only_conf,
                          hs.cx, hs.cy, pose, agl_m, t, "THERMAL", hs.temp_c, frame_path)
            if s:
                new.append(s)
        return new

    def _tag(self, species, conf, cx, cy, pose, agl_m, t, source, temp,
             frame_path=None) -> Sighting | None:
        lat, lon = self._project(cx, cy, pose, agl_m)
        m_lat = 110540.0
        m_lon = 111320.0 * math.cos(math.radians(lat))
        for s in self.sightings:
            if s.species != species:
                continue
            if abs(t - s.sighted_at) > self.merge_window_s:
                continue
            if math.hypot((s.lat - lat) * m_lat, (s.lon - lon) * m_lon) <= self.merge_dist_m:
                s.lat, s.lon = lat, lon               # latest fix
                s.hits += 1
                s.confidence = max(s.confidence, conf)
                if temp is not None:
                    s.body_temp_c = temp
                    if s.source == "RGB":
                        s.source = "FUSED"
                return None                            # same animal, no new tag
        self._n += 1
        s = Sighting(label=f"A-{self._n:03d}", species=species, confidence=conf,
                     lat=lat, lon=lon, sighted_at=t, source=source, body_temp_c=temp,
                     frame_path=frame_path)
        self.sightings.append(s)
        if self.on_new_sighting:
            try:
                self.on_new_sighting(s)
            except Exception as e:
                log.warning("live sighting hook failed: %s", e)
        return s

    def _project(self, cx, cy, pose, agl_m) -> tuple[float, float]:
        half_x = agl_m * math.tan(self.hfov / 2)
        half_y = agl_m * math.tan(self.vfov / 2)
        dx = (cx - 0.5) * 2 * half_x
        dy = (0.5 - cy) * 2 * half_y
        c, s = math.cos(pose.yaw), math.sin(pose.yaw)
        north = dy * c - dx * s
        east = dy * s + dx * c
        m_lat = 110540.0
        m_lon = 111320.0 * math.cos(math.radians(pose.lat))
        return pose.lat + north / m_lat, pose.lon + east / m_lon

    def result(self) -> list[dict]:
        out = sorted(self.sightings, key=lambda s: s.sighted_at)
        log.info("wildlife tagging: %d sightings (%d fused, %d thermal-only)",
                 len(out), sum(1 for s in out if s.source == "FUSED"),
                 sum(1 for s in out if s.source == "THERMAL"))
        return [s.as_dict() for s in out]
