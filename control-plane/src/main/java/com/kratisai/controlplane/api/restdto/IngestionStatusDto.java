package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.IngestionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Ingestion status and batch information for a repository")
public record IngestionStatusDto(
        @Schema(description = "Batch ID", example = "550e8400-e29b-41d4-a716-446655440000")
        String batchId,

        @Schema(description = "Current ingestion status", example = "QUEUED")
        IngestionStatus status,

        @Schema(description = "Git commit hash of the ingestion", example = "abc123def456", nullable = true)
        String commitHash,

        @Schema(description = "Last ingestion timestamp", example = "2026-05-19T10:05:00Z", nullable = true)
        Instant lastIngestedAt,

        @Schema(
                description = "Position in the ingestion queue (1-based, only set when status is QUEUED)",
                example = "1",
                nullable = true)
        Integer queuePosition,

        @Schema(description = "Batch started at timestamp", example = "2026-05-19T10:00:00Z", nullable = true)
        Instant startedAt,

        @Schema(description = "Batch completed at timestamp", example = "2026-05-19T10:05:00Z", nullable = true)
        Instant completedAt,

        @Schema(description = "Error message if failed", nullable = true)
        String errorMessage) {}
