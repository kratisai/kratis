package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.ProviderType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for testing a provider connection and discovering models")
public record TestConnectionRequest(
        @Schema(description = "Provider type", example = "OPENAI") @NotNull(message = "Provider type is required")
        ProviderType providerType,

        @Schema(description = "API key for authentication", example = "sk-...")
        @Size(max = 500, message = "API key must be at most 500 characters")
        String apiKey,

        @Schema(description = "Base URL for API", example = "https://api.openai.com/v1")
        @Size(max = 500, message = "Base URL must be at most 500 characters")
        String baseUrl) {}
