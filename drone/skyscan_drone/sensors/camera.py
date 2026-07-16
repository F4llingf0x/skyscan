"""IMX219 (CSI) camera on the Jetson: two jobs.

1. Support role: photos are captured alongside the lidar for texture/context.
2. Customer deliverable: geotagged stills uploaded with the scan and shown in
   the SkyScan portal's photo gallery.

Capture uses nvarguscamerasrc (hardware ISP). Every photo gets EXIF GPS tags
from the flight controller pose at capture time.
"""
from __future__ import annotations

import logging
import os
import subprocess
import time
from pathlib import Path

log = logging.getLogger("camera")
MOCK = os.environ.get("SKYSCAN_MOCK_SENSORS") == "1"


class Imx219Camera:
    def __init__(self, cfg: dict, pose_provider):
        self.cfg = cfg
        self.pose_provider = pose_provider
        self.session_dir: Path | None = None
        self._n = 0

    def start_session(self, mission_id: str) -> None:
        self.session_dir = Path(self.cfg["session_dir"]) / mission_id
        self.session_dir.mkdir(parents=True, exist_ok=True)
        self._n = 0

    def capture(self) -> str | None:
        if not self.cfg.get("enabled") or self.session_dir is None:
            return None
        path = self.session_dir / f"photo_{self._n:04d}.jpg"
        self._n += 1
        pose = self.pose_provider()
        if MOCK:
            path.write_bytes(b"\xff\xd8\xff\xdb" + b"\x00" * 64 + b"\xff\xd9")  # minimal jpeg
        else:
            pipeline = (
                f"nvarguscamerasrc num-buffers=1 ! "
                f"video/x-raw(memory:NVMM),width={self.cfg['width']},height={self.cfg['height']} ! "
                f"nvjpegenc ! filesink location={path}")
            subprocess.run(["gst-launch-1.0", "-e"] + pipeline.split(" ! "),
                           check=True, capture_output=True, timeout=15)
        self._geotag(path, pose)
        return str(path)

    def _geotag(self, path: Path, pose) -> None:
        try:
            import piexif
            def dms(v):
                v = abs(v); d = int(v); m = int((v - d) * 60)
                s = int(((v - d) * 60 - m) * 60 * 100)
                return ((d, 1), (m, 1), (s, 100))
            gps = {piexif.GPSIFD.GPSLatitude: dms(pose.lat),
                   piexif.GPSIFD.GPSLatitudeRef: b"N" if pose.lat >= 0 else b"S",
                   piexif.GPSIFD.GPSLongitude: dms(pose.lon),
                   piexif.GPSIFD.GPSLongitudeRef: b"E" if pose.lon >= 0 else b"W",
                   piexif.GPSIFD.GPSAltitude: (int(pose.alt_rel_m * 100), 100)}
            piexif.insert(piexif.dump({"GPS": gps}), str(path))
        except Exception as e:
            log.debug("geotag skipped: %s", e)

    def all_photos(self) -> list[str]:
        if self.session_dir is None:
            return []
        return sorted(str(p) for p in self.session_dir.glob("photo_*.jpg"))
