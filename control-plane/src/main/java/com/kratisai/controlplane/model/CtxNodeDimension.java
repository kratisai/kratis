package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "ctx_node_dimensions")
public class CtxNodeDimension {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ctx_node_dimensions_node"))
    private CtxNode node;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "dimension_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_ctx_node_dimensions_dimension"))
    private CtxDimension dimension;

    @Column(name = "rank_score")
    private Double rankScore;

    public CtxNodeDimension() {}

    public CtxNodeDimension(CtxNode node, CtxDimension dimension, Double rankScore) {
        this.node = node;
        this.dimension = dimension;
        this.rankScore = rankScore;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public CtxNode getNode() {
        return node;
    }

    public void setNode(CtxNode node) {
        this.node = node;
    }

    public CtxDimension getDimension() {
        return dimension;
    }

    public void setDimension(CtxDimension dimension) {
        this.dimension = dimension;
    }

    public Double getRankScore() {
        return rankScore;
    }

    public void setRankScore(Double rankScore) {
        this.rankScore = rankScore;
    }
}
