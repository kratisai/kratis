package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.RegisterRepositoryRequest;
import com.kratisai.controlplane.api.restdto.RepositoryDto;
import com.kratisai.controlplane.api.restdto.UpdateRepositoryRequest;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.service.RepositoryService;
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
@RequestMapping("/api/v1/teams/{teamId}/repositories")
@Tag(name = "Repositories", description = "Repository management endpoints scoped to teams")
@SecurityRequirement(name = "bearerAuth")
public class RepositoryController {

    private final RepositoryService repositoryService;

    public RepositoryController(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @PostMapping
    @Operation(summary = "Register a repository", description = "Registers a new repository for the given team")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Repository registered",
                        content = @Content(schema = @Schema(implementation = RepositoryDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "409", description = "Repository name already exists", content = @Content)
            })
    public ResponseEntity<RepositoryDto> registerRepository(
            @PathVariable UUID teamId, @Valid @RequestBody RegisterRepositoryRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        RepositoryDto repo = repositoryService.registerRepository(userId, teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(repo);
    }

    @GetMapping
    @Operation(summary = "List repositories", description = "Lists all repositories for the given team")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "List of repositories",
                        content = @Content(schema = @Schema(implementation = RepositoryDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content)
            })
    public ResponseEntity<List<RepositoryDto>> listRepositories(@PathVariable UUID teamId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(repositoryService.listRepositories(userId, teamId));
    }

    @GetMapping("/{repoId}")
    @Operation(summary = "Get repository details", description = "Returns detailed repository information")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Repository details",
                        content = @Content(schema = @Schema(implementation = RepositoryDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Repository not found", content = @Content)
            })
    public ResponseEntity<RepositoryDto> getRepository(@PathVariable UUID teamId, @PathVariable UUID repoId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(repositoryService.getRepository(userId, teamId, repoId));
    }

    @PutMapping("/{repoId}")
    @Operation(summary = "Update a repository", description = "Updates repository name, branch, or auth token")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Repository updated",
                        content = @Content(schema = @Schema(implementation = RepositoryDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Repository not found", content = @Content)
            })
    public ResponseEntity<RepositoryDto> updateRepository(
            @PathVariable UUID teamId, @PathVariable UUID repoId, @Valid @RequestBody UpdateRepositoryRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(repositoryService.updateRepository(userId, teamId, repoId, request));
    }

    @DeleteMapping("/{repoId}")
    @Operation(summary = "Delete a repository", description = "Deletes a repository from the team")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "204", description = "Repository deleted"),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Repository not found", content = @Content)
            })
    public ResponseEntity<Void> deleteRepository(@PathVariable UUID teamId, @PathVariable UUID repoId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        repositoryService.deleteRepository(userId, teamId, repoId);
        return ResponseEntity.noContent().build();
    }
}
