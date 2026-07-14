package com.skyscan.connector.stub;

// =============================================================================
// STUB: Simulated drone / companion computer.
//
// This class stands in for the entire Jetson + Pixhawk stack. It:
//   * acknowledges mission uploads,
//   * "flies" the mission on a timer, emitting telemetry along a lawnmower-ish
//     path derived from the requested polygon,
//   * on landing, hands back a bundled sample scan (LAS) with the correct
//     sensorType, plus a sample preview artifact for the crude pass.
//
// No real coverage-path planning, MAVLink, ROS, SLAM or lidar drivers here —
// those are explicit non-goals; see README "Replacing the stub".
// =============================================================================

import com.skyscan.connector.*;
import com.skyscan.domain.PassType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(name = "skyscan.connector.impl", havingValue = "stub", matchIfMissing = true)
public class StubDroneConnector implements DroneConnector {

    private static final Logger log = LoggerFactory.getLogger(StubDroneConnector.class);

    private static final int FLIGHT_TICKS = 18;        // ~18 s simulated flight
    private static final long TICK_MS = 1000;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<UUID, MissionPlan> uploaded = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> flights = new ConcurrentHashMap<>();
    private final Path samplesDir;
    private volatile DroneConnectorListener listener;

    public StubDroneConnector(@Value("${skyscan.samples.dir}") String samplesDir) {
        this.samplesDir = Path.of(samplesDir);
    }

    @Override public String describe() { return "StubDroneConnector (simulated hardware, samples: " + samplesDir + ")"; }

    @Override public void setListener(DroneConnectorListener listener) { this.listener = listener; }

    @Override
    public void uploadMission(MissionPlan plan) {
        uploaded.put(plan.missionId(), plan);
        // STUB: a real connector would stream MAVLink MISSION_ITEMs here and wait for MISSION_ACK.
        scheduler.schedule(() -> listener.onMissionAccepted(plan.missionId()), 700, TimeUnit.MILLISECONDS);
        log.info("STUB connector: mission {} ({} pass) uploaded", plan.missionId(), plan.passType());
    }

    @Override
    public void startMission(UUID missionId) {
        MissionPlan plan = uploaded.get(missionId);
        if (plan == null) { listener.onFailure(missionId, "No uploaded mission on vehicle"); return; }

        double[] home = Centroid.of(plan.polygonGeoJson());
        double[][] route = parseRoute(plan.routeJson());   // planned coverage route, if provided
        listener.onTakeoff(missionId);

        final int[] tick = {0};
        ScheduledFuture<?> f = scheduler.scheduleAtFixedRate(() -> {
            try {
                int t = ++tick[0];
                double progress = Math.min(100.0, 100.0 * t / FLIGHT_TICKS);
                double lat, lon;
                double phase = t * 0.8;   // used for the simulated altitude wobble below
                if (route != null && route.length >= 2) {
                    // Fly the planned route: interpolate along the waypoint list over the flight.
                    double u = Math.min(1.0, (double) t / FLIGHT_TICKS) * (route.length - 1);
                    int i = Math.min(route.length - 2, (int) u);
                    double f2 = u - i;
                    lon = route[i][0] + f2 * (route[i + 1][0] - route[i][0]);
                    lat = route[i][1] + f2 * (route[i + 1][1] - route[i][1]);
                } else {
                    // STUB fallback path: sinusoidal sweep around the polygon centroid.
                    lat = home[1] + 0.0006 * Math.sin(phase);
                    lon = home[0] + 0.0009 * ((t % (FLIGHT_TICKS / 3.0)) / (FLIGHT_TICKS / 3.0) - 0.5);
                }
                listener.onTelemetry(new TelemetrySample(
                        missionId, Instant.now(), lat, lon,
                        plan.altitudeM() + 1.5 * Math.sin(phase / 2),
                        plan.speedMps(), Math.max(20, 100 - t * 2.5), progress, "AUTO.MISSION"));

                if (t >= FLIGHT_TICKS) {
                    cancelFlight(missionId);
                    land(plan);
                }
            } catch (Exception e) {
                cancelFlight(missionId);
                listener.onFailure(missionId, "Stub flight error: " + e.getMessage());
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        flights.put(missionId, f);
    }

    private void land(MissionPlan plan) throws Exception {
        // STUB: copy the bundled sample scan to a temp file, as if the Jetson had just
        // finished writing the capture and pushed it over the ground link.
        boolean crude = plan.passType() == PassType.CRUDE;
        Path sample = samplesDir.resolve(crude ? "crude_fixed3d.las" : "detailed_spinning.las");
        Path scan = Files.createTempFile("skyscan-raw-", ".las");
        Files.copy(sample, scan, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Path preview = null;
        if (crude) {
            Path samplePreview = samplesDir.resolve("crude_preview.png");
            preview = Files.createTempFile("skyscan-preview-", ".png");
            Files.copy(samplePreview, preview, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        // STUB GNSS/IMU metadata: fabricated but realistically shaped.
        String meta = """
            {"gnss":{"fixType":"RTK_FIXED","hAccM":0.014,"vAccM":0.021,"datum":"WGS84"},
             "imu":{"model":"STUB-IMU-9DOF","biasCalibrated":true},
             "trajectorySamples":%d,"note":"STUB metadata generated by StubDroneConnector"}
            """.formatted(FLIGHT_TICKS * 10);

        log.info("STUB connector: mission {} landed, delivering {} scan", plan.missionId(), plan.sensorType());
        listener.onLanded(new ScanUpload(plan.missionId(), plan.passType(), plan.sensorType(), scan, preview, meta));
    }

    @Override
    public void abortMission(UUID missionId, String reason) {
        cancelFlight(missionId);
        // STUB: simulate return-to-home taking a couple of seconds.
        scheduler.schedule(() -> listener.onAborted(missionId, reason), 2, TimeUnit.SECONDS);
        log.info("STUB connector: mission {} aborted ({}), returning to home", missionId, reason);
    }

    private void cancelFlight(UUID missionId) {
        ScheduledFuture<?> f = flights.remove(missionId);
        if (f != null) f.cancel(false);
    }

    /** Waypoints of a GeoJSON LineString route, or null. */
    private static double[][] parseRoute(String routeJson) {
        if (routeJson == null || routeJson.isBlank()) return null;
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(routeJson);
            var coords = node.get("coordinates");
            double[][] out = new double[coords.size()][2];
            for (int i = 0; i < coords.size(); i++) {
                out[i][0] = coords.get(i).get(0).asDouble();
                out[i][1] = coords.get(i).get(1).asDouble();
            }
            return out;
        } catch (Exception e) { return null; }
    }

    /** Minimal centroid of the first ring of a GeoJSON Polygon; enough for the simulated path. */
    static final class Centroid {
        static double[] of(String polygonGeoJson) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = mapper.readTree(polygonGeoJson);
                var coords = node.get("coordinates").get(0);
                double sx = 0, sy = 0; int n = 0;
                for (var c : coords) { sx += c.get(0).asDouble(); sy += c.get(1).asDouble(); n++; }
                return n == 0 ? new double[]{19.04, 47.50} : new double[]{sx / n, sy / n};
            } catch (Exception e) {
                return new double[]{19.04, 47.50};   // fall back to a fixed home point
            }
        }
    }
}
