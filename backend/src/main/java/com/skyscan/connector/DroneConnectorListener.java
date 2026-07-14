package com.skyscan.connector;

import java.util.UUID;

/** Events the backend subscribes to. Implemented by the mission orchestrator. */
public interface DroneConnectorListener {
    void onMissionAccepted(UUID missionId);
    void onTakeoff(UUID missionId);
    void onTelemetry(TelemetrySample sample);
    void onLanded(ScanUpload upload);
    void onAborted(UUID missionId, String reason);
    void onFailure(UUID missionId, String error);
}
