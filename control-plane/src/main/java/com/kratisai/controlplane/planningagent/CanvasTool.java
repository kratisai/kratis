package com.kratisai.controlplane.planningagent;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.api.wsdto.CanvasEvent;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.CanvasType;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.service.BatchResolutionService;
import com.kratisai.controlplane.service.CanvasService;
import com.kratisai.controlplane.validation.MermaidDiagramValidator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

@Component
public class CanvasTool {
    private static final Logger log = LoggerFactory.getLogger(CanvasTool.class);

    private final CanvasService canvasService;
    private final BatchResolutionService batchResolutionService;
    private final MermaidDiagramValidator mermaidDiagramValidator;

    public CanvasTool(
            CanvasService canvasService,
            BatchResolutionService batchResolutionService,
            MermaidDiagramValidator mermaidDiagramValidator) {
        this.canvasService = canvasService;
        this.batchResolutionService = batchResolutionService;
        this.mermaidDiagramValidator = mermaidDiagramValidator;
    }

    @KratisTool(name = "list_canvas_documents", description = "List a summary of each document in the canvas")
    public String listCanvasDocuments(ToolContext toolContext) {
        UUID chatId = (UUID) toolContext.getContext().get("chatId");
        List<CanvasEntity> canvases = canvasService.getCanvasesForChat(chatId);
        if (canvases.isEmpty()) {
            return "Documents in canvas: []";
        }
        return canvases.stream()
                .map(this::describeDocument)
                .collect(Collectors.joining("}, {", "Documents in canvas: [{", "}]"));
    }

    private String describeDocument(CanvasEntity canvasEntity) {
        String repo = canvasEntity.getRepoLabel() != null ? ", Repository: " + canvasEntity.getRepoLabel() : "";
        return "Document ID: " + canvasEntity.getDocumentId() + ", Title: " + canvasEntity.getTitle() + ", CanvasType: "
                + canvasEntity.getCanvasType() + repo;
    }

    @KratisTool(name = "retrieve_from_canvas", description = "Fetch the current content of a document in the canvas")
    public String retrieveFromCanvas(
            @ToolParam(description = "The ID of the document to retrieve") String documentId, ToolContext toolContext) {
        UUID chatId = (UUID) toolContext.getContext().get("chatId");
        try {
            CanvasEntity canvas = canvasService.getCanvas(chatId, documentId);
            return canvas == null ? "Document not found" : canvas.getContent();
        } catch (Exception e) {
            log.warn("Failed to retrieve canvas for document {}", documentId, e);
            return "Retrieving canvas failed: " + e.getMessage();
        }
    }

    @KratisTool(
            name = "write_canvas",
            description = "Create a new canvas document or fully overwrite an existing one. "
                    + "A SPEC is a hand-off artifact for a sandbox agent to implement — not a note of your own next conversational step. "
                    + "Write a SPEC only when the user wants work executed against a repository and enough ambiguity is resolved. "
                    + "Formality may be high (API contracts, acceptance criteria) or light (a short implementation brief); both are SPECs if an agent can execute them. "
                    + "Use DOCUMENT for research notes, architecture write-ups, or other knowledge that is not meant to be launched/executed by an agent."
                    + "**Strongly** prefer updating an existing document if possible - do not create duplicates.")
    public String writeCanvas(
            @ToolParam(description = "The ID of the document to write (omit or empty for new document)")
                    String documentId,
            @ToolParam(description = "The title of the document") String title,
            @ToolParam(description = "The complete markdown content of the document") String content,
            @ToolParam(
                            description =
                                    "SPEC: a hand-off for a sandbox agent to execute against a repository (requires exactly one of repoName or newRepoName). "
                                            + "Do not use SPEC for per-turn reasoning or conversational next-steps. "
                                            + "DOCUMENT: a plain markdown document not meant to be launched/executed by an agent.")
                    CanvasType canvasType,
            @ToolParam(
                            description =
                                    "Name of an existing repository to associate with a SPEC canvas (from list_repositories)",
                            required = false)
                    String repoName,
            @ToolParam(
                            description =
                                    "Name of a new repository to create for a SPEC canvas.  Confirm your choice with the user. Required if this spec should create a new repository - Omit if we are enhancing an existing repository.",
                            required = false)
                    String newRepoName,
            ToolContext toolContext) {
        UUID chatId = (UUID) toolContext.getContext().get("chatId");
        UUID teamId = (UUID) toolContext.getContext().get("teamId");

        validateWriteCanvasRequest(canvasType, repoName, newRepoName);
        List<String> mermaidIssues = mermaidDiagramValidator.findIssues(content);
        if (!mermaidIssues.isEmpty()) {
            return "ERROR: Invalid Mermaid diagram syntax in content. Fix and retry:\n"
                    + String.join("\n", mermaidIssues);
        }

        Repository repository = null;
        if (canvasType == CanvasType.SPEC) {
            if (repoName != null && !repoName.isBlank()) {
                repository = batchResolutionService.resolveRepository(teamId, repoName);
            }
        }

        try {
            String docId = documentId == null || documentId.isBlank()
                    ? UUID.randomUUID().toString()
                    : documentId;

            CanvasEntity entity;
            CanvasEntity existingCanvas = canvasService.getCanvas(chatId, docId);

            if (existingCanvas != null) {
                // Replace existing document
                entity = canvasService.updateCanvas(chatId, docId, title, content, canvasType, repository, newRepoName);
                // Emit Update event (frontend should handle this even if doc not in store yet)
                emitSyncEvent(toolContext, CanvasEvent.Update.of(entity, 1));
            } else {
                // Create new document
                entity = canvasService.createCanvas(chatId, docId, title, content, canvasType, repository, newRepoName);
                emitSyncEvent(toolContext, CanvasEvent.Create.of(entity));
            }
            emitSyncEvent(toolContext, new CanvasEvent.Commit(entity.getDocumentId(), chatId, 1));

            return "Document successfully written. ID: " + entity.getDocumentId() + ", Title: " + entity.getTitle();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to write canvas document", e);
            return "Error writing canvas document: " + e.getMessage();
        }
    }

