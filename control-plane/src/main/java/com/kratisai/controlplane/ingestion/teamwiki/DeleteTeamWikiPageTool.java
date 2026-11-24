package com.kratisai.controlplane.ingestion.teamwiki;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.agentloop.KratisToolException;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.CtxWikiPages;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DeleteTeamWikiPageTool {

    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final TeamWikiBatchRepository teamWikiBatchRepository;

    public DeleteTeamWikiPageTool(
            CtxWikiPageRepository ctxWikiPageRepository, TeamWikiBatchRepository teamWikiBatchRepository) {
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.teamWikiBatchRepository = teamWikiBatchRepository;
    }

    @KratisTool(
            name = "delete_team_wiki_page",
            description = "Delete an obsolete team wiki page (and any child pages under it) from this incremental"
                    + " build by its pageSlug. Use this when a repository or cluster was removed or reorganized.")
    @Transactional
    public String deleteTeamWikiPage(
            @ToolParam(description = "The pageSlug of the team wiki page to delete") String pageSlug,
            ToolContext toolContext) {
        if (pageSlug == null || pageSlug.isBlank()) {
            throw new KratisToolException("pageSlug must not be blank.");
        }
        UUID batchId = TeamWikiContext.requireBatchId(toolContext, "DeleteTeamWikiPageTool");
        // Deleting cascades to children, so serialize with page writes on the same batch.
        teamWikiBatchRepository
                .findByIdForUpdate(batchId)
                .orElseThrow(() -> new KratisToolException("TeamWikiBatch not found: " + batchId));
        CtxWikiPage target = ctxWikiPageRepository
                .findByTeamWikiBatchIdAndPageSlug(batchId, pageSlug)
                .orElseThrow(() -> new KratisToolException(
                        "Team wiki page not found: " + pageSlug + ". Use list_team_wiki_pages to find valid slugs."));

        int deletedCount = deleteRecursively(batchId, target);
        if (toolContext.getContext().get(TeamWikiContext.LOGGER_KEY)
                instanceof TeamWikiBatchLogService.BatchLogger batchLogger) {
            batchLogger.info(
                    "GENERATE_WIKI",
                    "Deleted obsolete team wiki page \"" + target.getTitle() + "\" (slug=" + pageSlug + ", removed="
                            + deletedCount + ")");
        }
        return "SUCCESS: Deleted team wiki page '" + pageSlug + "' (" + deletedCount + " page(s) removed).";
    }

    private int deleteRecursively(UUID batchId, CtxWikiPage page) {
        List<CtxWikiPage> children = CtxWikiPages.childPages(
                ctxWikiPageRepository.findByTeamWikiBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(batchId),
                page.getId());
        int count = 0;
        for (CtxWikiPage child : children) {
            count += deleteRecursively(batchId, child);
        }
        ctxWikiPageRepository.delete(page);
        ctxWikiPageRepository.flush();
        return count + 1;
    }
}
