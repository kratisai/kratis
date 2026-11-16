package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.Ceiling;
import com.kratisai.controlplane.model.Nature;
import java.util.Objects;
import java.util.UUID;

public record RelationStatDto(
        Nature nature,
        RelationSide currentSide,
        Ceiling confidence,
        UUID otherRepositoryId,
        String otherRepositoryName) {
    public RelationStatDto {
        Objects.requireNonNull(nature, "nature is required");
        Objects.requireNonNull(currentSide, "currentSide is required");
        Objects.requireNonNull(confidence, "confidence is required");
        Objects.requireNonNull(otherRepositoryId, "otherRepositoryId is required");
        Objects.requireNonNull(otherRepositoryName, "otherRepositoryName is required");
    }
}
