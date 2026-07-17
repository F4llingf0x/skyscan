"""Full recognition + health-evaluation BENCH TEST — run the entire CROP_HEALTH
vision chain on the bench, without flying and without the agent/backend:

    camera (or image folder, or mock)
      -> PlantDetector.detect          per frame
      -> PlantAggregator               frame -> world -> deduplicated plants
      -> DiseaseClassifier.classify    per-plant close-up burst
      -> mini report                   table + JSON, same disease IDs the
                                       backend catalog maps to cures/checks

    python3 -m skyscan_drone.vision_bench [options]

Frame sources (--source):
  mock    (default) synthetic detections over a simulated mini-lawnmower —
          verifies the WHOLE chain incl. projection + dedup with zero hardware
  camera  live IMX219 captures: N frames on a simulated flight path (the bench
          is stationary, so poses are synthetic; this tests the model + camera
          together, not the georeferencing)
  dir     run the detector over existing images (e.g. real field frames copied
          from a flight) — the honest way to evaluate a trained engine before
          trusting it in the air; poses are synthesized along a lawnmower

Backends come from --detector/--classifier (mock|tensorrt), so this same tool
is the acceptance test when the real .engine files arrive: point it at a
folder of labelled field frames and read the plant/finding tables.

Exit code: 0 if the chain produced >=1 plant and the classifier ran on every
plant; 1 otherwise. JSON results land in /tmp/vision_bench_<ts>.json.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
from pathlib import Path


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--task", choices=("plants", "animals"), default="plants",
                    help="plants = crop detect+classify chain; animals = wildlife "
                         "RGB+thermal fusion + time/location tagging chain")
    ap.add_argument("--source", choices=("mock", "camera", "dir"), default="mock")
    ap.add_argument("--dir", help="image folder for --source dir")
    ap.add_argument("--frames", type=int, default=25, help="frames for mock/camera")
    ap.add_argument("--agl", type=float, default=40.0, help="simulated crude-pass AGL (m)")
    ap.add_argument("--inspect-shots", type=int, default=3, help="close-ups per plant")
    ap.add_argument("--detector", choices=("mock", "tensorrt"), default=None,
                    help="override vision.detector.backend")
    ap.add_argument("--classifier", choices=("mock", "tensorrt"), default=None)
    ap.add_argument("--module", default="imx219-77",
                    help="camera module from the catalog: imx219-std | imx219-77 | "
                         "imx219-120 | imx219-160 (sets FOV)")
    ap.add_argument("--hfov", type=float, default=None, help="override horizontal FOV deg")
    ap.add_argument("--vfov", type=float, default=None)
    ap.add_argument("--merge-dist", type=float, default=1.5)
    ap.add_argument("--min-hits", type=int, default=1)
    ap.add_argument("--lat", type=float, default=47.4979)
    ap.add_argument("--lon", type=float, default=19.0402)
    ap.add_argument("--mock", action="store_true",
                    help="force mock sensors even off-SITL (implied by --source mock)")
    return ap.parse_args(argv)


class Pose:
    def __init__(self, lat, lon, yaw=0.0, alt=40.0):
        self.lat, self.lon, self.yaw, self.alt_rel_m = lat, lon, yaw, alt


def lawnmower_poses(a, n: int):
    """Synthetic crude-pass poses: serpentine lines spaced for ~30% side overlap
    at the given AGL/FOV, so the aggregator's dedup is genuinely exercised."""
    gx = 2 * a.agl * math.tan(math.radians(a.hfov) / 2)
    step_m = gx * 0.35                              # along-track step
    line_m = 2 * a.agl * math.tan(math.radians(a.vfov) / 2) * 0.7   # 30% overlap
    m_lat, m_lon = 110540.0, 111320.0 * math.cos(math.radians(a.lat))
    per_line = max(2, int(math.sqrt(n)))
    out = []
    for i in range(n):
        line, k = divmod(i, per_line)
        k = k if line % 2 == 0 else per_line - 1 - k          # serpentine
        out.append(Pose(a.lat + line * line_m / m_lat,
                        a.lon + k * step_m / m_lon, yaw=0.0, alt=a.agl))
    return out


