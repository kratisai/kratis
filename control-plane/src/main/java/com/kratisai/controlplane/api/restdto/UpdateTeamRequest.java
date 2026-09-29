package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(description = "Request payload for updating a team")
public record UpdateTeamRequest(
        @Schema(description = "Team name", example = "My Updated Team")
        @Size(max = 255, message = "Team name must be at most 255 characters")
        String name,

        @Schema(description = "Team description", example = "Updated description")
        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description,

        @Schema(description = "Tavily API key for web search integration")
        @Size(max = 500, message = "API key must be at most 500 characters")
        String tavilyApiKey,

        @Schema(description = "Ingestion provider ID for agentic research")
        UUID ingestionProvider,

        @Schema(description = "Ingestion model for agentic research", example = "gpt-4o")
        @Size(max = 255, message = "Ingestion model must be at most 255 characters")
        String ingestionModel,

        @Schema(description = "Embedding provider ID for semantic indexing")
        UUID embeddingProvider,

        @Schema(description = "Embedding model for semantic indexing", example = "text-embedding-3-small")
        @Size(max = 255, message = "Embedding model must be at most 255 characters")
        String embeddingModel) {}
