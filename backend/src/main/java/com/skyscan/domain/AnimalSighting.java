package com.skyscan.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One animal found during a WILDLIFE_SURVEY coverage pass — the tag the user
 * asked for: WHAT (species + confidence), WHEN (sightedAt, the moment the
 * detector saw it), WHERE (lat/lon projected from the frame), and HOW
 * (RGB, THERMAL, or FUSED = seen by both, the highest-confidence class;
 * bodyTempC filled when the thermal camera contributed).
 */
@Entity
@Table(name = "animal_sightings")
public class AnimalSighting {
    @Id @GeneratedValue private UUID id;
    @ManyToOne(optional = false) private Mission mission;
    @Column(nullable = false) private String label;         // A-001, A-002 ...
    @Column(nullable = false) private String species;       // detector class name
    private double confidence;
    private double lat;
    private double lon;
    @Column(nullable = false) private Instant sightedAt;    // time of finding
    @Column(nullable = false) private String source = "RGB"; // RGB | THERMAL | FUSED
    private Double bodyTempC;                                // thermal estimate, nullable
    private String photoObjectKey;                           // evidence crop, nullable

    public UUID getId() { return id; }
    public Mission getMission() { return mission; }
    public void setMission(Mission v) { mission = v; }
    public String getLabel() { return label; }
    public void setLabel(String v) { label = v; }
    public String getSpecies() { return species; }
    public void setSpecies(String v) { species = v; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double v) { confidence = v; }
    public double getLat() { return lat; }
    public void setLat(double v) { lat = v; }
    public double getLon() { return lon; }
    public void setLon(double v) { lon = v; }
    public Instant getSightedAt() { return sightedAt; }
    public void setSightedAt(Instant v) { sightedAt = v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v; }
    public Double getBodyTempC() { return bodyTempC; }
    public void setBodyTempC(Double v) { bodyTempC = v; }
    public String getPhotoObjectKey() { return photoObjectKey; }
    public void setPhotoObjectKey(String v) { photoObjectKey = v; }
}
