package com.example.geotime.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_checkpoints")
public class AgentCheckpoint {

    @Id
    @Column(name = "checkpoint_id", length = 64)
    private String checkpointId;

    @Column(name = "query_city", nullable = false)
    private String queryCity;

    @Column(name = "status", nullable = false)
    private String status; // PAUSED_FOR_HITL, RESUMED

    @Column(name = "candidates_json", columnDefinition = "TEXT", nullable = false)
    private String candidatesJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resumed_at")
    private Instant resumedAt;

    public AgentCheckpoint() {}

    public AgentCheckpoint(String checkpointId, String queryCity, String candidatesJson) {
        this.checkpointId = checkpointId;
        this.queryCity = queryCity;
        this.status = "PAUSED_FOR_HITL";
        this.candidatesJson = candidatesJson;
        this.createdAt = Instant.now();
    }

    public String getCheckpointId() { return checkpointId; }
    public String getQueryCity() { return queryCity; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCandidatesJson() { return candidatesJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResumedAt() { return resumedAt; }
    public void setResumedAt(Instant resumedAt) { this.resumedAt = resumedAt; }
}
