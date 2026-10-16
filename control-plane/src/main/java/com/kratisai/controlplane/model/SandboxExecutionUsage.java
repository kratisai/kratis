package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One LLM-usage segment of a sandbox execution, keyed by a distinct virtual key. A resumed
 * sandbox gets a fresh key (the previous one is revoked on detach), so an execution accumulates a
 * row per key generation; the execution's totals are the sum of its rows.
 */
@Entity
@Table(name = "sandbox_execution_usage")
public class SandboxExecutionUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "execution_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sandbox_execution_usage_execution"))
    private SandboxExecution execution;

    @Column(name = "virtual_key", columnDefinition = "TEXT")
    private String virtualKey;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "total_tokens")
    private Long totalTokens = 0L;

    @Column(name = "prompt_tokens")
    private Long promptTokens = 0L;

    @Column(name = "completion_tokens")
    private Long completionTokens = 0L;

    @Column(name = "total_spend")
    private Double totalSpend = 0.0;

    @Column(name = "usage_last_updated_at")
    private Instant usageLastUpdatedAt;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public SandboxExecutionUsage() {}

    public SandboxExecutionUsage(SandboxExecution execution, String virtualKey, String modelName) {
        this.execution = execution;
        this.virtualKey = virtualKey;
        this.modelName = modelName;
        this.startedAt = Instant.now();
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void apply(LlmUsageSnapshot snapshot) {
        this.totalSpend = snapshot.spend() != null ? snapshot.spend() : 0.0;
        this.totalTokens = snapshot.totalTokens() != null ? snapshot.totalTokens() : 0L;
        this.promptTokens = snapshot.promptTokens() != null ? snapshot.promptTokens() : 0L;
        this.completionTokens = snapshot.completionTokens() != null ? snapshot.completionTokens() : 0L;
        this.usageLastUpdatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public SandboxExecution getExecution() {
        return execution;
    }

    public void setExecution(SandboxExecution execution) {
        this.execution = execution;
    }

    public String getVirtualKey() {
        return virtualKey;
    }

    public void setVirtualKey(String virtualKey) {
        this.virtualKey = virtualKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Double getTotalSpend() {
        return totalSpend;
    }

    public void setTotalSpend(Double totalSpend) {
        this.totalSpend = totalSpend;
    }

    public Instant getUsageLastUpdatedAt() {
        return usageLastUpdatedAt;
    }

    public void setUsageLastUpdatedAt(Instant usageLastUpdatedAt) {
        this.usageLastUpdatedAt = usageLastUpdatedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }
}
