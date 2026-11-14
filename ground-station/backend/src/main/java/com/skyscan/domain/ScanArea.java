package com.skyscan.domain;

import jakarta.persistence.*;
import java.util.UUID;

/** Polygon + flight parameters for one pass of a mission. Geometry is stored as GeoJSON for map round-tripping. */
@Entity
@Table(name = "scan_areas")
public class ScanArea {
    @Id @GeneratedValue private UUID id;
    @ManyToOne(optional = false) private Mission mission;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private PassType passType;
    @Column(nullable = false, columnDefinition = "text") private String polygonGeoJson;
    private double altitudeM;
    private double lineSpacingM;
    private double speedMps;
    private boolean terrainFollowing;
    /** STRUCTURE mode: horizontal standoff from the target footprint (m). */
    private double standoffM;
    /** Ring tilt for this pass, degrees; FIXED for the whole flight (hardware constraint). */
    private double tiltDeg;
    /** Planned coverage route (GeoJSON LineString, WGS84), computed by RoutePlanner. */
    @Column(columnDefinition = "text") private String routeJson;
    /** Detection-rescan sub-polygon: when set on the CRUDE area, the next
     *  crude flight covers ONLY this sub-area and new detections MERGE into
     *  the existing plant list (missed-plant recovery loop). Cleared after. */
    @Column(columnDefinition = "text") private String rescanPolygonGeoJson;

    public UUID getId() { return id; }
    public Mission getMission() { return mission; }
    public void setMission(Mission v) { mission = v; }
    public PassType getPassType() { return passType; }
    public void setPassType(PassType v) { passType = v; }
    public String getPolygonGeoJson() { return polygonGeoJson; }
    public void setPolygonGeoJson(String v) { polygonGeoJson = v; }
    public double getAltitudeM() { return altitudeM; }
    public void setAltitudeM(double v) { altitudeM = v; }
    public double getLineSpacingM() { return lineSpacingM; }
    public void setLineSpacingM(double v) { lineSpacingM = v; }
    public double getSpeedMps() { return speedMps; }
    public void setSpeedMps(double v) { speedMps = v; }
    public boolean isTerrainFollowing() { return terrainFollowing; }
    public void setTerrainFollowing(boolean v) { terrainFollowing = v; }
    public double getStandoffM() { return standoffM; }
    public void setStandoffM(double v) { standoffM = v; }
    public double getTiltDeg() { return tiltDeg; }
    public void setTiltDeg(double v) { tiltDeg = v; }
    public String getRouteJson() { return routeJson; }
    public void setRouteJson(String v) { routeJson = v; }
    public String getRescanPolygonGeoJson() { return rescanPolygonGeoJson; }
    public void setRescanPolygonGeoJson(String v) { rescanPolygonGeoJson = v; }
}
