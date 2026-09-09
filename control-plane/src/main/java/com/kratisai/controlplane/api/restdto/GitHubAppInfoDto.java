package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "GitHub App configuration information")
public record GitHubAppInfoDto(
        @Schema(description = "GitHub App ID", example = "123456")
        String appId,

        @Schema(description = "GitHub App name", example = "kratis")
        String appName,

        @Schema(
                description = "URL to install the GitHub App",
                example = "https://github.com/apps/kratis/installations/new")
        String installationUrl,

        @Schema(description = "Whether GitHub App authentication is enabled for this deployment")
        boolean enabled) {}
