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
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ScanMode scanMode = ScanMode.LANDSCAPE;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private MissionType missionType = MissionType.SURVEY_3D;
    /** CROP_HEALTH deliverable: the findings report in object storage. */
    private String reportObjectKey;
    /** Mounted camera module id (see drone camera_modules catalog); operator-selectable per mission. */
    private String cameraModule = "imx219-77";
    /** WILDLIFE_SURVEY terrain: OPEN (field) or FOREST (canopy — thermal-primary
     *  detection, dawn/dusk recommended). Null for other mission types. */
    private String surveyEnvironment;
    /** Follow-up chain: a re-inspection created from this parent mission months
     *  later re-checks the SAME plants (crude pass skipped, plants copied). */
    @ManyToOne private Mission parentMission;
    /** Non-null while a detection-rescan pass is in flight: the sub-area being
     *  re-scanned. New detections MERGE into the plant map; cleared on landing. */
    @Column(columnDefinition = "text") private String rescanPolygonGeoJson;
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
    public ScanMode getScanMode() { return scanMode; }
    public void setScanMode(ScanMode v) { scanMode = v; }
    public MissionType getMissionType() { return missionType; }
    public void setMissionType(MissionType v) { missionType = v; }
    public String getReportObjectKey() { return reportObjectKey; }
    public void setReportObjectKey(String v) { reportObjectKey = v; }
    public String getCameraModule() { return cameraModule; }
    public void setCameraModule(String v) { cameraModule = v; }
    public String getSurveyEnvironment() { return surveyEnvironment; }
    public void setSurveyEnvironment(String v) { surveyEnvironment = v; }
    public Mission getParentMission() { return parentMission; }
    public void setParentMission(Mission v) { parentMission = v; }
    public String getRescanPolygonGeoJson() { return rescanPolygonGeoJson; }
    public void setRescanPolygonGeoJson(String v) { rescanPolygonGeoJson = v; }
    public String getPreviewObjectKey() { return previewObjectKey; }
    public void setPreviewObjectKey(String v) { previewObjectKey = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String v) { lastError = v; }
}
