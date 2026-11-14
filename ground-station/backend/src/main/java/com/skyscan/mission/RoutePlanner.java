package com.skyscan.mission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Coverage route planner for a survey pass ("the optimal scanning route").
 *
 * Boustrophedon (lawnmower) planner over the WGS84 polygon:
 *   1. project to local metres around the centroid (equirectangular — fine at site scale),
 *   2. pick the sweep orientation that minimizes the number of passes
 *      (smallest polygon extent perpendicular to the sweep, tested over all edge
 *      orientations — optimal for convex zones, a solid heuristic for concave ones),
 *   3. lay parallel lines at the configured line spacing, clip each against the
 *      polygon (even–odd rule, so concave zones yield multiple segments),
 *   4. serpentine-order the segments to minimize transit.
 *
 * Output: GeoJSON LineString (WGS84) + stats. The route is stored on the ScanArea,
 * shown on the map and in the cloud editor, and handed to the drone connector.
 */
@Service
public class RoutePlanner {

    public record PlannedRoute(String routeGeoJson, double lengthM, int lineCount, double estDurationS) {}

    private static final ObjectMapper M = new ObjectMapper();

    /** LANDSCAPE: boustrophedon coverage at a constant altitude (3rd route coordinate). */
    public PlannedRoute plan(String polygonGeoJson, double lineSpacingM, double speedMps, double altitudeM) {
        try {
            List<double[]> ring = parseRing(polygonGeoJson);
            if (ring.size() < 3) throw new IllegalArgumentException("Polygon needs at least 3 vertices");

            // ---- to local metres
            double lon0 = 0, lat0 = 0;
            for (double[] p : ring) { lon0 += p[0]; lat0 += p[1]; }
            lon0 /= ring.size(); lat0 /= ring.size();
            double mPerLon = 111320.0 * Math.cos(Math.toRadians(lat0)), mPerLat = 110540.0;
            List<double[]> local = new ArrayList<>();
            for (double[] p : ring) local.add(new double[]{(p[0] - lon0) * mPerLon, (p[1] - lat0) * mPerLat});

            // ---- best sweep orientation: minimize extent perpendicular to sweep
            double bestAngle = 0, bestSpan = Double.MAX_VALUE;
            for (int i = 0; i < local.size(); i++) {
                double[] a = local.get(i), b = local.get((i + 1) % local.size());
                double angle = Math.atan2(b[1] - a[1], b[0] - a[0]);
                double span = rotatedYSpan(local, -angle);
                if (span < bestSpan) { bestSpan = span; bestAngle = angle; }
            }
            List<double[]> rot = rotate(local, -bestAngle);
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] p : rot) { minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]); }

            // ---- scan lines, clipped to the polygon, serpentine order
            List<double[]> path = new ArrayList<>();   // rotated frame
            int lineCount = 0;
            boolean leftToRight = true;
            for (double y = minY + lineSpacingM / 2; y <= maxY; y += lineSpacingM) {
                List<double[]> segs = clipLine(rot, y);   // pairs of x: [x1,x2] inside polygon
                if (segs.isEmpty()) continue;
                lineCount++;
                if (!leftToRight) {
                    List<double[]> rev = new ArrayList<>();
                    for (int i = segs.size() - 1; i >= 0; i--) rev.add(new double[]{segs.get(i)[1], segs.get(i)[0]});
                    segs = rev;
                }
                for (double[] seg : segs) {
                    path.add(new double[]{seg[0], y});
                    path.add(new double[]{seg[1], y});
                }
                leftToRight = !leftToRight;
            }
            if (path.size() < 2) throw new IllegalArgumentException("Zone too small for the given line spacing");

            // ---- rotate back, to WGS84, measure
            List<double[]> world = rotate(path, bestAngle);
            ArrayNode coords = M.createArrayNode();
            double length = 0;
            for (int i = 0; i < world.size(); i++) {
                double[] p = world.get(i);
                if (i > 0) length += Math.hypot(p[0] - world.get(i - 1)[0], p[1] - world.get(i - 1)[1]);
                coords.addArray().add(p[0] / mPerLon + lon0).add(p[1] / mPerLat + lat0).add(altitudeM);
            }
            ObjectNode geo = M.createObjectNode();
            geo.put("type", "LineString");
            geo.set("coordinates", coords);
            return new PlannedRoute(geo.toString(), length, lineCount, speedMps > 0 ? length / speedMps : 0);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Route planning failed: " + e.getMessage(), e);
        }
    }

    /**
     * STRUCTURE: full scan of a building/statue with the front-facing 360° vertical
     * payload. The route is a stack of orbit loops around the target footprint:
     *   1. offset each footprint vertex outward from the centroid by the standoff
     *      distance (facade clearance for the front-facing scanner),
     *   2. fly the loop at each altitude level, levels spaced levelSpacingM apart
     *      from levelSpacingM/2 up to topHeightM (the vertical-plane 360° sweep
     *      covers the facade band around each level),
     *   3. climb to the next level at the loop's start corner — a stacked spiral.
     * Route coordinates are 3D: [lon, lat, relative altitude m].
     */
    public PlannedRoute planStructure(String polygonGeoJson, double levelSpacingM, double speedMps,
                                      double standoffM, double topHeightM) {
        try {
            List<double[]> ring = parseRing(polygonGeoJson);
            if (ring.size() < 3) throw new IllegalArgumentException("Footprint needs at least 3 vertices");
            if (levelSpacingM <= 0) throw new IllegalArgumentException("Level spacing must be positive");

            double lon0 = 0, lat0 = 0;
            for (double[] p : ring) { lon0 += p[0]; lat0 += p[1]; }
            lon0 /= ring.size(); lat0 /= ring.size();
            double mPerLon = 111320.0 * Math.cos(Math.toRadians(lat0)), mPerLat = 110540.0;

            // outward-offset loop (approximate buffer: radial from centroid — good for compact footprints)
            List<double[]> loop = new ArrayList<>();
            for (double[] p : ring) {
                double x = (p[0] - lon0) * mPerLon, y = (p[1] - lat0) * mPerLat;
                double d = Math.hypot(x, y);
                double k = d < 1e-6 ? 0 : (d + Math.max(0, standoffM)) / d;
                loop.add(new double[]{x * k, y * k});
            }

            double perimeter = 0;
            for (int i = 0; i < loop.size(); i++) {
                double[] a = loop.get(i), b = loop.get((i + 1) % loop.size());
                perimeter += Math.hypot(b[0] - a[0], b[1] - a[1]);
            }

            ArrayNode coords = M.createArrayNode();
            int levels = 0;
            double length = 0, prevZ = -1;
            for (double z = levelSpacingM / 2; z <= Math.max(levelSpacingM / 2, topHeightM); z += levelSpacingM) {
                levels++;
                for (int i = 0; i <= loop.size(); i++) {         // closed loop: repeat first vertex
                    double[] p = loop.get(i % loop.size());
                    coords.addArray().add(p[0] / mPerLon + lon0).add(p[1] / mPerLat + lat0).add(z);
                }
                length += perimeter + (prevZ >= 0 ? z - prevZ : z);
                prevZ = z;
            }

            ObjectNode geo = M.createObjectNode();
            geo.put("type", "LineString");
            geo.set("coordinates", coords);
            return new PlannedRoute(geo.toString(), length, levels, speedMps > 0 ? length / speedMps : 0);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Structure route planning failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ geometry helpers

    private static List<double[]> parseRing(String polygonGeoJson) throws Exception {
        JsonNode node = M.readTree(polygonGeoJson);
        JsonNode ring = node.get("coordinates").get(0);
        List<double[]> out = new ArrayList<>();
        for (JsonNode c : ring) out.add(new double[]{c.get(0).asDouble(), c.get(1).asDouble()});
        // drop closing duplicate if present
        if (out.size() > 1) {
            double[] f = out.get(0), l = out.get(out.size() - 1);
            if (f[0] == l[0] && f[1] == l[1]) out.remove(out.size() - 1);
        }
        return out;
    }

    private static List<double[]> rotate(List<double[]> pts, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        List<double[]> out = new ArrayList<>(pts.size());
        for (double[] p : pts) out.add(new double[]{p[0] * c - p[1] * s, p[0] * s + p[1] * c});
        return out;
    }

    private static double rotatedYSpan(List<double[]> pts, double angle) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, c = Math.cos(angle), s = Math.sin(angle);
        for (double[] p : pts) {
            double y = p[0] * s + p[1] * c;
            min = Math.min(min, y); max = Math.max(max, y);
        }
        return max - min;
    }

    /** Horizontal line y=const vs polygon: even–odd intersections -> inside segments [x1,x2]. */
    private static List<double[]> clipLine(List<double[]> poly, double y) {
        List<Double> xs = new ArrayList<>();
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            double[] a = poly.get(i), b = poly.get((i + 1) % n);
            if ((a[1] <= y && b[1] > y) || (b[1] <= y && a[1] > y)) {
                double t = (y - a[1]) / (b[1] - a[1]);
                xs.add(a[0] + t * (b[0] - a[0]));
            }
        }
        xs.sort(Double::compare);
        List<double[]> segs = new ArrayList<>();
        for (int i = 0; i + 1 < xs.size(); i += 2) segs.add(new double[]{xs.get(i), xs.get(i + 1)});
        return segs;
    }
}
