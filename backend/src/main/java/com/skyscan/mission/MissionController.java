package com.skyscan.mission;

import com.skyscan.auth.CurrentUser;
import com.skyscan.domain.*;
import com.skyscan.dto.Dtos.*;
import com.skyscan.live.MissionEventPublisher;
import com.skyscan.repo.*;
import com.skyscan.storage.StorageService;
import org.springframework.beans.factory.annotation.Value;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/missions")
public class MissionController {

    private final MissionService service;
    private final MissionRepo missions;
    private final SiteRepo sites;
    private final ScanAreaRepo scanAreas;
    private final MissionEventRepo events;
    private final ProcessingJobRepo jobs;
    private final PointCloudAssetRepo assets;
    private final MissionEventPublisher publisher;
    private final StorageService storage;
    private final CurrentUser current;
    private final AcquisitionRepo acquisitions;
    private final PlantRepo plants;
    private final PlantFindingRepo plantFindings;
    private final AnimalSightingRepo animalSightings;
    private final String targetCrs;

    public MissionController(MissionService service, MissionRepo missions, SiteRepo sites,
                             ScanAreaRepo scanAreas, MissionEventRepo events, ProcessingJobRepo jobs,
                             PointCloudAssetRepo assets, MissionEventPublisher publisher,
                             StorageService storage, CurrentUser current, AcquisitionRepo acquisitions,
                             PlantRepo plants, PlantFindingRepo plantFindings,
                             AnimalSightingRepo animalSightings,
                             @Value("${skyscan.processing.crs}") String targetCrs) {
        this.service = service; this.missions = missions; this.sites = sites; this.scanAreas = scanAreas;
        this.events = events; this.jobs = jobs; this.assets = assets; this.publisher = publisher;
        this.storage = storage; this.current = current; this.acquisitions = acquisitions;
        this.plants = plants; this.plantFindings = plantFindings;
        this.animalSightings = animalSightings;
        this.targetCrs = targetCrs;
    }

