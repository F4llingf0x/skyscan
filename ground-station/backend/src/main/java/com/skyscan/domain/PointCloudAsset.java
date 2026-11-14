package com.skyscan.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A delivered, processed point cloud (COPC for streaming + LAZ for download + LAS for the built-in viewer). */
@Entity
@Table(name = "point_cloud_assets")
public class PointCloudAsset {
    @Id @GeneratedValue private UUID id;
    @ManyToOne(optional = false) private Mission mission;
    @Column(nullable = false) private String name;
    private String copcObjectKey;
    private String lazObjectKey;
    private String lasObjectKey;
    private long pointCount;
    private double minX, minY, minZ, maxX, maxY, maxZ;
    private String crs;
    @Column(nullable = false) private String status = "READY";
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public Mission getMission() { return mission; }
    public void setMission(Mission v) { mission = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getCopcObjectKey() { return copcObjectKey; }
    public void setCopcObjectKey(String v) { copcObjectKey = v; }
    public String getLazObjectKey() { return lazObjectKey; }
    public void setLazObjectKey(String v) { lazObjectKey = v; }
    public String getLasObjectKey() { return lasObjectKey; }
    public void setLasObjectKey(String v) { lasObjectKey = v; }
    public long getPointCount() { return pointCount; }
    public void setPointCount(long v) { pointCount = v; }
    public double getMinX() { return minX; } public void setMinX(double v) { minX = v; }
    public double getMinY() { return minY; } public void setMinY(double v) { minY = v; }
    public double getMinZ() { return minZ; } public void setMinZ(double v) { minZ = v; }
    public double getMaxX() { return maxX; } public void setMaxX(double v) { maxX = v; }
    public double getMaxY() { return maxY; } public void setMaxY(double v) { maxY = v; }
    public double getMaxZ() { return maxZ; } public void setMaxZ(double v) { maxZ = v; }
    public String getCrs() { return crs; } public void setCrs(String v) { crs = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public Instant getCreatedAt() { return createdAt; }
}
