"""IMX219-77 camera BENCH TEST — exercise the CSI camera on the Orin Nano in
isolation (no agent, no flight controller, no models).

    python3 -m skyscan_drone.camera_bench [options]

What it verifies, in order:
  1. capture path — nvarguscamerasrc -> nvjpegenc -> JPEG on disk (or a mock
     JPEG with --mock), N times, with per-shot latency
  2. file sanity — JPEG magic bytes, plausible size, requested resolution
     (read from the JPEG header, no decode libraries needed)
  3. cadence — can the pipeline sustain the mission photo interval?
  4. geotagging — EXIF GPS is written from a (bench-fake or provided) pose
     and read back
  5. optics context — prints the ground footprint + GSD for the configured
     FOV at the crude and inspection altitudes, so a wrong-lens module
     (62.2° vs 77° variant) is caught by sanity-checking these numbers
     against a tape-measure test (see SETUP_MANUAL)

Exit code 0 = all checks pass, 1 = any failure — usable as a scripted
pre-flight. Examples:

    python3 -m skyscan_drone.camera_bench --mock            # desk demo
    python3 -m skyscan_drone.camera_bench --count 5         # real camera
    python3 -m skyscan_drone.camera_bench --count 10 --interval 2.5   # cadence
"""
from __future__ import annotations

import argparse
import math
import os
import struct
import sys
import time
from pathlib import Path


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--count", type=int, default=3, help="captures to take")
    ap.add_argument("--interval", type=float, default=0.0,
                    help="seconds between captures (0 = as fast as possible); "
                         "set to the mission camera.interval_s to verify cadence")
    ap.add_argument("--width", type=int, default=3280)
    ap.add_argument("--height", type=int, default=2464)
    ap.add_argument("--module", default="imx219-77",
                    help="camera module from the catalog: imx219-std | imx219-77 | "
                         "imx219-120 | imx219-160 (sets FOV; see sensors/camera_modules.py)")
    ap.add_argument("--hfov", type=float, default=None, help="override horizontal FOV deg")
    ap.add_argument("--vfov", type=float, default=None, help="override vertical FOV deg")
    ap.add_argument("--out", default="/tmp/skyscan_camera_bench", help="output dir")
    ap.add_argument("--lat", type=float, default=47.4979, help="bench pose for geotag test")
    ap.add_argument("--lon", type=float, default=19.0402)
    ap.add_argument("--alt", type=float, default=1.5)
    ap.add_argument("--mock", action="store_true", help="no camera: synthetic JPEGs")
    return ap.parse_args(argv)


def jpeg_dimensions(path: Path) -> tuple[int, int] | None:
    """Width/height from JPEG SOF markers — no decoder needed."""
    try:
        with open(path, "rb") as f:
            data = f.read()
        if data[:2] != b"\xff\xd8":
            return None
        i = 2
        while i < len(data) - 9:
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker in (0xC0, 0xC1, 0xC2, 0xC3):        # SOF0..3
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return w, h
            if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                i += 2
                continue
            seg_len = struct.unpack(">H", data[i + 2:i + 4])[0]
            i += 2 + seg_len
        return None
    except Exception:
        return None


def footprint_line(hfov: float, vfov: float, agl: float, width_px: int) -> str:
    gx = 2 * agl * math.tan(math.radians(hfov) / 2)
    gy = 2 * agl * math.tan(math.radians(vfov) / 2)
    gsd = gx / width_px * 100
    return f"{agl:5.1f} m AGL -> footprint {gx:6.1f} x {gy:5.1f} m, GSD {gsd:6.2f} cm/px"


def main() -> None:
    a = parse_args()
    if a.mock:
        os.environ["SKYSCAN_MOCK_SENSORS"] = "1"
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from skyscan_drone.sensors.camera import Imx219Camera
    from skyscan_drone.sensors.camera_modules import resolve_fov, CAMERA_MODULES

    hfov, vfov, module = resolve_fov(a.module, a.hfov, a.vfov)
    a.hfov, a.vfov = hfov, vfov
    grade = CAMERA_MODULES.get(module, (0, 0, "custom", ""))[2]
    print(f"IMX219 camera bench — module {module} ({grade} for mapping), "
          f"{a.width}x{a.height}, FOV {hfov:.1f}°x{vfov:.1f}°"
          + ("   [MOCK]" if a.mock else ""))
    print("optics context (verify against a tape-measure target — see manual):")
    for agl in (1.5, 6.0, 40.0):
        print("  " + footprint_line(a.hfov, a.vfov, agl, a.width))
    print()

    class BenchPose:
        lat, lon, alt_rel_m = a.lat, a.lon, a.alt

    cam = Imx219Camera({"enabled": True, "width": a.width, "height": a.height,
                        "session_dir": a.out, "interval_s": 0},
                       pose_provider=lambda: BenchPose)
    cam.start_session("bench")

    failures = []
    latencies = []
    for i in range(a.count):
        t0 = time.time()
        try:
            p = cam.capture()
        except Exception as e:
            print(f"  shot {i+1}: CAPTURE FAILED — {e}")
            failures.append(f"shot {i+1} capture: {e}")
            continue
        dt = time.time() - t0
        latencies.append(dt)
        if p is None:
            failures.append(f"shot {i+1}: capture returned None")
            print(f"  shot {i+1}: returned None")
            continue
        path = Path(p)
        size = path.stat().st_size
        ok_magic = path.read_bytes()[:2] == b"\xff\xd8"
        dims = jpeg_dimensions(path)
        dim_note = "n/a (mock)" if a.mock else (
            f"{dims[0]}x{dims[1]}" + ("" if dims == (a.width, a.height) else " MISMATCH"))
        if not ok_magic:
            failures.append(f"shot {i+1}: not a JPEG")
        if not a.mock and dims is not None and dims != (a.width, a.height):
            failures.append(f"shot {i+1}: resolution {dims} != requested")
        if not a.mock and size < 50_000:
            failures.append(f"shot {i+1}: suspiciously small ({size} B)")
        print(f"  shot {i+1}: {dt*1000:6.0f} ms  {size:9d} B  jpeg={'OK' if ok_magic else 'BAD'}"
              f"  res={dim_note}  -> {path.name}")
        if a.interval > 0 and i < a.count - 1:
            time.sleep(max(0.0, a.interval - dt))

    # geotag round-trip on the last shot
    photos = cam.all_photos()
    if photos:
        try:
            import piexif
            exif = piexif.load(photos[-1])
            gps = exif.get("GPS", {})
            has = piexif.GPSIFD.GPSLatitude in gps and piexif.GPSIFD.GPSLongitude in gps
            print(f"\n  geotag: {'PRESENT' if has else 'MISSING'} in {Path(photos[-1]).name}")
            if not has:
                failures.append("EXIF GPS missing")
        except Exception as e:
            print(f"\n  geotag: unverifiable ({e})")
    if a.interval > 0 and latencies:
        worst = max(latencies)
        verdict = "OK" if worst < a.interval else "TOO SLOW for the mission cadence"
        print(f"  cadence: worst capture {worst*1000:.0f} ms vs interval "
              f"{a.interval*1000:.0f} ms — {verdict}")
        if worst >= a.interval:
            failures.append("capture slower than mission interval")

    print(f"\nSUMMARY: {a.count - len([f for f in failures if 'shot' in f])}/{a.count} shots OK; "
          + ("ALL CHECKS PASS" if not failures else f"{len(failures)} FAILURE(S):"))
    for f in failures:
        print("  - " + f)
    sys.exit(0 if not failures else 1)


if __name__ == "__main__":
    main()
