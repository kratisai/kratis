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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One inference-input record per batch: role, alias names and the three description sections. */
@Entity
@Table(name = "ctx_repo_synopses")
public class CtxRepoSynopsis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "batch_id",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(name = "fk_ctx_repo_synopses_batch"))
    private IngestionBatch batch;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "repo_role", nullable = false, length = 20)
    private RepoRole repoRole;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "known_as", nullable = false, columnDefinition = "jsonb")
    private List<String> knownAs = new ArrayList<>();

    @Column(name = "synopsis", nullable = false, columnDefinition = "TEXT")
    private String synopsis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", columnDefinition = "jsonb")
    private Map<String, Object> citations;

    @Column(name = "model_ref")
    private String modelRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CtxRepoSynopsis() {}

    public CtxRepoSynopsis(
            IngestionBatch batch, RepoRole repoRole, List<String> knownAs, String synopsis, String modelRef) {
        this.batch = Objects.requireNonNull(batch, "batch");
        this.repositoryId = batch.getRepository().getId();
        this.teamId = batch.getRepository().getTeam().getId();
        this.repoRole = Objects.requireNonNull(repoRole, "repoRole");
        this.knownAs = List.copyOf(Objects.requireNonNull(knownAs, "knownAs"));
        this.synopsis = Objects.requireNonNull(synopsis, "synopsis");
        this.modelRef = modelRef;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public IngestionBatch getBatch() {
        return batch;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public RepoRole getRepoRole() {
        return repoRole;
    }

    public void setRepoRole(RepoRole repoRole) {
        this.repoRole = Objects.requireNonNull(repoRole, "repoRole");
    }

    public List<String> getKnownAs() {
        return knownAs;
    }

    public void setKnownAs(List<String> knownAs) {
        this.knownAs = List.copyOf(Objects.requireNonNull(knownAs, "knownAs"));
    }

    public String getSynopsis() {
        return synopsis;
    }

    public void setSynopsis(String synopsis) {
        this.synopsis = Objects.requireNonNull(synopsis, "synopsis");
    }

    public Map<String, Object> getCitations() {
        return citations;
    }

    public void setCitations(Map<String, Object> citations) {
        this.citations = citations == null ? null : Map.copyOf(citations);
    }

    public String getModelRef() {
        return modelRef;
    }

    public void setModelRef(String modelRef) {
        this.modelRef = modelRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
