package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.ProviderType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Model provider configuration information")
public record ModelProviderDto(
        @Schema(description = "Provider ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "Team ID", example = "550e8400-e29b-41d4-a716-446655440001")
        UUID teamId,

        @Schema(description = "Provider display name", example = "My OpenAI Account")
        String displayName,

        @Schema(description = "Provider type", example = "OPENAI")
        ProviderType providerType,

        @Schema(description = "Base URL for API", example = "https://api.openai.com/v1")
        String baseUrl,

        @Schema(description = "Whether the provider is active", example = "true")
        boolean isActive,

        @Schema(description = "List of models with their kinds")
        List<ModelEntryDto> models,

        @Schema(description = "Creation timestamp", example = "2026-05-19T10:00:00Z")
        Instant createdAt,

        @Schema(description = "Last update timestamp", example = "2026-05-19T10:05:00Z")
        Instant updatedAt) {}
