package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Environment provider information")
public record EnvironmentProviderDto(
        @Schema(description = "Provider ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "Team ID", example = "550e8400-e29b-41d4-a716-446655440001")
        UUID teamId,

        @Schema(description = "Provider name", example = "Default Docker Provider")
        String name,

        @Schema(description = "Docker image", example = "kratis-runner-base:latest", nullable = true)
        String dockerImage) {}
