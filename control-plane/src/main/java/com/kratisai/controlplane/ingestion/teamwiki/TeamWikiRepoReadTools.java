package com.kratisai.controlplane.ingestion.teamwiki;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.agentloop.KratisToolException;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.CtxSurfaceAttribute;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.CtxWikiPages;
import com.kratisai.controlplane.repository.CtxSurfaceRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TeamWikiRepoReadTools {

    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final CtxSurfaceRepository ctxSurfaceRepository;

    public TeamWikiRepoReadTools(
            CtxWikiPageRepository ctxWikiPageRepository, CtxSurfaceRepository ctxSurfaceRepository) {
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.ctxSurfaceRepository = ctxSurfaceRepository;
    }

    @KratisTool(
            name = "list_repo_wiki_pages",
            description = "List the wiki pages for an ingested repository in hierarchy order with their slugs.")
    @Transactional(readOnly = true)
    public String listRepoWikiPages(
            @ToolParam(description = "Repository name") String repoName, ToolContext toolContext) {
        UUID repoBatchId = resolveRepoBatchId(repoName, toolContext);
        List<CtxWikiPage> pages =
                ctxWikiPageRepository.findByBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(repoBatchId);
        List<CtxWikiPage> roots = CtxWikiPages.topLevelPages(pages);
        if (roots.isEmpty()) {
            return "No wiki pages exist for repository '" + repoName + "'.";
        }
        StringBuilder builder = new StringBuilder();
        for (CtxWikiPage root : roots) {
            appendHierarchy(pages, root, 0, builder);
        }
        return builder.toString().stripTrailing();
    }

    @KratisTool(
            name = "read_repo_wiki_page",
            description = "Read the markdown content of a specific per-repository wiki page.")
    @Transactional(readOnly = true)
    public String readRepoWikiPage(
            @ToolParam(description = "Repository name") String repoName,
            @ToolParam(description = "The pageSlug from list_repo_wiki_pages") String pageSlug,
            ToolContext toolContext) {
        if (pageSlug == null || pageSlug.isBlank()) {
            throw new KratisToolException("pageSlug must not be blank.");
        }
        UUID repoBatchId = resolveRepoBatchId(repoName, toolContext);
        CtxWikiPage page = ctxWikiPageRepository
                .findByBatchIdAndPageSlug(repoBatchId, pageSlug)
                .orElseThrow(() -> new KratisToolException(
                        "Wiki page '" + pageSlug + "' not found in repository '" + repoName + "'."));
        return "# " + page.getTitle() + "\n\n" + page.getContent();
    }

    @KratisTool(
            name = "get_repo_surfaces",
            description = "Inspect the extracted boundary surfaces (APIs, events, dependencies, hosting, stores) for"
                    + " an ingested repository.")
    @Transactional(readOnly = true)
    public String getRepoSurfaces(
            @ToolParam(description = "Repository name") String repoName, ToolContext toolContext) {
        UUID repoBatchId = resolveRepoBatchId(repoName, toolContext);
        List<CtxSurface> surfaces = ctxSurfaceRepository.findByBatchIdIn(List.of(repoBatchId)).stream()
                .toList();
        if (surfaces.isEmpty()) {
            return "No boundary surfaces recorded for repository '" + repoName + "'.";
        }
        StringBuilder sb = new StringBuilder();
        for (CtxSurface surface : surfaces) {
            sb.append("- [").append(surface.getRole()).append("] ").append(surface.getDescription());
            String attrs = surface.getAttributeRows().stream()
                    .map(CtxSurfaceAttribute::getValue)
                    .distinct()
                    .limit(5)
                    .collect(Collectors.joining(", "));
            if (!attrs.isEmpty()) {
                sb.append(" (").append(attrs).append(")");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private UUID resolveRepoBatchId(String repoName, ToolContext toolContext) {
        if (repoName == null || repoName.isBlank()) {
            throw new KratisToolException("repoName must not be blank.");
        }
        Map<String, UUID> map = TeamWikiContext.repoBatchIdsByName(toolContext);
        UUID batchId = map.get(repoName);
        if (batchId == null) {
            throw new KratisToolException("Repository '" + repoName + "' not found in active ingested repositories: "
                    + map.keySet().stream().sorted().toList());
        }
        return batchId;
    }

    private void appendHierarchy(List<CtxWikiPage> pages, CtxWikiPage page, int depth, StringBuilder builder) {
        builder.repeat("  ", depth)
                .append("- ")
                .append(page.getPageSlug())
                .append(" (")
                .append(page.getTitle())
                .append(")\n");
        for (CtxWikiPage child : CtxWikiPages.childPages(pages, page.getId())) {
            appendHierarchy(pages, child, depth + 1, builder);
        }
    }
}
