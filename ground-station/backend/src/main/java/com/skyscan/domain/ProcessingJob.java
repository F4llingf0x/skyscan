package com.skyscan.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processing_jobs")
public class ProcessingJob {
    @Id @GeneratedValue private UUID id;
    @ManyToOne(optional = false) private Mission mission;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private JobStatus status = JobStatus.PENDING;
    @Column(columnDefinition = "text") private String inputRefsJson;   // acquisition ids + object keys
    @Column(columnDefinition = "text") private String outputRefsJson;  // produced object keys
    @Column(length = 4000) private String message;
    /** MERGE (post-flight processing) or FINALIZE (re-convert an operator-edited cloud). */
    @Column(nullable = false) private String jobType = "MERGE";
    /** For FINALIZE jobs: the asset being updated. */
    private UUID assetId;
    private Instant createdAt = Instant.now();
    private Instant finishedAt;

    public UUID getId() { return id; }
    public Mission getMission() { return mission; }
    public void setMission(Mission v) { mission = v; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus v) { status = v; }
    public String getInputRefsJson() { return inputRefsJson; }
    public void setInputRefsJson(String v) { inputRefsJson = v; }
    public String getOutputRefsJson() { return outputRefsJson; }
    public void setOutputRefsJson(String v) { outputRefsJson = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public String getJobType() { return jobType; }
    public void setJobType(String v) { jobType = v; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID v) { assetId = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant v) { finishedAt = v; }
}
