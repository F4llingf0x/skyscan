"""Plant/tree detection for the CROP_HEALTH crude pass.

Pipeline (all on the Jetson, in flight or in a post-pass sweep of the captured
frames):

    frame (+ pose + AGL)  --PlantDetector.detect-->  [Detection boxes]
    box center + pose + AGL  --_project--> world (lat, lon)
    world points across frames  --PlantAggregator.add--> deduplicated plants

The detector is an interface with two backends, chosen by config
`vision.detector.backend`:
  * "tensorrt" — a real YOLO exported to a TensorRT .engine (implementation
    stub below; the load/infer calls are marked TODO and raise until a model
    is provided). This is the production path on the Orin Nano.
  * "mock" (default off-hardware / SITL) — deterministic synthetic detections
    so the whole crop workflow runs end-to-end without a model or camera.

The world projection and cross-frame dedup (PlantAggregator) are REAL and
backend-agnostic: they are what turns per-frame boxes into the georeferenced
plant list the backend ingests, and they are the same for mock or TensorRT.
"""
from __future__ import annotations

import logging
import math
import os
from dataclasses import dataclass, field

log = logging.getLogger("detector")
MOCK = os.environ.get("SKYSCAN_MOCK_SENSORS") == "1"


@dataclass
class Detection:
    """One detection in one frame. Box in normalized image coords [0,1]."""
    kind: str            # "CROP" | "TREE"
    cx: float            # box center x, 0=left .. 1=right
    cy: float            # box center y, 0=top .. 1=bottom
    w: float
    h: float
    confidence: float


@dataclass
class WorldPlant:
    """A deduplicated plant in world coordinates, ready for the backend."""
    kind: str
    lat: float
    lon: float
    height_m: float = 0.0
    canopy_dia_m: float = 0.0
    hits: int = 1                       # frames it was seen in (dedup support)
    confidence: float = 0.0


# ---------------------------------------------------------------- interface


class PlantDetector:
    """Interface: detect(frame_path, pose, agl_m) -> list[Detection]."""

    def detect(self, frame_path: str, pose, agl_m: float) -> list[Detection]:
        raise NotImplementedError

    @staticmethod
    def create(cfg: dict) -> "PlantDetector":
        backend = cfg.get("backend", "mock" if MOCK else "tensorrt")
        if backend == "tensorrt":
            return TensorRTPlantDetector(cfg)
        if backend == "mock":
            return MockPlantDetector(cfg)
        raise ValueError(f"unknown vision.detector.backend: {backend}")


# ---------------------------------------------------------------- tensorrt backend


class TensorRTPlantDetector(PlantDetector):
    """Real path: a YOLO model exported to a TensorRT engine.

    Provide `vision.detector.engine_path` (a built .engine) and a class map
    (`vision.detector.classes`, YOLO class id -> "CROP"/"TREE"). The infer()
    body is the ONLY thing left to fill in once a trained model exists; the
    projection + dedup around it are already done (see PlantAggregator).
    """

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.conf_thresh = float(cfg.get("conf_threshold", 0.35))
        self.classes = cfg.get("classes", {0: "CROP", 1: "TREE"})
        self.engine_path = cfg.get("engine_path", "/opt/skyscan/models/plants.engine")
        self._session = None
        self._load()

    def _load(self) -> None:
        if not os.path.exists(self.engine_path):
            raise FileNotFoundError(
                f"detector engine not found: {self.engine_path} — build/export a YOLO "
                f"TensorRT engine, or set vision.detector.backend: mock for SITL")
        # TODO(model): load the TensorRT engine (tensorrt + pycuda, or ultralytics
        # with the exported engine). Kept out of the import path so the agent runs
        # on machines without CUDA when using the mock backend.
        raise NotImplementedError(
            "TensorRT detector inference not wired yet — drop in the engine loader here")

    def detect(self, frame_path: str, pose, agl_m: float) -> list[Detection]:
        # TODO(model): preprocess frame -> infer -> NMS -> [Detection(...)].
        raise NotImplementedError


# ---------------------------------------------------------------- mock backend


