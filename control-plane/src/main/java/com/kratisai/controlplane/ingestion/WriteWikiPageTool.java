package com.kratisai.controlplane.ingestion;

import static org.apache.commons.lang3.StringUtils.isEmpty;

import com.kratisai.controlplane.agentloop.KratisTool;
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
                            description = "Slug of the parent page - must match an existing pageSlug for this wiki",
                            required = false)
                    String parentPageSlug,
            ToolContext toolContext) {
        if (pageSlug == null || pageSlug.isBlank()) {
            return "ERROR: pageSlug must not be blank.";
        }
        if (title == null || title.isBlank()) {
            return "ERROR: title must not be blank.";
        }
        if (content == null || content.isBlank()) {
            return "ERROR: content must not be blank.";
        }

        List<String> mermaidIssues = mermaidDiagramValidator.findIssues(content);
        if (!mermaidIssues.isEmpty()) {
            return "ERROR: Invalid Mermaid diagram syntax in content. Fix and retry:\n"
                    + String.join("\n", mermaidIssues);
        }

        Object batchIdObj = toolContext != null ? toolContext.getContext().get("batchId") : null;
        if (batchIdObj == null) {
            throw new IllegalStateException("WriteWikiPage ERROR: batchId not found in tool context.");
        }

        UUID batchId;
        try {
            batchId = batchIdObj instanceof UUID ? (UUID) batchIdObj : UUID.fromString(batchIdObj.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("WriteWikiPage ERROR: Invalid batchId format.");
        }

        IngestionBatch batch = ingestionBatchRepository
                .findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        CtxWikiPage existingPage = ctxWikiPageRepository
                .findByBatchIdAndPageSlug(batchId, pageSlug)
                .orElse(null);

        CtxWikiPage parentPage = isEmpty(parentPageSlug)
                ? null
                : ctxWikiPageRepository
                        .findByBatchIdAndPageSlug(batchId, parentPageSlug)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No wiki page exists matching parent slug: " + parentPageSlug));

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
}