def main() -> None:
    a = parse_args()
    if a.mock or a.source == "mock":
        os.environ["SKYSCAN_MOCK_SENSORS"] = "1"
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from skyscan_drone.vision.detector import PlantDetector, PlantAggregator
    from skyscan_drone.vision.classifier import DiseaseClassifier
    from skyscan_drone.sensors.camera_modules import resolve_fov

    a.hfov, a.vfov, module = resolve_fov(a.module, a.hfov, a.vfov)

    if a.task == "animals":
        run_animals(a)
        return

    det_cfg = {"backend": a.detector} if a.detector else {}
    clf_cfg = {"backend": a.classifier} if a.classifier else {}
    det_cfg.setdefault("hfov_deg", a.hfov); det_cfg.setdefault("vfov_deg", a.vfov)
    detector = PlantDetector.create(det_cfg)
    classifier = DiseaseClassifier.create(clf_cfg)
    agg = PlantAggregator({"hfov_deg": a.hfov, "vfov_deg": a.vfov,
                           "merge_dist_m": a.merge_dist, "min_hits": a.min_hits})

    print(f"vision bench — source={a.source}  detector={detector.__class__.__name__}"
          f"  classifier={classifier.__class__.__name__}")
    print(f"optics: module {module}, {a.hfov:.1f}°x{a.vfov:.1f}°, {a.agl:.0f} m AGL")

    # ---------------- stage 1: frames -> detections -> aggregated plants
    t0 = time.time()
    frames: list[tuple[str, Pose]] = []
    if a.source == "dir":
        if not a.dir or not Path(a.dir).is_dir():
            print("ERROR: --source dir needs --dir <folder>"); sys.exit(1)
        imgs = sorted(str(p) for p in Path(a.dir).iterdir()
                      if p.suffix.lower() in (".jpg", ".jpeg", ".png"))
        frames = list(zip(imgs, lawnmower_poses(a, len(imgs))))
    elif a.source == "camera":
        from skyscan_drone.sensors.camera import Imx219Camera
        poses = lawnmower_poses(a, a.frames)
        cam = Imx219Camera({"enabled": True, "width": 3280, "height": 2464,
                            "session_dir": "/tmp/vision_bench_frames", "interval_s": 0},
                           pose_provider=lambda: poses[0])
        cam.start_session(f"bench-{int(t0)}")
        for p in poses:
            shot = cam.capture()
            if shot:
                frames.append((shot, p))
    else:
        frames = [(f"mock_{i}.jpg", p) for i, p in enumerate(lawnmower_poses(a, a.frames))]

    total_dets = 0
    det_latency = []
    for path, pose in frames:
        t = time.time()
        dets = detector.detect(path, pose, a.agl)
        det_latency.append(time.time() - t)
        total_dets += len(dets)
        agg.add(dets, pose, a.agl)
    plants = agg.result()
    trees = sum(1 for p in plants if p["kind"] == "TREE")
    multi = sum(1 for p in plants if p["hits"] > 1)
    print(f"\nstage 1 — recognition: {len(frames)} frames, {total_dets} detections"
          f" -> {len(plants)} plants ({trees} trees), {multi} seen in >1 frame")
    if det_latency:
        print(f"  detector latency: avg {sum(det_latency)/len(det_latency)*1000:.1f} ms"
              f", worst {max(det_latency)*1000:.1f} ms/frame")

    # ---------------- stage 2: per-plant health evaluation
    findings = []
    clf_latency = []
    for i, p in enumerate(plants):
        burst = [f"{p['label']}_shot{s}.jpg" for s in range(a.inspect_shots)]
        if a.source == "dir" and frames:
            burst = [frames[min(i, len(frames) - 1)][0]] * a.inspect_shots
        t = time.time()
        fs = classifier.classify(burst, p["kind"])
        clf_latency.append(time.time() - t)
        for f in fs:
            findings.append({"label": p["label"], "kind": p["kind"],
                             "lat": p["lat"], "lon": p["lon"],
                             "diseaseId": f.disease_id, "severity": f.severity,
                             "confidence": f.confidence})
    affected = len({f["label"] for f in findings})
    print(f"\nstage 2 — health evaluation: {len(plants)} plants classified,"
          f" {affected} with findings ({len(findings)} findings)")
    if clf_latency:
        print(f"  classifier latency: avg {sum(clf_latency)/len(clf_latency)*1000:.1f} ms/plant")

    # ---------------- mini report
    if findings:
        print(f"\n  {'plant':7s} {'kind':5s} {'disease':22s} {'sev':6s} conf   location")
        for f in sorted(findings, key=lambda x: ("LOW MEDIUM HIGH".split().index(x["severity"]), x["label"]))[::-1][:15]:
            print(f"  {f['label']:7s} {f['kind']:5s} {f['diseaseId']:22s} {f['severity']:6s}"
                  f" {f['confidence']:.2f}   {f['lat']:.5f},{f['lon']:.5f}")
        if len(findings) > 15:
            print(f"  ... and {len(findings) - 15} more")
    else:
        print("  no findings — all plants healthy")

    out = {"generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
           "source": a.source, "frames": len(frames), "detections": total_dets,
           "plants": plants, "findings": findings}
    out_path = f"/tmp/vision_bench_{int(t0)}.json"
    Path(out_path).write_text(json.dumps(out, indent=2))
    print(f"\nJSON -> {out_path}")

    ok = len(plants) > 0 and len(clf_latency) == len(plants)
    print("RESULT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


def run_animals(a) -> None:
    """Wildlife chain: RGB animal detector + thermal hotspots -> fusion ->
    space+time dedup -> tags with time + location. Mirrors the agent's
    WILDLIFE_SURVEY coverage-pass path."""
    from skyscan_drone.vision.animal_detector import AnimalDetector, SightingTagger
    from skyscan_drone.sensors.thermal import ThermalCamera
    from skyscan_drone.sensors.camera_modules import resolve_fov

    hfov, vfov, module = resolve_fov(a.module, a.hfov, a.vfov)
    det = AnimalDetector.create({"backend": a.detector} if a.detector else {})
    thermal = ThermalCamera({"enabled": True})
    tagger = SightingTagger({"hfov_deg": hfov, "vfov_deg": vfov,
                             "merge_dist_m": a.merge_dist if a.merge_dist != 1.5 else 12.0})
    live = []
    tagger.on_new_sighting = lambda s: live.append(s.label)

    print(f"wildlife bench — detector={det.__class__.__name__}, module {module}, "
          f"{hfov:.1f}x{vfov:.1f} deg, {a.agl:.0f} m AGL")
    a.hfov, a.vfov = hfov, vfov
    frames = [(f"mock_{i}.jpg", p) for i, p in enumerate(lawnmower_poses(a, a.frames))]
    if a.source == "dir":
        if not a.dir or not Path(a.dir).is_dir():
            print("ERROR: --source dir needs --dir <folder>")
            sys.exit(1)
        imgs = sorted(str(p) for p in Path(a.dir).iterdir()
                      if p.suffix.lower() in (".jpg", ".jpeg", ".png"))
        frames = list(zip(imgs, lawnmower_poses(a, len(imgs))))

    t = time.time()
    lat = []
    for path, pose in frames:
        t += 3.0
        t0 = time.time()
        dets = det.detect(path, pose, a.agl)
        tf = thermal.capture(pose)
        tagger.add(dets, tf.hotspots if tf else [], pose, a.agl, t=t)
        lat.append(time.time() - t0)
    tags = tagger.result()
    fused = sum(1 for s in tags if s["source"] == "FUSED")
    thermal_only = sum(1 for s in tags if s["source"] == "THERMAL")
    print(f"\n{len(frames)} frames -> {len(tags)} animals tagged "
          f"({fused} fused, {thermal_only} thermal-only), live-streamed={len(live)}")
    if lat:
        print(f"  per-frame chain latency: avg {sum(lat)/len(lat)*1000:.1f} ms, "
              f"worst {max(lat)*1000:.1f} ms")
    hdr = f"  {'tag':6s} {'species':24s} {'time of finding':19s} {'source':7s} {'degC':6s} location"
    print("\n" + hdr)
    for s in tags[:12]:
        temp = ("%.1f" % s["bodyTempC"]) if s["bodyTempC"] else "-"
        print(f"  {s['label']:6s} {s['species']:24s} {s['sightedAt'][:19]} {s['source']:7s} "
              f"{temp:6s} {s['lat']:.5f},{s['lon']:.5f}")
    if len(tags) > 12:
        print(f"  ... and {len(tags) - 12} more")
    out_path = f"/tmp/wildlife_bench_{int(time.time())}.json"
    Path(out_path).write_text(json.dumps({"tags": tags}, indent=2))
    print(f"\nJSON -> {out_path}")
    ok = len(tags) > 0 and len(live) == len(tags)
    print("RESULT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
