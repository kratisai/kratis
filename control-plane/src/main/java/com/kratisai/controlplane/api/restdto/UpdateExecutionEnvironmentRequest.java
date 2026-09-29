package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record UpdateExecutionEnvironmentRequest(
        @Schema(description = "Display name for the environment", example = "My Updated Environment")
        @Size(max = 255, message = "Display name must be at most 255 characters")
        String name) {}
