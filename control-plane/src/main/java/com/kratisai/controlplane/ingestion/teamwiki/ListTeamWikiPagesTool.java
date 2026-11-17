package com.kratisai.controlplane.ingestion.teamwiki;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Component
public class ListTeamWikiPagesTool {

    private final CtxWikiPageRepository ctxWikiPageRepository;

    public ListTeamWikiPagesTool(CtxWikiPageRepository ctxWikiPageRepository) {
        this.ctxWikiPageRepository = ctxWikiPageRepository;
    }

    @KratisTool(
            name = "list_team_wiki_pages",
            description = "List the team wiki pages currently in this build, in hierarchy order with their slugs."
                    + " Use this to find valid parentPageSlug values or inspect existing pages before updating.")
    public String listTeamWikiPages(ToolContext toolContext) {
        UUID batchId = TeamWikiContext.requireBatchId(toolContext, "ListTeamWikiPagesTool");

        List<CtxWikiPage> roots =
                ctxWikiPageRepository.findByTeamWikiBatchIdAndParentPageIsNullOrderByOrderIndexAsc(batchId);
        if (roots.isEmpty()) {
            return "No team wiki pages have been written yet."
                    + " Write the single top-level page first (omit parentPageSlug).";
        }

        StringBuilder builder = new StringBuilder();
        for (CtxWikiPage root : roots) {
            appendHierarchy(batchId, root, 0, builder);
        }
        return builder.toString().stripTrailing();
    }

    private void appendHierarchy(UUID batchId, CtxWikiPage page, int depth, StringBuilder builder) {
        builder.repeat("  ", depth)
                .append("- ")
                .append(page.getPageSlug())
                .append(" (")
                .append(page.getTitle())
                .append(")\n");
        List<CtxWikiPage> children =
                ctxWikiPageRepository.findByTeamWikiBatchIdAndParentPageIdOrderByOrderIndexAsc(batchId, page.getId());
        for (CtxWikiPage child : children) {
            appendHierarchy(batchId, child, depth + 1, builder);
        }
    }
}
