package com.kratisai.controlplane.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Objects;

@Embeddable
public class ProviderModel {

    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;

    @Column(name = "base_model", length = 255)
    private String baseModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_kind", nullable = false, length = 20)
    private ModelKind kind;

    @Column(name = "context_window_tokens")
    private Long contextWindowTokens;

    public ProviderModel() {}

    public ProviderModel(String modelName, ModelKind kind) {
        this(modelName, null, kind);
    }

    public ProviderModel(String modelName, String baseModel, ModelKind kind) {
        this(modelName, baseModel, kind, null);
    }

    public ProviderModel(String modelName, String baseModel, ModelKind kind, Long contextWindowTokens) {
        this.modelName = modelName;
        this.baseModel = baseModel;
        this.kind = kind;
        this.contextWindowTokens = contextWindowTokens;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getBaseModel() {
        return baseModel;
    }

    public void setBaseModel(String baseModel) {
        this.baseModel = baseModel;
    }

    public ModelKind getKind() {
        return kind;
    }

    public void setKind(ModelKind kind) {
        this.kind = kind;
    }

    public Long getContextWindowTokens() {
        return contextWindowTokens;
    }

    public void setContextWindowTokens(Long contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProviderModel that = (ProviderModel) o;
        return Objects.equals(modelName, that.modelName)
                && Objects.equals(baseModel, that.baseModel)
                && kind == that.kind
                && Objects.equals(contextWindowTokens, that.contextWindowTokens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelName, baseModel, kind, contextWindowTokens);
    }
}
