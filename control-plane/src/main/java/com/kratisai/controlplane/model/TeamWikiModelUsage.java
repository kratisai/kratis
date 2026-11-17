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
        name = "team_wiki_model_usage",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_team_wiki_model_usage_batch_kind",
                        columnNames = {"team_wiki_batch_id", "model_kind"}))
public class TeamWikiModelUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "team_wiki_batch_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_team_wiki_model_usage_batch"))
    private TeamWikiBatch teamWikiBatch;

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

    public TeamWikiModelUsage() {}

    public TeamWikiModelUsage(
            TeamWikiBatch teamWikiBatch, ModelKind modelKind, String modelIdentifier, String litellmAlias) {
        this.teamWikiBatch = Objects.requireNonNull(teamWikiBatch, "teamWikiBatch is required");
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

    public TeamWikiBatch getTeamWikiBatch() {
        return teamWikiBatch;
    }

    public ModelKind getModelKind() {
        return modelKind;
    }

    public String getModelIdentifier() {
        return modelIdentifier;
    }

    public String getLitellmAlias() {
        return litellmAlias;
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

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public Instant getUsageLastUpdatedAt() {
        return usageLastUpdatedAt;
    }
}
