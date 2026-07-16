package com.skyscan.mission;

import com.skyscan.connector.*;
import com.skyscan.domain.*;
import com.skyscan.live.MissionEventPublisher;
import com.skyscan.processing.ProcessingService;
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

    public MissionService(MissionRepo missions, ScanAreaRepo scanAreas, AcquisitionRepo acquisitions,
                          MissionEventRepo events, MissionStateMachine fsm, DroneConnector connector,
                          StorageService storage, MissionEventPublisher publisher, ProcessingService processing,
                          RoutePlanner routePlanner) {
        this.missions = missions; this.scanAreas = scanAreas; this.acquisitions = acquisitions;
        this.events = events; this.fsm = fsm; this.connector = connector;
        this.storage = storage; this.publisher = publisher; this.processing = processing;
        this.routePlanner = routePlanner;
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
        Mission m = new Mission();
        m.setName(name);
        m.setSite(site);
        m.setScanMode(scanMode == null ? ScanMode.LANDSCAPE : scanMode);
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
        connector.uploadMission(plan(m, area, SensorType.FIXED_3D));
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

            // Push raw scan to object storage.
            String rawKey = "raw/%s/%s_%s.las".formatted(m.getId(), upload.passType().name().toLowerCase(), Instant.now().getEpochSecond());
            storage.uploadFile(rawKey, upload.scanFile(), "application/octet-stream");
            Files.deleteIfExists(upload.scanFile());

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

            Acquisition acq = new Acquisition();
            acq.setMission(m);
            acq.setPassType(upload.passType());
            acq.setSensorType(upload.sensorType());
            acq.setRawObjectKey(rawKey);
            acq.setGnssImuMetaJson(upload.gnssImuMetaJson());
            acquisitions.save(acq);

            if (crude) {
                // Field preview. The preview artifact itself is a bundled sample (STUB),
                // but the flow — store it, flip state, notify the UI — is real.
                String previewKey = "previews/" + m.getId() + "/crude_preview.png";
                if (upload.previewFile() != null) {
                    storage.uploadFile(previewKey, upload.previewFile(), "image/png");
                    Files.deleteIfExists(upload.previewFile());
                }
                m.setPreviewObjectKey(previewKey);
                transition(m, MissionState.PREVIEW_READY, "Field preview available");
            } else {
                // Final landing -> dispatch processing for all acquisitions of this mission.
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
        return new MissionPlan(m.getId(), area.getPassType(), sensor, area.getPolygonGeoJson(),
                area.getAltitudeM(), area.getLineSpacingM(), area.getSpeedMps(), area.isTerrainFollowing(),
                area.getTiltDeg(), area.getRouteJson());
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
