package com.skyscan.agri;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyscan.domain.*;
import com.skyscan.repo.*;
import com.skyscan.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * The agronomy brain of a CROP_HEALTH mission.
 *
 *  detectPlants()  — after the crude pass: derive every crop plant + tree in
 *                    the zone from the crude cloud/imagery and persist them as
 *                    Plant rows (map markers for the operator to review).
 *  buildInspectionRoute() — a low/slow waypoint route visiting each detected
 *                    plant, handed to the drone as the detail pass. The route
 *                    executor already cuts this into battery legs and resumes
 *                    across swaps, so many plants over many packs "just work".
 *  analyzeInspection() — after the detail pass: attach disease findings to the
 *                    visited plants and render the final report (HTML + JSON).
 *
 * Detection and disease-scoring are STUBS with deterministic, plausible output
 * (seeded by mission id) so the whole flow is demoable end-to-end without a CV
 * model. The seams where a real detector / classifier plugs in are marked.
 */
@Service
public class CropAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CropAnalysisService.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final PlantRepo plants;
    private final PlantFindingRepo findings;
    private final ScanAreaRepo scanAreas;
    private final StorageService storage;
    private final DiseaseCatalog catalog;
    private final ReportNarrator narrator;

    public CropAnalysisService(PlantRepo plants, PlantFindingRepo findings, ScanAreaRepo scanAreas,
                               StorageService storage, DiseaseCatalog catalog, ReportNarrator narrator) {
        this.plants = plants; this.findings = findings; this.scanAreas = scanAreas;
        this.storage = storage; this.catalog = catalog; this.narrator = narrator;
    }

    // ---------------------------------------------------------------- stage 1: detection

    /**
     * STUB detector: scatter a plausible set of plants across the crude
     * polygon. Real replacement: run a CV/point-cloud detector on the crude
     * acquisition (canopy segmentation → stem positions, tree vs row-crop
     * classification, NDVI-style vigor from multispectral if available).
     */
    @Transactional
    public int detectPlants(Mission m) {
        List<double[]> ring = parseRing(scanAreas.findByMissionIdAndPassType(m.getId(), PassType.CRUDE)
                .orElseThrow().getPolygonGeoJson());
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (double[] p : ring) {
            minLon = Math.min(minLon, p[0]); maxLon = Math.max(maxLon, p[0]);
            minLat = Math.min(minLat, p[1]); maxLat = Math.max(maxLat, p[1]);
        }
        Random rnd = new Random(m.getId().getMostSignificantBits() ^ m.getId().getLeastSignificantBits());
        int nCrop = 0, nTree = 0, nField = 0, made = 0;
        double latSpan = maxLat - minLat, lonSpan = maxLon - minLon;

        // 1-2 CROP_FIELD ZONES: contiguous row-crop patches (a real detector
        // coalesces dense uniform detections into these — see the drone-side
        // PlantAggregator field coalescing). Individual plants avoid the zones.
        List<double[]> fields = new ArrayList<>();   // {lat, lon, diaM}
        int wantFields = 1 + rnd.nextInt(2);
        double sideM = Math.max(latSpan * 110540.0, lonSpan * 111320.0 * Math.cos(Math.toRadians(minLat)));
        for (int k = 0; k < wantFields; k++) {
            double fl = minLat + latSpan * (0.25 + 0.5 * rnd.nextDouble());
            double fo = minLon + lonSpan * (0.25 + 0.5 * rnd.nextDouble());
            if (!inRing(ring, fo, fl)) continue;
            double dia = Math.max(25, sideM * (0.25 + rnd.nextDouble() * 0.2));
            Plant f = new Plant();
            f.setMission(m);
            f.setKind(PlantKind.CROP_FIELD);
            f.setLabel("F-" + String.format("%03d", ++nField));
            f.setLat(fl); f.setLon(fo);
            f.setHeightM(0.6 + rnd.nextDouble() * 0.6);
            f.setCanopyDiaM(Math.round(dia));
            f.setHealthIndex(clamp01(0.6 + rnd.nextGaussian() * 0.18));
            plants.save(f);
            fields.add(new double[]{fl, fo, dia});
            made++;
        }
        int rows = 6 + rnd.nextInt(4), cols = 6 + rnd.nextInt(4);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double lat = minLat + latSpan * (r + 0.5) / rows + (rnd.nextDouble() - 0.5) * latSpan / rows * 0.3;
                double lon = minLon + lonSpan * (c + 0.5) / cols + (rnd.nextDouble() - 0.5) * lonSpan / cols * 0.3;
                if (!inRing(ring, lon, lat)) continue;
                if (insideField(fields, lat, lon)) continue;   // zone covers these
                boolean tree = rnd.nextDouble() < 0.15;
                Plant p = new Plant();
                p.setMission(m);
                p.setKind(tree ? PlantKind.TREE : PlantKind.CROP);
                p.setLabel((tree ? "T-" : "C-") + String.format("%03d", tree ? ++nTree : ++nCrop));
                p.setLat(lat); p.setLon(lon);
                p.setHeightM(tree ? 3 + rnd.nextDouble() * 6 : 0.3 + rnd.nextDouble() * 1.2);
                p.setCanopyDiaM(tree ? 2 + rnd.nextDouble() * 4 : 0.3 + rnd.nextDouble() * 0.6);
                p.setHealthIndex(clamp01(0.55 + rnd.nextGaussian() * 0.2));   // vigor proxy
                plants.save(p);
                made++;
            }
        }
        log.info("crop detection (stub): mission {} -> {} entries ({} trees, {} field zones)",
                m.getId(), made, nTree, nField);
        return made;
    }

    /**
     * Ingest plants detected ON THE DRONE (YOLO + projection + dedup) — the real
     * path. FIRST pass: replaces everything. RESCAN pass (mission has an active
     * rescanPolygon): MERGES — a detection within merge_dist of an existing
     * same-kind entry updates it (position averaged, keeps label/exclusion);
     * genuinely new detections get the next free labels. Returns entries ADDED.
     */
    @Transactional
    public int ingestDetectedPlants(Mission m, com.fasterxml.jackson.databind.JsonNode plantsJson) {
        boolean merge = m.getRescanPolygonGeoJson() != null;
        if (merge) return mergeDetectedPlants(m, plantsJson);
        plants.deleteAll(plants.findByMissionIdOrderByLabelAsc(m.getId()));
        int n = 0;
        for (JsonNode p : plantsJson) {
            Plant pl = new Plant();
            pl.setMission(m);
            pl.setLabel(p.path("label").asText("C-" + String.format("%03d", ++n)));
            pl.setKind(PlantKind.valueOf(p.path("kind").asText("CROP")));
            pl.setLat(p.path("lat").asDouble());
            pl.setLon(p.path("lon").asDouble());
            pl.setHeightM(p.path("heightM").asDouble());
            pl.setCanopyDiaM(p.path("canopyDiaM").asDouble());
            // vigor proxy: detector confidence stands in until NDVI/other is available
            pl.setHealthIndex(clamp01(p.path("confidence").asDouble(0.7)));
            plants.save(pl);
            n++;
        }
        log.info("ingested {} drone-detected plants for mission {}", n, m.getId());
        return n;
    }

    private static final double MERGE_DIST_M = 1.5;

    @Transactional
    public int mergeDetectedPlants(Mission m, com.fasterxml.jackson.databind.JsonNode plantsJson) {
        List<Plant> existing = new ArrayList<>(plants.findByMissionIdOrderByLabelAsc(m.getId()));
        int nc = 0, nt = 0, nf = 0;
        for (Plant p : existing) {
            String d = p.getLabel().replaceAll("\\D", "");
            int num = d.isEmpty() ? 0 : Integer.parseInt(d);
            switch (p.getKind()) {
                case CROP -> nc = Math.max(nc, num);
                case TREE -> nt = Math.max(nt, num);
                case CROP_FIELD -> nf = Math.max(nf, num);
            }
        }
        int added = 0, merged = 0;
        for (JsonNode j : plantsJson) {
            PlantKind kind = PlantKind.valueOf(j.path("kind").asText("CROP"));
            double lat = j.path("lat").asDouble(), lon = j.path("lon").asDouble();
            double mLat = 110540.0, mLon = 111320.0 * Math.cos(Math.toRadians(lat));
            Plant hit = null;
            for (Plant p : existing) {
                if (p.getKind() != kind) continue;
                double dm = Math.hypot((p.getLat() - lat) * mLat, (p.getLon() - lon) * mLon);
                double thresh = kind == PlantKind.CROP_FIELD
                        ? Math.max(p.getCanopyDiaM(), j.path("canopyDiaM").asDouble()) / 2.0
                        : MERGE_DIST_M;
                if (dm <= thresh) { hit = p; break; }
            }
            if (hit != null) {
                hit.setLat((hit.getLat() + lat) / 2.0);      // refined fix
                hit.setLon((hit.getLon() + lon) / 2.0);
                if (j.hasNonNull("confidence"))
                    hit.setHealthIndex(clamp01(Math.max(hit.getHealthIndex(),
                            j.get("confidence").asDouble())));
                plants.save(hit);
                merged++;
                continue;
            }
            Plant p = new Plant();
            p.setMission(m);
            p.setKind(kind);
            p.setLabel(switch (kind) {
                case TREE -> "T-" + String.format("%03d", ++nt);
                case CROP_FIELD -> "F-" + String.format("%03d", ++nf);
                default -> "C-" + String.format("%03d", ++nc);
            });
            p.setLat(lat); p.setLon(lon);
            p.setHeightM(j.path("heightM").asDouble());
            p.setCanopyDiaM(j.path("canopyDiaM").asDouble());
            p.setHealthIndex(clamp01(j.path("confidence").asDouble(0.7)));
            plants.save(p);
            existing.add(p);
            added++;
        }
        log.info("detection rescan merge for {}: {} new, {} refined existing", m.getId(), added, merged);
        return added;
    }

    /** STUB supplemental detection for a rescan sub-area: a few plausible new
     *  plants INSIDE the sub-polygon, merged like the real path. */
    @Transactional
    public int detectSupplemental(Mission m, String subPolygonGeoJson) {
        List<double[]> ring = parseRing(subPolygonGeoJson);
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (double[] p : ring) {
            minLon = Math.min(minLon, p[0]); maxLon = Math.max(maxLon, p[0]);
            minLat = Math.min(minLat, p[1]); maxLat = Math.max(maxLat, p[1]);
        }
        Random rnd = new Random(m.getId().getLeastSignificantBits()
                ^ subPolygonGeoJson.hashCode());
        ObjectNode payload = M.createObjectNode();
        ArrayNode arr = payload.putArray("plants");
        int want = 2 + rnd.nextInt(4), tries = 0;
        while (arr.size() < want && tries++ < 100) {
            double lat = minLat + rnd.nextDouble() * (maxLat - minLat);
            double lon = minLon + rnd.nextDouble() * (maxLon - minLon);
            if (!inRing(ring, lon, lat)) continue;
            boolean tree = rnd.nextDouble() < 0.2;
            ObjectNode p = arr.addObject();
            p.put("kind", tree ? "TREE" : "CROP");
            p.put("lat", lat); p.put("lon", lon);
            p.put("heightM", tree ? 3 + rnd.nextDouble() * 5 : 0.4 + rnd.nextDouble());
            p.put("canopyDiaM", tree ? 2 + rnd.nextDouble() * 3 : 0.4 + rnd.nextDouble() * 0.5);
            p.put("confidence", 0.55 + rnd.nextDouble() * 0.35);
        }
        return mergeDetectedPlants(m, arr);
    }

    /**
     * Ingest disease findings classified ON THE DRONE. Each entry carries a
     * plantIndex = the inspection route order (0-based) OR a label; we map it to
     * a Plant. diseaseId keys the catalog for cures/checks in the report.
     */
    @Transactional
    public int ingestFindings(Mission m, com.fasterxml.jackson.databind.JsonNode findingsJson) {
        List<Plant> ordered = plants.findByMissionIdOrderByLabelAsc(m.getId());
        // plantIndex from the drone = ROUTE waypoint index. Rebuild the exact
        // same deterministic route plan to map indices to entities (a
        // CROP_FIELD zone owns several consecutive sample-point indices).
        List<Map.Entry<Plant, double[]>> plan;
        try { plan = routePlan(m); } catch (IllegalStateException e) { plan = List.of(); }
        Map<Plant, int[]> fieldSampleCounts = new HashMap<>();   // field -> {samples, hits placeholder}
        for (Map.Entry<Plant, double[]> e : plan)
            if (e.getKey().getKind() == PlantKind.CROP_FIELD)
                fieldSampleCounts.computeIfAbsent(e.getKey(), k -> new int[]{0})[0]++;
        // field -> diseaseId -> {count, maxSeverityRank, maxConf}
        Map<Plant, Map<String, Object[]>> zoneAgg = new HashMap<>();
        int n = 0;
        for (JsonNode f : findingsJson) {
            Plant plant = null;
            if (f.hasNonNull("label")) {
                plant = ordered.stream().filter(p -> p.getLabel().equals(f.get("label").asText()))
                        .findFirst().orElse(null);
            } else if (f.hasNonNull("plantIndex")) {
                int idx = f.get("plantIndex").asInt();
                if (idx >= 0 && idx < plan.size()) plant = plan.get(idx).getKey();
            }
            if (plant == null) continue;
            if (plant.getKind() == PlantKind.CROP_FIELD) {
                // collect per-sample results; persisted as ONE zone finding below
                Map<String, Object[]> byDisease = zoneAgg.computeIfAbsent(plant, k -> new HashMap<>());
                String did = f.path("diseaseId").asText();
                Object[] agg = byDisease.computeIfAbsent(did, k -> new Object[]{0, 0, 0.0});
                agg[0] = (int) agg[0] + 1;
                agg[1] = Math.max((int) agg[1], severityRank(f.path("severity").asText("MEDIUM")));
                agg[2] = Math.max((double) agg[2], f.path("confidence").asDouble(0.5));
                continue;
            }
            DiseaseCatalog.Disease d = catalog.get(f.path("diseaseId").asText());
            PlantFinding pf = new PlantFinding();
            pf.setPlant(plant);
            pf.setDiseaseId(f.path("diseaseId").asText());
            pf.setDiseaseName(d != null ? d.name() : f.path("diseaseId").asText());
            pf.setSeverity(f.path("severity").asText("MEDIUM"));
            pf.setConfidence(f.path("confidence").asDouble(0.5));
            pf.setNote("Classified on-board from inspection close-ups.");
            findings.save(pf);
            plant.setStatus("SCANNED");
            plants.save(plant);
            n++;
        }
        for (Map.Entry<Plant, Map<String, Object[]>> ze : zoneAgg.entrySet()) {
            Plant field = ze.getKey();
            int samples = fieldSampleCounts.getOrDefault(field, new int[]{1})[0];
            int worstHits = 0;
            for (Map.Entry<String, Object[]> de : ze.getValue().entrySet()) {
                int hits = (int) de.getValue()[0];
                worstHits = Math.max(worstHits, hits);
                DiseaseCatalog.Disease d = catalog.get(de.getKey());
                PlantFinding pf = new PlantFinding();
                pf.setPlant(field);
                pf.setDiseaseId(de.getKey());
                pf.setDiseaseName(d != null ? d.name() : de.getKey());
                pf.setSeverity(zoneSeverity(hits, samples, (int) de.getValue()[1]));
                pf.setConfidence((double) de.getValue()[2]);
                pf.setNote("Zone-level: detected at " + hits + " of " + samples + " sample points.");
                findings.save(pf);
                n++;
            }
            // zone health: fraction of clean sample points
            field.setHealthIndex(clamp01(1.0 - (double) worstHits / Math.max(1, samples)));
            field.setStatus("SCANNED");
            plants.save(field);
        }
        log.info("ingested {} drone findings for mission {}", n, m.getId());
        return n;
    }

    private static int severityRank(String s) {
        return "HIGH".equals(s) ? 2 : "MEDIUM".equals(s) ? 1 : 0;
    }

    /** Zone severity: coverage-driven, never below the worst sample severity. */
    private static String zoneSeverity(int hits, int samples, int worstRank) {
        double frac = (double) hits / Math.max(1, samples);
        int byCoverage = frac > 0.6 ? 2 : frac > 0.3 ? 1 : 0;
        int r = Math.max(byCoverage, worstRank);
        return r == 2 ? "HIGH" : r == 1 ? "MEDIUM" : "LOW";
    }

    /** True if the drone already supplied plants (skip the stub detector). */
    public boolean hasPlants(Mission m) {
        return plants.countByMissionId(m.getId()) > 0;
    }

    /** True if the drone already supplied findings (skip the stub analyzer). */
    public boolean hasFindings(Mission m) {
        return !findings.findByPlantMissionIdOrderByPlantLabelAsc(m.getId()).isEmpty();
    }

    /**
     * DETECTION RESCAN (stub): generate plants only inside the rescan
     * sub-polygon and MERGE — anything within dedup distance of an existing
     * entry is a re-detection, not a new plant; labels continue (C-013...).
     */
    @Transactional
    public int detectMorePlants(Mission m, String rescanPolygonJson) {
        List<double[]> ring = parseRing(rescanPolygonJson);
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (double[] p : ring) {
            minLon = Math.min(minLon, p[0]); maxLon = Math.max(maxLon, p[0]);
            minLat = Math.min(minLat, p[1]); maxLat = Math.max(maxLat, p[1]);
        }
        List<Plant> existing = plants.findByMissionIdOrderByLabelAsc(m.getId());
        int[] counters = labelCounters(existing);
        Random rnd = new Random(m.getId().getLeastSignificantBits() ^ existing.size());
        int added = 0, tries = 0, target = 2 + rnd.nextInt(5);
        while (added < target && tries++ < 300) {
            double lat = minLat + rnd.nextDouble() * (maxLat - minLat);
            double lon = minLon + rnd.nextDouble() * (maxLon - minLon);
            if (!inRing(ring, lon, lat)) continue;
            if (nearExisting(existing, lat, lon, 2.0)) continue;   // re-detection
            boolean tree = rnd.nextDouble() < 0.2;
            Plant p = new Plant();
            p.setMission(m);
            p.setKind(tree ? PlantKind.TREE : PlantKind.CROP);
            p.setLabel((tree ? "T-" : "C-") + String.format("%03d",
                    tree ? ++counters[1] : ++counters[0]));
            p.setLat(lat); p.setLon(lon);
            p.setHeightM(tree ? 3 + rnd.nextDouble() * 6 : 0.3 + rnd.nextDouble() * 1.2);
            p.setCanopyDiaM(tree ? 2 + rnd.nextDouble() * 4 : 0.3 + rnd.nextDouble() * 0.6);
            p.setHealthIndex(clamp01(0.55 + rnd.nextGaussian() * 0.2));
            plants.save(p);
            existing.add(p);
            added++;
        }
        log.info("detection rescan (stub): mission {} +{} plants", m.getId(), added);
        return added;
    }

    private static int[] labelCounters(List<Plant> existing) {
        int c = 0, t = 0, f = 0;
        for (Plant p : existing) {
            try {
                int v = Integer.parseInt(p.getLabel().substring(2));
                if (p.getLabel().startsWith("C-")) c = Math.max(c, v);
                else if (p.getLabel().startsWith("T-")) t = Math.max(t, v);
                else if (p.getLabel().startsWith("F-")) f = Math.max(f, v);
            } catch (Exception ignore) { }
        }
        return new int[]{c, t, f};
    }

    private static boolean nearExisting(List<Plant> existing, double lat, double lon, double distM) {
        double mLat = 110540.0;
        double mLon = 111320.0 * Math.cos(Math.toRadians(lat));
        for (Plant p : existing) {
            double d = Math.hypot((p.getLat() - lat) * mLat, (p.getLon() - lon) * mLon);
            double r = p.getKind() == PlantKind.CROP_FIELD ? p.getCanopyDiaM() / 2.0 : distM;
            if (d < r) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- stage 2: inspection route

    /**
     * The DETERMINISTIC route plan: nearest-neighbor order over the included
     * entries, with each CROP_FIELD zone expanded into K sample points
     * (K = 3..7 scaled by zone diameter, laid out on a fixed cross pattern).
     * One list entry per route waypoint, pointing at the Plant it inspects —
     * the SAME function maps drone results (findings/photos, keyed by route
     * index) back to entities, so builder and ingester can never disagree.
     */
    public List<Map.Entry<Plant, double[]>> routePlan(Mission m) {
        List<Plant> ps = new ArrayList<>(plants.findByMissionIdOrderByLabelAsc(m.getId()));
        ps.removeIf(Plant::isExcluded);
        if (ps.isEmpty()) throw new IllegalStateException("No included plants to inspect");
        List<Map.Entry<Plant, double[]>> plan = new ArrayList<>();
        for (Plant p : nearestNeighbor(ps)) {
            if (p.getKind() == PlantKind.CROP_FIELD) {
                for (double[] pt : fieldSamplePoints(p))
                    plan.add(Map.entry(p, pt));
            } else {
                plan.add(Map.entry(p, new double[]{p.getLat(), p.getLon()}));
            }
        }
        return plan;
    }

    /** Sample points across a zone: center + N on a fixed compass cross at
     *  0.35×radius — deterministic, order-stable. N scales with diameter. */
    static List<double[]> fieldSamplePoints(Plant field) {
        int k = (int) Math.max(3, Math.min(7, Math.round(field.getCanopyDiaM() / 25.0) + 2));
        double rM = field.getCanopyDiaM() / 2.0 * 0.7;      // stay inside the zone
        double mLat = 110540.0;
        double mLon = 111320.0 * Math.cos(Math.toRadians(field.getLat()));
        List<double[]> out = new ArrayList<>();
        out.add(new double[]{field.getLat(), field.getLon()});
        for (int i = 1; i < k; i++) {
            double az = 2 * Math.PI * (i - 1) / Math.max(1, k - 1);
            out.add(new double[]{field.getLat() + rM * Math.cos(az) / mLat,
                                 field.getLon() + rM * Math.sin(az) / mLon});
        }
        return out;
    }

    public String buildInspectionRoute(Mission m, double inspectAltM, double speedMps) {
        ArrayNode coords = M.createArrayNode();
        for (Map.Entry<Plant, double[]> e : routePlan(m))
            coords.addArray().add(e.getValue()[1]).add(e.getValue()[0]).add(inspectAltM);
        ObjectNode geo = M.createObjectNode();
        geo.put("type", "LineString");
        geo.set("coordinates", coords);
        return geo.toString();
    }

    // ---------------------------------------------------------------- stage 3: analysis + report

    /**
     * STUB classifier: assign findings from the catalog to plants, biased by
     * the (stubbed) health index so the report is internally consistent — low
     * vigor ⇒ more/worse findings. Real replacement: run a disease classifier
     * over the per-plant close-up imagery captured on the inspection pass.
     */
    @Transactional
    public void analyzeInspection(Mission m) {
        List<Plant> ps = plants.findByMissionIdOrderByLabelAsc(m.getId());
        Random rnd = new Random(0xC0FFEEL ^ m.getId().getLeastSignificantBits());
        for (Plant p : ps) {
            if (p.isExcluded()) {
                p.setStatus("SKIPPED");
                plants.save(p);
                continue;
            }
            if (p.getKind() == PlantKind.CROP_FIELD) {
                stubZoneResult(m, p, rnd);
                continue;
            }
            p.setStatus("SCANNED");
            if (p.getPhotoObjectKey() == null) {
                // STUB flight: attach a clearly-marked placeholder close-up.
                try {
                    java.nio.file.Path card = PlaceholderImage.plantCard(
                            p.getLabel(), p.getKind().name(), p.getHealthIndex());
                    String key = "plants/" + m.getId() + "/" + p.getLabel() + ".png";
                    storage.uploadFile(key, card, "image/png");
                    java.nio.file.Files.deleteIfExists(card);
                    p.setPhotoObjectKey(key);
                } catch (Exception e) {
                    log.warn("placeholder photo failed for {}: {}", p.getLabel(), e.getMessage());
                }
            }
            plants.save(p);
            double risk = 1.0 - p.getHealthIndex();
            if (rnd.nextDouble() > risk * 1.1) continue;         // healthy plant: no finding
            List<DiseaseCatalog.Disease> options = catalog.forKind(p.getKind().name());
            DiseaseCatalog.Disease d = options.get(rnd.nextInt(options.size()));
            PlantFinding f = new PlantFinding();
            f.setPlant(p);
            f.setDiseaseId(d.id());
            f.setDiseaseName(d.name());
            f.setSeverity(risk > 0.6 ? "HIGH" : risk > 0.35 ? "MEDIUM" : "LOW");
            f.setConfidence(clamp01(0.6 + rnd.nextGaussian() * 0.15));
            f.setNote("Auto-flagged from inspection imagery (stub classifier).");
            findings.save(f);
        }
        log.info("crop analysis (stub): mission {} analyzed {} plants", m.getId(), ps.size());
    }

    /** STUB zone analysis: an affected fraction of the zone's sample points,
     *  reported exactly like the real aggregation ("k of n sample points"). */
    private void stubZoneResult(Mission m, Plant field, Random rnd) {
        int samples = fieldSamplePoints(field).size();
        field.setStatus("SCANNED");
        if (field.getPhotoObjectKey() == null) {
            try {
                java.nio.file.Path card = PlaceholderImage.plantCard(
                        field.getLabel(), "CROP_FIELD", field.getHealthIndex());
                String key = "plants/" + m.getId() + "/" + field.getLabel() + ".png";
                storage.uploadFile(key, card, "image/png");
                java.nio.file.Files.deleteIfExists(card);
                field.setPhotoObjectKey(key);
            } catch (Exception ignore) { }
        }
        double risk = 1.0 - field.getHealthIndex();
        if (rnd.nextDouble() > risk * 1.2) {           // healthy zone
            plants.save(field);
            return;
        }
        int hits = 1 + rnd.nextInt(Math.max(1, (int) Math.round(samples * Math.min(1.0, risk + 0.2))));
        List<DiseaseCatalog.Disease> options = catalog.forKind("CROP");
        DiseaseCatalog.Disease d = options.get(rnd.nextInt(options.size()));
        PlantFinding f = new PlantFinding();
        f.setPlant(field);
        f.setDiseaseId(d.id());
        f.setDiseaseName(d.name());
        f.setSeverity(zoneSeverity(hits, samples, 0));
        f.setConfidence(clamp01(0.55 + rnd.nextGaussian() * 0.15));
        f.setNote("Zone-level: detected at " + hits + " of " + samples + " sample points (stub).");
        findings.save(f);
        field.setHealthIndex(clamp01(1.0 - (double) hits / samples));
        plants.save(field);
    }

    /** Findings of the PARENT mission keyed "label|diseaseId" (follow-up comparison). */
    private Map<String, PlantFinding> parentFindings(Mission m) {
        if (m.getParentMission() == null) return Map.of();
        Map<String, PlantFinding> out = new HashMap<>();
        for (PlantFinding f : findings.findByPlantMissionIdOrderByPlantLabelAsc(m.getParentMission().getId()))
            out.put(f.getPlant().getLabel() + "|" + f.getDiseaseId(), f);
        return out;
    }

    /** Render + store the deliverable report (HTML for humans, JSON for machines). Returns the HTML object key. */
    @Transactional(readOnly = true)
    public String buildAndStoreReport(Mission m) {
        List<Plant> ps = plants.findByMissionIdOrderByLabelAsc(m.getId());
        List<PlantFinding> fs = findings.findByPlantMissionIdOrderByPlantLabelAsc(m.getId());
        Map<UUID, List<PlantFinding>> byPlant = new HashMap<>();
        for (PlantFinding f : fs) byPlant.computeIfAbsent(f.getPlant().getId(), k -> new ArrayList<>()).add(f);

        String jsonKey = "reports/" + m.getId() + "/crop_report.json";
        String htmlKey = "reports/" + m.getId() + "/crop_report.html";
        try {
            Path json = Files.createTempFile("crop-", ".json");
            Files.writeString(json, reportJson(m, ps, byPlant));
            storage.uploadFile(jsonKey, json, "application/json");
            Files.deleteIfExists(json);

            Path html = Files.createTempFile("crop-", ".html");
            Files.writeString(html, reportHtml(m, ps, byPlant), StandardCharsets.UTF_8);
            storage.uploadFile(htmlKey, html, "text/html; charset=utf-8");
            Files.deleteIfExists(html);
        } catch (Exception e) {
            throw new RuntimeException("Report generation failed", e);
        }
        return htmlKey;
    }

    // ---------------------------------------------------------------- report rendering

    private String reportJson(Mission m, List<Plant> ps, Map<UUID, List<PlantFinding>> byPlant) {
        ObjectNode root = M.createObjectNode();
        root.put("missionId", m.getId().toString());
        root.put("missionName", m.getName());
        root.put("generatedAt", Instant.now().toString());
        root.put("plantCount", ps.size());
        int affected = 0;
        ArrayNode plantsN = root.putArray("plants");
        for (Plant p : ps) {
            ObjectNode pn = plantsN.addObject();
            pn.put("label", p.getLabel()); pn.put("kind", p.getKind().name());
            pn.put("lat", p.getLat()); pn.put("lon", p.getLon());
            pn.put("heightM", round(p.getHeightM())); pn.put("canopyDiaM", round(p.getCanopyDiaM()));
            pn.put("healthIndex", round(p.getHealthIndex()));
            List<PlantFinding> pf = byPlant.getOrDefault(p.getId(), List.of());
            if (!pf.isEmpty()) affected++;
            ArrayNode fn = pn.putArray("findings");
            for (PlantFinding f : pf) {
                DiseaseCatalog.Disease d = catalog.get(f.getDiseaseId());
                ObjectNode o = fn.addObject();
                o.put("disease", f.getDiseaseName()); o.put("severity", f.getSeverity());
                o.put("confidence", round(f.getConfidence()));
                if (d != null) {
                    d.cures().forEach(o.putArray("cures")::add);
                    d.checks().forEach(o.putArray("checks")::add);
                }
            }
        }
        root.put("affectedCount", affected);
        return root.toPrettyString();
    }

    private String reportHtml(Mission m, List<Plant> ps, Map<UUID, List<PlantFinding>> byPlant) {
        long trees = ps.stream().filter(p -> p.getKind() == PlantKind.TREE).count();
        long affected = ps.stream().filter(p -> byPlant.containsKey(p.getId())).count();
        Map<String, Integer> diseaseTally = new LinkedHashMap<>();
        for (List<PlantFinding> l : byPlant.values())
            for (PlantFinding f : l) diseaseTally.merge(f.getDiseaseName(), 1, Integer::sum);

        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset='utf-8'><title>Crop health report — ")
         .append(esc(m.getName())).append("</title><style>")
         .append("body{font:14px/1.5 system-ui,sans-serif;margin:2rem;color:#1a1f26;max-width:960px}")
         .append("h1{margin:0 0 .2rem}.dim{color:#667}.cards{display:flex;gap:1rem;flex-wrap:wrap;margin:1rem 0}")
         .append(".card{border:1px solid #dde;border-radius:8px;padding:.8rem 1.1rem}.card b{font-size:1.6rem}")
         .append("table{border-collapse:collapse;width:100%;margin:.5rem 0}th,td{border-bottom:1px solid #e6e9ef;padding:.4rem .6rem;text-align:left;vertical-align:top}")
         .append("th{font-size:.75rem;text-transform:uppercase;letter-spacing:.04em;color:#667}")
         .append(".sev-HIGH{color:#b3261e;font-weight:600}.sev-MEDIUM{color:#a25c00}.sev-LOW{color:#3a6}")
         .append(".pill{display:inline-block;background:#eef;border-radius:10px;padding:.05rem .5rem;font-size:.75rem;margin:.1rem}")
         .append("ul{margin:.2rem 0 .2rem 1.1rem;padding:0}.disc{background:#fff8e1;border:1px solid #ffe08a;padding:.6rem .9rem;border-radius:8px;margin:1rem 0}")
         .append("</style></head><body>");
        h.append("<h1>Crop health report</h1><p class='dim'>").append(esc(m.getName()))
         .append(" · generated ").append(Instant.now().toString()).append("</p>");
        long zones = ps.stream().filter(p -> p.getKind() == PlantKind.CROP_FIELD).count();
        h.append("<div class='cards'>")
         .append(card(ps.size(), "entries detected"))
         .append(card((int) (ps.size() - trees - zones), "crop plants"))
         .append(card((int) trees, "trees"))
         .append(card((int) zones, "crop field zones"))
         .append(card((int) affected, "with findings"))
         .append("</div>");

        // Optional LLM narrative (off-flight; null when disabled or on failure).
        try {
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (Plant p : ps) {
                List<PlantFinding> pf = byPlant.get(p.getId());
                if (pf == null) continue;
                for (PlantFinding f : pf)
                    lines.add("%s, %s, %.5f/%.5f, %s, %s".formatted(
                            p.getLabel(), p.getKind(), p.getLat(), p.getLon(),
                            f.getDiseaseName(), f.getSeverity()));
            }
            String narrative = narrator.narrate(m.getName(), ps.size(), (int) affected,
                    diseaseTally, lines);
            if (narrative != null)
                h.append("<h2>Summary</h2><div class='narrative'>").append(narrative).append("</div>");
        } catch (Exception ignore) { }

        if (!diseaseTally.isEmpty()) {
            h.append("<h2>Findings by disease</h2><table><tr><th>Disease</th><th>Plants affected</th></tr>");
            diseaseTally.forEach((d, c) -> h.append("<tr><td>").append(esc(d)).append("</td><td>").append(c).append("</td></tr>"));
            h.append("</table>");
        }

        h.append("<h2>Affected plants</h2>");
        if (affected == 0) {
            h.append("<p>No disease findings — all inspected plants appear healthy.</p>");
        } else {
            Map<String, PlantFinding> prev = parentFindings(m);
            boolean followUp = m.getParentMission() != null;
            h.append("<table><tr><th>Plant</th><th>Kind</th><th>Location</th><th>Disease</th><th>Severity</th>");
            if (followUp) h.append("<th>Change since last inspection</th>");
            h.append("<th>Possible cures</th><th>What to check</th></tr>");
            for (Plant p : ps) {
                List<PlantFinding> pf = byPlant.get(p.getId());
                if (pf == null) continue;
                for (PlantFinding f : pf) {
                    DiseaseCatalog.Disease d = catalog.get(f.getDiseaseId());
                    h.append("<tr><td><b>").append(esc(p.getLabel())).append("</b></td><td>")
                     .append(p.getKind()).append("</td><td class='dim'>")
                     .append(String.format("%.5f, %.5f", p.getLat(), p.getLon())).append("</td><td>")
                     .append(esc(f.getDiseaseName())).append("<br><span class='dim'>conf ")
                     .append(String.format("%.0f%%", f.getConfidence() * 100))
                     .append(p.getKind() == PlantKind.CROP_FIELD && f.getNote() != null
                             ? " · " + esc(f.getNote().replace("Zone-level: ", "")) : "")
                     .append("</span></td><td class='sev-")
                     .append(f.getSeverity()).append("'>").append(f.getSeverity()).append("</td>");
                    if (followUp) {
                        PlantFinding was = prev.get(p.getLabel() + "|" + f.getDiseaseId());
                        h.append("<td>").append(was == null ? "<b>NEW</b>"
                                : "PERSISTS (was " + esc(was.getSeverity()) + ")").append("</td>");
                    }
                    h.append("<td>")
                     .append(list(d == null ? List.of() : d.cures())).append("</td><td>")
                     .append(list(d == null ? List.of() : d.checks())).append("</td></tr>");
                }
            }
            h.append("</table>");
        }

        if (m.getParentMission() != null) {
            Map<String, PlantFinding> prev = parentFindings(m);
            Set<String> nowKeys = new HashSet<>();
            for (List<PlantFinding> l : byPlant.values())
                for (PlantFinding f : l) nowKeys.add(f.getPlant().getLabel() + "|" + f.getDiseaseId());
            List<PlantFinding> resolved = new ArrayList<>();
            for (Map.Entry<String, PlantFinding> e : prev.entrySet())
                if (!nowKeys.contains(e.getKey())) resolved.add(e.getValue());
            if (!resolved.isEmpty()) {
                h.append("<h2>Resolved since last inspection</h2><table>")
                 .append("<tr><th>Plant</th><th>Disease</th><th>Was</th></tr>");
                for (PlantFinding f : resolved)
                    h.append("<tr><td>").append(esc(f.getPlant().getLabel())).append("</td><td>")
                     .append(esc(f.getDiseaseName())).append("</td><td class='sev-")
                     .append(f.getSeverity()).append("'>").append(f.getSeverity()).append("</td></tr>");
                h.append("</table>");
            }
        }

        h.append("<h2>All detected plants</h2><table><tr><th>Plant</th><th>Kind</th><th>Location</th>")
         .append("<th>Height</th><th>Canopy</th><th>Vigor</th><th>Status</th></tr>");
        for (Plant p : ps) {
            h.append("<tr><td>").append(esc(p.getLabel())).append("</td><td>").append(p.getKind())
             .append("</td><td class='dim'>").append(String.format("%.5f, %.5f", p.getLat(), p.getLon()))
             .append("</td><td>").append(String.format("%.1f m", p.getHeightM()))
             .append("</td><td>").append(String.format(
                     p.getKind() == PlantKind.CROP_FIELD ? "%.0f m zone" : "%.1f m", p.getCanopyDiaM()))
             .append("</td><td>").append(String.format("%.2f", p.getHealthIndex()))
             .append("</td><td>").append(esc(p.getStatus())).append("</td></tr>");
        }
        h.append("</table>");
        h.append("<div class='disc'><b>Advisory only.</b> Detections and disease calls are produced ")
         .append("automatically from drone imagery and are not a substitute for inspection by a ")
         .append("qualified agronomist. Confirm findings on the ground before applying any treatment, ")
         .append("and follow local regulations and product labels for all chemicals.</div>");
        h.append("</body></html>");
        return h.toString();
    }

    private static String card(int n, String label) {
        return "<div class='card'><b>" + n + "</b><br><span class='dim'>" + esc(label) + "</span></div>";
    }
    private static String list(List<String> items) {
        if (items.isEmpty()) return "<span class='dim'>—</span>";
        StringBuilder b = new StringBuilder("<ul>");
        for (String s : items) b.append("<li>").append(esc(s)).append("</li>");
        return b.append("</ul>").toString();
    }

    // ---------------------------------------------------------------- helpers

    private List<Plant> nearestNeighbor(List<Plant> in) {
        List<Plant> remaining = new ArrayList<>(in);
        List<Plant> order = new ArrayList<>();
        Plant cur = remaining.remove(0);
        order.add(cur);
        while (!remaining.isEmpty()) {
            Plant best = null; double bestD = Double.MAX_VALUE;
            for (Plant p : remaining) {
                double d = Math.hypot(p.getLat() - cur.getLat(), p.getLon() - cur.getLon());
                if (d < bestD) { bestD = d; best = p; }
            }
            remaining.remove(best); order.add(best); cur = best;
        }
        return order;
    }

    private List<double[]> parseRing(String geoJson) {
        try {
            var node = M.readTree(geoJson);
            var coords = node.has("coordinates") ? node.get("coordinates").get(0) : node.get(0);
            List<double[]> ring = new ArrayList<>();
            for (var c : coords) ring.add(new double[]{c.get(0).asDouble(), c.get(1).asDouble()});
            return ring;
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad polygon GeoJSON", e);
        }
    }

    private static boolean insideField(List<double[]> fields, double lat, double lon) {
        for (double[] f : fields) {
            double dm = Math.hypot((f[0] - lat) * 110540.0,
                    (f[1] - lon) * 111320.0 * Math.cos(Math.toRadians(lat)));
            if (dm < f[2] / 2.0) return true;
        }
        return false;
    }

    private static boolean inRing(List<double[]> ring, double lon, double lat) {
        boolean in = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double xi = ring.get(i)[0], yi = ring.get(i)[1], xj = ring.get(j)[0], yj = ring.get(j)[1];
            if (((yi > lat) != (yj > lat)) && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) in = !in;
        }
        return in;
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
