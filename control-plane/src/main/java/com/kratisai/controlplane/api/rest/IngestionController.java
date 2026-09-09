package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.IngestionBatchLogDto;
import com.kratisai.controlplane.api.restdto.IngestionBatchStatsDto;
import com.kratisai.controlplane.api.restdto.IngestionStatusDto;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionCoordinatorService;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.IngestionStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/repositories/{repoId}")
@Tag(name = "Ingestion", description = "Repository ingestion endpoints")
@SecurityRequirement(name = "bearerAuth")
public class IngestionController {

    private final IngestionCoordinatorService ingestionCoordinatorService;
    private final RepositoryRepository repositoryRepository;
    private final IngestionBatchRepository ingestionBatchRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final IngestionBatchLogService ingestionBatchLogService;
    private final IngestionStatsService ingestionStatsService;

    public IngestionController(
            IngestionCoordinatorService ingestionCoordinatorService,
            RepositoryRepository repositoryRepository,
            IngestionBatchRepository ingestionBatchRepository,
            TeamMemberRepository teamMemberRepository,
            IngestionBatchLogService ingestionBatchLogService,
            IngestionStatsService ingestionStatsService) {
        this.ingestionCoordinatorService = ingestionCoordinatorService;
        this.repositoryRepository = repositoryRepository;
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.ingestionBatchLogService = ingestionBatchLogService;
        this.ingestionStatsService = ingestionStatsService;
    }

    private void requireTeamMembership(UUID userId, UUID teamId) {
        boolean isMember = teamMemberRepository.existsByTeamIdAndUserId(teamId, userId);
        if (!isMember) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }

    @PostMapping("/ingest")
    @Operation(
            summary = "Trigger repository ingestion",
            description = "Starts the ingestion pipeline for the specified repository")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "202",
                        description = "Ingestion started",
                        content = @Content(schema = @Schema(implementation = IngestionStatusDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Repository not found", content = @Content)
            })
    public ResponseEntity<IngestionStatusDto> triggerIngestion(@PathVariable UUID teamId, @PathVariable UUID repoId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);

        // Verify repository exists and belongs to the specified team
        repositoryRepository
                .findByTeamIdAndId(teamId, repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found"));

        IngestionBatch batch = ingestionCoordinatorService.triggerIngestion(repoId);

        // Calculate queue position for the newly queued batch
        int positionBefore = ingestionBatchRepository.countQueuedBatchesBefore(batch.getStartedAt(), batch.getId());

        IngestionStatusDto dto = new IngestionStatusDto(
                batch.getId().toString(),
                batch.getStatus(),
                null,
                null,
                positionBefore + 1,
                batch.getStartedAt(),
                null,
                null);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(dto);
    }

    @GetMapping("/ingestion-status")
    @Operation(
            summary = "Get ingestion status",
            description = "Returns the current ingestion status for the specified repository")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Ingestion status retrieved",
                        content = @Content(schema = @Schema(implementation = IngestionStatusDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Repository not found", content = @Content)
            })
    public ResponseEntity<IngestionStatusDto> getIngestionStatus(@PathVariable UUID teamId, @PathVariable UUID repoId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);

        // Verify repository exists
        repositoryRepository
                .findByTeamIdAndId(teamId, repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found"));

        Optional<IngestionBatch> latestBatch =
                ingestionBatchRepository.findFirstByRepositoryIdOrderByStartedAtDesc(repoId);

        if (latestBatch.isEmpty()) {
            return ResponseEntity.ok(null);
        }

        IngestionBatch batch = latestBatch.get();

        Integer queuePosition = null;
        if (batch.getStatus() == IngestionStatus.QUEUED) {
            int positionBefore = ingestionBatchRepository.countQueuedBatchesBefore(batch.getStartedAt(), batch.getId());
            queuePosition = positionBefore + 1;
        }

        IngestionStatusDto dto = new IngestionStatusDto(
                batch.getId().toString(),
                batch.getStatus(),
                batch.getCommitHash(),
                batch.getCompletedAt(),
                queuePosition,
                batch.getStartedAt(),
                batch.getCompletedAt(),
                batch.getErrorMessage());

        return ResponseEntity.ok(dto);
    }

    @GetMapping("/batches")
    @Operation(
            summary = "Get ingestion batch history",
            description = "Returns the 10 most recent ingestion batches for the specified repository")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Batch history retrieved",
                        content = @Content(schema = @Schema(implementation = IngestionStatusDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Repository not found", content = @Content)
            })
    public ResponseEntity<List<IngestionStatusDto>> getBatchHistory(
            @PathVariable UUID teamId, @PathVariable UUID repoId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);

        // Verify repository exists
        repositoryRepository
                .findByTeamIdAndId(teamId, repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found"));

        List<IngestionStatusDto> batches = ingestionBatchRepository.findByRepositoryId(repoId).stream()
                .sorted((a, b) -> b.getStartedAt().compareTo(a.getStartedAt()))
                .limit(10)
                .map(batch -> new IngestionStatusDto(
                        batch.getId().toString(),
                        batch.getStatus(),
                        batch.getCommitHash(),
                        batch.getCompletedAt(),
                        null,
                        batch.getStartedAt(),
                        batch.getCompletedAt(),
                        batch.getErrorMessage()))
                .toList();

        return ResponseEntity.ok(batches);
    }

    @GetMapping("/batches/{batchId}/logs")
    @Operation(
            summary = "Get ingestion batch logs",
            description = "Returns all database logs generated during a specific ingestion batch")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "Batch logs retrieved successfully"),
                @ApiResponse(responseCode = "403", description = "Not a member of this team"),
                @ApiResponse(responseCode = "404", description = "Repository not found")
            })
    public ResponseEntity<List<IngestionBatchLogDto>> getBatchLogs(
            @PathVariable UUID teamId, @PathVariable UUID repoId, @PathVariable UUID batchId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);

        // Verify repository exists
        repositoryRepository
                .findByTeamIdAndId(teamId, repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found"));

        List<IngestionBatchLogDto> logs = ingestionBatchLogService.getLogsForBatch(batchId).stream()
                .map(log -> new IngestionBatchLogDto(
                        log.getId().toString(),
                        log.getBatch().getId().toString(),
                        log.getLevel(),
                        log.getStep(),
                        log.getMessage(),
                        log.getCreatedAt()))
                .toList();

        return ResponseEntity.ok(logs);
    }

    @GetMapping("/batches/{batchId}/stats")
    @Operation(
            summary = "Get ingestion batch statistics",
            description = "Returns statistics for a specific ingestion batch including node/edge counts and duration")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Batch statistics retrieved",
                        content = @Content(schema = @Schema(implementation = IngestionBatchStatsDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team"),
                @ApiResponse(responseCode = "404", description = "Repository or batch not found")
            })
    public ResponseEntity<IngestionBatchStatsDto> getBatchStats(
            @PathVariable UUID teamId, @PathVariable UUID repoId, @PathVariable UUID batchId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);

        // Verify repository exists
        repositoryRepository
                .findByTeamIdAndId(teamId, repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found"));

        return ingestionStatsService
                .getBatchStats(batchId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found"));
    }
}
