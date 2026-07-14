package com.skyscan.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "missions")
public class Mission {
    @Id @GeneratedValue private UUID id;
    @Column(nullable = false) private String name;
    @ManyToOne(optional = false) private Site site;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private MissionState state = MissionState.IDLE;
    private String previewObjectKey;   // field-preview artifact in object storage
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    @Column(length = 2000) private String lastError;

    @PreUpdate void touch() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public Site getSite() { return site; }
    public void setSite(Site v) { site = v; }
    public MissionState getState() { return state; }
    public void setState(MissionState v) { state = v; }
    public String getPreviewObjectKey() { return previewObjectKey; }
    public void setPreviewObjectKey(String v) { previewObjectKey = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String v) { lastError = v; }
}
