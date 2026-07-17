package com.skyscan.agri;

import com.fasterxml.jackson.databind.JsonNode;
import com.skyscan.mission.MissionService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Internal ingestion of on-board CV results (CROP_HEALTH and WILDLIFE_SURVEY),
 * guarded by the X-Internal-Token filter (like all /internal/** routes).
 * The Jetson agent POSTs here:
 *   - plants   after the crude pass (YOLO detection + world projection + dedup)
 *   - findings after the inspection pass (on-board disease classifier)
 * Both are optional: if the drone doesn't supply them (e.g. the simulated
 * stub connector), MissionService falls back to its own stub detector/analyzer.
 */
@RestController
@RequestMapping("/internal/drone/missions")
public class CropIngestController {

    private final MissionService missions;

    public CropIngestController(MissionService missions) { this.missions = missions; }

    @PostMapping("/{id}/plants")
    public Map<String, Object> plants(@PathVariable UUID id, @RequestBody JsonNode body) {
        JsonNode arr = body.has("plants") ? body.get("plants") : body;
        int n = missions.ingestDronePlants(id, arr);
        return Map.of("ok", true, "plants", n);
    }

    @PostMapping("/{id}/findings")
    public Map<String, Object> findings(@PathVariable UUID id, @RequestBody JsonNode body) {
        JsonNode arr = body.has("findings") ? body.get("findings") : body;
        int n = missions.ingestDroneFindings(id, arr);
        return Map.of("ok", true, "findings", n);
    }

    /** WILDLIFE_SURVEY: full sightings list uploaded at landing. */
    @PostMapping("/{id}/sightings")
    public Map<String, Object> sightings(@PathVariable UUID id, @RequestBody JsonNode body) {
        JsonNode arr = body.has("sightings") ? body.get("sightings") : body;
        int n = missions.ingestDroneSightings(id, arr);
        return Map.of("ok", true, "sightings", n);
    }

    /** CROP_HEALTH: representative close-up per plant, keyed by route index. */
    @PostMapping(value = "/{id}/plants/by-index/{idx}/photo", consumes = "multipart/form-data")
    public Map<String, Object> plantPhoto(@PathVariable UUID id, @PathVariable int idx,
            @org.springframework.web.bind.annotation.RequestPart("file")
            org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("plantphoto-", ".jpg");
        file.transferTo(tmp);
        missions.attachPlantPhoto(id, idx, tmp);
        return Map.of("ok", true);
    }

    /** WILDLIFE_SURVEY: evidence frame for one tagged animal, by label. */
    @PostMapping(value = "/{id}/sightings/{label}/photo", consumes = "multipart/form-data")
    public Map<String, Object> sightingPhoto(@PathVariable UUID id, @PathVariable String label,
            @org.springframework.web.bind.annotation.RequestPart("file")
            org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("sightphoto-", ".jpg");
        file.transferTo(tmp);
        missions.attachSightingPhoto(id, label, tmp);
        return Map.of("ok", true);
    }

    /** WILDLIFE_SURVEY: one live sighting streamed DURING the pass (SSE relay). */
    @PostMapping("/{id}/sightings/live")
    public Map<String, Object> liveSighting(@PathVariable UUID id, @RequestBody JsonNode body) {
        missions.onLiveSighting(id, body);
        return Map.of("ok", true);
    }
}
