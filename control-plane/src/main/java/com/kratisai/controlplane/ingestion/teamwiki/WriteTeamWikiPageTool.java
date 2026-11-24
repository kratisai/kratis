package com.kratisai.controlplane.ingestion.teamwiki;

import static org.apache.commons.lang3.StringUtils.isBlank;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.agentloop.KratisToolException;
import com.kratisai.controlplane.ingestion.write.WikiPageLinkExtractor;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.CtxWikiPages;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.validation.MermaidDiagramValidator;
import com.kratisai.controlplane.validation.WikiPageContentValidator;
import com.kratisai.controlplane.validation.mermaid.MermaidNormalizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WriteTeamWikiPageTool {

    private static final Logger logger = LoggerFactory.getLogger(WriteTeamWikiPageTool.class);

    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final TeamWikiBatchRepository teamWikiBatchRepository;
    private final MermaidDiagramValidator mermaidDiagramValidator;
    private final MermaidNormalizer mermaidNormalizer;
    private final WikiPageContentValidator wikiPageContentValidator;
    private final WikiPageLinkExtractor wikiPageLinkExtractor;

    public WriteTeamWikiPageTool(
            CtxWikiPageRepository ctxWikiPageRepository,
            TeamWikiBatchRepository teamWikiBatchRepository,
            MermaidDiagramValidator mermaidDiagramValidator,
            MermaidNormalizer mermaidNormalizer,
            WikiPageContentValidator wikiPageContentValidator,
            WikiPageLinkExtractor wikiPageLinkExtractor) {
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.teamWikiBatchRepository = teamWikiBatchRepository;
        this.mermaidDiagramValidator = mermaidDiagramValidator;
        this.mermaidNormalizer = mermaidNormalizer;
        this.wikiPageContentValidator = wikiPageContentValidator;
        this.wikiPageLinkExtractor = wikiPageLinkExtractor;
    }

    @KratisTool(
            name = "write_team_wiki_page",
            description = "Create or update a team wiki page. If a page with the same pageSlug already exists in this"
                    + " build, its title, content, and explicit parent/orderIndex are updated; an omitted"
                    + " parentPageSlug keeps the page's current parent.")
    @Transactional
    public String writeTeamWikiPage(
            @ToolParam(description = "Friendly URL slug for this team wiki page") String pageSlug,
            @ToolParam(description = "Page title") String title,
            @ToolParam(description = "Markdown content optionally including mermaid diagrams and /wiki/repo/... links")
                    String content,
            @ToolParam(
                            description =
                                    "Slug of an already-written parent page. Omit it for the single top-level page;"
                                            + " use list_team_wiki_pages to find valid slugs.",
                            required = false)
                    String parentPageSlug,
            @ToolParam(
                            description = "Reading order among sibling pages, e.g. 10, 20, 30. Provide it so pages"
                                    + " appear in a logical narrative order; when omitted the page is appended"
                                    + " after its current siblings.",
                            required = false)
                    Integer orderIndex,
            ToolContext toolContext) {
        if (pageSlug == null || pageSlug.isBlank()) {
            throw new KratisToolException("pageSlug must not be blank.");
        }
        if (title == null || title.isBlank()) {
            throw new KratisToolException("title must not be blank.");
        }
        if (content == null || content.isBlank()) {
            throw new KratisToolException("content must not be blank.");
        }

        content = mermaidNormalizer.normalize(content);
        wikiPageContentValidator.requireBody(content);
        List<String> mermaidIssues = mermaidDiagramValidator.findIssues(content);
        if (!mermaidIssues.isEmpty()) {
            throw new KratisToolException(
                    "Invalid Mermaid diagram syntax in content. Fix and retry:\n" + String.join("\n", mermaidIssues));
        }

        validateRepoWikiLinks(content, TeamWikiContext.repoBatchIdsByName(toolContext));

        UUID batchId = TeamWikiContext.requireBatchId(toolContext, "WriteTeamWikiPageTool");
        TeamWikiBatch batch = teamWikiBatchRepository
                .findByIdForUpdate(batchId)
                .orElseThrow(() -> new KratisToolException("TeamWikiBatch not found: " + batchId));

        CtxWikiPage requestedParent = lookupParent(batchId, pageSlug, parentPageSlug);

        CtxWikiPage existingPage = ctxWikiPageRepository
                .findByTeamWikiBatchIdAndPageSlug(batchId, pageSlug)
                .orElse(null);

        if (existingPage != null) {
            ensureNoParentCycle(existingPage, requestedParent);
            existingPage.setTitle(title);
            existingPage.setContent(content);
            if (requestedParent != null) {
                existingPage.setParentPage(requestedParent);
            }
            if (orderIndex != null) {
                existingPage.setOrderIndex(orderIndex);
            }
            ctxWikiPageRepository.saveAndFlush(existingPage);
            logger.info("WriteTeamWikiPageTool: Updated team wiki page: {}", pageSlug);
            logPageWrite(toolContext, "Updated", title, pageSlug, content);
            return "SUCCESS: Team wiki page '" + pageSlug + "' updated successfully.";
        }

        int effectiveOrderIndex = orderIndex != null ? orderIndex : nextSiblingOrderIndex(batchId, requestedParent);
        CtxWikiPage newPage = new CtxWikiPage(
                batch, batch.getTeam().getId(), requestedParent, pageSlug, title, effectiveOrderIndex, content);
        ctxWikiPageRepository.saveAndFlush(newPage);
        logger.info("WriteTeamWikiPageTool: Created team wiki page: {}", pageSlug);
        logPageWrite(toolContext, "Created", title, pageSlug, content);
        return "SUCCESS: Team wiki page '" + pageSlug + "' created successfully.";
    }

    /** {@code null} means no parent was requested; callers must not read that as "make it a root". */
    private CtxWikiPage lookupParent(UUID batchId, String pageSlug, String parentPageSlug) {
        if (isBlank(parentPageSlug)) return null;
        if (parentPageSlug.equals(pageSlug)) {
            throw new KratisToolException("A page cannot be its own parent: " + pageSlug);
        }
        return ctxWikiPageRepository
                .findByTeamWikiBatchIdAndPageSlug(batchId, parentPageSlug)
                .orElseThrow(() -> parentPageNotFound(batchId, parentPageSlug));
    }

    private int nextSiblingOrderIndex(UUID batchId, CtxWikiPage parentPage) {
        int maxOrderIndex = CtxWikiPages.maxSiblingOrderIndex(
                ctxWikiPageRepository.findByTeamWikiBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(batchId),
                parentPage);
        return maxOrderIndex + 10;
    }

    private static void ensureNoParentCycle(CtxWikiPage existingPage, CtxWikiPage candidateParent) {
        if (candidateParent == null || existingPage.getId() == null) {
            return;
        }
        if (CtxWikiPages.isDescendantOf(candidateParent, existingPage.getId())) {
            throw new KratisToolException("Cannot set parentPageSlug to '" + candidateParent.getPageSlug()
                    + "' because it is a descendant of '" + existingPage.getPageSlug() + "'.");
        }
    }

    private void validateRepoWikiLinks(String content, Map<String, UUID> repoBatchIdsByName) {
        if (repoBatchIdsByName.isEmpty()) {
            return;
        }
        for (String rawPath : wikiPageLinkExtractor.extractWikiLinks(content).repoPaths()) {
            TeamWikiLinkResolver.RepoTarget target = TeamWikiLinkResolver.resolve(rawPath, repoBatchIdsByName.keySet())
                    .orElse(null);
            if (target == null) {
                throw new KratisToolException("Invalid repository wiki link '/wiki/repo/" + rawPath
                        + "': repository is not in the active ingested repositories "
                        + repoBatchIdsByName.keySet().stream().sorted().toList() + ".");
            }
            UUID repoBatchId = repoBatchIdsByName.get(target.repoName());
            String targetSlug = target.pageSlug();
            if (targetSlug != null) {
                boolean exists = ctxWikiPageRepository
                        .findByBatchIdAndPageSlug(repoBatchId, targetSlug)
                        .isPresent();
                if (!exists) {
                    List<String> availableSlugs = ctxWikiPageRepository.findByBatchId(repoBatchId).stream()
                            .map(CtxWikiPage::getPageSlug)
                            .sorted()
                            .toList();
                    throw new KratisToolException("Invalid repository wiki link '/wiki/repo/" + target.repoName() + "/"
                            + targetSlug + "': slug '" + targetSlug + "' does not exist in '" + target.repoName()
                            + "'. Available slugs: " + availableSlugs
                            + ", or link to the repo root '/wiki/repo/" + target.repoName() + "'.");
                }
            }
        }
    }

    private static void logPageWrite(
            ToolContext toolContext, String action, String title, String pageSlug, String content) {
        if (toolContext == null) {
            return;
        }
        Object loggerObj = toolContext.getContext().get(TeamWikiContext.LOGGER_KEY);
        if (loggerObj instanceof TeamWikiBatchLogService.BatchLogger batchLogger) {
            String trimmed = content.trim();
            int words = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
            batchLogger.info(
                    "GENERATE_WIKI",
                    action + " team wiki page \"" + title + "\" (slug=" + pageSlug + ", " + words + " words)");
        }
    }

    private KratisToolException parentPageNotFound(UUID batchId, String parentPageSlug) {
        List<String> availableSlugs = ctxWikiPageRepository.findByTeamWikiBatchId(batchId).stream()
                .map(CtxWikiPage::getPageSlug)
                .sorted()
                .toList();
        String available = availableSlugs.isEmpty()
                ? "No team wiki pages have been written yet."
                : "Available page slugs: " + availableSlugs + ".";
        return new KratisToolException("parent slug '" + parentPageSlug + "' does not exist in this team wiki. "
                + available + " Write the parent page first, use one of the available slugs,"
                + " or omit parentPageSlug to create a top-level page.");
    }
}
