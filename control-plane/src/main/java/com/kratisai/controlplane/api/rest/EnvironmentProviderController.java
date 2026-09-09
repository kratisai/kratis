package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.CreateEnvironmentProviderRequest;
import com.kratisai.controlplane.api.restdto.EnvironmentProviderDto;
import com.kratisai.controlplane.api.restdto.UpdateEnvironmentProviderRequest;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.service.EnvironmentProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/environment-providers")
@Tag(name = "Environment Providers", description = "Environment provider management endpoints scoped to teams")
@SecurityRequirement(name = "bearerAuth")
public class EnvironmentProviderController {

    private final EnvironmentProviderService environmentProviderService;

    public EnvironmentProviderController(EnvironmentProviderService environmentProviderService) {
        this.environmentProviderService = environmentProviderService;
    }

    @GetMapping
    @Operation(summary = "List providers", description = "Lists all environment providers for the given team")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "List of environment providers",
                        content = @Content(schema = @Schema(implementation = EnvironmentProviderDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content)
            })
    public ResponseEntity<List<EnvironmentProviderDto>> listProviders(@PathVariable UUID teamId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(environmentProviderService.listProviders(userId, teamId));
    }

    @PostMapping
    @Operation(summary = "Create a provider", description = "Creates a new environment provider for the given team")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Provider created",
                        content = @Content(schema = @Schema(implementation = EnvironmentProviderDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content)
            })
    public ResponseEntity<EnvironmentProviderDto> createProvider(
            @PathVariable UUID teamId, @Valid @RequestBody CreateEnvironmentProviderRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        EnvironmentProviderDto provider = environmentProviderService.createProvider(userId, teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(provider);
    }

    @PutMapping("/{providerId}")
    @Operation(summary = "Update a provider", description = "Updates an existing environment provider")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Provider updated",
                        content = @Content(schema = @Schema(implementation = EnvironmentProviderDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Provider not found", content = @Content)
            })
    public ResponseEntity<EnvironmentProviderDto> updateProvider(
            @PathVariable UUID teamId,
            @PathVariable UUID providerId,
            @Valid @RequestBody UpdateEnvironmentProviderRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(environmentProviderService.updateProvider(userId, teamId, providerId, request));
    }

    @DeleteMapping("/{providerId}")
    @Operation(summary = "Delete a provider", description = "Deletes an environment provider from the team")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "204", description = "Provider deleted"),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Provider not found", content = @Content)
            })
    public ResponseEntity<Void> deleteProvider(@PathVariable UUID teamId, @PathVariable UUID providerId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        environmentProviderService.deleteProvider(userId, teamId, providerId);
        return ResponseEntity.noContent().build();
    }
}
