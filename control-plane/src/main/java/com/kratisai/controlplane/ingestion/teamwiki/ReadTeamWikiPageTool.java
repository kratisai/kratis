package com.kratisai.controlplane.ingestion.teamwiki;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.agentloop.KratisToolException;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ReadTeamWikiPageTool {

    private final CtxWikiPageRepository ctxWikiPageRepository;

    public ReadTeamWikiPageTool(CtxWikiPageRepository ctxWikiPageRepository) {
        this.ctxWikiPageRepository = ctxWikiPageRepository;
    }

    @KratisTool(
            name = "read_team_wiki_page",
            description = "Read the markdown content of a team wiki page in this build by its pageSlug.")
    public String readTeamWikiPage(
            @ToolParam(description = "The pageSlug of the team wiki page") String pageSlug, ToolContext toolContext) {
        if (pageSlug == null || pageSlug.isBlank()) {
            throw new KratisToolException("pageSlug must not be blank.");
        }
        UUID batchId = TeamWikiContext.requireBatchId(toolContext, "ReadTeamWikiPageTool");

        CtxWikiPage page = ctxWikiPageRepository
                .findByTeamWikiBatchIdAndPageSlug(batchId, pageSlug)
                .orElseThrow(() -> new KratisToolException(
                        "Team wiki page not found: " + pageSlug + ". Use list_team_wiki_pages to find valid slugs."));

        return "Title: " + page.getTitle() + "\n\nContent:\n" + page.getContent();
    }
}
