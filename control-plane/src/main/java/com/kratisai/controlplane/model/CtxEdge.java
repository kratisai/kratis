package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "ctx_edges")
public class CtxEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "batch_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_ctx_edges_batch"))
    private IngestionBatch batch;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "source_node_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_ctx_edges_source_node"))
    private CtxNode sourceNode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "target_node_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_ctx_edges_target_node"))
    private CtxNode targetNode;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", length = 50)
    private RelationType relationType;

    public CtxEdge() {}

    public CtxEdge(
            IngestionBatch batch, UUID teamId, CtxNode sourceNode, CtxNode targetNode, RelationType relationType) {
        this.batch = batch;
        this.teamId = teamId;
        this.sourceNode = sourceNode;
        this.targetNode = targetNode;
        this.relationType = relationType;
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

    public CtxNode getSourceNode() {
        return sourceNode;
    }

    public void setSourceNode(CtxNode sourceNode) {
        this.sourceNode = sourceNode;
    }

    public CtxNode getTargetNode() {
        return targetNode;
    }

    public void setTargetNode(CtxNode targetNode) {
        this.targetNode = targetNode;
    }

    public RelationType getRelationType() {
        return relationType;
    }

    public void setRelationType(RelationType relationType) {
        this.relationType = relationType;
    }

    @Override
    public String toString() {
        return "CtxEdge{" + "relationType="
                + relationType + ", sourceNode="
                + sourceNode + ", targetNode="
                + targetNode + '}';
    }
}
