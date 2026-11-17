package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Team wiki batch log entry")
public record TeamWikiBatchLogDto(
        @Schema(description = "Log ID") UUID id,
        @Schema(description = "Log level") String level,
        @Schema(description = "Pipeline step") String step,
        @Schema(description = "Log message") String message,
        @Schema(description = "Timestamp") Instant createdAt) {}
