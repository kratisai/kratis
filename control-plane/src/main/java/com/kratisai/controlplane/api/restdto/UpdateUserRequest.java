package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for updating user profile")
public record UpdateUserRequest(
        @Schema(description = "User display name", example = "John Doe")
        @Size(max = 255, message = "Display name must be at most 255 characters")
        String displayName,

        @Schema(description = "User email address", example = "user@example.com")
        @Email(message = "Email must be valid")
        String email) {}
