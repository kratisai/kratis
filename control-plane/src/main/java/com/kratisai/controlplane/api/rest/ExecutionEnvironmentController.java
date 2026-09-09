package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.*;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.service.ExecutionEnvironmentService;
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
@RequestMapping("/api/v1/teams/{teamId}/environments")
@Tag(name = "Execution Environments", description = "Execution environment management endpoints scoped to teams")
@SecurityRequirement(name = "bearerAuth")
public class ExecutionEnvironmentController {

    private final ExecutionEnvironmentService executionEnvironmentService;

    public ExecutionEnvironmentController(ExecutionEnvironmentService executionEnvironmentService) {
        this.executionEnvironmentService = executionEnvironmentService;
    }

    @GetMapping
    @Operation(summary = "List environments", description = "Lists all execution environments for the given team")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "List of execution environments",
                        content = @Content(schema = @Schema(implementation = ExecutionEnvironmentDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content)
            })
    public ResponseEntity<List<ExecutionEnvironmentDto>> listEnvironments(@PathVariable UUID teamId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(executionEnvironmentService.listEnvironments(userId, teamId));
    }

    @PostMapping
    @Operation(
            summary = "Create a new execution environment",
            description = "Creates a new execution environment (e.g., a workspace connector) for the given team")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Environment created successfully",
                        content =
                                @Content(schema = @Schema(implementation = CreateExecutionEnvironmentResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid request payload", content = @Content),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content)
            })
    public ResponseEntity<CreateExecutionEnvironmentResponse> createEnvironment(
            @PathVariable UUID teamId, @Valid @RequestBody CreateExecutionEnvironmentRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        CreateExecutionEnvironmentResponse response =
                executionEnvironmentService.createConnector(userId, teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{envId}")
    @Operation(summary = "Update an environment", description = "Updates an existing environment")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Environment updated",
                        content = @Content(schema = @Schema(implementation = ExecutionEnvironmentDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Environment not found", content = @Content)
            })
    public ResponseEntity<ExecutionEnvironmentDto> updateEnvironment(
            @PathVariable UUID teamId,
            @PathVariable UUID envId,
            @Valid @RequestBody UpdateExecutionEnvironmentRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(executionEnvironmentService.updateEnvironment(userId, teamId, envId, request));
    }

    @PostMapping("/{envId}/terminate")
    @Operation(summary = "Terminate an environment", description = "Terminates a running sandbox execution environment")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "204", description = "Environment terminated"),
                @ApiResponse(
                        responseCode = "400",
                        description = "Environment is not a running sandbox",
                        content = @Content),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Environment not found", content = @Content)
            })
    public ResponseEntity<Void> terminateEnvironment(@PathVariable UUID teamId, @PathVariable UUID envId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        executionEnvironmentService.terminateEnvironment(userId, teamId, envId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{envId}")
    @Operation(summary = "Delete an environment", description = "Deletes an execution environment from the team")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "204", description = "Environment deleted"),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Environment not found", content = @Content)
            })
    public ResponseEntity<Void> deleteEnvironment(@PathVariable UUID teamId, @PathVariable UUID envId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        executionEnvironmentService.deleteEnvironment(userId, teamId, envId);
        return ResponseEntity.noContent().build();
    }
}
