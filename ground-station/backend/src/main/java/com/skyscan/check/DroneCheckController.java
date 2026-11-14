package com.skyscan.check;

import com.skyscan.connector.DroneConnector;
import com.skyscan.live.MissionEventPublisher;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * Pre-flight drone check page API (staff only):
 *   POST /api/drone/self-test          run the on-vehicle self-test (props off)
 *   GET  /api/drone/self-test/latest   last report JSON
 *   POST /api/drone/hover-test         GPS-free hover & dodge test (props ON!)
 *   POST /api/drone/hover-test/abort   controlled landing now
 *   GET  /api/drone/events             SSE: selftest / hover_status / hover_event
 */
@RestController
@RequestMapping("/api/drone")
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
public class DroneCheckController {

    public record HoverTestRequest(@Positive double heightM, @Positive double durationS) {}

    private final DroneConnector connector;
    private final DroneCheckService check;
    private final MissionEventPublisher publisher;

    public DroneCheckController(DroneConnector connector, DroneCheckService check,
                                MissionEventPublisher publisher) {
        this.connector = connector; this.check = check; this.publisher = publisher;
    }

    @PostMapping("/self-test")
    public Map<String, Object> selfTest() {
        connector.requestSelfTest(DroneCheckService.CHECK_ID);
        return Map.of("ok", true);
    }

    @GetMapping("/self-test/latest")
    public Map<String, Object> latest() {
        String r = check.latestSelfTest();
        return r == null ? Map.of() : Map.of("report", r);
    }

    @PostMapping("/hover-test")
    public Map<String, Object> hoverTest(@RequestBody HoverTestRequest req) {
        // hard server-side clamps regardless of what the UI sent
        double h = Math.max(0.3, Math.min(3.0, req.heightM()));
        double d = Math.max(10, Math.min(180, req.durationS()));
        connector.startHoverTest(DroneCheckService.CHECK_ID, h, d);
        return Map.of("ok", true, "heightM", h, "durationS", d);
    }

    @PostMapping("/hover-test/abort")
    public Map<String, Object> hoverAbort() {
        connector.abortHoverTest(DroneCheckService.CHECK_ID);
        return Map.of("ok", true);
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return publisher.subscribe(DroneCheckService.CHECK_ID);
    }

    @GetMapping("/hover-test/status")
    public Map<String, Object> hoverStatus() { return check.hoverStatus(); }
}
