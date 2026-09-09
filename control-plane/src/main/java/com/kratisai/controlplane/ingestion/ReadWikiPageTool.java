package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class ReadWikiPageTool {

    private final CtxWikiPageRepository ctxWikiPageRepository;

    public ReadWikiPageTool(CtxWikiPageRepository ctxWikiPageRepository) {
        this.ctxWikiPageRepository = ctxWikiPageRepository;
    }

    @Tool(
            name = "read_wiki_page",
            description = "Read the content of an existing wiki page for the repository by its pageSlug.")
    public String readWikiPage(String pageSlug, ToolContext toolContext) {
        if (pageSlug == null || pageSlug.isBlank()) {
            return "ERROR: pageSlug must not be blank.";
        }

        Object batchIdObj = toolContext != null ? toolContext.getContext().get("batchId") : null;
        if (batchIdObj == null) {
            throw new IllegalStateException("ReadWikiPageTool ERROR: batchId not found in tool context.");
        }

        UUID batchId;
        try {
            batchId = batchIdObj instanceof UUID ? (UUID) batchIdObj : UUID.fromString(batchIdObj.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ReadWikiPageTool ERROR: Invalid batchId format.");
        }

        CtxWikiPage page = ctxWikiPageRepository
                .findByBatchIdAndPageSlug(batchId, pageSlug)
                .orElse(null);
        if (page == null) {
            return "ERROR: Wiki page not found: " + pageSlug;
        }

        return "Title: " + page.getTitle() + "\n\nContent:\n" + page.getContent();
    }
}
