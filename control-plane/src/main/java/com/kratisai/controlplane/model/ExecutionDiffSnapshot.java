package com.kratisai.controlplane.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "execution_diff_snapshots",
        indexes = {@Index(name = "idx_diff_snapshots_execution", columnList = "execution_id")})
public class ExecutionDiffSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "execution_id", nullable = false, unique = true)
    private UUID executionId;

    @Column(name = "base_commit", length = 64)
    private String baseCommit;

    @Column(name = "head_commit", length = 64)
    private String headCommit;

    @Column(name = "total_additions", nullable = false)
    private int totalAdditions;

    @Column(name = "total_deletions", nullable = false)
    private int totalDeletions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary_json", columnDefinition = "jsonb", nullable = false)
    private String summaryJson;

    @Column(name = "patch_storage_path", nullable = false, length = 255)
    private String patchStoragePath;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ExecutionDiffSnapshot() {}

    public ExecutionDiffSnapshot(
            UUID executionId,
            String baseCommit,
            String headCommit,
            int totalAdditions,
            int totalDeletions,
            String summaryJson,
            String patchStoragePath) {
        this.executionId = executionId;
        this.baseCommit = baseCommit;
        this.headCommit = headCommit;
        this.totalAdditions = totalAdditions;
        this.totalDeletions = totalDeletions;
        this.summaryJson = summaryJson;
        this.patchStoragePath = patchStoragePath;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public void setExecutionId(UUID executionId) {
        this.executionId = executionId;
    }

    public String getBaseCommit() {
        return baseCommit;
    }

    public void setBaseCommit(String baseCommit) {
        this.baseCommit = baseCommit;
    }

    public String getHeadCommit() {
        return headCommit;
    }

    public void setHeadCommit(String headCommit) {
        this.headCommit = headCommit;
    }

    public int getTotalAdditions() {
        return totalAdditions;
    }

    public void setTotalAdditions(int totalAdditions) {
        this.totalAdditions = totalAdditions;
    }

    public int getTotalDeletions() {
        return totalDeletions;
    }

    public void setTotalDeletions(int totalDeletions) {
        this.totalDeletions = totalDeletions;
    }

    public String getSummaryJson() {
        return summaryJson;
    }

    public void setSummaryJson(String summaryJson) {
        this.summaryJson = summaryJson;
    }

    public String getPatchStoragePath() {
        return patchStoragePath;
    }

    public void setPatchStoragePath(String patchStoragePath) {
        this.patchStoragePath = patchStoragePath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
