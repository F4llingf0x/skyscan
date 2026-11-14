package com.skyscan.mission;

import com.skyscan.connector.*;
import com.skyscan.domain.*;
import com.skyscan.live.MissionEventPublisher;
import com.skyscan.processing.ProcessingService;
import com.skyscan.agri.CropAnalysisService;
import com.skyscan.wildlife.WildlifeService;
import com.skyscan.check.DroneCheckService;
import com.skyscan.repo.PlantRepo;
import com.skyscan.repo.*;
import com.skyscan.storage.StorageService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The orchestrator: owns all mission state transitions, drives the DroneConnector,
 * consumes its events, triggers preview generation and processing jobs, and pushes
 * everything to the live SSE stream. This is the single source of truth the UI follows.
 */
@Service
public class MissionService implements DroneConnectorListener {

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);

    private final MissionRepo missions;
    private final ScanAreaRepo scanAreas;
    private final AcquisitionRepo acquisitions;
    private final MissionEventRepo events;
    private final MissionStateMachine fsm;
    private final DroneConnector connector;
    private final StorageService storage;
    private final MissionEventPublisher publisher;
    private final ProcessingService processing;
    private final RoutePlanner routePlanner;
    private final CropAnalysisService crop;
    private final PlantRepo plants;
    private final WildlifeService wildlife;
    private final DroneCheckService droneCheck;

    public MissionService(MissionRepo missions, ScanAreaRepo scanAreas, AcquisitionRepo acquisitions,
                          MissionEventRepo events, MissionStateMachine fsm, DroneConnector connector,
                          StorageService storage, MissionEventPublisher publisher, ProcessingService processing,
                          RoutePlanner routePlanner, CropAnalysisService crop, PlantRepo plants,
                          WildlifeService wildlife, DroneCheckService droneCheck) {
        this.missions = missions; this.scanAreas = scanAreas; this.acquisitions = acquisitions;
        this.events = events; this.fsm = fsm; this.connector = connector;
        this.storage = storage; this.publisher = publisher; this.processing = processing;
        this.routePlanner = routePlanner;
        this.crop = crop;
        this.plants = plants;
        this.wildlife = wildlife;
        this.droneCheck = droneCheck;
    }

    @PostConstruct
    void subscribeToConnector() {
        connector.setListener(this);
        log.info("Mission orchestrator wired to connector: {}", connector.describe());
    }

    // ------------------------------------------------------------------ commands (API-driven)

    @Transactional
    public Mission createMission(Site site, String name, String polygonGeoJson,
                                 double altitudeM, double lineSpacingM, double speedMps, ScanMode scanMode) {
        return createMission(site, name, polygonGeoJson, altitudeM, lineSpacingM, speedMps,
                scanMode, MissionType.SURVEY_3D);
    }

    @Transactional
    public Mission createMission(Site site, String name, String polygonGeoJson,
                                 double altitudeM, double lineSpacingM, double speedMps,
                                 ScanMode scanMode, MissionType missionType) {
        return createMission(site, name, polygonGeoJson, altitudeM, lineSpacingM, speedMps,
                scanMode, missionType, null);
    }

    @Transactional
    public Mission createMission(Site site, String name, String polygonGeoJson,
                                 double altitudeM, double lineSpacingM, double speedMps,
                                 ScanMode scanMode, MissionType missionType, String cameraModule) {
        Mission m = new Mission();
        m.setName(name);
        m.setSite(site);
        m.setScanMode(scanMode == null ? ScanMode.LANDSCAPE : scanMode);
        m.setMissionType(missionType == null ? MissionType.SURVEY_3D : missionType);
        if (cameraModule != null && !cameraModule.isBlank()) m.setCameraModule(cameraModule);
        m.setState(MissionState.IDLE);
        m = missions.save(m);
        transition(m, MissionState.AREA_SELECTED, "Crude scan area selected");

        ScanArea area = new ScanArea();
        area.setMission(m);
        area.setPassType(PassType.CRUDE);
        area.setPolygonGeoJson(polygonGeoJson);
        area.setAltitudeM(altitudeM);
        area.setLineSpacingM(lineSpacingM);
        area.setSpeedMps(speedMps);
        area.setTiltDeg(-30.0);   // crude pass: ring tipped to swathe the terrain (roll mount)
        try {
            // Crude pass always flies the fixed downward lidar over the area, regardless of scan mode.
            area.setRouteJson(routePlanner.plan(polygonGeoJson, lineSpacingM, speedMps, altitudeM).routeGeoJson());
        } catch (IllegalArgumentException e) {
            log.warn("Crude route planning skipped: {}", e.getMessage());
        }
        scanAreas.save(area);
        return m;
    }

    @Transactional
    public void launchCrude(UUID missionId) {
        Mission m = get(missionId);
        ScanArea area = scanAreas.findByMissionIdAndPassType(missionId, PassType.CRUDE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No crude scan area defined"));
        transition(m, MissionState.CRUDE_MISSION_UPLOADED, "Crude mission uploading to vehicle");
        SensorType crudeSensor = m.getMissionType() == MissionType.WILDLIFE_SURVEY
                ? SensorType.WILDLIFE_CAM : SensorType.FIXED_3D;
        connector.uploadMission(plan(m, area, crudeSensor));
    }

    /**
     * Store the refined detail zone and compute the optimal route for it —
     * strategy depends on the mounted payload: LANDSCAPE -> boustrophedon coverage,
     * STRUCTURE -> stacked orbit loops around the footprint at standoff distance
     * (altitudeM doubles as the structure's top height to cover).
     */
    @Transactional
    public RoutePlanner.PlannedRoute submitDetailArea(UUID missionId, String polygonGeoJson,
                                 double altitudeM, double lineSpacingM, double speedMps,
                                 boolean terrainFollowing, ScanMode scanMode, double standoffM,
                                 double tiltDeg) {
        Mission m = get(missionId);
        if (scanMode != null) m.setScanMode(scanMode);   // operator can re-select the payload here
        RoutePlanner.PlannedRoute route = m.getScanMode() == ScanMode.STRUCTURE
                ? routePlanner.planStructure(polygonGeoJson, lineSpacingM, speedMps, standoffM, altitudeM)
                : routePlanner.plan(polygonGeoJson, lineSpacingM, speedMps, altitudeM);
        transition(m, MissionState.DETAIL_SELECTED, "%s route planned (%d %s, %.0f m)"
                .formatted(m.getScanMode(), route.lineCount(),
                        m.getScanMode() == ScanMode.STRUCTURE ? "levels" : "lines", route.lengthM()));
        ScanArea area = scanAreas.findByMissionIdAndPassType(missionId, PassType.DETAILED).orElseGet(ScanArea::new);
        area.setMission(m);
        area.setPassType(PassType.DETAILED);
        area.setPolygonGeoJson(polygonGeoJson);
        area.setAltitudeM(altitudeM);
        area.setLineSpacingM(lineSpacingM);
        area.setSpeedMps(speedMps);
        area.setTerrainFollowing(terrainFollowing);
        area.setStandoffM(standoffM);
        area.setTiltDeg(tiltDeg);
        area.setRouteJson(route.routeGeoJson());
        scanAreas.save(area);
        return route;
    }

    @Transactional
    public void launchDetail(UUID missionId) {
        Mission m = get(missionId);
        ScanArea area = scanAreas.findByMissionIdAndPassType(missionId, PassType.DETAILED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No detail zone defined"));
        transition(m, MissionState.DETAIL_MISSION_UPLOADED,
                "Detailed mission uploading to vehicle (" + m.getScanMode() + " payload)");
        SensorType detailSensor = m.getScanMode() == ScanMode.STRUCTURE
                ? SensorType.FRONT_360_VERTICAL : SensorType.SPINNING_TILTED;
        connector.uploadMission(plan(m, area, detailSensor));
    }

    /** Missions whose current flight already delivered drone-detected plants. */
    private final java.util.Set<UUID> dronePlantsThisFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * DETECTION RESCAN: after reviewing the plant map at PREVIEW_READY, the
     * operator can send the drone BACK over a chosen sub-area to find missed
     * plants. New detections MERGE into the list (dedup against existing);
     * the mission returns to PREVIEW_READY for verification. Repeat until the
     * operator is satisfied, then launch the inspection as usual.
     */
    @Transactional
    public void launchDetectionRescan(UUID missionId, String rescanPolygonJson,
                                      double altitudeM, double speedMps) {
        Mission m = get(missionId);
        if (m.getMissionType() != MissionType.CROP_HEALTH)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Detection rescan is for crop-health missions");
        if (m.getState() != MissionState.PREVIEW_READY)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Review the plant map first (PREVIEW_READY)");
        ScanArea area = scanAreas.findByMissionIdAndPassType(missionId, PassType.CRUDE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No crude area"));
        area.setRescanPolygonGeoJson(rescanPolygonJson);
        area.setAltitudeM(altitudeM);
        area.setSpeedMps(speedMps);
        scanAreas.save(area);
        dronePlantsThisFlight.remove(missionId);
        record(m, "DETECTION_RESCAN", "{\"altitudeM\":" + altitudeM + "}");
        transition(m, MissionState.CRUDE_MISSION_UPLOADED,
                "Detection rescan uploading — new finds will merge into the plant list");
        connector.uploadMission(planRescan(m, area));
    }

    /** The rescan plan: same crude sensor, but ONLY over the sub-polygon. */
    private MissionPlan planRescan(Mission m, ScanArea area) {
        return new MissionPlan(m.getId(), PassType.CRUDE, SensorType.FIXED_3D, m.getMissionType(),
                m.getCameraModule(), m.getSurveyEnvironment(),
                area.getRescanPolygonGeoJson(), area.getAltitudeM(),
                area.getLineSpacingM(), area.getSpeedMps(), area.isTerrainFollowing(),
                area.getTiltDeg(), null);
    }

    /** Toggle a plant in/out of the inspection flight plan. */
    @Transactional
    public void setPlantExcluded(UUID missionId, UUID plantId, boolean excluded) {
        Mission m = get(missionId);
        Plant p = plants.findById(plantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such plant"));
        if (!p.getMission().getId().equals(m.getId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Plant belongs to another mission");
        p.setExcluded(excluded);
        p.setStatus(excluded ? "EXCLUDED" : "DETECTED");
        plants.save(p);
    }

    /**
     * FOLLOW-UP inspection: months later, re-check the SAME plants. Creates a
     * new CROP_HEALTH mission linked to the parent, copies its plant list
     * (positions, labels, exclusions — statuses reset to DETECTED) and its
     * crude polygon, and starts directly at PREVIEW_READY: no crude pass is
     * flown, the operator reviews/excludes and launches the inspection. The
     * follow-up report then shows NEW / PERSISTS / RESOLVED per finding.
     */
    @Transactional
    public Mission createFollowUp(UUID parentId, String name) {
        Mission parent = get(parentId);
        if (parent.getMissionType() != MissionType.CROP_HEALTH)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Follow-ups are for crop-health missions");
        if (parent.getState() != MissionState.COMPLETE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Parent mission is not complete");
        List<Plant> parentPlants = plants.findByMissionIdOrderByLabelAsc(parentId);
        if (parentPlants.isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Parent has no plants to re-check");

        Mission m = new Mission();
        m.setName(name == null || name.isBlank() ? parent.getName() + " — follow-up" : name);
        m.setSite(parent.getSite());
        m.setMissionType(MissionType.CROP_HEALTH);
        m.setScanMode(parent.getScanMode());
        m.setCameraModule(parent.getCameraModule());
        m.setParentMission(parent);
        m.setState(MissionState.PREVIEW_READY);      // crude pass skipped by design
        m = missions.save(m);

        ScanArea parentArea = scanAreas.findByMissionIdAndPassType(parentId, PassType.CRUDE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Parent has no crude area"));
        ScanArea area = new ScanArea();
        area.setMission(m);
        area.setPassType(PassType.CRUDE);
        area.setPolygonGeoJson(parentArea.getPolygonGeoJson());
        area.setAltitudeM(parentArea.getAltitudeM());
        area.setLineSpacingM(parentArea.getLineSpacingM());
        area.setSpeedMps(parentArea.getSpeedMps());
        area.setTiltDeg(parentArea.getTiltDeg());
        scanAreas.save(area);

        for (Plant pp : parentPlants) {
            Plant p = new Plant();
            p.setMission(m);
            p.setLabel(pp.getLabel());
            p.setKind(pp.getKind());
            p.setLat(pp.getLat());
            p.setLon(pp.getLon());
            p.setHeightM(pp.getHeightM());
            p.setCanopyDiaM(pp.getCanopyDiaM());
            p.setHealthIndex(pp.getHealthIndex());
            p.setExcluded(pp.isExcluded());
            p.setStatus(pp.isExcluded() ? "EXCLUDED" : "DETECTED");
            plants.save(p);
        }
        record(m, "FOLLOW_UP_CREATED", "{\"parent\":\"" + parentId + "\",\"plants\":"
                + parentPlants.size() + "}");
        return m;
    }

    @Transactional
    public void attachSightingPhoto(UUID missionId, String label, java.nio.file.Path file) {
        wildlife.attachPhoto(get(missionId), label, file);
    }

    /** Drone photo ingest: representative close-up per plant (by route index). */
    @Transactional
    public void attachPlantPhoto(UUID missionId, int plantIndex, java.nio.file.Path file) {
        Mission m = get(missionId);
        // Waypoint index -> entity via the SAME deterministic route plan the
        // route was built from (a CROP_FIELD zone owns several indices; the
        // first photo wins as its representative).
        java.util.List<java.util.Map.Entry<Plant, double[]>> plan;
        try { plan = crop.routePlan(m); } catch (IllegalStateException e) { return; }
        if (plantIndex < 0 || plantIndex >= plan.size()) return;
        Plant p = plan.get(plantIndex).getKey();
        if (p.getPhotoObjectKey() != null && p.getKind() == com.skyscan.domain.PlantKind.CROP_FIELD) {
            try { Files.deleteIfExists(file); } catch (Exception ignore) { }
            return;                                   // keep the first sample's photo
        }
        try {
            String key = "plants/" + m.getId() + "/" + p.getLabel() + ".jpg";
            storage.uploadFile(key, file, "image/jpeg");
            Files.deleteIfExists(file);
            p.setPhotoObjectKey(key);
            plants.save(p);
        } catch (Exception e) {
            log.warn("plant photo store failed: {}", e.getMessage());
        }
    }

    /**
     * CROP_HEALTH detail pass: build the per-plant inspection route from the
     * plants detected in the crude pass and fly it. Reuses the detail-pass
     * plumbing (route executor legs + battery-swap resume), so inspecting many
     * plants across many battery packs needs no special handling here.
     */
    @Transactional
    public void launchInspection(UUID missionId, double inspectAltM, double speedMps) {
        Mission m = get(missionId);
        if (m.getMissionType() != MissionType.CROP_HEALTH)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Not a crop-health mission");
        if (plants.countByMissionId(missionId) == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No detected plants to inspect");
        String routeJson = crop.buildInspectionRoute(m, inspectAltM, speedMps);
        ScanArea area = scanAreas.findByMissionIdAndPassType(missionId, PassType.DETAILED).orElseGet(ScanArea::new);
        area.setMission(m);
        area.setPassType(PassType.DETAILED);
        area.setPolygonGeoJson(scanAreas.findByMissionIdAndPassType(missionId, PassType.CRUDE)
                .orElseThrow().getPolygonGeoJson());
        area.setAltitudeM(inspectAltM);
        area.setSpeedMps(speedMps);
        area.setTiltDeg(0.0);                  // level ring: canopy profile under each close-up
        area.setRouteJson(routeJson);
        scanAreas.save(area);
        transition(m, MissionState.DETAIL_SELECTED, "Inspection route planned ("
                + plants.countByMissionId(missionId) + " plants)");
        transition(m, MissionState.DETAIL_MISSION_UPLOADED, "Inspection pass uploading to vehicle");
        connector.uploadMission(plan(m, area, SensorType.PLANT_INSPECT));
    }

    @Transactional
    public void abort(UUID missionId, String reason) {
        Mission m = get(missionId);
        if (!fsm.isAbortable(m.getState()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mission not abortable in state " + m.getState());
        transition(m, MissionState.RETURN_TO_HOME, "Abort requested: " + reason);
        connector.abortMission(missionId, reason);
    }

    // ------------------------------------------------------------------ connector events

    @Override
    @Transactional
    public void onMissionAccepted(UUID missionId) {
        // Vehicle acked the upload; start immediately (demo behavior — a real service might wait for operator arm).
        connector.startMission(missionId);
    }

    @Override
    @Transactional
    public void onTakeoff(UUID missionId) {
        Mission m = get(missionId);
        MissionState flying = m.getState() == MissionState.CRUDE_MISSION_UPLOADED
                ? MissionState.CRUDE_FLYING : MissionState.DETAIL_FLYING;
        transition(m, flying, "Vehicle airborne");
    }

    @Override
    public void onTelemetry(TelemetrySample s) {
        // Telemetry is ephemeral: streamed live, not persisted row-by-row.
        publisher.publish(s.missionId(), "telemetry", s);
    }

    @Override
    @Transactional
    public void onLanded(ScanUpload upload) {
        Mission m = get(upload.missionId());
        try {
            boolean crude = upload.passType() == PassType.CRUDE;
            transition(m, crude ? MissionState.CRUDE_LANDED : MissionState.DETAIL_LANDED, "Vehicle landed, scan received");

            // Push raw scan to object storage. For CROP_HEALTH / WILDLIFE_SURVEY
            // the lidar flies for OBSTACLE AVOIDANCE ONLY — there is no point
            // cloud deliverable, and the agent may not send a scan at all.
            String rawKey = null;
            if (upload.scanFile() != null) {
                rawKey = "raw/%s/%s_%s.las".formatted(m.getId(), upload.passType().name().toLowerCase(), Instant.now().getEpochSecond());
                storage.uploadFile(rawKey, upload.scanFile(), "application/octet-stream");
                Files.deleteIfExists(upload.scanFile());
            }

            // Customer photos from the onboard camera -> photos/{missionId}/
            if (upload.photoFiles() != null) {
                int i = 0;
                for (java.nio.file.Path ph : upload.photoFiles()) {
                    String key = "photos/%s/%s_%03d.jpg".formatted(
                            m.getId(), upload.passType().name().toLowerCase(), i++);
                    storage.uploadFile(key, ph, "image/jpeg");
                    Files.deleteIfExists(ph);
                }
                if (i > 0) record(m, "INFO", "{\"photos\":" + i + "}");
            }
            if (rawKey != null) {

                Acquisition acq = new Acquisition();
                acq.setMission(m);
                acq.setPassType(upload.passType());
                acq.setSensorType(upload.sensorType());
                acq.setRawObjectKey(rawKey);
                acq.setGnssImuMetaJson(upload.gnssImuMetaJson());
                acquisitions.save(acq);
            }

            if (crude) {
                if (upload.previewFile() != null) {
                    String previewKey = "previews/" + m.getId() + "/crude_preview.png";
                    storage.uploadFile(previewKey, upload.previewFile(), "image/png");
                    Files.deleteIfExists(upload.previewFile());
                    m.setPreviewObjectKey(previewKey);
                }
                if (m.getMissionType() == MissionType.WILDLIFE_SURVEY) {
                    // Single-pass: animals move — tag, report, done.
                    // Prefer sightings the drone streamed/uploaded; stub only
                    // as a fallback for the simulated connector.
                    int n = wildlife.hasSightings(m)
                            ? (int) wildlife.countSightings(m)
                            : wildlife.generateStubSightings(m);
                    record(m, "ANIMALS_TAGGED", "{\"count\":" + n + "}");
                    transition(m, MissionState.PROCESSING,
                            n + " animals tagged — generating survey report");
                    String reportKey = wildlife.buildAndStoreReport(m);
                    m.setReportObjectKey(reportKey);
                    missions.save(m);
                    record(m, "REPORT_READY", "{\"key\":\"" + reportKey + "\"}");
                    transition(m, MissionState.COMPLETE, "Wildlife survey report ready");
                } else if (m.getMissionType() == MissionType.CROP_HEALTH) {
                    ScanArea crudeArea = scanAreas.findByMissionIdAndPassType(m.getId(), PassType.CRUDE)
                            .orElse(null);
                    String rescanPoly = crudeArea == null ? null : crudeArea.getRescanPolygonGeoJson();
                    if (rescanPoly != null) {
                        // Rescan landing: MERGE. If the drone already ingested
                        // (merge path in ingestDronePlants), just count; else
                        // the stub generates missed plants in the sub-area.
                        int added = dronePlantsThisFlight.remove(m.getId())
                                ? 0 : crop.detectMorePlants(m, rescanPoly);
                        crudeArea.setRescanPolygonGeoJson(null);
                        scanAreas.save(crudeArea);
                        int total = (int) plants.countByMissionId(m.getId());
                        record(m, "PLANTS_MERGED", "{\"added\":" + added + ",\"total\":" + total + "}");
                        transition(m, MissionState.PREVIEW_READY,
                                total + " plants total after rescan — verify, rescan again, or launch inspection");
                    } else {
                        int n = crop.hasPlants(m) ? (int) plants.countByMissionId(m.getId())
                                                  : crop.detectPlants(m);
                        dronePlantsThisFlight.remove(m.getId());
                        record(m, "PLANTS_DETECTED", "{\"count\":" + n + "}");
                        transition(m, MissionState.PREVIEW_READY,
                                n + " plants detected — review, then launch the inspection pass");
                    }
                } else {
                    transition(m, MissionState.PREVIEW_READY, "Field preview available");
                }
            } else if (m.getMissionType() == MissionType.CROP_HEALTH) {
                // Inspection pass done: analyze the visited plants + render the report.
                transition(m, MissionState.PROCESSING, "Inspection complete, analyzing plant health");
                // Use on-board classifier findings if the drone supplied them;
                // otherwise run the stub analyzer (simulated connector).
                if (!crop.hasFindings(m)) crop.analyzeInspection(m);
                String reportKey = crop.buildAndStoreReport(m);
                m.setReportObjectKey(reportKey);
                missions.save(m);
                record(m, "REPORT_READY", "{\"key\":\"" + reportKey + "\"}");
                transition(m, MissionState.COMPLETE, "Crop health report ready");
            } else {
                transition(m, MissionState.PROCESSING, "Raw scans stored, processing dispatched");
                processing.dispatchMergeJob(m, acquisitions.findByMissionIdOrderByCapturedAtAsc(m.getId()));
            }
        } catch (Exception e) {
            log.error("Landing handling failed for mission {}", m.getId(), e);
            fail(m, "Landing handling failed: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public void onAborted(UUID missionId, String reason) {
        Mission m = get(missionId);
        transition(m, MissionState.ABORTED, "Vehicle returned home: " + reason);
    }

    @Override
    @Transactional
    public void onFailure(UUID missionId, String error) {
        fail(get(missionId), error);
    }

    @Override
    public void onSelfTestReport(UUID missionId, String reportJson) {
        if (DroneCheckService.CHECK_ID.equals(missionId)) {
            droneCheck.storeSelfTest(reportJson);      // maintenance channel, no mission
            return;
        }
        Mission m = get(missionId);
        record(m, "SELFTEST", reportJson);
        publisher.publish(missionId, "selftest", reportJson);
        log.info("Self-test report stored for mission {}", missionId);
    }

    @Override
    public void onFlightLog(UUID missionId, java.nio.file.Path logFile) {
        Mission m = get(missionId);
        try {
            String key = "logs/%s/flight-%d.jsonl".formatted(missionId, Instant.now().getEpochSecond());
            storage.uploadFile(key, logFile, "application/x-ndjson");
            Files.deleteIfExists(logFile);
            record(m, "INFO", "{\"flightLog\":\"" + key + "\"}");
            publisher.publish(missionId, "flightlog", Map.of("key", key));
            log.info("Flight log stored for mission {}: {}", missionId, key);
        } catch (Exception e) {
            log.error("Flight log storage failed for {}", missionId, e);
        }
    }

    @Override
    public void onDroneCheckEvent(UUID checkId, String type, String payloadJson) {
        droneCheck.onHoverEvent(type, payloadJson);
    }

    @Transactional
    public int ingestDronePlants(UUID missionId, com.fasterxml.jackson.databind.JsonNode plantsJson) {
        Mission m = get(missionId);
        dronePlantsThisFlight.add(missionId);
        ScanArea crudeArea = scanAreas.findByMissionIdAndPassType(missionId, PassType.CRUDE)
                .orElse(null);
        boolean rescan = crudeArea != null && crudeArea.getRescanPolygonGeoJson() != null;
        return rescan ? crop.mergeDetectedPlants(m, plantsJson)
                      : crop.ingestDetectedPlants(m, plantsJson);
    }

    @Transactional
    public int ingestDroneFindings(UUID missionId, com.fasterxml.jackson.databind.JsonNode findings) {
        return crop.ingestFindings(get(missionId), findings);
    }

    @Transactional
    public int ingestDroneSightings(UUID missionId, com.fasterxml.jackson.databind.JsonNode sightings) {
        int n = wildlife.ingestSightings(get(missionId), sightings);
        publisher.publish(missionId, "sightings", Map.of("count", n));
        return n;
    }

    /** Live single-sighting relay: the agent (or stub) streams these DURING the
     * pass. Persisted immediately so the landing report is built from exactly
     * the tags the console showed live (the real agent's landing upload then
     * replaces the set with its authoritative list). */
    @Transactional
    public void onLiveSighting(UUID missionId, com.fasterxml.jackson.databind.JsonNode sighting) {
        Mission m = get(missionId);
        wildlife.ingestOne(m, sighting);
        publisher.publish(missionId, "animal", sighting);
        record(m, "ANIMAL_SIGHTED", sighting.toString());
    }

    @Override
    public void onLiveAnimalSighting(UUID missionId, String sightingJson) {
        try {
            onLiveSighting(missionId, new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(sightingJson));
        } catch (Exception e) {
            log.warn("bad live sighting payload: {}", e.getMessage());
        }
    }


    /** Remote diagnostics trigger — allowed in any mission state (ground checks only). */
    public void requestSelfTest(UUID missionId) {
        get(missionId);
        connector.requestSelfTest(missionId);
    }

    // ------------------------------------------------------------------ processing outcomes (called by ProcessingService)

    @Transactional
    public void onProcessingComplete(UUID missionId) {
        transition(get(missionId), MissionState.COMPLETE, "Deliverables ready");
    }

    @Transactional
    public void onProcessingFailed(UUID missionId, String error) {
        fail(get(missionId), "Processing failed: " + error);
    }

    // ------------------------------------------------------------------ internals

    public Mission get(UUID missionId) {
        return missions.findById(missionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mission not found"));
    }

    private MissionPlan plan(Mission m, ScanArea area, SensorType sensor) {
        return new MissionPlan(m.getId(), area.getPassType(), sensor, m.getMissionType(),
                m.getCameraModule(), m.getSurveyEnvironment(),
                area.getPolygonGeoJson(), area.getAltitudeM(), area.getLineSpacingM(),
                area.getSpeedMps(), area.isTerrainFollowing(), area.getTiltDeg(), area.getRouteJson());
    }

    private void transition(Mission m, MissionState to, String note) {
        fsm.assertTransition(m.getState(), to);
        MissionState from = m.getState();
        m.setState(to);
        missions.save(m);
        record(m, "STATE_CHANGE", "{\"from\":\"%s\",\"to\":\"%s\",\"note\":\"%s\"}".formatted(from, to, note));
        publisher.publish(m.getId(), "state", Map.of("state", to.name(), "note", note, "at", Instant.now().toString()));
        log.info("Mission {}: {} -> {} ({})", m.getId(), from, to, note);
    }

    private void fail(Mission m, String error) {
        m.setLastError(error);
        m.setState(MissionState.FAILED);
        missions.save(m);
        record(m, "ERROR", "{\"error\":\"" + error.replace("\"", "'") + "\"}");
        publisher.publish(m.getId(), "state", Map.of("state", "FAILED", "note", error, "at", Instant.now().toString()));
        log.error("Mission {} FAILED: {}", m.getId(), error);
    }

    private void record(Mission m, String type, String payload) {
        MissionEvent e = new MissionEvent();
        e.setMission(m);
        e.setType(type);
        e.setPayloadJson(payload);
        events.save(e);
    }
}
