package com.skyscan.check;

import com.skyscan.live.MissionEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The drone-check (maintenance) channel — pre-flight verification that is NOT
 * tied to any mission: remote self-test + the GPS-free hover & dodge test.
 * Uses a fixed channel UUID so the existing SSE fan-out carries its events.
 */
@Service
public class DroneCheckService {

    /** The maintenance channel id — never collides with mission UUIDs (v0). */
    public static final UUID CHECK_ID = new UUID(0L, 0xD10EL);

    private final MissionEventPublisher publisher;
    private final AtomicReference<String> lastSelfTest = new AtomicReference<>();
    private final AtomicReference<String> lastHoverStatus = new AtomicReference<>();

    public DroneCheckService(MissionEventPublisher publisher) { this.publisher = publisher; }

    public void storeSelfTest(String reportJson) {
        lastSelfTest.set(reportJson);
        publisher.publish(CHECK_ID, "selftest", reportJson);
    }

    public String latestSelfTest() { return lastSelfTest.get(); }

    public void onHoverEvent(String type, String payloadJson) {
        if ("HOVER_STATUS".equals(type)) lastHoverStatus.set(payloadJson);
        publisher.publish(CHECK_ID, type.toLowerCase(), payloadJson);
    }

    public Map<String, Object> hoverStatus() {
        String s = lastHoverStatus.get();
        return s == null ? Map.of() : Map.of("status", s);
    }
}
