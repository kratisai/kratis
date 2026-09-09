package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.RepositoryType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for updating a repository")
public record UpdateRepositoryRequest(
        @Schema(description = "Repository name", example = "my-project-renamed")
        @Size(max = 255, message = "Repository name must be at most 255 characters")
        String name,

        @Schema(description = "Branch name", example = "develop")
        @Size(max = 255, message = "Branch name must be at most 255 characters")
        String branch,

        @Schema(description = "Repository type / provider", example = "GITHUB")
        RepositoryType repositoryType,

        @Schema(
                description = "Linked credential ID for private repositories",
                example = "550e8400-e29b-41d4-a716-446655440010")
        java.util.UUID credentialId) {}