    // ---------------------------------------------------------------- lifecycle commands

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public MissionView create(@Valid @RequestBody CreateMissionRequest req) {
        Site site = sites.findById(req.siteId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found"));
        Mission m = service.createMission(site, req.name(), req.polygonGeoJson(),
                req.altitudeM(), req.lineSpacingM(), req.speedMps(), req.scanMode(),
                req.missionType(), req.cameraModule());
        if (req.surveyEnvironment() != null && !req.surveyEnvironment().isBlank()) {
            m.setSurveyEnvironment(req.surveyEnvironment());
            missions.save(m);
        }
        return MissionView.of(m);
    }

    @PostMapping("/{id}/launch-crude")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public MissionView launchCrude(@PathVariable UUID id) {
        service.launchCrude(id);
        return MissionView.of(service.get(id));
    }

    @PostMapping("/{id}/refine")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public RefineResponse refine(@PathVariable UUID id, @Valid @RequestBody RefineRequest req) {
        try {
            var route = service.submitDetailArea(id, req.polygonGeoJson(), req.altitudeM(),
                    req.lineSpacingM(), req.speedMps(), req.terrainFollowing(),
                    req.scanMode(), req.standoffM() == null ? 15.0 : req.standoffM(),
                    req.tiltDeg() == null ? 0.0 : req.tiltDeg());
            return new RefineResponse(MissionView.of(service.get(id)),
                    route.routeGeoJson(), route.lengthM(), route.lineCount(), route.estDurationS());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    @PostMapping("/{id}/launch-detail")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public MissionView launchDetail(@PathVariable UUID id) {
        service.launchDetail(id);
        return MissionView.of(service.get(id));
    }

    @PostMapping("/{id}/abort")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public MissionView abort(@PathVariable UUID id, @RequestBody(required = false) AbortRequest req) {
        service.abort(id, req == null || req.reason() == null ? "operator abort" : req.reason());
        return MissionView.of(service.get(id));
    }

    // ---------------------------------------------------------------- queries

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        Mission m = checkedMission(id);
        Map<String, Object> out = new HashMap<>();
        out.put("mission", MissionView.of(m));
        out.put("areas", scanAreas.findAll().stream()
                .filter(a -> a.getMission().getId().equals(id)).map(ScanAreaView::of).toList());
        out.put("jobs", jobs.findByMissionIdOrderByCreatedAtDesc(id).stream()
                .map(j -> Map.of("id", j.getId(), "status", j.getStatus().name(),
                        "message", j.getMessage() == null ? "" : j.getMessage())).toList());
        return out;
    }

    @GetMapping(params = "siteId")
    public List<MissionView> listBySite(@RequestParam UUID siteId) {
        Site site = sites.findById(siteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found"));
        current.checkSiteAccess(current.require(), site);
        return missions.findBySiteIdOrderByCreatedAtDesc(siteId).stream().map(MissionView::of).toList();
    }

    /** Field preview (crude-pass artifact) as a short-lived presigned URL. */
    @GetMapping("/{id}/preview")
    public Map<String, String> preview(@PathVariable UUID id) {
        Mission m = checkedMission(id);
        if (m.getPreviewObjectKey() == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No preview yet");
        return Map.of("previewUrl", storage.presignExternalGet(m.getPreviewObjectKey(), 30));
    }

    /**
     * The raw crude-pass point cloud (available as soon as CRUDE_LANDED) as a
     * presigned, range-capable URL, plus its CRS so the editor can convert the
     * drawn detail polygon back to WGS84 for the map and the /refine call.
     * NOTE for LAN access from other devices: presigned URLs embed the storage
     * hostname — set MINIO_EXTERNAL_ENDPOINT to http://<host-lan-ip>:9000.
     */
    @GetMapping("/{id}/crude-cloud")
    public Map<String, String> crudeCloud(@PathVariable UUID id) {
        checkedMission(id);
        Acquisition crude = acquisitions.findByMissionIdOrderByCapturedAtAsc(id).stream()
                .filter(a -> a.getPassType() == PassType.CRUDE)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No crude scan yet"));
        return Map.of(
                "cloudUrl", storage.presignExternalGet(crude.getRawObjectKey(), 60),
                "crs", targetCrs,
                "sensorType", crude.getSensorType().name());
    }

    /** Remote diagnostics: run the on-drone self-test (ground checks; safe any time). */
    @PostMapping("/{id}/self-test")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Map<String, String> selfTest(@PathVariable UUID id) {
        checkedMission(id);
        service.requestSelfTest(id);
        return Map.of("status", "requested");
    }

    /** Most recent self-test report for this mission (persisted from the SELFTEST event). */
    @PostMapping("/{id}/launch-inspection")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public MissionView launchInspection(@PathVariable UUID id,
                                        @Valid @RequestBody LaunchInspectionRequest req) {
        service.launchInspection(id, req.inspectAltM(), req.speedMps());
        return MissionView.of(service.get(id));
    }

    @GetMapping("/{id}/plants")
    public List<PlantView> plants(@PathVariable UUID id) {
        Mission m = service.get(id);
        current.checkSiteAccess(current.require(), m.getSite());
        List<PlantView> out = new java.util.ArrayList<>();
        for (Plant p : plants.findByMissionIdOrderByLabelAsc(id)) {
            String photo = p.getPhotoObjectKey() == null ? null
                    : storage.presignExternalGet(p.getPhotoObjectKey(), 30);
            out.add(PlantView.of(p, plantFindings.findByPlantIdOrderBySeverityAsc(p.getId()), photo));
        }
        return out;
    }

    @GetMapping("/{id}/sightings")
    public List<Map<String, Object>> sightings(@PathVariable UUID id) {
        Mission m = service.get(id);
        current.checkSiteAccess(current.require(), m.getSite());
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var a : animalSightings.findByMissionIdOrderBySightedAtAsc(id)) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", a.getId()); row.put("label", a.getLabel());
            row.put("species", a.getSpecies()); row.put("confidence", a.getConfidence());
            row.put("lat", a.getLat()); row.put("lon", a.getLon());
            row.put("sightedAt", a.getSightedAt().toString()); row.put("source", a.getSource());
            row.put("bodyTempC", a.getBodyTempC());
            row.put("photoUrl", a.getPhotoObjectKey() == null ? null
                    : storage.presignExternalGet(a.getPhotoObjectKey(), 30));
            out.add(row);
        }
        return out;
    }

    @PostMapping("/{id}/rescan-detection")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public MissionView rescanDetection(@PathVariable UUID id,
                                       @Valid @RequestBody RescanDetectionRequest req) {
        service.launchDetectionRescan(id, req.polygonGeoJson(), req.altitudeM(), req.speedMps());
        return MissionView.of(service.get(id));
    }

    @PostMapping("/{id}/plants/{plantId}/excluded")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public List<PlantView> setExcluded(@PathVariable UUID id, @PathVariable UUID plantId,
                                       @RequestBody ExcludeRequest req) {
        service.setPlantExcluded(id, plantId, req.excluded());
        return plants(id);
    }

    @PostMapping("/{id}/follow-up")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public MissionView followUp(@PathVariable UUID id, @RequestBody FollowUpRequest req) {
        return MissionView.of(service.createFollowUp(id, req.name()));
    }

    @GetMapping("/{id}/report")
    public Map<String, String> report(@PathVariable UUID id) {
        Mission m = service.get(id);
        current.checkSiteAccess(current.require(), m.getSite());
        if (m.getReportObjectKey() == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No report yet");
        return Map.of("reportUrl", storage.presignExternalGet(m.getReportObjectKey(), 30));
    }

    @GetMapping("/{id}/self-test/latest")
    public Map<String, Object> latestSelfTest(@PathVariable UUID id) {
        checkedMission(id);
        return events.findFirstByMissionIdAndTypeOrderByAtDesc(id, "SELFTEST")
                .<Map<String, Object>>map(e -> Map.of("report", e.getPayloadJson(), "at", e.getAt()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No self-test yet"));
    }

    /** Full-detail flight logs (JSONL) uploaded by the drone after each flight. */
    @GetMapping("/{id}/logs")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public List<Map<String, String>> flightLogs(@PathVariable UUID id) {
        checkedMission(id);
        return storage.listKeys("logs/" + id + "/").stream()
                .map(k -> Map.of("url", storage.presignExternalGet(k, 60),
                        "name", k.substring(k.lastIndexOf('/') + 1)))
                .toList();
    }

    /** Geotagged photos captured by the drone camera — customer deliverable. */
    @GetMapping("/{id}/photos")
    public List<Map<String, String>> photos(@PathVariable UUID id) {
        checkedMission(id);
        return storage.listKeys("photos/" + id + "/").stream()
                .map(k -> Map.of("url", storage.presignExternalGet(k, 60),
                        "name", k.substring(k.lastIndexOf('/') + 1)))
                .toList();
    }

    @GetMapping("/{id}/deliverables")
    public List<AssetView> deliverables(@PathVariable UUID id) {
        checkedMission(id);
        boolean staff = current.isStaff(current.require());
        return assets.findByMissionIdOrderByCreatedAtDesc(id).stream()
                .filter(a -> staff || "READY".equals(a.getStatus()))   // customers see only published clouds
                .map(AssetView::of).toList();
    }

    /** Persisted event history (audit trail). */
    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(@PathVariable UUID id) {
        checkedMission(id);
        return events.findTop100ByMissionIdOrderByAtDesc(id).stream()
                .map(e -> Map.<String, Object>of("type", e.getType(), "payload", e.getPayloadJson(), "at", e.getAt()))
                .toList();
    }

    /** Live stream: mission state changes + telemetry (SSE). Token via ?access_token= for EventSource. */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id) {
        checkedMission(id);
        return publisher.subscribe(id);
    }

    private Mission checkedMission(UUID id) {
        Mission m = service.get(id);
        current.checkSiteAccess(current.require(), m.getSite());
        return m;
    }
}
