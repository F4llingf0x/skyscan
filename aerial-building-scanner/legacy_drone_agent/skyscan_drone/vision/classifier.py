"""Per-plant disease classification for the CROP_HEALTH inspection pass.

Input: the close-up burst captured while hovering over one plant. Output: zero
or more findings (disease id + severity + confidence). Interface with two
backends via config `vision.classifier.backend`:
  * "tensorrt" — a disease-classification CNN/ViT exported to a TensorRT
    engine (implementation stub; infer() raises until a model is provided).
  * "mock" (default off-hardware) — deterministic findings so the inspection
    pass produces a coherent report without a model.

The disease IDs MUST match the backend DiseaseCatalog ids, since the backend
maps them to cures/checks in the report. Kept in one place here.
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass

log = logging.getLogger("classifier")
MOCK = os.environ.get("SKYSCAN_MOCK_SENSORS") == "1"

# Must stay in sync with backend DiseaseCatalog. "healthy" == no finding.
DISEASE_IDS_CROP = ["late_blight", "powdery_mildew", "leaf_rust",
                    "nutrient_deficiency", "water_stress"]
DISEASE_IDS_TREE = ["powdery_mildew", "leaf_rust", "fire_blight",
                    "nutrient_deficiency", "water_stress"]


@dataclass
class Finding:
    disease_id: str
    severity: str        # "LOW" | "MEDIUM" | "HIGH"
    confidence: float


class DiseaseClassifier:
    """Interface: classify(image_paths, kind) -> list[Finding]."""

    def classify(self, image_paths: list[str], kind: str) -> list[Finding]:
        raise NotImplementedError

    @staticmethod
    def create(cfg: dict) -> "DiseaseClassifier":
        backend = cfg.get("backend", "mock" if MOCK else "tensorrt")
        if backend == "tensorrt":
            return TensorRTDiseaseClassifier(cfg)
        if backend == "mock":
            return MockDiseaseClassifier(cfg)
        raise ValueError(f"unknown vision.classifier.backend: {backend}")


class TensorRTDiseaseClassifier(DiseaseClassifier):
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.conf_thresh = float(cfg.get("conf_threshold", 0.5))
        self.engine_path = cfg.get("engine_path", "/opt/skyscan/models/disease.engine")
        self.labels = cfg.get("labels")   # engine output index -> disease id
        if not os.path.exists(self.engine_path):
            raise FileNotFoundError(
                f"classifier engine not found: {self.engine_path} — export a disease "
                f"CNN/ViT TensorRT engine, or set vision.classifier.backend: mock")
        # TODO(model): load the TensorRT engine here.
        raise NotImplementedError(
            "TensorRT classifier inference not wired yet — drop in the engine loader here")

    def classify(self, image_paths: list[str], kind: str) -> list[Finding]:
        # TODO(model): run each close-up, aggregate (max/mean) across the burst,
        # threshold, map output index -> disease id via self.labels, -> [Finding].
        raise NotImplementedError


class MockDiseaseClassifier(DiseaseClassifier):
    """Deterministic findings seeded by the plant's images so a SITL inspection
    pass yields a stable, plausible report. Bias toward 'healthy' so most
    plants pass, matching real fields."""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.rate = float(cfg.get("mock_finding_rate", 0.35))   # fraction flagged

    def classify(self, image_paths: list[str], kind: str) -> list[Finding]:
        import random
        seed = hash(tuple(image_paths)) & 0xFFFFFFFF
        rnd = random.Random(seed)
        if rnd.random() > self.rate:
            return []                                # healthy
        pool = DISEASE_IDS_TREE if kind == "TREE" else DISEASE_IDS_CROP  # CROP_FIELD -> crop pool
        did = pool[rnd.randrange(len(pool))]
        r = rnd.random()
        sev = "HIGH" if r > 0.66 else "MEDIUM" if r > 0.33 else "LOW"
        return [Finding(disease_id=did, severity=sev,
                        confidence=round(0.6 + rnd.random() * 0.35, 3))]
