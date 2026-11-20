package com.kratisai.controlplane.ingestion.teamwiki;

import static org.apache.commons.lang3.StringUtils.isEmpty;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.agentloop.KratisToolException;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.CtxWikiPages;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.validation.MermaidDiagramValidator;
import com.kratisai.controlplane.validation.WikiPageContentValidator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WriteTeamWikiPageTool {

    private static final Logger logger = LoggerFactory.getLogger(WriteTeamWikiPageTool.class);
    private static final Pattern REPO_WIKI_LINK_PATTERN =
            Pattern.compile("\\(/wiki/repo/([^\\s)#?]+)(?:[?#][^)\\s]*)?\\)");

    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final TeamWikiBatchRepository teamWikiBatchRepository;
    private final MermaidDiagramValidator mermaidDiagramValidator;
    private final WikiPageContentValidator wikiPageContentValidator;

    public WriteTeamWikiPageTool(
            CtxWikiPageRepository ctxWikiPageRepository,
            TeamWikiBatchRepository teamWikiBatchRepository,
            MermaidDiagramValidator mermaidDiagramValidator,
            WikiPageContentValidator wikiPageContentValidator) {
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.teamWikiBatchRepository = teamWikiBatchRepository;
        this.mermaidDiagramValidator = mermaidDiagramValidator;
        this.wikiPageContentValidator = wikiPageContentValidator;
    }

    @KratisTool(
            name = "write_team_wiki_page",
            description = "Create or update a team wiki page. If a page with the same pageSlug already exists in this"
                    + " build, its title, parent, and content will be updated.")
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

        content = mermaidDiagramValidator.normalizeFences(content);
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

        CtxWikiPage parentPage = null;
        if (!isEmpty(parentPageSlug)) {
            if (parentPageSlug.equals(pageSlug)) {
                throw new KratisToolException("A page cannot be its own parent: " + pageSlug);
            }
            parentPage = ctxWikiPageRepository
                    .findByTeamWikiBatchIdAndPageSlug(batchId, parentPageSlug)
                    .orElseThrow(() -> parentPageNotFound(batchId, parentPageSlug));
        }

        CtxWikiPage existingPage = ctxWikiPageRepository
                .findByTeamWikiBatchIdAndPageSlug(batchId, pageSlug)
                .orElse(null);

        if (existingPage != null) {
            ensureNoParentCycle(existingPage, parentPage);
            existingPage.setTitle(title);
            existingPage.setContent(content);
            existingPage.setParentPage(parentPage);
            if (orderIndex != null) {
                existingPage.setOrderIndex(orderIndex);
            }
            ctxWikiPageRepository.saveAndFlush(existingPage);
            logger.info("WriteTeamWikiPageTool: Updated team wiki page: {}", pageSlug);
            logPageWrite(toolContext, "Updated", title, pageSlug, content);
            return "SUCCESS: Team wiki page '" + pageSlug + "' updated successfully.";
        }

        int effectiveOrderIndex = orderIndex != null ? orderIndex : nextSiblingOrderIndex(batchId, parentPage);
        CtxWikiPage newPage = new CtxWikiPage(
                batch, batch.getTeam().getId(), parentPage, pageSlug, title, effectiveOrderIndex, content);
        ctxWikiPageRepository.saveAndFlush(newPage);
        logger.info("WriteTeamWikiPageTool: Created team wiki page: {}", pageSlug);
        logPageWrite(toolContext, "Created", title, pageSlug, content);
        return "SUCCESS: Team wiki page '" + pageSlug + "' created successfully.";
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
        Set<UUID> visited = new HashSet<>();
        CtxWikiPage cursor = candidateParent;
        while (cursor != null) {
            if (existingPage.getId().equals(cursor.getId())) {
                throw new KratisToolException("Cannot set parentPageSlug to '" + candidateParent.getPageSlug()
                        + "' because it is a descendant of '" + existingPage.getPageSlug() + "'.");
            }
            if (cursor.getId() != null && !visited.add(cursor.getId())) {
                break;
            }
            cursor = cursor.getParentPage();
        }
    }

    private void validateRepoWikiLinks(String content, Map<String, UUID> repoBatchIdsByName) {
        if (repoBatchIdsByName.isEmpty()) {
            return;
        }
        Matcher matcher = REPO_WIKI_LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            String rawPath = matcher.group(1);
            if (rawPath.endsWith("/")) {
                rawPath = rawPath.substring(0, rawPath.length() - 1);
            }

            String repoName = null;
            String targetSlug = null;
            UUID repoBatchId = repoBatchIdsByName.get(rawPath);
            if (repoBatchId != null) {
                repoName = rawPath;
            } else {
                int lastSlash = rawPath.lastIndexOf('/');
                if (lastSlash > 0) {
                    String candidateRepo = rawPath.substring(0, lastSlash);
                    UUID candidateBatch = repoBatchIdsByName.get(candidateRepo);
                    if (candidateBatch != null) {
                        repoName = candidateRepo;
                        targetSlug = rawPath.substring(lastSlash + 1);
                        repoBatchId = candidateBatch;
                    }
                }
            }

            if (repoBatchId == null) {
                throw new KratisToolException("Invalid repository wiki link '/wiki/repo/" + rawPath
                        + "': repository is not in the active ingested repositories "
                        + repoBatchIdsByName.keySet().stream().sorted().toList() + ".");
            }
            if (targetSlug != null && !targetSlug.isBlank()) {
                boolean exists = ctxWikiPageRepository
                        .findByBatchIdAndPageSlug(repoBatchId, targetSlug)
                        .isPresent();
                if (!exists) {
                    List<String> availableSlugs = ctxWikiPageRepository.findByBatchId(repoBatchId).stream()
                            .map(CtxWikiPage::getPageSlug)
                            .sorted()
                            .toList();
                    throw new KratisToolException("Invalid repository wiki link '/wiki/repo/" + repoName + "/"
                            + targetSlug + "': slug '" + targetSlug + "' does not exist in '" + repoName
                            + "'. Available slugs: " + availableSlugs
                            + ", or link to the repo root '/wiki/repo/" + repoName + "'.");
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
