package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.TeamWikiBatchDto;
import com.kratisai.controlplane.api.restdto.TeamWikiBatchLogDto;
import com.kratisai.controlplane.api.restdto.TeamWikiRebuildRequest;
import com.kratisai.controlplane.api.restdto.TeamWikiStatusDto;
import com.kratisai.controlplane.api.restdto.WikiPageDto;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiBatchLogService;
import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiSchedulerService;
import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiSchedulerService.TeamWikiScheduleStatus;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.model.TeamWikiBuildMode;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/wiki")
@Tag(name = "Team Wiki", description = "Team-wide wiki page, status, and rebuild endpoints")
@SecurityRequirement(name = "bearerAuth")
public class TeamWikiController {

    private final CtxWikiPageRepository wikiPageRepository;
    private final TeamWikiBatchRepository teamWikiBatchRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamWikiSchedulerService schedulerService;
    private final TeamWikiBatchLogService batchLogService;

    public TeamWikiController(
            CtxWikiPageRepository wikiPageRepository,
            TeamWikiBatchRepository teamWikiBatchRepository,
            TeamMemberRepository teamMemberRepository,
            TeamWikiSchedulerService schedulerService,
            TeamWikiBatchLogService batchLogService) {
        this.wikiPageRepository = wikiPageRepository;
        this.teamWikiBatchRepository = teamWikiBatchRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.schedulerService = schedulerService;
        this.batchLogService = batchLogService;
    }

    private void requireTeamMembership(UUID userId, UUID teamId) {
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }

