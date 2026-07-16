package com.skyscan.connector;

import java.util.UUID;

/**
 * ==============================================================================
 *  THE stub/real hardware boundary.
 *
 *  Everything that would talk to the Jetson Orin Nano / Pixhawk lives behind
 *  this interface. The backend never imports anything hardware-specific.
 *
 *  Today: {@link com.skyscan.connector.stub.StubDroneConnector} (simulation).
 *  Later: implement this interface with real MAVLink/ROS transport, register it
 *  as a Spring bean, and select it with the `skyscan.connector.impl` property.
 *  Nothing else in the system changes.
 * ==============================================================================
 */
public interface DroneConnector {

    /** Push a compiled mission to the vehicle. Asynchronous; ack arrives via onMissionAccepted. */
    void uploadMission(MissionPlan plan);

    /** Start the most recently uploaded mission. */
    void startMission(UUID missionId);

    /** Abort in-flight; vehicle returns to home. */
    void abortMission(UUID missionId, String reason);

    /**
     * Remote diagnostics: run the on-vehicle self-test suite (ground, props off)
     * and report back via {@link DroneConnectorListener#onSelfTestReport}.
     * Makes initial setup and pre-flight verification fully remote.
     */
    void requestSelfTest(UUID missionId);

    /** Backend subscribes exactly once at startup. */
    void setListener(DroneConnectorListener listener);

    /** Human-readable connector identity for logs/health. */
    String describe();
}
