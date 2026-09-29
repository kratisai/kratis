package com.kratisai.controlplane.planningagent;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.model.CtxEmbedding;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxEmbeddingRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamRepository;
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
    private final EmbeddingModelFactory embeddingModelFactory;

    public WikiTool(
            CtxWikiPageRepository ctxWikiPageRepository,
            CtxEmbeddingRepository ctxEmbeddingRepository,
            BatchResolutionService batchResolutionService,
            TeamRepository teamRepository,
            EmbeddingModelFactory embeddingModelFactory) {
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.ctxEmbeddingRepository = ctxEmbeddingRepository;
        this.batchResolutionService = batchResolutionService;
        this.teamRepository = teamRepository;
        this.embeddingModelFactory = embeddingModelFactory;
    }

    @KratisTool(
            name = "list_wiki_pages",
            description =
                    "List the auto-generated wiki pages for a repository. The wiki contains architectural documentation, module descriptions, system diagrams, and coding guides generated from the actual codebase. Pages are shown in hierarchy order with their slugs (needed for read_wiki_page). Use this to discover available documentation.")
    @Transactional(readOnly = true)
    public List<WikiPageSummary> listWikiPages(
            @ToolParam(description = "The repository name") String repoName, ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        IngestionBatch batch = batchResolutionService.resolveActiveBatch(teamId, repoName);

        List<CtxWikiPage> rootPages =
                ctxWikiPageRepository.findByBatchIdAndParentPageIsNullOrderByOrderIndexAsc(batch.getId());

        List<WikiPageSummary> result = new ArrayList<>();
        for (CtxWikiPage root : rootPages) {
            collectPageHierarchy(root, batch.getId(), 0, result);
        }
        return result;
    }

    @KratisTool(
            name = "read_wiki_page",
            description =
                    "Read the full content of a wiki page as markdown. Wiki pages contain architectural documentation, module descriptions, and system diagrams (as Mermaid). Use list_wiki_pages first to find available page slugs, then read the pages relevant to your task.")
    @Transactional(readOnly = true)
    public String readWikiPage(
            @ToolParam(description = "The repository name") String repoName,
            @ToolParam(description = "The page slug (from list_wiki_pages)") String pageSlug,
            ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
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
                    "Search the repository's documentation using natural language. Returns the most relevant text passages from wiki pages, along with the page they came from. Use this when you have a vague question about the codebase — for example, 'how does authentication work?' or 'what handles payment processing?'. After finding relevant passages, use read_wiki_page to read the full page for more context.")
    @Transactional(readOnly = true)
    public WikiSearchResponse searchWiki(
            @ToolParam(description = "The repository name") String repoName,
            @ToolParam(description = "Natural language search query") String query,
            ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        IngestionBatch batch = batchResolutionService.resolveActiveBatch(teamId, repoName);
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
        List<CtxEmbedding> embeddings =
                ctxEmbeddingRepository.findSimilar(teamId, batch.getId(), vector, vector.length, 5);

        List<WikiSearchResult> results = embeddings.stream()
                .map(e -> {
                    CtxWikiPage page = e.getPage();
                    return new WikiSearchResult(page.getPageSlug(), page.getTitle(), e.getChunkText());
                })
                .toList();

        return new WikiSearchResponse(results);
    }

    private void collectPageHierarchy(CtxWikiPage page, UUID batchId, int depth, List<WikiPageSummary> result) {
        result.add(new WikiPageSummary(
                page.getPageSlug(),
                page.getTitle(),
                depth,
                page.getContent() != null ? page.getContent().length() : 0));

        List<CtxWikiPage> children =
                ctxWikiPageRepository.findByBatchIdAndParentPageIdOrderByOrderIndexAsc(batchId, page.getId());
        for (CtxWikiPage child : children) {
            collectPageHierarchy(child, batchId, depth + 1, result);
        }
    }

    public record WikiPageSummary(String pageSlug, String title, int depth, int contentLength) {}

    public record WikiSearchResponse(List<WikiSearchResult> results) {}

    public record WikiSearchResult(String pageSlug, String pageTitle, String matchedText) {}
}
