package com.skyscan.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One plant (crop or tree) detected in the crude pass of a CROP_HEALTH
 * mission. Location is WGS84; height/canopy come from the point cloud;
 * healthIndex is a 0..1 vegetation-vigor proxy (1 = vigorous). Status walks
 * DETECTED -> SCANNED as the inspection pass visits it.
 */
@Entity
@Table(name = "plants")
public class Plant {
    @Id @GeneratedValue private UUID id;
    @ManyToOne(optional = false) private Mission mission;
    /** Short human label used on the map and in the report, e.g. T-004, C-117. */
    @Column(nullable = false) private String label;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private PlantKind kind;
    private double lat;
    private double lon;
    private double heightM;
    private double canopyDiaM;
    private double healthIndex;
    @Column(nullable = false) private String status = "DETECTED";
    /** Operator can exclude a plant from the inspection flight plan. */
    private boolean excluded = false;
    /** Representative close-up from the inspection pass (object storage). */
    private String photoObjectKey;
    private Instant detectedAt = Instant.now();

    public UUID getId() { return id; }
    public Mission getMission() { return mission; }
    public void setMission(Mission v) { mission = v; }
    public String getLabel() { return label; }
    public void setLabel(String v) { label = v; }
    public PlantKind getKind() { return kind; }
    public void setKind(PlantKind v) { kind = v; }
    public double getLat() { return lat; }
    public void setLat(double v) { lat = v; }
    public double getLon() { return lon; }
    public void setLon(double v) { lon = v; }
    public double getHeightM() { return heightM; }
    public void setHeightM(double v) { heightM = v; }
    public double getCanopyDiaM() { return canopyDiaM; }
    public void setCanopyDiaM(double v) { canopyDiaM = v; }
    public double getHealthIndex() { return healthIndex; }
    public void setHealthIndex(double v) { healthIndex = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public boolean isExcluded() { return excluded; }
    public void setExcluded(boolean v) { excluded = v; }
    public String getPhotoObjectKey() { return photoObjectKey; }
    public void setPhotoObjectKey(String v) { photoObjectKey = v; }
    public Instant getDetectedAt() { return detectedAt; }
}
