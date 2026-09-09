package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "User information returned after registration or login")
public record UserDto(
        @Schema(description = "Unique user identifier", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "User email address", example = "user@example.com")
        String email,

        @Schema(description = "User display name", example = "John Doe")
        String displayName,

        @Schema(description = "Account creation timestamp", example = "2026-05-19T10:00:00Z")
        Instant createdAt) {}
