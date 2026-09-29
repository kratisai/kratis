package com.kratisai.controlplane.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ctx_dimensions")
public class CtxDimension {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "batch_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_ctx_dimensions_batch"))
    private IngestionBatch batch;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    private DimensionCategory category;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "synopsis", columnDefinition = "TEXT")
    private String synopsis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "glob_patterns", columnDefinition = "jsonb")
    private List<String> globPatterns;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CtxDimension() {}

    public CtxDimension(
            IngestionBatch batch,
            UUID teamId,
            DimensionCategory category,
            String name,
            String synopsis,
            List<String> globPatterns) {
        this.batch = batch;
        this.teamId = teamId;
        this.category = category;
        this.name = name;
        this.synopsis = synopsis;
        this.globPatterns = globPatterns;
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
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

    public DimensionCategory getCategory() {
        return category;
    }

    public void setCategory(DimensionCategory category) {
        this.category = category;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSynopsis() {
        return synopsis;
    }

    public void setSynopsis(String synopsis) {
        this.synopsis = synopsis;
    }

    public List<String> getGlobPatterns() {
        return globPatterns;
    }

    public void setGlobPatterns(List<String> globPatterns) {
        this.globPatterns = globPatterns;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "CtxDimension{" + "id="
                + id + ", batch="
                + batch + ", teamId="
                + teamId + ", category="
                + category + ", name='"
                + name + '\'' + ", synopsis='"
                + synopsis + '\'' + ", globPatterns=["
                + String.join(", ", globPatterns) + "]" + ", createdAt="
                + createdAt + '}';
    }
}
