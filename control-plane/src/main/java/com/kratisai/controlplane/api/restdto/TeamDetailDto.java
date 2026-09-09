package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Detailed team information including members")
public record TeamDetailDto(
        @Schema(description = "Team ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "Team name", example = "My Team")
        String name,

        @Schema(description = "Team description", example = "A team for my project")
        String description,

        @Schema(description = "User's role in the team", example = "owner")
        String role,

        @Schema(description = "Whether this is the user's default team", example = "true")
        boolean isDefault,

        @Schema(description = "Whether Tavily API key is configured", example = "true")
        boolean tavilyApiKeyConfigured,

        @Schema(description = "Ingestion provider ID configured for the team")
        UUID ingestionProvider,

        @Schema(description = "Ingestion model configured for the team", example = "gpt-4o")
        String ingestionModel,

        @Schema(description = "Embedding provider ID configured for the team")
        UUID embeddingProvider,

        @Schema(description = "Embedding model configured for the team", example = "text-embedding-3-small")
        String embeddingModel,

        @Schema(description = "Team members") List<TeamMemberDto> members,

        @Schema(description = "Creation timestamp", example = "2026-05-19T10:00:00Z")
        Instant createdAt,

        @Schema(description = "Last update timestamp", example = "2026-05-19T10:05:00Z")
        Instant updatedAt) {}
