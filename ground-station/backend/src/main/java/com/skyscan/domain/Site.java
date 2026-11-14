package com.skyscan.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A physical site / project the customer wants scanned. */
@Entity
@Table(name = "sites")
public class Site {
    @Id @GeneratedValue private UUID id;
    @Column(nullable = false) private String name;
    @Column(length = 2000) private String description;
    @ManyToOne(optional = false) private Organization organization;
    private double centerLat;
    private double centerLon;
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public Organization getOrganization() { return organization; }
    public void setOrganization(Organization v) { organization = v; }
    public double getCenterLat() { return centerLat; }
    public void setCenterLat(double v) { centerLat = v; }
    public double getCenterLon() { return centerLon; }
    public void setCenterLon(double v) { centerLon = v; }
    public Instant getCreatedAt() { return createdAt; }
}
