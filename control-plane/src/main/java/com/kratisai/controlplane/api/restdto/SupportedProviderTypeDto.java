package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Metadata for a supported provider type")
public record SupportedProviderTypeDto(
        @Schema(description = "Provider type enum value", example = "OPENAI")
        String type,

        @Schema(description = "Human-readable display name", example = "OpenAI")
        String displayName,

        @Schema(description = "Default API base URL", example = "https://api.openai.com/v1")
        String defaultBaseUrl,

        @Schema(description = "Whether a custom base URL is required", example = "false")
        boolean requiresBaseUrl,

        @Schema(description = "Whether an API key is required for authentication", example = "true")
        boolean requiresApiKey,

        @Schema(description = "Short description of the provider", example = "OpenAI API (GPT-4, GPT-4o, etc.)")
        String description) {}
