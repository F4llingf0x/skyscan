import proj4 from 'proj4';

/**
 * Coordinate bridge between the point cloud's projected CRS (e.g. EPSG:32634,
 * UTM zone 34N) and WGS84 lat/lon — this is what keeps the 3D editor and the
 * Leaflet map referring to the same ground.
 */
export function utmDefFromEpsg(crs: string): string {
  const m = /EPSG:(\d+)/i.exec(crs);
  if (!m) throw new Error(`Unsupported CRS: ${crs}`);
  const code = parseInt(m[1], 10);
  if (code >= 32601 && code <= 32660)
    return `+proj=utm +zone=${code - 32600} +datum=WGS84 +units=m +no_defs`;
  if (code >= 32701 && code <= 32760)
    return `+proj=utm +zone=${code - 32700} +south +datum=WGS84 +units=m +no_defs`;
  throw new Error(`Only UTM WGS84 codes are supported, got ${crs}`);
}

/** [easting, northing] -> [lon, lat] */
export function utmToLonLat(crs: string, e: number, n: number): [number, number] {
  return proj4(utmDefFromEpsg(crs), 'WGS84', [e, n]) as [number, number];
}

/** [lon, lat] -> [easting, northing] */
export function lonLatToUtm(crs: string, lon: number, lat: number): [number, number] {
  return proj4('WGS84', utmDefFromEpsg(crs), [lon, lat]) as [number, number];
}

/** GeoJSON Polygon/LineString string -> UTM vertex list [[e,n],...] (first ring for polygons). */
export function geoJsonToUtmRing(crs: string, geojson: string): Array<[number, number]> {
  const g = JSON.parse(geojson);
  const coords: Array<[number, number]> = g.type === 'Polygon' ? g.coordinates[0] : g.coordinates;
  return coords.map(([lon, lat]: [number, number]) => lonLatToUtm(crs, lon, lat));
}

/** Ring of UTM vertices -> GeoJSON Polygon string (closed ring, [lon,lat]). */
export function utmRingToGeoJson(crs: string, ring: Array<[number, number]>): string {
  const coords = ring.map(([e, n]) => utmToLonLat(crs, e, n));
  coords.push(coords[0]);   // close the ring
  return JSON.stringify({ type: 'Polygon', coordinates: [coords] });
}
