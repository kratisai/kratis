package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.validation.ValidPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for user registration")
public record RegisterUserRequest(
        @Schema(description = "User email address", example = "user@example.com")
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        String email,

        @Schema(description = "User password (min 8 characters)", example = "secure-password")
        @NotBlank(message = "Password is required")
        @ValidPassword
        String password,

        @Schema(description = "User display name", example = "John Doe")
        @NotBlank(message = "Display name is required")
        @Size(max = 255, message = "Display name must be at most 255 characters")
        String displayName) {}