    private TeamWikiBatch resolveActiveBatch(UUID teamId) {
        return teamWikiBatchRepository
                .findByTeamIdAndIsActiveTrue(teamId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "No active team wiki found for this team"));
    }

    private WikiPageDto toPageDto(CtxWikiPage page, boolean hasChildren) {
        return new WikiPageDto(
                page.getId(),
                page.getRepoName(),
                page.getParentPage() != null ? page.getParentPage().getId() : null,
                page.getPageSlug(),
                page.getTitle(),
                page.getOrderIndex(),
                page.getContent(),
                hasChildren);
    }

    private static TeamWikiBatchDto toBatchDto(TeamWikiBatch batch) {
        if (batch == null) {
            return null;
        }
        int repoCount = batch.getSourceSnapshot() != null
                ? batch.getSourceSnapshot().repos().size()
                : 0;
        return new TeamWikiBatchDto(
                batch.getId(),
                batch.getStatus(),
                batch.isActive(),
                batch.getBuildMode(),
                batch.getTriggerKind(),
                repoCount,
                batch.getStartedAt(),
                batch.getCompletedAt(),
                batch.getErrorMessage(),
                batch.getTotalToolCalls());
    }

    @GetMapping("/status")
    @Operation(summary = "Get team wiki status", description = "Returns active build metadata, staleness, and history")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Status retrieved",
                content = @Content(schema = @Schema(implementation = TeamWikiStatusDto.class))),
        @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content)
    })
    public ResponseEntity<TeamWikiStatusDto> getStatus(@PathVariable UUID teamId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);
        TeamWikiScheduleStatus status = schedulerService.computeStatus(teamId);
        List<TeamWikiBatchDto> recent = status.recentBatches().stream()
                .limit(20)
                .map(TeamWikiController::toBatchDto)
                .toList();
        return ResponseEntity.ok(new TeamWikiStatusDto(
                status.ingestedRepoCount(),
                status.dirty(),
                status.nonStructuralChangedRepoCount(),
                status.deferralReason(),
                status.nextEligibleAt(),
                toBatchDto(status.activeBatch()),
                toBatchDto(status.latestBatch()),
                recent));
    }

    @GetMapping("/pages")
    @Operation(summary = "List top-level team wiki pages", description = "Returns top-level team wiki pages")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Top-level pages retrieved",
                content = @Content(schema = @Schema(implementation = WikiPageDto.class))),
        @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
        @ApiResponse(responseCode = "404", description = "No active team wiki found", content = @Content)
    })
    public ResponseEntity<List<WikiPageDto>> getTopLevelPages(@PathVariable UUID teamId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);
        TeamWikiBatch activeBatch = resolveActiveBatch(teamId);
        List<CtxWikiPage> pages =
                wikiPageRepository.findByTeamWikiBatchIdAndParentPageIsNullOrderByOrderIndexAsc(activeBatch.getId());
        List<WikiPageDto> dtos = pages.stream()
                .map(p -> {
                    boolean hasChildren = !wikiPageRepository
                            .findByTeamWikiBatchIdAndParentPageIdOrderByOrderIndexAsc(activeBatch.getId(), p.getId())
                            .isEmpty();
                    return toPageDto(p, hasChildren);
                })
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/pages/{pageId}/children")
    @Operation(summary = "List child team wiki pages", description = "Returns direct child pages of a team wiki page")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Child pages retrieved",
                content = @Content(schema = @Schema(implementation = WikiPageDto.class))),
        @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
        @ApiResponse(responseCode = "404", description = "No active team wiki found", content = @Content)
    })
    public ResponseEntity<List<WikiPageDto>> getChildPages(@PathVariable UUID teamId, @PathVariable UUID pageId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);
        TeamWikiBatch activeBatch = resolveActiveBatch(teamId);
        List<CtxWikiPage> children = wikiPageRepository.findByTeamWikiBatchIdAndParentPageIdOrderByOrderIndexAsc(
                activeBatch.getId(), pageId);
        List<WikiPageDto> dtos = children.stream()
                .map(p -> {
                    boolean hasChildren = !wikiPageRepository
                            .findByTeamWikiBatchIdAndParentPageIdOrderByOrderIndexAsc(activeBatch.getId(), p.getId())
                            .isEmpty();
                    return toPageDto(p, hasChildren);
                })
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/pages/{pageId}")
    @Operation(summary = "Get a team wiki page", description = "Returns a single team wiki page by ID")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Page retrieved",
                content = @Content(schema = @Schema(implementation = WikiPageDto.class))),
        @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
        @ApiResponse(responseCode = "404", description = "Team wiki or page not found", content = @Content)
    })
    public ResponseEntity<WikiPageDto> getPage(@PathVariable UUID teamId, @PathVariable UUID pageId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);
        TeamWikiBatch activeBatch = resolveActiveBatch(teamId);
        CtxWikiPage page = wikiPageRepository
                .findByTeamWikiBatchIdAndId(activeBatch.getId(), pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team wiki page not found"));
        boolean hasChildren = !wikiPageRepository
                .findByTeamWikiBatchIdAndParentPageIdOrderByOrderIndexAsc(activeBatch.getId(), pageId)
                .isEmpty();
        return ResponseEntity.ok(toPageDto(page, hasChildren));
    }

    @PostMapping("/rebuild")
    @Operation(summary = "Trigger manual team wiki rebuild", description = "Enqueues an incremental or full rebuild")
    @ApiResponses({
        @ApiResponse(
                responseCode = "202",
                description = "Rebuild queued",
                content = @Content(schema = @Schema(implementation = TeamWikiBatchDto.class))),
        @ApiResponse(responseCode = "400", description = "Preconditions not met", content = @Content),
        @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
        @ApiResponse(responseCode = "409", description = "Build already in progress", content = @Content)
    })
    public ResponseEntity<TeamWikiBatchDto> triggerRebuild(
            @PathVariable UUID teamId, @RequestBody(required = false) TeamWikiRebuildRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);
        TeamWikiBuildMode mode = request != null ? request.mode() : null;
        TeamWikiBatch batch = schedulerService.triggerManualBuild(teamId, mode);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toBatchDto(batch));
    }

    @GetMapping("/batches/{batchId}/logs")
    @Operation(summary = "Get team wiki batch logs", description = "Returns ordered step logs for a team wiki batch")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Logs retrieved",
                content = @Content(schema = @Schema(implementation = TeamWikiBatchLogDto.class))),
        @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
        @ApiResponse(responseCode = "404", description = "Batch not found", content = @Content)
    })
    public ResponseEntity<List<TeamWikiBatchLogDto>> getBatchLogs(
            @PathVariable UUID teamId, @PathVariable UUID batchId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);
        TeamWikiBatch batch = teamWikiBatchRepository
                .findByIdWithTeam(batchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team wiki batch not found"));
        if (!batch.getTeam().getId().equals(teamId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Team wiki batch not found");
        }
        List<TeamWikiBatchLogDto> logs = batchLogService.getLogsForBatch(batchId).stream()
                .map(l ->
                        new TeamWikiBatchLogDto(l.getId(), l.getLevel(), l.getStep(), l.getMessage(), l.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(logs);
    }
}
