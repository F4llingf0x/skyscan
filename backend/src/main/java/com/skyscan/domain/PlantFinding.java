package com.skyscan.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One disease/stress finding on one plant, produced by the detail-pass
 * analysis. diseaseId keys into the disease catalog (cures, checks);
 * severity is LOW / MEDIUM / HIGH; confidence 0..1.
 */
@Entity
@Table(name = "plant_findings")
public class PlantFinding {
    @Id @GeneratedValue private UUID id;
    @ManyToOne(optional = false) private Plant plant;
    @Column(nullable = false) private String diseaseId;
    @Column(nullable = false) private String diseaseName;
    @Column(nullable = false) private String severity;
    private double confidence;
    @Column(length = 2000) private String note;
    private Instant foundAt = Instant.now();

    public UUID getId() { return id; }
    public Plant getPlant() { return plant; }
    public void setPlant(Plant v) { plant = v; }
    public String getDiseaseId() { return diseaseId; }
    public void setDiseaseId(String v) { diseaseId = v; }
    public String getDiseaseName() { return diseaseName; }
    public void setDiseaseName(String v) { diseaseName = v; }
    public String getSeverity() { return severity; }
    public void setSeverity(String v) { severity = v; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double v) { confidence = v; }
    public String getNote() { return note; }
    public void setNote(String v) { note = v; }
    public Instant getFoundAt() { return foundAt; }
}
