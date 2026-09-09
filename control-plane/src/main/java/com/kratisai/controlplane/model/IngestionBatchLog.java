package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ingestion_batch_logs")
public class IngestionBatchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "batch_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_ingestion_batch_logs_batch"))
    private IngestionBatch batch;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(nullable = false, length = 20)
    private String level = "INFO";

    @Column(nullable = false, length = 50)
    private String step;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public IngestionBatchLog() {}

    public IngestionBatchLog(IngestionBatch batch, UUID teamId, String level, String step, String message) {
        this.batch = batch;
        this.teamId = teamId;
        this.level = level;
        this.step = step;
        this.message = message;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public IngestionBatch getBatch() {
        return batch;
    }

    public void setBatch(IngestionBatch batch) {
        this.batch = batch;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public void setTeamId(UUID teamId) {
        this.teamId = teamId;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getStep() {
        return step;
    }

    public void setStep(String step) {
        this.step = step;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
