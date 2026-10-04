package com.kratisai.controlplane.ingestion;

import static org.apache.commons.lang3.StringUtils.isEmpty;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.agentloop.KratisToolException;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.validation.MermaidDiagramValidator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WriteWikiPageTool {

    private static final Logger logger = LoggerFactory.getLogger(WriteWikiPageTool.class);

    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final IngestionBatchRepository ingestionBatchRepository;
    private final MermaidDiagramValidator mermaidDiagramValidator;

    public WriteWikiPageTool(
            CtxWikiPageRepository ctxWikiPageRepository,
            IngestionBatchRepository ingestionBatchRepository,
            MermaidDiagramValidator mermaidDiagramValidator) {
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.mermaidDiagramValidator = mermaidDiagramValidator;
    }

    @KratisTool(
            name = "write_wiki_page",
            description =
                    "Write or update a wiki page for the repository. If a page with the same pageSlug already exists for this batch, its content will be replaced.")
    @Transactional
    public String writeWikiPage(
            @ToolParam(description = "Friendly url-slug for this wiki") String pageSlug,
            @ToolParam(description = "Page title") String title,
            @ToolParam(description = "Markdown content optionally including mermaid diagrams") String content,
            @ToolParam(
                            description =
                                    "Slug of an already-written parent page. Omit it for the single top-level page;"
                                            + " use list_wiki_pages to find valid slugs.",
                            required = false)
                    String parentPageSlug,
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

        List<String> mermaidIssues = mermaidDiagramValidator.findIssues(content);
        if (!mermaidIssues.isEmpty()) {
            throw new KratisToolException(
                    "Invalid Mermaid diagram syntax in content. Fix and retry:\n" + String.join("\n", mermaidIssues));
        }

        UUID batchId = BatchContext.requireBatchId(toolContext, "WriteWikiPageTool");

        IngestionBatch batch = ingestionBatchRepository
                .findById(batchId)
                .orElseThrow(() -> new KratisToolException("Batch not found: " + batchId));

        CtxWikiPage existingPage = ctxWikiPageRepository
                .findByBatchIdAndPageSlug(batchId, pageSlug)
                .orElse(null);

        CtxWikiPage parentPage = null;
        if (!isEmpty(parentPageSlug)) {
            parentPage = ctxWikiPageRepository
                    .findByBatchIdAndPageSlug(batchId, parentPageSlug)
                    .orElseThrow(() -> parentPageNotFound(batchId, parentPageSlug));
        }

        Team team = batch.getRepository().getTeam();

        if (existingPage != null) {
            existingPage.setTitle(title);
            existingPage.setContent(content);
            ctxWikiPageRepository.saveAndFlush(existingPage);
            logger.info("WriteWikiPageTool: Updated existing wiki page: {}", pageSlug);
            return "SUCCESS: Wiki page '" + pageSlug + "' updated successfully.";
        } else {
            CtxWikiPage newPage = new CtxWikiPage(
                    batch,
                    team.getId(),
                    batch.getRepository().getName(),
                    parentPage,
                    pageSlug,
                    title,
                    0, // orderIndex
                    content);
            ctxWikiPageRepository.saveAndFlush(newPage);
            logger.info("WriteWikiPageTool: Created new wiki page: {}", pageSlug);
            return "SUCCESS: Wiki page '" + pageSlug + "' created successfully.";
        }
    }

    private KratisToolException parentPageNotFound(UUID batchId, String parentPageSlug) {
        List<String> availableSlugs = ctxWikiPageRepository.findByBatchId(batchId).stream()
                .map(CtxWikiPage::getPageSlug)
                .sorted()
                .toList();
        String available = availableSlugs.isEmpty()
                ? "No wiki pages have been written yet."
                : "Available page slugs: " + availableSlugs + ".";
        logger.warn("WriteWikiPageTool: rejected parent slug '{}' for batch {}", parentPageSlug, batchId);
        return new KratisToolException("parent slug '" + parentPageSlug + "' does not exist for this wiki. " + available
                + " Fix and retry: write the parent page first, use one of the available slugs,"
                + " or omit parentPageSlug to create a top-level page.");
    }
}
