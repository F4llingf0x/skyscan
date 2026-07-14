package com.skyscan.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** One raw scan captured by the drone during a pass, plus (stubbed) GNSS/IMU metadata. */
@Entity
@Table(name = "acquisitions")
public class Acquisition {
    @Id @GeneratedValue private UUID id;
    @ManyToOne(optional = false) private Mission mission;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private PassType passType;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private SensorType sensorType;
    @Column(nullable = false) private String rawObjectKey;
    /** STUB: GNSS/IMU trajectory metadata as JSON; a real connector supplies real ephemeris/trajectory files. */
    @Column(columnDefinition = "text") private String gnssImuMetaJson;
    private Instant capturedAt = Instant.now();

    public UUID getId() { return id; }
    public Mission getMission() { return mission; }
    public void setMission(Mission v) { mission = v; }
    public PassType getPassType() { return passType; }
    public void setPassType(PassType v) { passType = v; }
    public SensorType getSensorType() { return sensorType; }
    public void setSensorType(SensorType v) { sensorType = v; }
    public String getRawObjectKey() { return rawObjectKey; }
    public void setRawObjectKey(String v) { rawObjectKey = v; }
    public String getGnssImuMetaJson() { return gnssImuMetaJson; }
    public void setGnssImuMetaJson(String v) { gnssImuMetaJson = v; }
    public Instant getCapturedAt() { return capturedAt; }
}
