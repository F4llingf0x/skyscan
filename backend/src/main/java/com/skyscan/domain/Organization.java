package com.skyscan.domain;

import jakarta.persistence.*;
import java.util.UUID;

/** A customer organization; customers and sites are scoped to it. */
@Entity
@Table(name = "organizations")
public class Organization {
    @Id @GeneratedValue private UUID id;
    @Column(nullable = false, unique = true) private String name;
    private String contactEmail;

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String v) { contactEmail = v; }
}
