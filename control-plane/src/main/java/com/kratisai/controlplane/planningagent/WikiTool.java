package com.kratisai.controlplane.planningagent;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.model.CtxEmbedding;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.CtxWikiPages;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.repository.CtxEmbeddingRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.service.BatchResolutionService;
import com.kratisai.controlplane.service.EmbeddingModelFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WikiTool {

    private static final Logger logger = LoggerFactory.getLogger(WikiTool.class);

    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final CtxEmbeddingRepository ctxEmbeddingRepository;
    private final BatchResolutionService batchResolutionService;
    private final TeamRepository teamRepository;
    private final TeamWikiBatchRepository teamWikiBatchRepository;
    private final EmbeddingModelFactory embeddingModelFactory;

    public WikiTool(
            CtxWikiPageRepository ctxWikiPageRepository,
            CtxEmbeddingRepository ctxEmbeddingRepository,
            BatchResolutionService batchResolutionService,
            TeamRepository teamRepository,
            TeamWikiBatchRepository teamWikiBatchRepository,
            EmbeddingModelFactory embeddingModelFactory) {
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.ctxEmbeddingRepository = ctxEmbeddingRepository;
        this.batchResolutionService = batchResolutionService;
        this.teamRepository = teamRepository;
        this.teamWikiBatchRepository = teamWikiBatchRepository;
        this.embeddingModelFactory = embeddingModelFactory;
    }

    @KratisTool(
            name = "list_wiki_pages",
            description =
                    "List the auto-generated wiki pages for a repository or the team-wide wiki (omit repoName for the"
                            + " team wiki). Pages are shown in hierarchy order with their slugs (needed for"
                            + " read_wiki_page). Use this to discover available documentation.")
    @Transactional(readOnly = true)
    public List<WikiPageSummary> listWikiPages(
            @ToolParam(description = "Optional repository name; omit to list the team-wide wiki", required = false)
                    String repoName,
            ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        List<WikiPageSummary> result = new ArrayList<>();
        if (repoName == null || repoName.isBlank()) {
            TeamWikiBatch teamBatch = teamWikiBatchRepository
                    .findByTeamIdAndIsActiveTrue(teamId)
                    .orElseThrow(() -> new IllegalArgumentException("No active team wiki for team " + teamId));
            List<CtxWikiPage> pages =
                    ctxWikiPageRepository.findByTeamWikiBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(
                            teamBatch.getId());
            for (CtxWikiPage root : CtxWikiPages.topLevelPages(pages)) {
                collectTeamPageHierarchy(root, pages, 0, result);
            }
            return result;
        }

        IngestionBatch batch = batchResolutionService.resolveActiveBatch(teamId, repoName);

        List<CtxWikiPage> pages =
                ctxWikiPageRepository.findByBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(batch.getId());

        for (CtxWikiPage root : CtxWikiPages.topLevelPages(pages)) {
            collectPageHierarchy(root, pages, 0, result);
        }
        return result;
    }

    @KratisTool(
            name = "read_wiki_page",
            description =
                    "Read the full content of a wiki page as markdown. Omit repoName to read a team-wide wiki page,"
                            + " or pass repoName for a repository wiki page. Use list_wiki_pages first to find slugs.")
    @Transactional(readOnly = true)
    public String readWikiPage(
            @ToolParam(description = "Optional repository name; omit to read from the team-wide wiki", required = false)
                    String repoName,
            @ToolParam(description = "The page slug (from list_wiki_pages)") String pageSlug,
            ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        if (repoName == null || repoName.isBlank()) {
            TeamWikiBatch teamBatch = teamWikiBatchRepository
                    .findByTeamIdAndIsActiveTrue(teamId)
                    .orElseThrow(() -> new IllegalArgumentException("No active team wiki for team " + teamId));
            CtxWikiPage page = ctxWikiPageRepository
                    .findByTeamWikiBatchIdAndPageSlug(teamBatch.getId(), pageSlug)
                    .orElseThrow(() -> new IllegalArgumentException("Team wiki page '" + pageSlug + "' not found"));
            return "# " + page.getTitle() + "\n\n" + page.getContent();
        }

        IngestionBatch batch = batchResolutionService.resolveActiveBatch(teamId, repoName);

        CtxWikiPage page = ctxWikiPageRepository
                .findByBatchIdAndPageSlug(batch.getId(), pageSlug)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Wiki page '" + pageSlug + "' not found in repository '" + repoName + "'"));

        return "# " + page.getTitle() + "\n\n" + page.getContent();
    }

    @KratisTool(
            name = "search_wiki",
            description =
                    "Search documentation using natural language. Omit repoName to search across the team-wide wiki"
                            + " and all active repository wikis; pass repoName to focus on one repository plus the"
                            + " team-wide wiki. Returns matching passages with repoName (null for team wiki) and"
                            + " pageSlug for follow-up read_wiki_page calls.")
    @Transactional(readOnly = true)
    public WikiSearchResponse searchWiki(
            @ToolParam(
                            description = "Optional repository name; omit to search across team and all repo wikis",
                            required = false)
                    String repoName,
            @ToolParam(description = "Natural language search query") String query,
            ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        logger.debug("Searching Wiki: {}", query);

        Team team = teamRepository
                .findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));

        ModelProvider provider = team.getEmbeddingProvider();
        if (provider == null) {
            throw new IllegalStateException("Team does not have an embedding provider configured.");
        }

        EmbeddingModel embeddingModel = embeddingModelFactory.createEmbeddingModel(provider, team.getEmbeddingModel());

        float[] vector = embeddingModel.embed(query);
        List<CtxEmbedding> embeddings;
        if (repoName == null || repoName.isBlank()) {
            embeddings = ctxEmbeddingRepository.findSimilarTeamWide(teamId, vector, vector.length, 5);
        } else {
            IngestionBatch batch = batchResolutionService.resolveActiveBatch(teamId, repoName);
            embeddings = ctxEmbeddingRepository.findSimilarRepoAndTeam(teamId, batch.getId(), vector, vector.length, 5);
        }

        List<WikiSearchResult> results = embeddings.stream()
                .map(e -> {
                    CtxWikiPage page = e.getPage();
                    return new WikiSearchResult(
                            page.getRepoName(), page.getPageSlug(), page.getTitle(), e.getChunkText());
                })
                .toList();

        return new WikiSearchResponse(results);
    }

    private void collectPageHierarchy(
            CtxWikiPage page, List<CtxWikiPage> pages, int depth, List<WikiPageSummary> result) {
        result.add(new WikiPageSummary(
                page.getPageSlug(),
                page.getTitle(),
                depth,
                page.getContent() != null ? page.getContent().length() : 0));

        for (CtxWikiPage child : CtxWikiPages.childPages(pages, page.getId())) {
            collectPageHierarchy(child, pages, depth + 1, result);
        }
    }

    private void collectTeamPageHierarchy(
            CtxWikiPage page, List<CtxWikiPage> pages, int depth, List<WikiPageSummary> result) {
        result.add(new WikiPageSummary(
                page.getPageSlug(),
                page.getTitle(),
                depth,
                page.getContent() != null ? page.getContent().length() : 0));

        for (CtxWikiPage child : CtxWikiPages.childPages(pages, page.getId())) {
            collectTeamPageHierarchy(child, pages, depth + 1, result);
        }
    }

    public record WikiPageSummary(String pageSlug, String title, int depth, int contentLength) {}

    public record WikiSearchResponse(List<WikiSearchResult> results) {}

    public record WikiSearchResult(String repoName, String pageSlug, String pageTitle, String matchedText) {}
}
