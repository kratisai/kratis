package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Component
public class ListWikiPagesTool {

    private final CtxWikiPageRepository ctxWikiPageRepository;

    public ListWikiPagesTool(CtxWikiPageRepository ctxWikiPageRepository) {
        this.ctxWikiPageRepository = ctxWikiPageRepository;
    }

    @KratisTool(
            name = "list_wiki_pages",
            description = "List the wiki pages already written for this ingestion batch, in hierarchy order with their"
                    + " slugs. Use this to find a valid parentPageSlug before calling write_wiki_page.")
    public String listWikiPages(ToolContext toolContext) {
        UUID batchId = BatchContext.requireBatchId(toolContext, "ListWikiPagesTool");

        List<CtxWikiPage> roots = ctxWikiPageRepository.findByBatchIdAndParentPageIsNullOrderByOrderIndexAsc(batchId);
        if (roots.isEmpty()) {
            return "No wiki pages have been written yet."
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
                ctxWikiPageRepository.findByBatchIdAndParentPageIdOrderByOrderIndexAsc(batchId, page.getId());
        for (CtxWikiPage child : children) {
            appendHierarchy(batchId, child, depth + 1, builder);
        }
    }
}
