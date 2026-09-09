package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.GitHubAppInfoDto;
import com.kratisai.controlplane.api.restdto.InstallationInfoDto;
import com.kratisai.controlplane.config.GitHubAppConfig;
import com.kratisai.controlplane.config.TelemetryProperties;
import com.kratisai.controlplane.service.InstallationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/config")
@Tag(name = "Configuration", description = "Application configuration endpoints")
@SecurityRequirement(name = "bearerAuth")
public class ConfigController {

    private final GitHubAppConfig gitHubAppConfig;
    private final InstallationService installationService;
    private final TelemetryProperties telemetryProperties;

    public ConfigController(
            GitHubAppConfig gitHubAppConfig,
            InstallationService installationService,
            TelemetryProperties telemetryProperties) {
        this.gitHubAppConfig = gitHubAppConfig;
        this.installationService = installationService;
        this.telemetryProperties = telemetryProperties;
    }

    @GetMapping("/installation")
    @Operation(
            summary = "Get installation details",
            description = "Returns the unique installation ID and version for this Kratis instance")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Installation details",
                content = @Content(schema = @Schema(implementation = InstallationInfoDto.class)))
    })
    public ResponseEntity<InstallationInfoDto> getInstallationInfo() {
        UUID installId = installationService.getOrCreateInstallationId();
        return ResponseEntity.ok(new InstallationInfoDto(installId, telemetryProperties.getVersion()));
    }

    @GetMapping("/github-app")
    @Operation(
            summary = "Get GitHub App configuration",
            description = "Returns the GitHub App configuration for installation")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "GitHub App configuration",
                content = @Content(schema = @Schema(implementation = GitHubAppInfoDto.class)))
    })
    public ResponseEntity<GitHubAppInfoDto> getGitHubAppInfo() {
        boolean enabled = gitHubAppConfig.isEnabled();
        GitHubAppInfoDto appInfo = new GitHubAppInfoDto(
                enabled ? gitHubAppConfig.getAppId() : null,
                enabled ? gitHubAppConfig.getAppName() : null,
                enabled ? gitHubAppConfig.getInstallationUrl() : null,
                enabled);

        return ResponseEntity.ok(appInfo);
    }
}
