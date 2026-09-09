package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for updating an environment provider")
public record UpdateEnvironmentProviderRequest(
        @Schema(description = "Provider name", example = "Java Docker Provider Updated")
        @Size(max = 100, message = "Provider name must be at most 100 characters")
        String name,

        @Schema(description = "Docker image", example = "java:17", nullable = true)
        @Size(max = 255, message = "Docker image must be at most 255 characters")
        String dockerImage) {}
