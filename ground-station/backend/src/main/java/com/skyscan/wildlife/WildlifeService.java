package com.skyscan.wildlife;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyscan.domain.*;
import com.skyscan.repo.AnimalSightingRepo;
import com.skyscan.repo.ScanAreaRepo;
import com.skyscan.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * WILDLIFE_SURVEY brain — mirrors CropAnalysisService's shape:
 *
 *  ingestSightings()  — the REAL path: animals detected ON THE DRONE
 *                       (RGB YOLO + thermal fusion), each already tagged with
 *                       species, time-of-finding and world location.
 *  generateStubSightings() — deterministic fallback for the simulated
 *                       connector, so the demo runs without hardware.
 *  buildAndStoreReport() — the deliverable: sightings map data + HTML report
 *                       (species tally, timeline-ordered tag table) + JSON.
 *
 * Single-pass: MissionService calls this from the CRUDE_LANDED hook and goes
 * straight to PROCESSING -> COMPLETE (animals move; there is no detail pass).
 */
@Service
public class WildlifeService {

    private static final Logger log = LoggerFactory.getLogger(WildlifeService.class);
    private static final ObjectMapper M = new ObjectMapper();
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    /** Species the stub generator draws from (the real detector's classes come from its model). */
    private static final String[] STUB_SPECIES = {"deer", "wild boar", "fox", "hare", "roe deer", "bird (large)"};

    private final AnimalSightingRepo sightings;
    private final ScanAreaRepo scanAreas;
    private final StorageService storage;

    public WildlifeService(AnimalSightingRepo sightings, ScanAreaRepo scanAreas, StorageService storage) {
        this.sightings = sightings; this.scanAreas = scanAreas; this.storage = storage;
    }

    // ---------------------------------------------------------------- ingest (real path)

    @Transactional
    public int ingestSightings(Mission m, JsonNode arr) {
        sightings.deleteAll(sightings.findByMissionIdOrderBySightedAtAsc(m.getId()));
        int n = 0;
        for (JsonNode s : arr) {
            AnimalSighting a = new AnimalSighting();
            a.setMission(m);
            a.setLabel(s.path("label").asText("A-" + String.format("%03d", n + 1)));
            a.setSpecies(s.path("species").asText("animal"));
            a.setConfidence(s.path("confidence").asDouble(0.5));
            a.setLat(s.path("lat").asDouble());
            a.setLon(s.path("lon").asDouble());
            a.setSightedAt(s.hasNonNull("sightedAt")
                    ? Instant.parse(s.get("sightedAt").asText()) : Instant.now());
            a.setSource(s.path("source").asText("RGB"));
            if (s.hasNonNull("bodyTempC")) a.setBodyTempC(s.get("bodyTempC").asDouble());
            sightings.save(a);
            n++;
        }
        log.info("ingested {} drone animal sightings for mission {}", n, m.getId());
        return n;
    }

    /** Append ONE live-streamed sighting (keeps the label the drone assigned). */
    @Transactional
    public void ingestOne(Mission m, JsonNode s) {
        AnimalSighting a = new AnimalSighting();
        a.setMission(m);
        a.setLabel(s.path("label").asText("A-" + String.format("%03d", (int) countByMission(m) + 1)));
        a.setSpecies(s.path("species").asText("animal"));
        a.setConfidence(s.path("confidence").asDouble(0.5));
        a.setLat(s.path("lat").asDouble());
        a.setLon(s.path("lon").asDouble());
        a.setSightedAt(s.hasNonNull("sightedAt")
                ? Instant.parse(s.get("sightedAt").asText()) : Instant.now());
        a.setSource(s.path("source").asText("RGB"));
        if (s.hasNonNull("bodyTempC")) a.setBodyTempC(s.get("bodyTempC").asDouble());
        if (s.hasNonNull("photoObjectKey")) a.setPhotoObjectKey(s.get("photoObjectKey").asText());
        else attachStubPhoto(m, a);
        sightings.save(a);
    }

    /** Clearly-marked placeholder evidence card (simulated flights only — the
     *  real drone uploads actual frame crops which take precedence). */
    private void attachStubPhoto(Mission m, AnimalSighting a) {
        try {
            java.nio.file.Path card = com.skyscan.agri.PlaceholderImage.animalCard(
                    a.getLabel(), a.getSpecies(), a.getSource(), a.getBodyTempC());
            String key = "sightings/" + m.getId() + "/" + a.getLabel() + ".png";
            storage.uploadFile(key, card, "image/png");
            java.nio.file.Files.deleteIfExists(card);
            a.setPhotoObjectKey(key);
        } catch (Exception e) {
            log.warn("placeholder photo failed for {}: {}", a.getLabel(), e.getMessage());
        }
    }

