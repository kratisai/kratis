package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ctx_surfaces")
public class CtxSurface {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ctx_surfaces_batch"))
    private IngestionBatch batch;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private SurfaceRole role;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "detected_by", nullable = false, length = 20)
    private SurfaceDetection detectedBy;

    @OneToMany(mappedBy = "surface", fetch = FetchType.LAZY)
    private List<CtxSurfaceAttribute> attributeRows = new ArrayList<>();

    public CtxSurface() {}

    public CtxSurface(
            IngestionBatch batch,
            UUID repositoryId,
            UUID teamId,
            SurfaceRole role,
            String description,
            SurfaceDetection detectedBy) {
        this.batch = batch;
        this.repositoryId = repositoryId;
        this.teamId = teamId;
        this.role = role;
        this.description = description;
        this.detectedBy = detectedBy;
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

    public SurfaceRole getRole() {
        return role;
    }

    public String getDescription() {
        return description;
    }

    public SurfaceDetection getDetectedBy() {
        return detectedBy;
    }

    public List<CtxSurfaceAttribute> getAttributeRows() {
        return attributeRows;
    }

    public void addAttribute(CtxSurfaceAttribute attribute) {
        attributeRows.add(attribute);
    }
}