class MockPlantDetector(PlantDetector):
    """Deterministic synthetic detections seeded by frame index, so a SITL
    crude pass yields a stable, plausible plant field without a camera/model.
    A handful of plants scattered per frame; the aggregator dedups the overlap
    into a coherent set, exercising the real projection + dedup code."""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self._field: list[tuple[float, float, str]] | None = None   # lazily seeded truth
        self._origin: tuple[float, float] | None = None
        self.hfov = math.radians(float(cfg.get("hfov_deg", 62.2)))
        self.vfov = math.radians(float(cfg.get("vfov_deg", 51.0)))

    def _ensure_field(self, pose) -> None:
        # A fixed grid of ground-truth plants around the first frame, so
        # overlapping frames re-observe the same plants and dedup merges them.
        if self._field is not None:
            return
        import random
        rnd = random.Random(42)
        self._origin = (pose.lat, pose.lon)
        self._field = []
        for i in range(-6, 7):
            for j in range(-6, 7):
                lat = pose.lat + i * 0.00010 + (rnd.random() - 0.5) * 0.00003
                lon = pose.lon + j * 0.00013 + (rnd.random() - 0.5) * 0.00004
                self._field.append((lat, lon, "TREE" if rnd.random() < 0.15 else "CROP"))

    def detect(self, frame_path: str, pose, agl_m: float) -> list[Detection]:
        self._ensure_field(pose)
        # Which truth plants fall in this frame's footprint? Emit them as boxes.
        half_x = agl_m * math.tan(self.hfov / 2)
        half_y = agl_m * math.tan(self.vfov / 2)
        m_lat = 110540.0
        m_lon = 111320.0 * math.cos(math.radians(pose.lat))
        cy, sy = math.cos(pose.yaw), math.sin(pose.yaw)
        out = []
        for lat, lon, kind in self._field:
            north = (lat - pose.lat) * m_lat
            east = (lon - pose.lon) * m_lon
            fwd = north * cy + east * sy         # inverse of the aggregator rotation
            right = -north * sy + east * cy
            if abs(right) <= half_x and abs(fwd) <= half_y:
                out.append(Detection(
                    kind=kind,
                    cx=0.5 + right / (2 * half_x),
                    cy=0.5 - fwd / (2 * half_y),
                    w=0.08, h=0.08, confidence=0.85))
        return out


# ---------------------------------------------------------------- projection + dedup


