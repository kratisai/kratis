package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.WikiPageDto;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.CtxWikiPages;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/repositories/{repoId}/wiki")
@Tag(name = "Wiki", description = "Repository wiki page endpoints")
@SecurityRequirement(name = "bearerAuth")
public class WikiController {

    private final CtxWikiPageRepository wikiPageRepository;
    private final RepositoryRepository repositoryRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final IngestionBatchRepository ingestionBatchRepository;

    public WikiController(
            CtxWikiPageRepository wikiPageRepository,
            RepositoryRepository repositoryRepository,
            TeamMemberRepository teamMemberRepository,
            IngestionBatchRepository ingestionBatchRepository) {
        this.wikiPageRepository = wikiPageRepository;
        this.repositoryRepository = repositoryRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.ingestionBatchRepository = ingestionBatchRepository;
    }

    private void requireTeamMembership(UUID userId, UUID teamId) {
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }

    private void resolveRepo(UUID teamId, UUID repoId) {
        repositoryRepository
                .findByTeamIdAndId(teamId, repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found"));
    }

    private IngestionBatch resolveActiveBatch(UUID repoId) {
        return ingestionBatchRepository
                .findByRepositoryIdAndIsActiveTrue(repoId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No active ingestion batch found for this repository"));
    }

    private WikiPageDto toDto(CtxWikiPage page, boolean hasChildren) {
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

    private List<CtxWikiPage> orderedPages(IngestionBatch activeBatch) {
        return wikiPageRepository.findByBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(activeBatch.getId());
    }

    @GetMapping("/pages")
    @Operation(
            summary = "List top-level wiki pages",
            description =
                    "Returns all top-level wiki pages for the repository, ordered by orderIndex, then title, then slug")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Top-level pages retrieved",
                        content = @Content(schema = @Schema(implementation = WikiPageDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Repository not found", content = @Content)
            })
    public ResponseEntity<List<WikiPageDto>> getTopLevelPages(@PathVariable UUID teamId, @PathVariable UUID repoId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);
        resolveRepo(teamId, repoId);
        IngestionBatch activeBatch = resolveActiveBatch(repoId);

        List<CtxWikiPage> pages = orderedPages(activeBatch);

        List<WikiPageDto> dtos = CtxWikiPages.topLevelPages(pages).stream()
                .map(p -> toDto(p, CtxWikiPages.hasChildren(pages, p)))
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/pages/{pageId}/children")
    @Operation(
            summary = "List child wiki pages",
            description =
                    "Returns all direct child pages of the specified wiki page, ordered by orderIndex, then title, then slug")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Child pages retrieved",
                        content = @Content(schema = @Schema(implementation = WikiPageDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Repository or page not found", content = @Content)
            })
    public ResponseEntity<List<WikiPageDto>> getChildPages(
            @PathVariable UUID teamId, @PathVariable UUID repoId, @PathVariable UUID pageId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);
        resolveRepo(teamId, repoId);
        IngestionBatch activeBatch = resolveActiveBatch(repoId);

        List<CtxWikiPage> pages = orderedPages(activeBatch);

        List<WikiPageDto> dtos = CtxWikiPages.childPages(pages, pageId).stream()
                .map(p -> toDto(p, CtxWikiPages.hasChildren(pages, p)))
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/pages/{pageId}")
    @Operation(
            summary = "Get a wiki page",
            description = "Returns a single wiki page by ID including its Markdown content")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Page retrieved",
                        content = @Content(schema = @Schema(implementation = WikiPageDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(responseCode = "404", description = "Repository or page not found", content = @Content)
            })
    public ResponseEntity<WikiPageDto> getPage(
            @PathVariable UUID teamId, @PathVariable UUID repoId, @PathVariable UUID pageId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);
        resolveRepo(teamId, repoId);
        IngestionBatch activeBatch = resolveActiveBatch(repoId);

        CtxWikiPage page = wikiPageRepository
                .findByBatchIdAndId(activeBatch.getId(), pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wiki page not found"));

        boolean hasChildren = CtxWikiPages.hasChildren(orderedPages(activeBatch), page);

        return ResponseEntity.ok(toDto(page, hasChildren));
    }
}
