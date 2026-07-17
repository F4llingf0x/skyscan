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
import com.skyscan.domain.MissionType;
import com.skyscan.domain.SensorType;
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
    private final Map<UUID, StringBuilder> flightLogs = new ConcurrentHashMap<>();
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
                double routeAlt = Double.NaN;
                if (route != null && route.length >= 2) {
                    // Fly the planned route: interpolate along the waypoint list over the flight.
                    double u = Math.min(1.0, (double) t / FLIGHT_TICKS) * (route.length - 1);
                    int i = Math.min(route.length - 2, (int) u);
                    double f2 = u - i;
                    lon = route[i][0] + f2 * (route[i + 1][0] - route[i][0]);
                    lat = route[i][1] + f2 * (route[i + 1][1] - route[i][1]);
                    if (!Double.isNaN(route[i][2]) && !Double.isNaN(route[i + 1][2]))
                        routeAlt = route[i][2] + f2 * (route[i + 1][2] - route[i][2]);   // orbit-level altitude
                } else {
                    // STUB fallback path: sinusoidal sweep around the polygon centroid.
                    lat = home[1] + 0.0006 * Math.sin(phase);
                    lon = home[0] + 0.0009 * ((t % (FLIGHT_TICKS / 3.0)) / (FLIGHT_TICKS / 3.0) - 0.5);
                }
                double altNow = (Double.isNaN(routeAlt) ? plan.altitudeM() : routeAlt) + 0.4 * Math.sin(phase / 2);
                listener.onTelemetry(new TelemetrySample(
                        missionId, Instant.now(), lat, lon, altNow,
                        plan.speedMps(), Math.max(20, 100 - t * 2.5), progress, "AUTO.MISSION"));
                flightLogs.computeIfAbsent(missionId, k -> new StringBuilder())
                        .append("{\"t\":").append(Instant.now().toEpochMilli() / 1000.0)
                        .append(",\"kind\":\"state\",\"lat\":").append(lat)
                        .append(",\"lon\":").append(lon).append(",\"altM\":").append(altNow)
                        .append(",\"batteryPct\":").append(Math.max(20, 100 - t * 2.5))
                        .append(",\"mode\":\"AUTO.MISSION\",\"note\":\"STUB simulated frame\"}\n");

                // WILDLIFE demo: stream live animal tags mid-pass, near the
                // vehicle's current position, exactly like the real agent does.
                if (plan.sensorType() == SensorType.WILDLIFE_CAM && (t == 5 || t == 9 || t == 13)) {
                    String[][] demo = {
                        {"A-001", "roe deer", "FUSED", "37.8", "0.91"},
                        {"A-002", "wild boar", "FUSED", "38.6", "0.84"},
                        {"A-003", "hare", "RGB", "", "0.67"},
                    };
                    String[] d = demo[t == 5 ? 0 : t == 9 ? 1 : 2];
                    String temp = d[3].isEmpty() ? "null" : d[3];
                    String s = "{\"label\":\"" + d[0] + "\",\"species\":\"" + d[1]
                            + "\",\"confidence\":" + d[4]
                            + ",\"lat\":" + String.format(java.util.Locale.US, "%.6f", lat + 0.00008)
                            + ",\"lon\":" + String.format(java.util.Locale.US, "%.6f", lon - 0.00012)
                            + ",\"sightedAt\":\"" + Instant.now() + "\",\"source\":\"" + d[2]
                            + "\",\"bodyTempC\":" + temp + "}";
                    listener.onLiveAnimalSighting(missionId, s);
                }

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
        // CROP_HEALTH / WILDLIFE_SURVEY: the lidar flies for OBSTACLE AVOIDANCE
        // only — no point-cloud deliverable, so no scan file and no preview
        // (the deliverable is the plant/animal map + photos + report).
        boolean cloudless = plan.missionType() == MissionType.CROP_HEALTH
                || plan.missionType() == MissionType.WILDLIFE_SURVEY;

        Path scan = null;
        if (!cloudless) {
            String sampleFile = switch (plan.sensorType()) {
                case FIXED_3D -> "crude_fixed3d.las";
                case FRONT_360_VERTICAL -> "detailed_front360.las";   // building full scan
                case SPINNING_TILTED -> "detailed_spinning.las";      // landscape detail
                case PLANT_INSPECT, WILDLIFE_CAM -> "crude_fixed3d.las"; // unreachable (cloudless)
            };
            Path sample = samplesDir.resolve(sampleFile);
            scan = Files.createTempFile("skyscan-raw-", ".las");
            Files.copy(sample, scan, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        Path preview = null;
        if (crude && !cloudless) {
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

        // STUB customer photos: bundled samples, as if captured by the IMX219 during the pass.
        java.util.List<Path> photos = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Path samplePhoto = samplesDir.resolve("photo_" + i + ".jpg");
            if (Files.exists(samplePhoto)) {
                Path t = Files.createTempFile("skyscan-photo-", ".jpg");
                Files.copy(samplePhoto, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                photos.add(t);
            }
        }

        log.info("STUB connector: mission {} landed, delivering {} scan + {} photos",
                plan.missionId(), plan.sensorType(), photos.size());
        listener.onLanded(new ScanUpload(plan.missionId(), plan.passType(), plan.sensorType(),
                scan, preview, meta, photos));

        // STUB flight log: the simulated telemetry frames as JSONL, same shape as the real agent's.
        StringBuilder logBuf = flightLogs.remove(plan.missionId());
        if (logBuf != null) {
            Path logFile = Files.createTempFile("skyscan-flightlog-", ".jsonl");
            Files.writeString(logFile, logBuf.toString());
            listener.onFlightLog(plan.missionId(), logFile);
        }
    }

    @Override
    public void startHoverTest(UUID checkId, double heightM, double durationS) {
        // Simulated hover & dodge: climb, hold, one scripted approach at 90
        // degrees (dodge -> clear), land. Mirrors the real agent's event stream.
        hoverAbort = false;
        new Thread(() -> {
            try {
                var l = listener;
                java.util.function.BiConsumer<String, String> ev = (t, p) ->
                        l.onDroneCheckEvent(checkId, t, p);
                ev.accept("HOVER_EVENT", "{\"event\":\"PREFLIGHT\",\"heightM\":" + heightM + "}");
                Thread.sleep(600);
                ev.accept("HOVER_EVENT", "{\"event\":\"ARMING\"}");
                Thread.sleep(600);
                ev.accept("HOVER_EVENT", "{\"event\":\"CLIMB\",\"targetM\":" + heightM + "}");
                double agl = 0;
                long tEnd = System.currentTimeMillis() + (long) (durationS * 1000);
                boolean dodged = false;
                boolean holdSent = false;
                while (System.currentTimeMillis() < tEnd && !hoverAbort) {
                    agl = Math.min(heightM, agl + 0.25);
                    String near = "null";
                    String state = agl < heightM ? "CLIMB" : "HOLD";
                    long into = (long) (durationS * 1000) - (tEnd - System.currentTimeMillis());
                    if (into > 5000 && into < 9000) {
                        double d = 3.0 - (into - 5000) / 4000.0 * 1.8;
                        near = "{\"az\":90.0,\"dist\":" + String.format(java.util.Locale.US, "%.2f", d)
                                + ",\"closing\":0.45}";
                        if (!dodged && d < 2.0) {
                            dodged = true;
                            ev.accept("HOVER_EVENT",
                                    "{\"event\":\"DODGE\",\"azimuthDeg\":90.0,\"distM\":"
                                    + String.format(java.util.Locale.US, "%.2f", d)
                                    + ",\"closingMps\":0.45}");
                        }
                        state = "DODGE";
                    } else if (dodged && into >= 9000) {
                        dodged = false;
                        ev.accept("HOVER_EVENT", "{\"event\":\"CLEAR\",\"dodgeS\":4.0}");
                    }
                    if (!holdSent && agl >= heightM) {
                        holdSent = true;
                        ev.accept("HOVER_EVENT", "{\"event\":\"HOLD\",\"aglM\":" + heightM + "}");
                    }
                    ev.accept("HOVER_STATUS", "{\"state\":\"" + state + "\",\"aglM\":"
                            + String.format(java.util.Locale.US, "%.2f", agl)
                            + ",\"targetM\":" + heightM + ",\"nearest\":" + near
                            + ",\"endsInS\":" + Math.max(0, (tEnd - System.currentTimeMillis()) / 1000) + "}");
                    Thread.sleep(1000);
                }
                ev.accept("HOVER_EVENT", "{\"event\":\"LANDING\",\"reason\":\""
                        + (hoverAbort ? "abort" : "duration complete") + "\"}");
                Thread.sleep(1200);
                ev.accept("HOVER_EVENT", "{\"event\":\"" + (hoverAbort ? "ABORTED" : "DONE") + "\"}");
                hoverAbort = false;
            } catch (Exception ignored) { }
        }, "stub-hover").start();
    }

    private volatile boolean hoverAbort = false;

    @Override
    public void abortHoverTest(UUID checkId) { hoverAbort = true; }

    @Override
    public void requestSelfTest(UUID missionId) {
        // STUB: fabricate a realistic report (one WARN so the UI shows all states).
        scheduler.schedule(() -> {
            String checks = String.join(",",
                check("backend_link", "PASS", "12 ms", "SkyScan backend reachable and authenticated", ""),
                check("fc_link", "PASS", "pose age 41 ms", "Flight controller streaming (MAVLink healthy)", ""),
                check("fc.gyro_3d", "PASS", "healthy", "3D gyroscope: healthy", ""),
                check("fc.accel_3d", "PASS", "healthy", "3D accelerometer: healthy", ""),
                check("fc.mag_3d", "PASS", "healthy", "3D magnetometer (compass): healthy", ""),
                check("fc.baro", "PASS", "healthy", "absolute pressure (baro): healthy", ""),
                check("fc.rc_receiver", "PASS", "healthy", "RC receiver: healthy", ""),
                check("gps", "WARN", "3D, 9 sats", "3D fix but no RTK - cloud accuracy will be metre-class",
                        "Check NTRIP corrections / F9P; acceptable for test flights"),
                check("ekf", "PASS", "healthy", "EKF position/velocity estimates healthy", ""),
                check("battery", "PASS", "97% / 25.1 V", "Battery ready for a full leg", ""),
                check("lidar", "PASS", "10.2 scans/s", "RPLidar C1 streaming", ""),
                check("tilt", "PASS", "max tracking error 0.21 deg",
                        "Tilt axis tracks across full range (encoder verified)", ""),
                check("rangefinder", "PASS", "1.42 m", "Downward rangefinder healthy", ""),
                check("camera", "PASS", "2489133 bytes", "IMX219 test capture OK", ""),
                check("storage", "PASS", "213.4 GB free", "Enough space for scan sessions", ""));
            String report = "{\"droneId\":\"stub-uav\",\"mode\":\"full\",\"overall\":\"WARN\",\"at\":"
                    + Instant.now().getEpochSecond() + ",\"checks\":[" + checks + "]}";
            log.info("STUB connector: self-test report for mission {}", missionId);
            listener.onSelfTestReport(missionId, report);
        }, 1500, TimeUnit.MILLISECONDS);
    }

    private static String check(String comp, String status, String value, String msg, String hint) {
        return "{\"component\":\"" + comp + "\",\"status\":\"" + status + "\",\"value\":\"" + value
                + "\",\"message\":\"" + msg + "\",\"hint\":\"" + hint + "\"}";
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

    /** Waypoints of a GeoJSON LineString route ([lon, lat, alt?]), or null. */
    private static double[][] parseRoute(String routeJson) {
        if (routeJson == null || routeJson.isBlank()) return null;
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(routeJson);
            var coords = node.get("coordinates");
            double[][] out = new double[coords.size()][3];
            for (int i = 0; i < coords.size(); i++) {
                out[i][0] = coords.get(i).get(0).asDouble();
                out[i][1] = coords.get(i).get(1).asDouble();
                out[i][2] = coords.get(i).size() > 2 ? coords.get(i).get(2).asDouble() : Double.NaN;
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
