package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ctx_architecture_patterns")
public class CtxArchitecturePattern {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "batch_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_ctx_architecture_patterns_batch"))
    private IngestionBatch batch;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "ctx_architecture_pattern_exemplars",
            joinColumns =
                    @JoinColumn(
                            name = "pattern_id",
                            foreignKey = @ForeignKey(name = "fk_ctx_arch_pattern_exemplars_pattern")),
            inverseJoinColumns =
                    @JoinColumn(
                            name = "node_id",
                            foreignKey = @ForeignKey(name = "fk_ctx_arch_pattern_exemplars_node")))
    private List<CtxNode> exemplarNodes = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public CtxArchitecturePattern() {}

    public CtxArchitecturePattern(
            IngestionBatch batch, UUID teamId, String name, String description, List<CtxNode> exemplarNodes) {
        this.batch = batch;
        this.teamId = teamId;
        this.name = name;
        this.description = description;
        this.exemplarNodes = exemplarNodes != null ? exemplarNodes : new ArrayList<>();
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<CtxNode> getExemplarNodes() {
        return exemplarNodes;
    }

    public void setExemplarNodes(List<CtxNode> exemplarNodes) {
        this.exemplarNodes = exemplarNodes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
