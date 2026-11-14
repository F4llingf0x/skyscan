"""On-board computer-vision models for CROP_HEALTH missions.

Two pluggable model interfaces, each with a real (TensorRT) implementation
stub and a deterministic MOCK used in SITL / on machines without the models:

  detector.py    PlantDetector  — YOLO-class per-frame plant/tree detection,
                 plus the frame->world projection + dedup that turns detections
                 into a georeferenced plant list (the crude-pass deliverable).
  classifier.py  DiseaseClassifier — per-plant close-up -> disease findings
                 (the inspection-pass deliverable).

Nothing here talks to the backend or the flight controller: the agent owns
that wiring. Swapping the mock for a trained .engine is a config change
(vision.detector.backend / vision.classifier.backend), no orchestration edits.
"""
