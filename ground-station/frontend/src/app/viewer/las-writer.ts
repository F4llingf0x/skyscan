import { LasCloud } from './las-loader';

/**
 * Serialize an (edited) LasCloud back to a LAS 1.2 / point-format-0 file —
 * mirror of the loader. Centered float positions are shifted back by the
 * cloud's original center, so the edited file stays in the same projected CRS.
 */
export function writeLas(cloud: LasCloud): Blob {
  const { positions, intensities, count, center, maxIntensity } = cloud;
  const scale = 0.001;
  const [cx, cy, cz] = center;

  let minX = Infinity, minY = Infinity, minZ = Infinity;
  let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;
  for (let i = 0; i < count; i++) {
    const x = positions[i * 3] + cx, y = positions[i * 3 + 1] + cy, z = positions[i * 3 + 2] + cz;
    if (x < minX) minX = x; if (x > maxX) maxX = x;
    if (y < minY) minY = y; if (y > maxY) maxY = y;
    if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
  }

  const headerSize = 227, recLen = 20;
  const buf = new ArrayBuffer(headerSize + count * recLen);
  const dv = new DataView(buf);
  const ascii = (off: number, str: string, len: number) => {
    for (let i = 0; i < len; i++) dv.setUint8(off + i, i < str.length ? str.charCodeAt(i) : 0);
  };

  ascii(0, 'LASF', 4);
  // 4..23: source id, encoding, guids -> zeros
  dv.setUint8(24, 1); dv.setUint8(25, 2);              // LAS 1.2
  ascii(26, 'SkyScan Review Editor', 32);
  ascii(58, 'skyscan-ui las-writer', 32);
  dv.setUint16(90, 186, true); dv.setUint16(92, 2026, true);
  dv.setUint16(94, headerSize, true);
  dv.setUint32(96, headerSize, true);                  // offset to point data
  dv.setUint32(100, 0, true);                          // VLR count
  dv.setUint8(104, 0);                                 // point format 0
  dv.setUint16(105, recLen, true);
  dv.setUint32(107, count, true);
  dv.setUint32(111, count, true);                      // returns[0]
  dv.setFloat64(131, scale, true); dv.setFloat64(139, scale, true); dv.setFloat64(147, scale, true);
  dv.setFloat64(155, minX, true); dv.setFloat64(163, minY, true); dv.setFloat64(171, minZ, true);
  dv.setFloat64(179, maxX, true); dv.setFloat64(187, minX, true);
  dv.setFloat64(195, maxY, true); dv.setFloat64(203, minY, true);
  dv.setFloat64(211, maxZ, true); dv.setFloat64(219, minZ, true);

  for (let i = 0; i < count; i++) {
    const base = headerSize + i * recLen;
    dv.setInt32(base,      Math.round((positions[i * 3] + cx - minX) / scale), true);
    dv.setInt32(base + 4,  Math.round((positions[i * 3 + 1] + cy - minY) / scale), true);
    dv.setInt32(base + 8,  Math.round((positions[i * 3 + 2] + cz - minZ) / scale), true);
    dv.setUint16(base + 12, Math.round(intensities[i] * maxIntensity), true);
    dv.setUint8(base + 14, 0x09);                      // return 1 of 1
    dv.setUint8(base + 15, 1);                         // unclassified
    // 16..19: scan angle, user data, point source id -> zeros
  }
  return new Blob([buf], { type: 'application/octet-stream' });
}