    /** Real drone path: attach the evidence frame for one tagged animal. */
    @Transactional
    public void attachPhoto(Mission m, String label, java.nio.file.Path file) {
        for (AnimalSighting a : sightings.findByMissionIdOrderBySightedAtAsc(m.getId())) {
            if (!a.getLabel().equals(label)) continue;
            try {
                String key = "sightings/" + m.getId() + "/" + label + ".jpg";
                storage.uploadFile(key, file, "image/jpeg");
                java.nio.file.Files.deleteIfExists(file);
                a.setPhotoObjectKey(key);
                sightings.save(a);
            } catch (Exception e) {
                log.warn("sighting photo store failed: {}", e.getMessage());
            }
            return;
        }
    }

    private long countByMission(Mission m) { return sightings.countByMissionId(m.getId()); }

    public boolean hasSightings(Mission m) {
        return sightings.countByMissionId(m.getId()) > 0;
    }

    public long countSightings(Mission m) {
        return sightings.countByMissionId(m.getId());
    }

    // ---------------------------------------------------------------- stub fallback

    /** Deterministic sightings inside the polygon (simulated connector only). */
    @Transactional
    public int generateStubSightings(Mission m) {
        List<double[]> ring = parseRing(scanAreas.findByMissionIdAndPassType(m.getId(), PassType.CRUDE)
                .orElseThrow().getPolygonGeoJson());
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (double[] p : ring) {
            minLon = Math.min(minLon, p[0]); maxLon = Math.max(maxLon, p[0]);
            minLat = Math.min(minLat, p[1]); maxLat = Math.max(maxLat, p[1]);
        }
        Random rnd = new Random(m.getId().getMostSignificantBits());
        Instant t = Instant.now().minusSeconds(600);           // "during the pass"
        int made = 0, tries = 0, target = 3 + rnd.nextInt(6);
        while (made < target && tries++ < 200) {
            double lat = minLat + rnd.nextDouble() * (maxLat - minLat);
            double lon = minLon + rnd.nextDouble() * (maxLon - minLon);
            if (!inRing(ring, lon, lat)) continue;
            AnimalSighting a = new AnimalSighting();
            a.setMission(m);
            a.setLabel("A-" + String.format("%03d", made + 1));
            a.setSpecies(STUB_SPECIES[rnd.nextInt(STUB_SPECIES.length)]);
            a.setConfidence(0.55 + rnd.nextDouble() * 0.4);
            a.setLat(lat); a.setLon(lon);
            t = t.plusSeconds(20 + rnd.nextInt(90));           // spread along the pass
            a.setSightedAt(t);
            boolean thermal = rnd.nextDouble() < 0.6;
            a.setSource(thermal ? "FUSED" : "RGB");
            if (thermal) a.setBodyTempC(34 + rnd.nextDouble() * 6);
            attachStubPhoto(m, a);
            sightings.save(a);
            made++;
        }
        log.info("wildlife stub: mission {} -> {} sightings", m.getId(), made);
        return made;
    }

    // ---------------------------------------------------------------- report

    @Transactional(readOnly = true)
    public String buildAndStoreReport(Mission m) {
        List<AnimalSighting> list = sightings.findByMissionIdOrderBySightedAtAsc(m.getId());
        String jsonKey = "reports/" + m.getId() + "/wildlife_report.json";
        String htmlKey = "reports/" + m.getId() + "/wildlife_report.html";
        try {
            Path json = Files.createTempFile("wild-", ".json");
            Files.writeString(json, reportJson(m, list));
            storage.uploadFile(jsonKey, json, "application/json");
            Files.deleteIfExists(json);
            Path html = Files.createTempFile("wild-", ".html");
            Files.writeString(html, reportHtml(m, list), StandardCharsets.UTF_8);
            storage.uploadFile(htmlKey, html, "text/html; charset=utf-8");
            Files.deleteIfExists(html);
        } catch (Exception e) {
            throw new RuntimeException("Wildlife report generation failed", e);
        }
        return htmlKey;
    }

    private String reportJson(Mission m, List<AnimalSighting> list) {
        ObjectNode root = M.createObjectNode();
        root.put("missionId", m.getId().toString());
        root.put("missionName", m.getName());
        root.put("generatedAt", Instant.now().toString());
        root.put("sightings", list.size());
        ArrayNode arr = root.putArray("animals");
        for (AnimalSighting a : list) {
            ObjectNode n = arr.addObject();
            n.put("label", a.getLabel()); n.put("species", a.getSpecies());
            n.put("confidence", a.getConfidence());
            n.put("lat", a.getLat()); n.put("lon", a.getLon());
            n.put("sightedAt", a.getSightedAt().toString());
            n.put("source", a.getSource());
            if (a.getBodyTempC() != null) n.put("bodyTempC", a.getBodyTempC());
        }
        return root.toPrettyString();
    }

