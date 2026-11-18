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
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ctx_repo_relations")
public class CtxRepoRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "repository_a_id", nullable = false)
    private UUID repositoryAId;

    @Column(name = "repository_b_id", nullable = false)
    private UUID repositoryBId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_a_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ctx_repo_relations_batch_a"))
    private IngestionBatch batchA;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_b_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ctx_repo_relations_batch_b"))
    private IngestionBatch batchB;

    @Enumerated(EnumType.STRING)
    @Column(name = "nature", nullable = false, length = 20)
    private Nature nature;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", nullable = false, length = 20)
    private Ceiling confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", columnDefinition = "jsonb")
    private String citations;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private RelationState state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public CtxRepoRelation() {}

    public CtxRepoRelation(
            UUID teamId,
            IngestionBatch batchA,
            IngestionBatch batchB,
            Nature nature,
            Direction direction,
            Ceiling confidence) {
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.batchA = Objects.requireNonNull(batchA, "batchA");
        this.batchB = Objects.requireNonNull(batchB, "batchB");
        UUID repositoryA = batchA.getRepository().getId();
        UUID repositoryB = batchB.getRepository().getId();
        if (compareBySqlOrder(repositoryA, repositoryB) >= 0) {
            throw new IllegalArgumentException(
                    "repository_a_id must be the lower id of the pair under SQL uuid ordering: " + repositoryA + " vs "
                            + repositoryB);
        }
        this.repositoryAId = repositoryA;
        this.repositoryBId = repositoryB;
        this.nature = Objects.requireNonNull(nature, "nature");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.confidence = Objects.requireNonNull(confidence, "confidence");
        this.state = RelationState.CURRENT;
    }

    /** Orders a pair of ids the way PostgreSQL compares uuid values: unsigned, byte by byte. */
    public static int compareBySqlOrder(UUID left, UUID right) {
        byte[] x = asBytes(Objects.requireNonNull(left, "left"));
        byte[] y = asBytes(Objects.requireNonNull(right, "right"));
        for (int i = 0; i < x.length; i++) {
            int difference = Byte.toUnsignedInt(x[i]) - Byte.toUnsignedInt(y[i]);
            if (difference != 0) {
                return difference;
            }
        }
        return 0;
    }

    private static byte[] asBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (state == null) {
            state = RelationState.CURRENT;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getRepositoryAId() {
        return repositoryAId;
    }

    public UUID getRepositoryBId() {
        return repositoryBId;
    }

    public IngestionBatch getBatchA() {
        return batchA;
    }

    public IngestionBatch getBatchB() {
        return batchB;
    }

    public Nature getNature() {
        return nature;
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = Objects.requireNonNull(direction, "direction");
    }

    public Ceiling getConfidence() {
        return confidence;
    }

    public void setConfidence(Ceiling confidence) {
        this.confidence = Objects.requireNonNull(confidence, "confidence");
    }

    public String getCitations() {
        return citations;
    }

    public void setCitations(String citations) {
        this.citations = citations;
    }

    public RelationState getState() {
        return state;
    }

    public void setState(RelationState state) {
        this.state = state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
