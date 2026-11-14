package com.skyscan.connector.jetson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyscan.connector.ScanUpload;
import com.skyscan.connector.TelemetrySample;
import com.skyscan.domain.PassType;
import com.skyscan.domain.SensorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /internal/drone — the agent-facing gateway (shared-secret guarded like all
 * internal endpoints). The Jetson polls commands and pushes events/scans here;
 * this controller translates them onto the DroneConnectorListener, i.e. the
 * same orchestrator path the stub uses.
 */
@RestController
@RequestMapping("/internal/drone")
@ConditionalOnProperty(name = "skyscan.connector.impl", havingValue = "jetson")
public class DroneGatewayController {

    private static final Logger log = LoggerFactory.getLogger(DroneGatewayController.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final JetsonDroneConnector connector;

    public DroneGatewayController(JetsonDroneConnector connector) { this.connector = connector; }

    @GetMapping(value = "/commands", produces = MediaType.APPLICATION_JSON_VALUE)
    public String commands(@RequestParam String droneId) {
        List<String> cmds = connector.drainCommands();
        return "[" + String.join(",", cmds) + "]";
    }

    @PostMapping("/events")
    public Map<String, String> event(@RequestBody JsonNode body) {
        String type = body.path("type").asText();
        UUID missionId = UUID.fromString(body.path("missionId").asText());
        JsonNode p = body.path("payload");
        var l = connector.listener();
        switch (type) {
            case "ACCEPTED" -> l.onMissionAccepted(missionId);
            case "TAKEOFF" -> l.onTakeoff(missionId);
            case "TELEMETRY" -> l.onTelemetry(new TelemetrySample(missionId, Instant.now(),
                    p.path("lat").asDouble(), p.path("lon").asDouble(), p.path("altM").asDouble(),
                    p.path("speedMps").asDouble(), p.path("batteryPct").asDouble(),
                    p.path("progressPct").asDouble(), p.path("flightMode").asText("AUTO")));
            case "ABORTED" -> l.onAborted(missionId, p.path("reason").asText("agent abort"));
            case "SELFTEST_REPORT" -> l.onSelfTestReport(missionId, p.toString());
            case "HOVER_STATUS", "HOVER_EVENT" -> l.onDroneCheckEvent(missionId, type, p.toString());
            case "FAILURE" -> l.onFailure(missionId, p.path("error").asText("agent failure"));
            // Operational events (BATTERY_SWAP, OBSTACLE_STOP/CLEAR, LEG_START, TILT_SET,
            // RESUMING, ROUTE_COMPLETE, LANDED-ack) ride the telemetry channel as flightMode
            // context or are informational; LANDED-with-scan arrives via /scans below.
            default -> log.info("agent event {} for {}: {}", type, missionId, p);
        }
        return Map.of("ok", "true");
    }

    /** Full-detail JSONL flight log from the agent. */
    @PostMapping(value = "/logs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> flightLog(@RequestParam String droneId,
                                         @RequestParam String missionId,
                                         @RequestPart("log") MultipartFile logFile) throws Exception {
        Path tmp = Files.createTempFile("skyscan-agent-log-", ".jsonl");
        logFile.transferTo(tmp);
        log.info("flight log received from {} for mission {} ({} bytes)", droneId, missionId, logFile.getSize());
        connector.listener().onFlightLog(UUID.fromString(missionId), tmp);
        return Map.of("ok", "true");
    }

    /** LANDED + product: the raw LAS, optional preview, and customer photos. */
    @PostMapping(value = "/scans", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> scan(@RequestParam String droneId,
                                    @RequestParam String missionId,
                                    @RequestParam String passType,
                                    @RequestParam String sensorType,
                                    @RequestParam(required = false) String gnssImuMetaJson,
                                    @RequestPart(value = "scan", required = false) MultipartFile scan,
                                    @RequestPart(value = "preview", required = false) MultipartFile preview,
                                    @RequestPart(value = "photos", required = false) List<MultipartFile> photos)
            throws Exception {
        // Cloudless missions (crop/wildlife: lidar = avoidance only) send no scan.
        Path scanTmp = null;
        if (scan != null && !scan.isEmpty()) {
            scanTmp = Files.createTempFile("skyscan-agent-scan-", ".las");
            scan.transferTo(scanTmp);
        }
        Path previewTmp = null;
        if (preview != null && !preview.isEmpty()) {
            previewTmp = Files.createTempFile("skyscan-agent-preview-", ".png");
            preview.transferTo(previewTmp);
        }
        List<Path> photoTmps = new ArrayList<>();
        if (photos != null) {
            for (MultipartFile ph : photos) {
                Path t = Files.createTempFile("skyscan-agent-photo-", ".jpg");
                ph.transferTo(t);
                photoTmps.add(t);
            }
        }
        log.info("landing received from {} for mission {} ({}, {} photos)",
                droneId, missionId, scanTmp == null ? "no cloud" : scan.getSize() + " bytes",
                photoTmps.size());
        connector.listener().onLanded(new ScanUpload(UUID.fromString(missionId),
                PassType.valueOf(passType), SensorType.valueOf(sensorType),
                scanTmp, previewTmp, gnssImuMetaJson == null ? "{}" : gnssImuMetaJson, photoTmps));
        return Map.of("ok", "true");
    }
}