    private String reportHtml(Mission m, List<AnimalSighting> list) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (AnimalSighting a : list) tally.merge(a.getSpecies(), 1, Integer::sum);
        long fused = list.stream().filter(a -> "FUSED".equals(a.getSource())).count();

        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset='utf-8'><title>Wildlife survey — ")
         .append(esc(m.getName())).append("</title><style>")
         .append("body{font:14px/1.5 system-ui,sans-serif;margin:2rem;color:#1a1f26;max-width:960px}")
         .append("h1{margin:0 0 .2rem}.dim{color:#667}.cards{display:flex;gap:1rem;flex-wrap:wrap;margin:1rem 0}")
         .append(".card{border:1px solid #dde;border-radius:8px;padding:.8rem 1.1rem}.card b{font-size:1.6rem}")
         .append("table{border-collapse:collapse;width:100%;margin:.5rem 0}th,td{border-bottom:1px solid #e6e9ef;padding:.4rem .6rem;text-align:left}")
         .append("th{font-size:.75rem;text-transform:uppercase;letter-spacing:.04em;color:#667}")
         .append(".src-FUSED{color:#0a7d5c;font-weight:600}.src-THERMAL{color:#a25c00}.src-RGB{color:#185fa5}")
         .append(".disc{background:#fff8e1;border:1px solid #ffe08a;padding:.6rem .9rem;border-radius:8px;margin:1rem 0}")
         .append("</style></head><body>");
        h.append("<h1>Wildlife survey report</h1><p class='dim'>").append(esc(m.getName()))
         .append(" · generated ").append(Instant.now().toString()).append("</p>");
        h.append("<div class='cards'>")
         .append("<div class='card'><b>").append(list.size()).append("</b><br><span class='dim'>animals tagged</span></div>")
         .append("<div class='card'><b>").append(tally.size()).append("</b><br><span class='dim'>species</span></div>")
         .append("<div class='card'><b>").append(fused).append("</b><br><span class='dim'>thermal-confirmed</span></div>")
         .append("</div>");
        if (!tally.isEmpty()) {
            h.append("<h2>By species</h2><table><tr><th>Species</th><th>Count</th></tr>");
            tally.forEach((s, c) -> h.append("<tr><td>").append(esc(s)).append("</td><td>").append(c).append("</td></tr>"));
            h.append("</table>");
        }
        h.append("<h2>Sighting tags (time-ordered)</h2>");
        if (list.isEmpty()) {
            h.append("<p>No animals detected during the pass.</p>");
        } else {
            h.append("<table><tr><th>Tag</th><th>Species</th><th>Time of finding</th>")
             .append("<th>Location</th><th>Source</th><th>Body temp</th><th>Conf</th></tr>");
            for (AnimalSighting a : list) {
                h.append("<tr><td><b>").append(esc(a.getLabel())).append("</b></td><td>")
                 .append(esc(a.getSpecies())).append("</td><td>")
                 .append(TS.format(a.getSightedAt())).append("</td><td class='dim'>")
                 .append(String.format("%.5f, %.5f", a.getLat(), a.getLon())).append("</td><td class='src-")
                 .append(a.getSource()).append("'>").append(a.getSource()).append("</td><td>")
                 .append(a.getBodyTempC() == null ? "—" : String.format("%.1f °C", a.getBodyTempC()))
                 .append("</td><td>").append(String.format("%.0f%%", a.getConfidence() * 100))
                 .append("</td></tr>");
            }
            h.append("</table>");
        }
        h.append("<div class='disc'><b>Note.</b> Detections are automated; species labels and counts ")
         .append("carry the detector's uncertainty (an animal seen twice may be tagged twice). ")
         .append("Positions are the frame projection at the moment of sighting — moving animals ")
         .append("will have moved since. Check local regulations before disturbing wildlife.</div>");
        h.append("</body></html>");
        return h.toString();
    }

    // ---------------------------------------------------------------- helpers

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

    private static boolean inRing(List<double[]> ring, double lon, double lat) {
        boolean in = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double xi = ring.get(i)[0], yi = ring.get(i)[1], xj = ring.get(j)[0], yj = ring.get(j)[1];
            if (((yi > lat) != (yj > lat)) && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) in = !in;
        }
        return in;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
