"""LAS 1.2 / point-format-0 writer (same layout SkyScan's engine and viewers read)."""
from __future__ import annotations

import struct


def write_las(path: str, points: list[tuple[float, float, float, int]], scale: float = 0.001) -> None:
    if not points:
        raise ValueError("no points to write")
    xs = [p[0] for p in points]; ys = [p[1] for p in points]; zs = [p[2] for p in points]
    ox, oy, oz = min(xs), min(ys), min(zs)
    n, header_size = len(points), 227
    with open(path, "wb") as f:
        h = b"LASF"
        h += struct.pack("<HHI", 0, 0, 0)
        h += struct.pack("<HH", 0, 0) + b"\x00" * 8
        h += struct.pack("<BB", 1, 2)
        h += b"SkyScan UAV RPLidarC1".ljust(32, b"\x00")
        h += b"skyscan-drone-agent".ljust(32, b"\x00")
        h += struct.pack("<HH", 186, 2026)
        h += struct.pack("<HII", header_size, header_size, 0)
        h += struct.pack("<B", 0)
        h += struct.pack("<H", 20)
        h += struct.pack("<I", n)
        h += struct.pack("<5I", n, 0, 0, 0, 0)
        h += struct.pack("<3d", scale, scale, scale)
        h += struct.pack("<3d", ox, oy, oz)
        h += struct.pack("<6d", max(xs), min(xs), max(ys), min(ys), max(zs), min(zs))
        assert len(h) == header_size
        f.write(h)
        for x, y, z, inten in points:
            f.write(struct.pack("<3iHBBbBH",
                                round((x - ox) / scale), round((y - oy) / scale),
                                round((z - oz) / scale), max(0, min(65535, inten)),
                                0b00001001, 1, 0, 0, 0))
