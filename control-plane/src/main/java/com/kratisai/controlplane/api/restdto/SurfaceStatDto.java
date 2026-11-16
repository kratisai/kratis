package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
import java.util.Objects;

public record SurfaceStatDto(SurfaceRole role, String description, SurfaceDetection detectedBy, int attributeCount) {
    public SurfaceStatDto {
        Objects.requireNonNull(role, "role is required");
        Objects.requireNonNull(description, "description is required");
        Objects.requireNonNull(detectedBy, "detectedBy is required");
        if (attributeCount < 0) {
            throw new IllegalArgumentException("attributeCount must not be negative");
        }
    }
}
