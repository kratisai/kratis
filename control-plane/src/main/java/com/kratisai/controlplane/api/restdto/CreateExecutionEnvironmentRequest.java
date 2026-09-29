package com.kratisai.controlplane.api.restdto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to create a new workspace connector execution environment")
public record CreateExecutionEnvironmentRequest(
        @Schema(description = "Connector name", example = "my-workspace-connector", requiredMode = REQUIRED)
        @NotBlank(message = "Name is required")
        String name) {}