class PlantAggregator:
    """Turns per-frame detections into a deduplicated, georeferenced plant list.

    Projection: a detection at normalized (cx, cy) in a nadir frame maps to a
    ground offset from the vehicle using the camera's horizontal/vertical FOV
    and the above-ground height (AGL). Small pitch/roll are folded in via the
    pose yaw for the heading; for a survey flown near-level this nadir model is
    the right first approximation (a full pinhole+attitude model is a drop-in
    refinement later). Dedup: any new world point within `merge_dist_m` of an
    existing plant of the same kind is merged (running-mean position, hit
    count and max confidence), so a plant seen in several overlapping frames
    becomes one row.
    """

    def __init__(self, cfg: dict):
        self.hfov = math.radians(float(cfg.get("hfov_deg", 64.9)))   # IMX219-77 (77° diag)
        self.vfov = math.radians(float(cfg.get("vfov_deg", 51.0)))
        self.merge_dist_m = float(cfg.get("merge_dist_m", 1.5))
        self.min_hits = int(cfg.get("min_hits", 1))
        # CROP_FIELD zone coalescing: a dense connected cluster of CROP
        # detections is one row-crop ZONE, not hundreds of individual plants.
        self.field_link_dist_m = float(cfg.get("field_link_dist_m", 3.0))
        self.field_min_plants = int(cfg.get("field_min_plants", 25))
        self.plants: list[WorldPlant] = []

    def add(self, dets: list[Detection], pose, agl_m: float) -> None:
        if agl_m <= 0.2:
            return                                   # no usable scale without AGL
        for d in dets:
            lat, lon = self._project(d, pose, agl_m)
            self._merge(d, lat, lon, agl_m)

    def _project(self, d: Detection, pose, agl_m: float) -> tuple[float, float]:
        # Ground half-extent covered by the frame at this AGL (nadir camera).
        half_x = agl_m * math.tan(self.hfov / 2)
        half_y = agl_m * math.tan(self.vfov / 2)
        # Detection offset from image center, in metres (image +y is down/forward-)
        dx = (d.cx - 0.5) * 2 * half_x               # right in body frame
        dy = (0.5 - d.cy) * 2 * half_y               # forward in body frame
        # Rotate body (fwd,right) into world by yaw, convert to lat/lon.
        cy, sy = math.cos(pose.yaw), math.sin(pose.yaw)
        north = dy * cy - dx * sy
        east = dy * sy + dx * cy
        m_lat = 110540.0
        m_lon = 111320.0 * math.cos(math.radians(pose.lat))
        return pose.lat + north / m_lat, pose.lon + east / m_lon

    def _merge(self, d: Detection, lat: float, lon: float, agl_m: float) -> None:
        m_lat = 110540.0
        m_lon = 111320.0 * math.cos(math.radians(lat))
        for p in self.plants:
            if p.kind != d.kind:
                continue
            dm = math.hypot((p.lat - lat) * m_lat, (p.lon - lon) * m_lon)
            if dm <= self.merge_dist_m:
                n = p.hits
                p.lat = (p.lat * n + lat) / (n + 1)
                p.lon = (p.lon * n + lon) / (n + 1)
                p.hits += 1
                p.confidence = max(p.confidence, d.confidence)
                return
        # canopy estimate from box size projected to the ground
        canopy = d.w * 2 * agl_m * math.tan(self.hfov / 2)
        self.plants.append(WorldPlant(
            kind=d.kind, lat=lat, lon=lon, canopy_dia_m=round(canopy, 2),
            height_m=0.0, hits=1, confidence=d.confidence))

    def result(self) -> list[dict]:
        """Entries meeting the min-hits gate — with dense CROP clusters
        COALESCED into CROP_FIELD zones — labelled C-/T-/F- in stable order."""
        keep = [p for p in self.plants if p.hits >= self.min_hits]
        keep = self._coalesce_fields(keep)
        keep.sort(key=lambda p: (p.kind, p.lat, p.lon))
        nc = nt = nf = 0
        out = []
        for p in keep:
            if p.kind == "TREE":
                nt += 1; p_label = f"T-{nt:03d}"
            elif p.kind == "CROP_FIELD":
                nf += 1; p_label = f"F-{nf:03d}"
            else:
                nc += 1; p_label = f"C-{nc:03d}"
            out.append((p_label, p))
        log.info("aggregated %d detections -> %d entries (%d trees, %d field zones)",
                 len(self.plants), len(out), nt, nf)
        return [self._as_dict(lbl, p) for lbl, p in out]

    def _coalesce_fields(self, keep: list[WorldPlant]) -> list[WorldPlant]:
        """Single-link clustering of CROP plants: connected components with
        neighbor spacing <= field_link_dist_m and >= field_min_plants members
        become ONE CROP_FIELD zone (centroid + spatial-extent diameter).
        Trees and sparse crops stay individual — how a real detector
        distinguishes an orchard row from a wheat field."""
        crops = [p for p in keep if p.kind == "CROP"]
        others = [p for p in keep if p.kind != "CROP"]
        if not crops:
            return keep
        m_lat = 110540.0
        m_lon = 111320.0 * math.cos(math.radians(crops[0].lat))
        cell = max(0.5, self.field_link_dist_m)
        grid: dict[tuple[int, int], list[int]] = {}
        for i, p in enumerate(crops):
            grid.setdefault((int(p.lat * m_lat / cell), int(p.lon * m_lon / cell)), []).append(i)
        parent = list(range(len(crops)))

        def find(a: int) -> int:
            while parent[a] != a:
                parent[a] = parent[parent[a]]
                a = parent[a]
            return a

        for i, p in enumerate(crops):
            ci = (int(p.lat * m_lat / cell), int(p.lon * m_lon / cell))
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for j in grid.get((ci[0] + dx, ci[1] + dy), []):
                        if j <= i:
                            continue
                        q = crops[j]
                        dm = math.hypot((p.lat - q.lat) * m_lat, (p.lon - q.lon) * m_lon)
                        if dm <= self.field_link_dist_m:
                            ra, rb = find(i), find(j)
                            if ra != rb:
                                parent[ra] = rb
        comps: dict[int, list[WorldPlant]] = {}
        for i, p in enumerate(crops):
            comps.setdefault(find(i), []).append(p)

        out = list(others)
        for members in comps.values():
            if len(members) < self.field_min_plants:
                out.extend(members)
                continue
            clat = sum(p.lat for p in members) / len(members)
            clon = sum(p.lon for p in members) / len(members)
            ext = max(math.hypot((p.lat - clat) * m_lat, (p.lon - clon) * m_lon)
                      for p in members) * 2.0
            out.append(WorldPlant(kind="CROP_FIELD", lat=clat, lon=clon,
                                  canopy_dia_m=round(ext, 1),
                                  hits=sum(p.hits for p in members),
                                  confidence=max(p.confidence for p in members)))
        return out

    @staticmethod
    def _as_dict(label: str, p: WorldPlant) -> dict:
        return {"label": label, "kind": p.kind, "lat": p.lat, "lon": p.lon,
                "heightM": round(p.height_m, 2), "canopyDiaM": round(p.canopy_dia_m, 2),
                "confidence": round(p.confidence, 3), "hits": p.hits}
