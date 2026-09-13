package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Request payload for creating a model provider")
public record CreateModelProviderRequest(
        @Schema(description = "Display name for the provider", example = "My OpenAI Account")
        @NotBlank(message = "Display name is required")
        @Size(max = 255, message = "Display name must be at most 255 characters")
        String displayName,

        @Schema(description = "Provider type", example = "OPENAI") @NotNull(message = "Provider type is required")
        ProviderType providerType,

        @Schema(description = "API key for authentication", example = "sk-...")
        @Size(
                max = ModelProvider.API_KEY_MAX_LENGTH,
                message = "API key must be at most " + ModelProvider.API_KEY_MAX_LENGTH + " characters")
        String apiKey,

        @Schema(description = "Base URL for API", example = "https://api.openai.com/v1")
        @Size(max = 500, message = "Base URL must be at most 500 characters")
        String baseUrl,

        @Schema(description = "List of models with their kinds")
        List<ModelEntryDto> models) {}
