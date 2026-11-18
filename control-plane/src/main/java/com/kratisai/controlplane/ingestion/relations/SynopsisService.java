package com.kratisai.controlplane.ingestion.relations;

import com.kratisai.controlplane.agentloop.ReActLoop;
import com.kratisai.controlplane.agentloop.ReActLoopExhaustedException;
import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.BatchChatClientFactory;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionUsageTracker;
import com.kratisai.controlplane.ingestion.ReadFileTool;
import com.kratisai.controlplane.model.CtxArchitecturePattern;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxRepoSynopsis;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.SurfaceRole;
import com.kratisai.controlplane.repository.CtxArchitecturePatternRepository;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import com.kratisai.controlplane.repository.CtxRepoSynopsisRepository;
import com.kratisai.controlplane.repository.CtxSurfaceRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SynopsisService {

    private static final Logger logger = LoggerFactory.getLogger(SynopsisService.class);

    private static final int MAX_TURNS = 8;
    static final int SURFACE_EVIDENCE_CHAR_BUDGET = 80_000;

    private final BatchChatClientFactory chatClients;
    private final ReadFileTool readFileTool;
    private final IngestionBatchRepository ingestionBatchRepository;
    private final CtxDimensionRepository ctxDimensionRepository;
    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final CtxArchitecturePatternRepository ctxArchitecturePatternRepository;
    private final CtxSurfaceRepository ctxSurfaceRepository;
    private final CtxRepoSynopsisRepository ctxRepoSynopsisRepository;
    private final IngestionUsageTracker usageTracker;
    private final Executor agentTaskExecutor;
    private final TransactionTemplate transactionTemplate;
    private final String baseCloneDir;

    public SynopsisService(
            BatchChatClientFactory chatClients,
            ReadFileTool readFileTool,
            IngestionBatchRepository ingestionBatchRepository,
            CtxDimensionRepository ctxDimensionRepository,
            CtxWikiPageRepository ctxWikiPageRepository,
            CtxArchitecturePatternRepository ctxArchitecturePatternRepository,
            CtxSurfaceRepository ctxSurfaceRepository,
            CtxRepoSynopsisRepository ctxRepoSynopsisRepository,
            IngestionUsageTracker usageTracker,
            Executor agentTaskExecutor,
            PlatformTransactionManager transactionManager,
            IngestionProperties ingestionProperties) {
        this.chatClients = chatClients;
        this.readFileTool = readFileTool;
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.ctxDimensionRepository = ctxDimensionRepository;
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.ctxArchitecturePatternRepository = ctxArchitecturePatternRepository;
        this.ctxSurfaceRepository = ctxSurfaceRepository;
        this.ctxRepoSynopsisRepository = ctxRepoSynopsisRepository;
        this.usageTracker = usageTracker;
        this.agentTaskExecutor = agentTaskExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.baseCloneDir = ingestionProperties.getCloneDir();
    }

    public record AttributeEvidence(UUID id, String kind, String value, String detectedBy) {}

    public record SurfaceEvidence(
            UUID id, SurfaceRole role, String description, String detectedBy, List<AttributeEvidence> attributes) {
        public SurfaceEvidence {
            attributes = List.copyOf(attributes);
        }
    }

    public record SynopsisInputs(
            List<CtxDimension> dimensions,
            List<CtxArchitecturePattern> patterns,
            List<SurfaceEvidence> evidenceSurfaces,
            CtxWikiPage wikiRoot) {}

    /** Everything the model step needs, read with the lazy graph inside one short transaction. */
    record Prepared(ChatClient chatClient, String repoName, String modelRef, SynopsisInputs inputs) {}

    public void synthesize(UUID batchId, IngestionBatchLogService.BatchLogger batchLogger) {
        long startNanos = System.nanoTime();
        Prepared prepared = transactionTemplate.execute(status -> prepare(batchId));
        ReActLoop<SynopsisResponse> loop = ReActLoop.structured(
                        prepared.chatClient(),
                        ToolCallbacks.from(readFileTool),
                        SynopsisResponse.class,
                        synopsisValidator())
                .maxIterations(MAX_TURNS)
                .executor(agentTaskExecutor)
                .onToolCallCount(count -> usageTracker.recordToolCalls(batchId, count));

        List<Message> history = List.of(
                new SystemMessage(buildSystemPrompt(prepared) + "\n" + loop.format()),
                new UserMessage("Synthesize the repository synopsis."));
        SynopsisResponse response = runLoop(loop, history, batchId, batchLogger);

        persist(batchId, response, prepared.modelRef(), prepared.inputs(), batchLogger);
        String elapsed = String.format(Locale.ROOT, "%.1fs", (System.nanoTime() - startNanos) / 1_000_000_000.0);
        batchLogger.info("SYNTHESIZE_SYNOPSIS", "SYNTHESIZE_SYNOPSIS completed in " + elapsed + ".");
    }

    private Prepared prepare(UUID batchId) {
        IngestionBatch batch = loadBatch(batchId);
        return new Prepared(
                chatClients.chatClientFor(batch),
                batch.getRepository().getName(),
                chatClients.modelRefFor(batch),
                loadInputs(batchId));
    }

    private SynopsisInputs loadInputs(UUID batchId) {
        CtxWikiPage wikiRoot =
                ctxWikiPageRepository.findByBatchIdAndParentPageIsNullOrderByOrderIndexAsc(batchId).stream()
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "The batch has no wiki root page; SYNTHESIZE_SYNOPSIS cannot run before GENERATE_WIKI."));
        return new SynopsisInputs(
                ctxDimensionRepository.findByBatchId(batchId),
                ctxArchitecturePatternRepository.findByBatchId(batchId),
                evidenceSurfaces(ctxSurfaceRepository.findByBatchId(batchId)),
                wikiRoot);
    }

    static List<SurfaceEvidence> evidenceSurfaces(List<CtxSurface> surfaces) {
        return surfaces.stream()
                .map(surface -> new SurfaceEvidence(
                        surface.getId(),
                        surface.getRole(),
                        surface.getDescription(),
                        surface.getDetectedBy().name(),
                        surface.getAttributeRows().stream()
                                .map(attribute -> new AttributeEvidence(
                                        attribute.getId(),
                                        attribute.getKind().name().toLowerCase(Locale.ROOT),
                                        attribute.getValue(),
                                        attribute.getDetectedBy().name()))
                                .toList()))
                .toList();
    }

    private SynopsisResponse runLoop(
            ReActLoop<SynopsisResponse> loop,
            List<Message> history,
            UUID batchId,
            IngestionBatchLogService.BatchLogger batchLogger) {
        try {
            SynopsisResponse response = loop.run(history, new ToolContext(Map.of("batchId", batchId)));
            if (response == null) {
                throw new IllegalStateException("The synopsis model call produced no answer.");
            }
            return response;
        } catch (ReActLoopExhaustedException e) {
            String message = "SYNTHESIZE_SYNOPSIS failed for " + batchId + ": " + e.getMessage();
            batchLogger.error("SYNTHESIZE_SYNOPSIS", message);
            throw new RuntimeException(message, e);
        }
    }

    private Consumer<SynopsisResponse> synopsisValidator() {
        return response -> {
            if (response.sectionsBlank()) {
                throw new IllegalStateException(
                        "All three description sections (domain, interfaces, deployment) are required and must not be blank.");
            }
            int words = Stream.of(response.domain(), response.interfaces(), response.deployment())
                    .mapToInt(section -> section.split("\\s+").length)
                    .sum();
            if (words > 250) {
                throw new IllegalStateException(
                        "The description is " + words + " words; keep the three sections close to 150 words in total.");
            }
        };
    }

    String buildSystemPrompt(Prepared prepared) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You synthesize the synopsis of a repository for repo-intelligence tool.

                Write a close-to-150-word description in three required sections:
                - Domain: the business or product purpose, in the repository's own terms.
                - Interfaces: what it exposes and consumes. Name concrete peers, services, datastores and topics.
                - Deployment: how it is built, packaged and run; which infrastructure it targets and uses.

                Also return:
                - repoRole: one of service, web-app, mobile-app, library, infra, tooling, docs, data.
                - knownAs: up to 8 names by which other repositories refer to this one (published package
                  coordinate, image name, service name, deployed name). The seed names are listed; add a name
                  only when the repository states it.
                - citations: the dimensionIds, wikiSlug, surfaceIds and attributeIds you actually used, plus the file paths you read.

                Use the read_file tool when the pre-fetched context is not enough; prefer
                manifests, README, Dockerfile, CI and deployment files. The synthesis runs under a strict
                turn limit: end with your final answer, not with further tool calls.""");
        prompt.append("\nRepository: ").append(prepared.repoName()).append("\n");
        prompt.append("\nWiki root page (slug ")
                .append(prepared.inputs().wikiRoot().getPageSlug())
                .append("):\n### ")
                .append(prepared.inputs().wikiRoot().getTitle())
                .append("\n")
                .append(trim(prepared.inputs().wikiRoot().getContent(), 4000))
                .append("\n");
        prompt.append("\nDimensions:\n");
        for (CtxDimension dimension : prepared.inputs().dimensions()) {
            prompt.append("- [")
                    .append(dimension.getId())
                    .append("] ")
                    .append(dimension.getName())
                    .append(" (")
                    .append(dimension.getCategory())
                    .append("): ")
                    .append(trim(dimension.getSynopsis(), 500))
                    .append("\n");
        }
        prompt.append("\nArchitecture patterns:\n");
        for (CtxArchitecturePattern pattern : prepared.inputs().patterns()) {
            prompt.append("- ")
                    .append(pattern.getName())
                    .append(": ")
                    .append(trim(pattern.getDescription(), 300))
                    .append("\n");
        }
        prompt.append("\nSurfaces and attributes:\n");
        appendSurfaces(prompt, prepared.inputs().evidenceSurfaces());
        prompt.append("\nSeed known_as names: ")
                .append(seedNames(prepared.inputs()))
                .append("\n");
        return prompt.toString();
    }

    private void appendSurfaces(StringBuilder prompt, List<SurfaceEvidence> surfaces) {
        int included = 0;
        int omittedAttributes = 0;
        for (int index = 0; index < surfaces.size(); index++) {
            SurfaceEvidence surface = surfaces.get(index);
            String header = "- [" + surface.id() + "] " + surface.role() + " " + surface.description() + " (source: "
                    + surface.detectedBy() + ")\n";
            if (prompt.length() + header.length() > SURFACE_EVIDENCE_CHAR_BUDGET) {
                int omittedSurfaces = surfaces.size() - index;
                prompt.append("(").append(omittedSurfaces).append(" surfaces omitted for prompt size)\n");
                logger.info(
                        "Synopsis surface evidence capped: {}/{} surfaces, {} attributes omitted",
                        included,
                        surfaces.size(),
                        omittedAttributes);
                return;
            }
            prompt.append(header);
            int attributes = 0;
            for (AttributeEvidence attribute : surface.attributes()) {
                String line = "  - [" + attribute.id() + "] " + attribute.kind() + "=" + attribute.value()
                        + " (source: " + attribute.detectedBy() + ")\n";
                if (prompt.length() + line.length() > SURFACE_EVIDENCE_CHAR_BUDGET) {
                    int omitted = surface.attributes().size() - attributes;
                    omittedAttributes += omitted;
                    prompt.append("  (").append(omitted).append(" attributes omitted for prompt size)\n");
                    break;
                }
                prompt.append(line);
                attributes++;
            }
            included = index + 1;
            if (prompt.length() >= SURFACE_EVIDENCE_CHAR_BUDGET) {
                int omittedSurfaces = surfaces.size() - included;
                if (omittedSurfaces > 0) {
                    prompt.append("(").append(omittedSurfaces).append(" surfaces omitted for prompt size)\n");
                }
                logger.info(
                        "Synopsis surface evidence capped: {}/{} surfaces, {} attributes omitted",
                        included,
                        surfaces.size(),
                        omittedAttributes);
                return;
            }
        }
    }

    private String seedNames(SynopsisInputs inputs) {
        return inputs.evidenceSurfaces().stream()
                .filter(surface -> surface.role() == SurfaceRole.PROVIDES)
                .flatMap(surface -> surface.attributes().stream())
                .filter(attribute -> "identifier".equals(attribute.kind()))
                .map(AttributeEvidence::value)
                .distinct()
                .limit(8)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String trim(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "\n...";
    }

    Map<String, Object> filterCitations(
            UUID batchId,
            SynopsisInputs inputs,
            SynopsisResponse response,
            IngestionBatchLogService.BatchLogger batchLogger) {
        SynopsisResponse.Citations citations = response.citations();
        Set<String> dimensionIds = inputs.dimensions().stream()
                .map(dimension -> dimension.getId().toString())
                .collect(Collectors.toSet());
        Set<String> surfaceIds = inputs.evidenceSurfaces().stream()
                .map(surface -> surface.id().toString())
                .collect(Collectors.toSet());
        Set<String> attributeIds = inputs.evidenceSurfaces().stream()
                .flatMap(surface -> surface.attributes().stream())
                .map(attribute -> attribute.id().toString())
                .collect(Collectors.toSet());
        List<String> keptDimensions = citations.dimensionIds().stream()
                .filter(dimensionIds::contains)
                .distinct()
                .toList();
        List<String> keptSurfaces = citations.surfaceIds().stream()
                .filter(surfaceIds::contains)
                .distinct()
                .toList();
        List<String> keptAttributes = citations.attributeIds().stream()
                .filter(attributeIds::contains)
                .distinct()
                .toList();
        boolean slugKept = inputs.wikiRoot().getPageSlug().equals(citations.wikiSlug());
        List<String> keptPaths = citations.filePaths().stream()
                .filter(path -> fileExists(batchId, path))
                .distinct()
                .toList();
        batchLogger.info(
                "SYNTHESIZE_SYNOPSIS",
                "Citations kept: %d dimension ids, %s wiki slug, %d file paths, %d surfaces, %d attributes."
                        .formatted(
                                keptDimensions.size(),
                                slugKept ? "the" : "no",
                                keptPaths.size(),
                                keptSurfaces.size(),
                                keptAttributes.size()));
        Map<String, Object> retained = new LinkedHashMap<>();
        retained.put("dimensionIds", keptDimensions);
        if (slugKept) {
            retained.put("wikiSlug", citations.wikiSlug());
        }
        retained.put("filePaths", keptPaths);
        retained.put("surfaceIds", keptSurfaces);
        retained.put("attributeIds", keptAttributes);
        return retained;
    }

    private boolean fileExists(UUID batchId, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        try {
            Path clone = Path.of(baseCloneDir, batchId.toString()).toRealPath();
            Path resolved = clone.resolve(relativePath).normalize();
            return resolved.startsWith(clone)
                    && resolved.toRealPath().startsWith(clone)
                    && Files.isRegularFile(resolved);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void persist(
            UUID batchId,
            SynopsisResponse response,
            String modelRef,
            SynopsisInputs inputs,
            IngestionBatchLogService.BatchLogger batchLogger) {
        Map<String, Object> citations = filterCitations(batchId, inputs, response, batchLogger);
        String synopsis = "Domain: %s\n\nInterfaces: %s\n\nDeployment: %s"
                .formatted(
                        response.domain().trim(),
                        response.interfaces().trim(),
                        response.deployment().trim());
        transactionTemplate.executeWithoutResult(status -> {
            CtxRepoSynopsis row = ctxRepoSynopsisRepository
                    .findByBatchId(batchId)
                    .orElseGet(() -> new CtxRepoSynopsis(
                            loadBatch(batchId), response.repoRole(), response.knownAs(), synopsis, modelRef));
            row.setRepoRole(response.repoRole());
            row.setKnownAs(response.knownAs());
            row.setSynopsis(synopsis);
            row.setCitations(citations);
            row.setModelRef(modelRef);
            ctxRepoSynopsisRepository.save(row);
        });
    }

    private IngestionBatch loadBatch(UUID batchId) {
        return ingestionBatchRepository
                .findByIdWithRepositoryAndTeam(batchId)
                .orElseThrow(() -> new IllegalStateException("Ingestion batch not found: " + batchId));
    }
}
