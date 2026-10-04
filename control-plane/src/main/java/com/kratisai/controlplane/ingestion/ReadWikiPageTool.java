package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.agentloop.KratisToolException;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Component
public class ReadWikiPageTool {

    private final CtxWikiPageRepository ctxWikiPageRepository;

    public ReadWikiPageTool(CtxWikiPageRepository ctxWikiPageRepository) {
        this.ctxWikiPageRepository = ctxWikiPageRepository;
    }

    @KratisTool(
            name = "read_wiki_page",
            description = "Read the content of an existing wiki page for the repository by its pageSlug.")
    public String readWikiPage(String pageSlug, ToolContext toolContext) {
        if (pageSlug == null || pageSlug.isBlank()) {
            throw new KratisToolException("pageSlug must not be blank.");
        }

        UUID batchId = BatchContext.requireBatchId(toolContext, "ReadWikiPageTool");

        CtxWikiPage page = ctxWikiPageRepository
                .findByBatchIdAndPageSlug(batchId, pageSlug)
                .orElseThrow(() -> new KratisToolException(
                        "Wiki page not found: " + pageSlug + ". Use list_wiki_pages to find valid slugs."));

        return "Title: " + page.getTitle() + "\n\nContent:\n" + page.getContent();
    }
}
