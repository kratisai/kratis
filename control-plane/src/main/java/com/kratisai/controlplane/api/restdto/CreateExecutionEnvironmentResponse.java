package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response containing the created execution environment and installation command")
public record CreateExecutionEnvironmentResponse(
        @Schema(description = "The created execution environment details")
        ExecutionEnvironmentDto environment,

        @Schema(
                description = "The installation command to register the connector",
                example = "kratis-connector --mode=daemon --server-url=ws://localhost:8080/ws/env --token=abc123")
        String installCommand) {}
