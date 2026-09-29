package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Repository credential representation")
public record RepoCredentialDto(
        @Schema(description = "Credential ID", example = "550e8400-e29b-41d4-a716-446655440010")
        UUID id,

        @Schema(description = "Credential Name", example = "Dave's GitHub PAT")
        String name,

        @Schema(description = "Credential Type", example = "pat")
        String type,

        @Schema(description = "Public Key (for SSH Deploy Keys only)", example = "ssh-rsa AAAAB3...")
        String publicKey,

        @Schema(description = "Provider Metadata", example = "{\"username\": \"git-user\"}")
        String providerMetadata,

        @Schema(description = "Creation timestamp", example = "2026-05-19T10:00:00Z")
        Instant createdAt) {}
