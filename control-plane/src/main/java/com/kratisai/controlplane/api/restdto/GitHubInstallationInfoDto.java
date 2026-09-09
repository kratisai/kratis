package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "GitHub App installation validation result")
public record GitHubInstallationInfoDto(
        @Schema(description = "Installation ID", example = "12345678")
        String installationId,

        @Schema(description = "Account login (username or org) that owns the installation", example = "my-org")
        String accountLogin) {

    public static GitHubInstallationInfoDto of(String installationId, String accountLogin) {
        return new GitHubInstallationInfoDto(installationId, accountLogin);
    }
}
