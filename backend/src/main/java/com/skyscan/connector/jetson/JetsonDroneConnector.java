package com.skyscan.connector.jetson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyscan.connector.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * REAL DroneConnector for the Jetson Orin Nano companion agent.
 *
 * Transport is pull-based on purpose: the drone (behind NAT/LTE) polls
 * GET /internal/drone/commands and POSTs events/scans back — no inbound
 * connectivity to the aircraft required. This class only queues commands and
 * relays agent events to the mission orchestrator; all flight intelligence
 * lives on the vehicle. Select with DRONE_CONNECTOR=jetson; nothing else in
 * the system changes (that was the whole point of the interface).
 */
@Component
@ConditionalOnProperty(name = "skyscan.connector.impl", havingValue = "jetson")
public class JetsonDroneConnector implements DroneConnector {

    private static final Logger log = LoggerFactory.getLogger(JetsonDroneConnector.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final Queue<String> commands = new ConcurrentLinkedQueue<>();
    private volatile DroneConnectorListener listener;

    @Override public String describe() { return "JetsonDroneConnector (pull-based agent gateway)"; }
    @Override public void setListener(DroneConnectorListener l) { this.listener = l; }
    DroneConnectorListener listener() { return listener; }

    @Override
    public void uploadMission(MissionPlan plan) {
        ObjectNode cmd = M.createObjectNode();
        cmd.put("type", "UPLOAD_MISSION");
        ObjectNode p = cmd.putObject("plan");
        p.put("missionId", plan.missionId().toString());
        p.put("passType", plan.passType().name());
        p.put("sensorType", plan.sensorType().name());
        p.put("missionType", plan.missionType().name());
        if (plan.cameraModule() != null) p.put("cameraModule", plan.cameraModule());
        if (plan.surveyEnvironment() != null) p.put("surveyEnvironment", plan.surveyEnvironment());
        p.put("polygonGeoJson", plan.polygonGeoJson());
        p.put("altitudeM", plan.altitudeM());
        p.put("lineSpacingM", plan.lineSpacingM());
        p.put("speedMps", plan.speedMps());
        p.put("terrainFollowing", plan.terrainFollowing());
        p.put("tiltDeg", plan.tiltDeg());
        if (plan.routeJson() != null) p.put("routeJson", plan.routeJson());
        commands.add(cmd.toString());
        log.info("queued UPLOAD_MISSION for {} ({} / {})", plan.missionId(), plan.passType(), plan.sensorType());
    }

    @Override
    public void startMission(UUID missionId) {
        commands.add("{\"type\":\"START\",\"missionId\":\"" + missionId + "\"}");
    }

    /** Battery-swap continuation from the console reuses START; explicit RESUME also supported. */
    public void resumeMission(UUID missionId) {
        commands.add("{\"type\":\"RESUME\",\"missionId\":\"" + missionId + "\"}");
    }

    @Override
    public void requestSelfTest(UUID missionId) {
        commands.add("{\"type\":\"SELF_TEST\",\"missionId\":\"" + missionId + "\"}");
        log.info("queued SELF_TEST for mission {}", missionId);
    }

    @Override
    public void abortMission(UUID missionId, String reason) {
        commands.add("{\"type\":\"ABORT\",\"missionId\":\"" + missionId + "\",\"reason\":\""
                + reason.replace("\"", "'") + "\"}");
    }

    @Override
    public void startHoverTest(UUID checkId, double heightM, double durationS) {
        commands.add("{\"type\":\"HOVER_TEST\",\"checkId\":\"" + checkId
                + "\",\"heightM\":" + heightM + ",\"durationS\":" + durationS + "}");
        log.info("queued HOVER_TEST h={} d={}", heightM, durationS);
    }

    @Override
    public void abortHoverTest(UUID checkId) {
        commands.add("{\"type\":\"HOVER_ABORT\",\"checkId\":\"" + checkId + "\"}");
    }

    /** Drained by the gateway controller when the agent polls. */
    public java.util.List<String> drainCommands() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String c;
        while ((c = commands.poll()) != null) out.add(c);
        return out;
    }
}
