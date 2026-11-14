package com.skyscan.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Persisted audit trail of state changes and notable connector events (telemetry itself is ephemeral, via SSE). */
@Entity
@Table(name = "mission_events")
public class MissionEvent {
    @Id @GeneratedValue private UUID id;
    @ManyToOne(optional = false) private Mission mission;
    @Column(nullable = false) private String type;          // STATE_CHANGE | TELEMETRY | INFO | ERROR
    @Column(columnDefinition = "text") private String payloadJson;
    private Instant at = Instant.now();

    public UUID getId() { return id; }
    public Mission getMission() { return mission; }
    public void setMission(Mission v) { mission = v; }
    public String getType() { return type; }
    public void setType(String v) { type = v; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { payloadJson = v; }
    public Instant getAt() { return at; }
}
