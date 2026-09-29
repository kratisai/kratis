package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request payload for token refresh")
public record RefreshTokenRequest(
        @Schema(description = "Refresh token", example = "dGhpcyBpcyBhIHJlZnJl...")
        @NotBlank(message = "Refresh token is required")
        String refreshToken) {}
