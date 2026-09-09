package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for saving a repository credential")
public record SaveRepoCredentialRequest(
        @Schema(description = "Credential name", example = "Personal GitHub Token")
        @NotBlank(message = "Credential name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @Schema(description = "Credential type", example = "pat")
        @NotBlank(message = "Credential type is required")
        @Size(max = 50, message = "Type must be at most 50 characters")
        String type,

        @Schema(description = "Unencrypted token, password, or SSH private key (write-only)", example = "ghp_12345...")
        String secret,

        @Schema(description = "Unencrypted SSH public key", example = "ssh-rsa AAA...")
        String publicKey,

        @Schema(description = "Custom structured metadata in JSON string", example = "{\"username\": \"git-user\"}")
        String providerMetadata) {}
