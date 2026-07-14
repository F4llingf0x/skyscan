/**
 * Minimal LAS 1.x loader for the built-in viewer: streams the processed (uncompressed)
 * LAS from a presigned URL and returns typed arrays ready for three.js.
 * Supports point formats 0-3 (XYZ + intensity are all we render).
 */
export interface LasCloud {
  positions: Float32Array;     // centered coordinates for float precision
  intensities: Float32Array;   // normalized 0..1
  count: number;
  center: [number, number, number];
  size: [number, number, number];
  minZ: number;
  maxZ: number;
  /** Max raw intensity before normalization (needed to re-serialize edited clouds). */
  maxIntensity: number;
}

export async function loadLas(url: string, onProgress?: (pct: number) => void): Promise<LasCloud> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`LAS fetch failed: HTTP ${res.status}`);
  const buf = await res.arrayBuffer();
  onProgress?.(60);

  const dv = new DataView(buf);
  const sig = String.fromCharCode(dv.getUint8(0), dv.getUint8(1), dv.getUint8(2), dv.getUint8(3));
  if (sig !== 'LASF') throw new Error('Not a LAS file');

  const pointOffset = dv.getUint32(96, true);
  const format = dv.getUint8(104) & 0x3f;
  const recLen = dv.getUint16(105, true);
  const count = dv.getUint32(107, true);
  if (format > 3) throw new Error(`Unsupported LAS point format ${format}`);

  const sx = dv.getFloat64(131, true), sy = dv.getFloat64(139, true), sz = dv.getFloat64(147, true);
  const ox = dv.getFloat64(155, true), oy = dv.getFloat64(163, true), oz = dv.getFloat64(171, true);
  const maxX = dv.getFloat64(179, true), minX = dv.getFloat64(187, true);
  const maxY = dv.getFloat64(195, true), minY = dv.getFloat64(203, true);
  const maxZ = dv.getFloat64(211, true), minZ = dv.getFloat64(219, true);

  const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, cz = (minZ + maxZ) / 2;
  const positions = new Float32Array(count * 3);
  const intensities = new Float32Array(count);

  let maxI = 1;
  for (let i = 0; i < count; i++) {
    const base = pointOffset + i * recLen;
    positions[i * 3]     = dv.getInt32(base, true) * sx + ox - cx;
    positions[i * 3 + 1] = dv.getInt32(base + 4, true) * sy + oy - cy;
    positions[i * 3 + 2] = dv.getInt32(base + 8, true) * sz + oz - cz;
    const inten = dv.getUint16(base + 12, true);
    intensities[i] = inten;
    if (inten > maxI) maxI = inten;
  }
  for (let i = 0; i < count; i++) intensities[i] /= maxI;
  onProgress?.(100);

  return {
    positions, intensities, count,
    center: [cx, cy, cz],
    size: [maxX - minX, maxY - minY, maxZ - minZ],
    minZ, maxZ,
    maxIntensity: maxI,
  };
}
