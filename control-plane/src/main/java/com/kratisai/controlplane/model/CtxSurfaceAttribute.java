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
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/** One evidence attribute of a surface interface: a closed kind and a plain value. */
@Entity
@Table(name = "ctx_surface_attributes")
public class CtxSurfaceAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "surface_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_ctx_surface_attributes_surface"))
    private CtxSurface surface;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private AttributeKind kind;

    @Column(name = "value", nullable = false, columnDefinition = "TEXT")
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "detected_by", nullable = false, length = 20)
    private SurfaceDetection detectedBy;

    public CtxSurfaceAttribute() {}

    public CtxSurfaceAttribute(CtxSurface surface, AttributeKind kind, String value, SurfaceDetection detectedBy) {
        this.detectedBy = Objects.requireNonNull(detectedBy, "detectedBy");
        this.surface = Objects.requireNonNull(surface, "surface");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.value = Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public SurfaceDetection getDetectedBy() {
        return detectedBy;
    }

    public UUID getId() {
        return id;
    }

    public CtxSurface getSurface() {
        return surface;
    }

    public AttributeKind getKind() {
        return kind;
    }

    public void setKind(AttributeKind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }
}
