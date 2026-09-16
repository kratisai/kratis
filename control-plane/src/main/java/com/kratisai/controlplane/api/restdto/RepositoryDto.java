package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.RepositoryType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Repository information")
public record RepositoryDto(
        @Schema(description = "Repository ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "Repository name", example = "my-project")
        String name,

        @Schema(description = "Repository URL", example = "https://github.com/user/my-project.git")
        String url,

        @Schema(description = "Branch name", example = "main")
        String branch,

        @Schema(description = "Repository type / provider", example = "GITHUB")
        RepositoryType repositoryType,

        @Schema(description = "Team ID", example = "550e8400-e29b-41d4-a716-446655440001")
        UUID teamId,

        @Schema(description = "Linked credential ID", example = "550e8400-e29b-41d4-a716-446655440010")
        UUID credentialId,

        @Schema(description = "Creation timestamp", example = "2026-05-19T10:00:00Z")
        Instant createdAt,

        @Schema(description = "Last update timestamp", example = "2026-05-19T10:05:00Z")
        Instant updatedAt,

        @Schema(
                description = "ID of the latest ingestion batch, used to address batch logs and stats",
                example = "550e8400-e29b-41d4-a716-446655440020",
                nullable = true)
        UUID latestBatchId,

        @Schema(description = "Current ingestion status from latest batch", example = "SUCCESS", nullable = true)
        IngestionStatus ingestionStatus,

        @Schema(description = "Last successful ingestion timestamp", example = "2026-05-19T10:05:00Z", nullable = true)
        Instant lastIngestedAt,

        @Schema(description = "Git commit hash of last ingestion", example = "abc123def456", nullable = true)
        String commitHash,

        @Schema(
                description = "Position in the ingestion queue (1-based, only set when status is QUEUED)",
                example = "1",
                nullable = true)
        Integer queuePosition) {}
