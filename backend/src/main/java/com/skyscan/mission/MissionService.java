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
                                 double altitudeM, double lineSpacingM, double speedMps) {
        Mission m = new Mission();
        m.setName(name);
        m.setSite(site);
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
        try {
            area.setRouteJson(routePlanner.plan(polygonGeoJson, lineSpacingM, speedMps).routeGeoJson());
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

    /** Store the refined detail zone and compute the optimal coverage route for it. */
    @Transactional
    public RoutePlanner.PlannedRoute submitDetailArea(UUID missionId, String polygonGeoJson,
                                 double altitudeM, double lineSpacingM, double speedMps, boolean terrainFollowing) {
        Mission m = get(missionId);
        RoutePlanner.PlannedRoute route = routePlanner.plan(polygonGeoJson, lineSpacingM, speedMps);
        transition(m, MissionState.DETAIL_SELECTED, "Detail zone refined; route planned (%d lines, %.0f m)"
                .formatted(route.lineCount(), route.lengthM()));
        ScanArea area = scanAreas.findByMissionIdAndPassType(missionId, PassType.DETAILED).orElseGet(ScanArea::new);
        area.setMission(m);
        area.setPassType(PassType.DETAILED);
        area.setPolygonGeoJson(polygonGeoJson);
        area.setAltitudeM(altitudeM);
        area.setLineSpacingM(lineSpacingM);
        area.setSpeedMps(speedMps);
        area.setTerrainFollowing(terrainFollowing);
        area.setRouteJson(route.routeGeoJson());
        scanAreas.save(area);
        return route;
    }

    @Transactional
    public void launchDetail(UUID missionId) {
        Mission m = get(missionId);
        ScanArea area = scanAreas.findByMissionIdAndPassType(missionId, PassType.DETAILED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No detail zone defined"));
        transition(m, MissionState.DETAIL_MISSION_UPLOADED, "Detailed mission uploading to vehicle");
        connector.uploadMission(plan(m, area, SensorType.SPINNING_TILTED));
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
                area.getRouteJson());
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
