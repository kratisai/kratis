package com.kratisai.controlplane.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "ingestion_model_usage",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_ingestion_model_usage_batch_kind",
                        columnNames = {"batch_id", "model_kind"}),
        indexes = {@Index(name = "idx_ingestion_model_usage_batch_id", columnList = "batch_id")})
public class IngestionModelUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ingestion_model_usage_batch"))
    private IngestionBatch batch;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_kind", nullable = false, length = 20)
    private ModelKind modelKind;

    @Column(name = "model_identifier", nullable = false)
    private String modelIdentifier;

    @Column(name = "litellm_alias")
    private String litellmAlias;

    @Column(name = "virtual_key", columnDefinition = "TEXT")
    private String virtualKey;

    @Column(name = "total_spend")
    private Double totalSpend = 0.0;

    @Column(name = "total_tokens")
    private Long totalTokens = 0L;

    @Column(name = "prompt_tokens")
    private Long promptTokens = 0L;

    @Column(name = "completion_tokens")
    private Long completionTokens = 0L;

    @Column(name = "usage_last_updated_at")
    private Instant usageLastUpdatedAt;

    public IngestionModelUsage() {}

    public IngestionModelUsage(IngestionBatch batch, ModelKind modelKind, String modelIdentifier, String litellmAlias) {
        this.batch = Objects.requireNonNull(batch, "batch is required");
        this.modelKind = Objects.requireNonNull(modelKind, "modelKind is required");
        this.modelIdentifier = Objects.requireNonNull(modelIdentifier, "modelIdentifier is required");
        this.litellmAlias = litellmAlias;
    }

    public void apply(LlmUsageSnapshot snapshot) {
        this.totalSpend = snapshot.spend() != null ? snapshot.spend() : 0.0;
        this.totalTokens = snapshot.totalTokens() != null ? snapshot.totalTokens() : 0L;
        this.promptTokens = snapshot.promptTokens() != null ? snapshot.promptTokens() : 0L;
        this.completionTokens = snapshot.completionTokens() != null ? snapshot.completionTokens() : 0L;
        this.usageLastUpdatedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (usageLastUpdatedAt == null) {
            usageLastUpdatedAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        usageLastUpdatedAt = Instant.now();
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

    public ModelKind getModelKind() {
        return modelKind;
    }

    public void setModelKind(ModelKind modelKind) {
        this.modelKind = modelKind;
    }

    public String getModelIdentifier() {
        return modelIdentifier;
    }

    public void setModelIdentifier(String modelIdentifier) {
        this.modelIdentifier = modelIdentifier;
    }

    public String getLitellmAlias() {
        return litellmAlias;
    }

    public void setLitellmAlias(String litellmAlias) {
        this.litellmAlias = litellmAlias;
    }

    public String getVirtualKey() {
        return virtualKey;
    }

    public void setVirtualKey(String virtualKey) {
        this.virtualKey = virtualKey;
    }

    public Double getTotalSpend() {
        return totalSpend;
    }

    public void setTotalSpend(Double totalSpend) {
        this.totalSpend = totalSpend;
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

    public Instant getUsageLastUpdatedAt() {
        return usageLastUpdatedAt;
    }

    public void setUsageLastUpdatedAt(Instant usageLastUpdatedAt) {
        this.usageLastUpdatedAt = usageLastUpdatedAt;
    }
}
