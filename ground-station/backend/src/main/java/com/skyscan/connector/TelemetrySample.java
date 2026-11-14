package com.skyscan.connector;

import java.time.Instant;
import java.util.UUID;

/** One live telemetry frame from the vehicle. */
public record TelemetrySample(
        UUID missionId,
        Instant at,
        double lat,
        double lon,
        double altM,
        double speedMps,
        double batteryPct,
        double progressPct,
        String flightMode) {}