    private void validateWriteCanvasRequest(CanvasType canvasType, String repoName, String newRepoName) {
        if (canvasType == null) {
            throw new IllegalArgumentException("canvasType is required: SPEC or DOCUMENT");
        }
        boolean hasRepoName = repoName != null && !repoName.isBlank();
        boolean hasNewRepo = newRepoName != null;
        if (canvasType == CanvasType.SPEC) {
            if (hasRepoName && hasNewRepo) {
                throw new IllegalArgumentException(
                        "write_canvas is ambiguous: provide exactly one of repoName or newRepoName for a SPEC canvas");
            }
            if (!hasRepoName && !hasNewRepo) {
                throw new IllegalArgumentException("spec canvas requires either a repoName or newRepoName");
            }
            if (hasNewRepo && newRepoName.isBlank()) {
                throw new IllegalArgumentException("newRepoName is required for a new-repository");
            }
        } else if (hasRepoName || hasNewRepo) {
            throw new IllegalArgumentException("document canvas must not define repoName or newRepoName");
        }
    }

    public record ReplaceBlock(
            @ToolParam(description = "The exact text block to search for")
            String targetText,

            @ToolParam(description = "The text to replace it with")
            String replacementText) {}

    @KratisTool(
            name = "patch_canvas",
            description =
                    "Apply atomic search/replace edits to an existing document. Prefer this to overwriting the entire document. Validate edits before and after patching, resort to write_canvas if you get in a mess.")
    public String patchCanvas(
            @ToolParam(description = "The ID of the document to patch") String documentId,
            @ToolParam(description = "List of search/replace blocks to apply") List<ReplaceBlock> patches,
            ToolContext toolContext) {
        UUID chatId = (UUID) toolContext.getContext().get("chatId");
        try {
            CanvasEntity canvas = canvasService.getCanvas(chatId, documentId);
            if (canvas == null) {
                return "Error: Document not found: " + documentId;
            }

            String currentContent = canvas.getContent();
            String patchedContent = currentContent;

            for (int i = 0; i < patches.size(); i++) {
                ReplaceBlock patch = patches.get(i);
                String target = patch.targetText();
                String replacement = patch.replacementText();

                if (!patchedContent.contains(target)) {
                    return "Error: Block "
                            + (i + 1)
                            + " not found. Document unaltered. Current document state:\n\n"
                            + currentContent;
                }

                patchedContent = patchedContent.replace(target, replacement);
            }

            List<String> mermaidIssues = mermaidDiagramValidator.findIssues(patchedContent);
            if (!mermaidIssues.isEmpty()) {
                return "ERROR: Invalid Mermaid diagram syntax after patching. Fix and retry:\n"
                        + String.join("\n", mermaidIssues);
            }

            // Persist the updated document
            CanvasEntity updated = canvasService.updateCanvas(chatId, documentId, patchedContent);

            // Broadcast sync event
            emitSyncEvent(toolContext, CanvasEvent.Update.of(updated, 1));
            emitSyncEvent(toolContext, new CanvasEvent.Commit(updated.getDocumentId(), chatId, 1));

            return "Document successfully patched.";
        } catch (Exception e) {
            log.error("Failed to patch canvas document", e);
            return "Error patching canvas document: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private void emitSyncEvent(ToolContext toolContext, CanvasEvent event) {
        try {
            var sink = (Sinks.Many<ClientPayload.ChatStreamPayload>)
                    toolContext.getContext().get("sink");
            if (sink != null) {
                sink.tryEmitNext(new ClientPayload.CanvasResult(event));
            }
        } catch (Exception e) {
            log.warn("Failed to emit canvas sync event", e);
        }
    }
}
