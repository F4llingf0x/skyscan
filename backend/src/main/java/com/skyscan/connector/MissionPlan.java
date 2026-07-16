package com.skyscan.connector;

import com.skyscan.domain.PassType;
import com.skyscan.domain.SensorType;
import java.util.UUID;

/**
 * Everything the drone/companion computer needs for one pass.
 * A real connector would compile this into MAVLink mission items / a ROS goal;
 * the stub only uses it to shape its simulated flight.
 */
public record MissionPlan(
        UUID missionId,
        PassType passType,
        SensorType sensorType,
        String polygonGeoJson,
        double altitudeM,
        double lineSpacingM,
        double speedMps,
        boolean terrainFollowing,
        /** Ring tilt in degrees, fixed for the whole flight. */
        double tiltDeg,
        /** Planned coverage route (GeoJSON LineString, WGS84); nullable. */
        String routeJson) {}
