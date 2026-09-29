package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Remote repository detail returned by integrations lookup")
public record RemoteRepositoryDto(
        @Schema(description = "Repository name", example = "kratis-app")
        String name,

        @Schema(description = "Clone URL (HTTPS)", example = "https://github.com/myorg/kratis-app.git")
        String cloneUrl,

        @Schema(description = "Clone URL (SSH)", example = "git@github.com:myorg/kratis-app.git")
        String sshUrl,

        @Schema(description = "Default branch", example = "main")
        String defaultBranch) {}
