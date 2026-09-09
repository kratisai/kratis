package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.RepositoryType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for registering a repository")
public record RegisterRepositoryRequest(
        @Schema(description = "Repository name", example = "my-project")
        @NotBlank(message = "Repository name is required")
        @Size(max = 255, message = "Repository name must be at most 255 characters")
        String name,

        @Schema(description = "Repository URL", example = "https://github.com/user/my-project.git")
        @NotBlank(message = "Repository URL is required")
        @Size(max = 500, message = "Repository URL must be at most 500 characters")
        String url,

        @Schema(description = "Branch name", example = "main")
        @Size(max = 255, message = "Branch name must be at most 255 characters")
        String branch,

        @Schema(description = "Repository type / provider", example = "GITHUB")
        @NotNull(message = "Repository type is required")
        RepositoryType repositoryType,

        @Schema(
                description = "Linked credential ID for private repositories",
                example = "550e8400-e29b-41d4-a716-446655440010")
        java.util.UUID credentialId) {}
