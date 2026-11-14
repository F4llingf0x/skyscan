package com.skyscan.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live status push: per-mission SSE fan-out. The UI subscribes to
 * GET /api/missions/{id}/events and receives state changes + telemetry.
 */
@Service
public class MissionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MissionEventPublisher.class);
    private final Map<UUID, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public SseEmitter subscribe(UUID missionId) {
        SseEmitter emitter = new SseEmitter(0L);   // no server-side timeout; nginx/browser manage lifecycle
        subscribers.computeIfAbsent(missionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> subscribers.getOrDefault(missionId, List.of()).remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try { emitter.send(SseEmitter.event().name("hello").data("{\"missionId\":\"" + missionId + "\"}")); }
        catch (IOException ignored) {}
        return emitter;
    }

    public void publish(UUID missionId, String eventName, Object payload) {
        List<SseEmitter> list = subscribers.get(missionId);
        if (list == null || list.isEmpty()) return;
        String json;
        try { json = mapper.writeValueAsString(payload); }
        catch (Exception e) { json = "{}"; }
        for (SseEmitter emitter : list) {
            try { emitter.send(SseEmitter.event().name(eventName).data(json)); }
            catch (Exception e) { list.remove(emitter); }
        }
    }
}
