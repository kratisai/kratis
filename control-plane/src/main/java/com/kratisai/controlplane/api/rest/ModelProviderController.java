package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.*;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.service.ModelProviderService;
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
@RequestMapping("/api/v1/model-providers")
@Tag(name = "Model Providers", description = "Model provider management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class ModelProviderController {

    private final ModelProviderService modelProviderService;

    public ModelProviderController(ModelProviderService modelProviderService) {
        this.modelProviderService = modelProviderService;
    }

    @PostMapping("/test-connection")
    @Operation(
            summary = "Test provider connection",
            description = "Tests connection to a provider and discovers available models")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Connection test result",
                        content = @Content(schema = @Schema(implementation = TestConnectionResponse.class)))
            })
    public ResponseEntity<TestConnectionResponse> testConnection(@Valid @RequestBody TestConnectionRequest request) {
        return ResponseEntity.ok(modelProviderService.testConnection(request));
    }

    @GetMapping("/supported-types")
    @Operation(
            summary = "List supported provider types",
            description = "Returns metadata for all supported provider types")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "List of supported provider types",
                        content = @Content(schema = @Schema(implementation = SupportedProviderTypeDto.class)))
            })
    public ResponseEntity<List<SupportedProviderTypeDto>> getSupportedTypes() {
        return ResponseEntity.ok(modelProviderService.getSupportedTypes());
    }

    @PostMapping("/teams/{teamId}")
    @Operation(
            summary = "Create a model provider",
            description = "Creates a new model provider for the given team. Requires owner role.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Model provider created",
                        content = @Content(schema = @Schema(implementation = ModelProviderDto.class))),
                @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
                @ApiResponse(
                        responseCode = "409",
                        description = "Model provider name already exists",
                        content = @Content)
            })
    public ResponseEntity<ModelProviderDto> createModelProvider(
            @PathVariable UUID teamId, @Valid @RequestBody CreateModelProviderRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        ModelProviderDto provider = modelProviderService.createModelProvider(userId, teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(provider);
    }

    @GetMapping("/teams/{teamId}")
    @Operation(summary = "List model providers", description = "Lists all model providers for the given team")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "List of model providers",
                        content = @Content(schema = @Schema(implementation = ModelProviderDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content)
            })
    public ResponseEntity<List<ModelProviderDto>> listModelProviders(@PathVariable UUID teamId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(modelProviderService.listModelProviders(userId, teamId));
    }

    @GetMapping("/teams/{teamId}/{providerId}")
    @Operation(summary = "Get model provider details", description = "Returns detailed model provider information")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Model provider details",
                        content = @Content(schema = @Schema(implementation = ModelProviderDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Model provider not found", content = @Content)
            })
    public ResponseEntity<ModelProviderDto> getModelProvider(@PathVariable UUID teamId, @PathVariable UUID providerId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(modelProviderService.getModelProvider(userId, teamId, providerId));
    }

    @PutMapping("/teams/{teamId}/{providerId}")
    @Operation(
            summary = "Update a model provider",
            description = "Updates model provider configuration. Requires owner role.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Model provider updated",
                        content = @Content(schema = @Schema(implementation = ModelProviderDto.class))),
                @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
                @ApiResponse(responseCode = "404", description = "Model provider not found", content = @Content)
            })
    public ResponseEntity<ModelProviderDto> updateModelProvider(
            @PathVariable UUID teamId,
            @PathVariable UUID providerId,
            @Valid @RequestBody UpdateModelProviderRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(modelProviderService.updateModelProvider(userId, teamId, providerId, request));
    }

    @DeleteMapping("/teams/{teamId}/{providerId}")
    @Operation(
            summary = "Delete a model provider",
            description = "Deletes a model provider from the team. Requires owner role.")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "204", description = "Model provider deleted"),
                @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
                @ApiResponse(responseCode = "404", description = "Model provider not found", content = @Content)
            })
    public ResponseEntity<Void> deleteModelProvider(@PathVariable UUID teamId, @PathVariable UUID providerId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        modelProviderService.deleteModelProvider(userId, teamId, providerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{providerId}/discover-models")
    @Operation(
            summary = "Discover models for a provider",
            description = "Queries the provider's API to discover available models")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "List of discovered model entries with their kinds",
                        content = @Content(schema = @Schema(implementation = ModelEntryDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Model provider not found", content = @Content)
            })
    public ResponseEntity<List<ModelEntryDto>> discoverModels(@PathVariable UUID providerId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(modelProviderService.discoverModelsForProvider(userId, providerId));
    }
}
