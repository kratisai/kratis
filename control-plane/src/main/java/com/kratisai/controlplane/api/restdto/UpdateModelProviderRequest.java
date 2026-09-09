package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Request payload for updating a model provider")
public record UpdateModelProviderRequest(
        @Schema(description = "Display name for the provider", example = "My Updated Provider")
        @Size(max = 255, message = "Display name must be at most 255 characters")
        String displayName,

        @Schema(description = "API key for authentication", example = "sk-...")
        String apiKey,

        @Schema(description = "Base URL for API", example = "https://api.openai.com/v1")
        @Size(max = 500, message = "Base URL must be at most 500 characters")
        String baseUrl,

        @Schema(description = "Whether the provider is active", example = "true")
        Boolean isActive,

        @Schema(description = "List of models with their kinds")
        List<ModelEntryDto> models) {}
