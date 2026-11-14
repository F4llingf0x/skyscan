package com.skyscan.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue private UUID id;
    @Column(nullable = false, unique = true) private String username;
    @Column(nullable = false) private String passwordHash;
    @Column(nullable = false) private String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Role role;
    @ManyToOne private Organization organization; // null for ADMIN/OPERATOR staff
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String v) { username = v; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String v) { passwordHash = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { displayName = v; }
    public Role getRole() { return role; }
    public void setRole(Role v) { role = v; }
    public Organization getOrganization() { return organization; }
    public void setOrganization(Organization v) { organization = v; }
    public Instant getCreatedAt() { return createdAt; }
}
