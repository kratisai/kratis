package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Execution environment information")
public record ExecutionEnvironmentDto(
        @Schema(description = "Environment ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "Team ID", example = "550e8400-e29b-41d4-a716-446655440001")
        UUID teamId,

        @Schema(description = "Environment name", example = "default-sandbox")
        String name,

        @Schema(description = "Environment type", example = "SANDBOX")
        ExecutionEnvironmentType type,

        @Schema(description = "Authentication token", example = "conn_tok_xxx", nullable = true)
        String authToken,

        @Schema(description = "Environment status", example = "DISCONNECTED")
        EnvironmentStatus status,

        @Schema(description = "Container ID", example = "abc123def456", nullable = true)
        String containerId,

        @Schema(description = "Last heartbeat timestamp", example = "2026-05-19T10:05:00Z", nullable = true)
        Instant lastHeartbeat) {}
