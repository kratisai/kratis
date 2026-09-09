package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Authentication tokens returned after login")
public record AuthTokensResponse(
        @Schema(description = "JWT access token", example = "eyJhbGciOiJIUzI1NiIs...")
        String accessToken,

        @Schema(description = "Refresh token for obtaining new access tokens", example = "dGhpcyBpcyBhIHJlZnJl...")
        String refreshToken,

        @Schema(description = "Access token expiration in seconds", example = "3600")
        long expiresIn,

        @Schema(description = "Authenticated user information")
        UserDto user) {}
