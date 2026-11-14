package com.kratisai.controlplane.ingestion.surfaces;

import com.kratisai.controlplane.agentloop.ReActLoop;
import com.kratisai.controlplane.agentloop.ReActLoopExhaustedException;
import com.kratisai.controlplane.agentloop.ReActLoopFatalException;
import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionUsageTracker;
import com.kratisai.controlplane.ingestion.ListRepositoryFilesTool;
import com.kratisai.controlplane.ingestion.ReadFileTool;
import com.kratisai.controlplane.ingestion.SearchRepositoryFilesTool;
import com.kratisai.controlplane.model.AttributeKind;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.CtxSurfaceAttribute;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionModelUsage;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.CtxSurfaceAttributeRepository;
import com.kratisai.controlplane.repository.CtxSurfaceRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AssistedSurfaceService {
    private static final Logger LOG = LoggerFactory.getLogger(AssistedSurfaceService.class);
    private static final String PHASE = "ASSIST_SURFACES";
    private static final String PROMPT = """
            Discover contracts between this codebase and external code or infrastructure: interfaces crossing repository,
            process, deployment, or resource boundaries.

            INCLUDE (one grouped surface per contract or resource family, grounded in present-state source files):
            - Network APIs and endpoints served or called (HTTP, gRPC, GraphQL, RPC), preserving static paths or
              runtime-composed URL/address expressions as ENDPOINT or REF, plus HTTP/RPC methods as METHOD.
            - Runtime message, event, or bidirectional stream contracts (WebSocket, Socket.IO, pub/sub topics, queues,
              brokers) — including domain event or command payloads sent or received over a connection hook or client
              SDK imported from a shared library. Describe the surface as the runtime contract with the external server
              or broker (mediated by the library), separate from any package dependency surface, recording event/payload
              types, channel/topic names, or endpoints as NAME, IDENTIFIER, or ENDPOINT attributes (and SDK connection
              hooks as REF; reserve METHOD for HTTP/RPC verbs).
            - Deployed services, container images, static-site/cloud targets, data stores, provisioned infrastructure
              resources, and build/package outputs owned or targeted by this repository.

            EXCLUDE:
            - Standard third-party UI, rendering, state, utility, framework, bundler, linter, or test libraries (Syft
              already records dependency manifests; only report a dependency surface when it mediates an internal or
              cross-repository contract, and do not attach package.json or lockfile third-party version strings to
              runtime HTTP/WebSocket/messaging surfaces).
            - Internal classes, hooks, components, state stores, helpers, individual config flags or credentials,
              hypothetical future integrations, and test-only code or mocks.

            ROLE (decide by boundary ownership, never by symbol names or the word "publish"):
            - PROVIDES: this repository hosts or serves the network endpoint, WebSocket server, build/package artifact,
              or provisioned infrastructure resource, or owns an outbound domain event topic/bus subscribed to by
              downstream services.
            - REQUIRES: this repository connects as a client to an external API, WebSocket server, broker, or resource
              — including when client code sends or publishes commands, actions, or events to an external server
              (directly or via an imported client SDK).
            - Omit observations whose PROVIDES or REQUIRES boundary role is not established.

            TOOLS AND OUTPUT:
            - Read candidate manifests, specs, entrypoints, and boundary files shown in the surface index with read_file
              (batch up to ten paths in parallel; page long files if needed). When a directory holds multiple
              per-service, per-app, or per-environment infrastructure, deployment, or route manifests, inspect the
              sibling files rather than sampling one file.
            - Large directories collapse leaf filenames in the index. Use search_files(pattern, pathGlob) specifically
              to locate call sites of boundary SDKs, client classes, hooks, channels, or config keys identified in
              manifests or entrypoints (do not run broad keyword fishing), or list_files(glob) to list filenames in a
              collapsed directory.
            - Retain distinct contract-identifying values — including each target service/repository, bucket/queue/topic
              name, endpoint path, or domain event type (up to ~15–25 per surface) — while omitting repetitive
              boilerplate. Every surface needs at least one identifying attribute beyond FILE, with surfaceId set to
              null and every fact citing the exact repository-relative file path where it is implemented or wired.
            """;

    private static final int MAX_TURNS = 20;

    private final IngestionBatchRepository ingestionBatchRepository;
    private final CtxSurfaceRepository ctxSurfaceRepository;
    private final CtxSurfaceAttributeRepository ctxSurfaceAttributeRepository;
    private final CtxNodeRepository ctxNodeRepository;
    private final CtxEdgeRepository ctxEdgeRepository;
    private final SurfaceExtractionService surfaceExtractionService;
    private final ChatModelFactory chatModelFactory;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final IngestionUsageTracker usageTracker;
    private final IngestionProperties ingestionProperties;
    private final TransactionTemplate transactionTemplate;
    private final ToolCallback[] toolCallbacks;
    private final Executor agentTaskExecutor;

    public AssistedSurfaceService(
            IngestionBatchRepository ingestionBatchRepository,
            CtxSurfaceRepository ctxSurfaceRepository,
            CtxSurfaceAttributeRepository ctxSurfaceAttributeRepository,
            CtxNodeRepository ctxNodeRepository,
            CtxEdgeRepository ctxEdgeRepository,
            SurfaceExtractionService surfaceExtractionService,
            ChatModelFactory chatModelFactory,
            LiteLLMProvisioningService litellmProvisioningService,
            IngestionUsageTracker usageTracker,
            IngestionProperties ingestionProperties,
            ReadFileTool readFileTool,
            ListRepositoryFilesTool listRepositoryFilesTool,
            SearchRepositoryFilesTool searchRepositoryFilesTool,
            PlatformTransactionManager transactionManager,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor) {
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.ctxSurfaceRepository = ctxSurfaceRepository;
        this.ctxSurfaceAttributeRepository = ctxSurfaceAttributeRepository;
        this.ctxNodeRepository = ctxNodeRepository;
        this.ctxEdgeRepository = ctxEdgeRepository;
        this.surfaceExtractionService = surfaceExtractionService;
        this.chatModelFactory = chatModelFactory;
        this.litellmProvisioningService = litellmProvisioningService;
        this.usageTracker = usageTracker;
        this.ingestionProperties = ingestionProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.agentTaskExecutor = agentTaskExecutor;
        this.toolCallbacks = ToolCallbackProvider.from(
                        ToolCallbacks.from(readFileTool, listRepositoryFilesTool, searchRepositoryFilesTool))
                .getToolCallbacks();
    }

    private record Prepared(
            Map<UUID, CtxSurface> existingById,
            List<CtxNode> parserNodes,
            Set<String> testFiles,
            ChatClient chatClient) {}

    public void assist(UUID batchId, IngestionBatchLogService.BatchLogger logger) {
        logger.info(PHASE, "Discovering and enriching repository surfaces.");
        Path root = Path.of(ingestionProperties.getCloneDir(), batchId.toString())
                .toAbsolutePath()
                .normalize();
        Prepared prepared = Objects.requireNonNull(
                transactionTemplate.execute(status -> prepare(batchId)), "Prepared state is required");
        List<String> inventory = ListRepositoryFilesTool.inventory(root);
        List<String> textInventory = inventory.stream()
                .filter(path -> isReadableTextFile(root.resolve(path)))
                .toList();
        String input = discoveryInput(prepared.parserNodes(), textInventory, prepared.testFiles(), logger);
        ReActLoop<AssistedResponse.Discovery> loop = ReActLoop.structured(
                        prepared.chatClient(), toolCallbacks, AssistedResponse.Discovery.class, response -> {
                            try {
                                validateDiscoveryResponse(response, root, prepared.existingById(), inventory);
                            } catch (IllegalArgumentException e) {
                                logger.error(PHASE, "Rejected discovery response: " + e.getMessage());
                                throw e;
                            }
                        })
                .maxIterations(MAX_TURNS)
                .executor(agentTaskExecutor)
                .onToolCallCount(count -> usageTracker.recordToolCalls(batchId, count))
                .onToolEvent((attempt, taskId, toolName, phase, detail) -> logToolEvent(logger, toolName, phase));
        List<Message> history = List.of(new SystemMessage(PROMPT + "\n" + loop.format()), new UserMessage(input));
        Set<String> textSet = new HashSet<>(textInventory);
        Set<String> excludedPaths = inventory.stream()
                .filter(path -> !textSet.contains(path) || isTestFile(path, prepared.testFiles()))
                .collect(Collectors.toUnmodifiableSet());
        AssistedResponse.Discovery response;
        try {
            response = loop.run(
                    history,
                    new ToolContext(Map.of("batchId", batchId, "batchLogger", logger, "excludedPaths", excludedPaths)));
        } catch (ReActLoopFatalException e) {
            logger.error(PHASE, "Fatal LLM error during assisted resolution: " + e.getMessage());
            throw new RuntimeException("LLM call failed during assisted resolution: " + e.getMessage(), e);
        } catch (ReActLoopExhaustedException e) {
            String message =
                    "Assisted resolution did not return a valid answer within " + e.maxIterations() + " turns.";
            String detail = e.lastValidationError();
            if (detail != null) {
                String finalTurnError = "Final turn returned tool calls; a text-only answer was required.";
                message += " Last error: "
                        + (detail.equals(finalTurnError)
                                ? finalTurnError
                                : "Structured response did not match the required format or was incomplete.");
            }
            logger.error(PHASE, message);
            throw new RuntimeException(message, e);
        }
        if (response == null) throw new IllegalStateException("No assisted discovery response");
        AssistedResponse.Discovery finalResponse = response;
        Integer accepted = transactionTemplate.execute(status -> {
            IngestionBatch batch = loadBatch(batchId);
            Map<UUID, CtxSurface> byId = new HashMap<>();
            ctxSurfaceRepository.findByBatchId(batchId).forEach(surface -> byId.put(surface.getId(), surface));
            return persist(batch, root, byId, finalResponse, logger, inventory, prepared.testFiles());
        });
        logger.info(PHASE, "Accepted " + accepted + " assisted observations; scope is model-assessed.");
        logger.info(PHASE, "Assisted surface discovery completed successfully.");
    }

    private Prepared prepare(UUID batchId) {
        IngestionBatch batch = loadBatch(batchId);
        List<CtxSurface> existing = ctxSurfaceRepository.findByBatchId(batchId);
        Map<UUID, CtxSurface> byId = new HashMap<>();
        existing.forEach(surface -> byId.put(surface.getId(), surface));
        List<CtxNode> parserNodes = ctxNodeRepository.findByBatchId(batchId);
        Set<String> testFiles = new HashSet<>(ctxEdgeRepository.findTestSourcePathsByBatchId(batchId));
        parserNodes.stream()
                .filter(node -> node.getPath() != null && isMarkedTest(node.getMetadata()))
                .map(CtxNode::getPath)
                .forEach(testFiles::add);
        return new Prepared(Map.copyOf(byId), parserNodes, Set.copyOf(testFiles), chatClientFor(batch));
    }

    private IngestionBatch loadBatch(UUID batchId) {
        return ingestionBatchRepository
                .findByIdWithRepositoryAndTeam(batchId)
                .orElseThrow(() -> new IllegalStateException("Ingestion batch not found: " + batchId));
    }

    private void logToolEvent(
            IngestionBatchLogService.BatchLogger batchLogger, String toolName, ReActLoop.ToolPhase phase) {
        boolean known = Arrays.stream(toolCallbacks)
                .anyMatch(callback -> callback.getToolDefinition().name().equals(toolName));
        String safeToolName = known ? toolName : "unregistered tool";
        switch (phase) {
            case START -> {
                LOG.debug("{}: calling tool {}", PHASE, safeToolName);
                batchLogger.info(PHASE, "Calling tool: " + safeToolName);
            }
            case COMPLETE -> LOG.debug("{}: tool {} completed", PHASE, safeToolName);
            case ERROR -> {
                LOG.debug("{}: tool {} failed", PHASE, safeToolName);
                batchLogger.error(PHASE, "Tool execution failed: " + safeToolName);
            }
        }
    }

    static String discoveryInput(
            List<CtxNode> nodes,
            List<String> inventory,
            Set<String> testFiles,
            IngestionBatchLogService.BatchLogger logger) {
        String input = "Surface index (no prior surface inventory):\n"
                + SurfaceIndex.build(nodes, inventory, testFiles, SurfaceIndex.DEFAULT_MAX_CHARS);
        logger.info(
                PHASE,
                "Discovery index contains " + inventory.size() + " file paths and " + input.length() + " characters.");
        return input;
    }

    static boolean isReadableTextFile(Path file) {
        return SearchRepositoryFilesTool.isReadableTextFile(file);
    }

    static void validateDiscoveryResponse(
            AssistedResponse.Discovery response, Path root, Map<UUID, CtxSurface> existing, List<String> inventory) {
        for (AssistedResponse.Observation observation : response.surfaces()) {
            if (observation.attributes().stream().anyMatch(attribute -> attribute.kind() == null)) {
                throw new IllegalArgumentException("Every attribute requires a valid AttributeKind");
            }
            CtxSurface referenced = observation.surfaceId() == null ? null : existing.get(observation.surfaceId());
            boolean newSurface = referenced == null
                    || referenced.getDetectedBy() == SurfaceDetection.CMM
                            && referenced.getRole() == SurfaceRole.UNKNOWN;
            if (newSurface
                    && observation.attributes().stream()
                            .noneMatch(attribute -> attribute.kind() != AttributeKind.FILE)) {
                throw new IllegalArgumentException("A new surface needs a source fact beyond its file anchor");
            }
            if (observation.description().isBlank()) {
                throw new IllegalArgumentException("Every surface observation requires a description");
            }
            if (observation.attributes().isEmpty()) {
                throw new IllegalArgumentException("Every surface observation requires source-anchored attributes");
            }
            List<String> invalidFiles = observation.attributes().stream()
                    .map(fact -> {
                        String resolved = resolveFile(fact.file(), inventory);
                        if (resolved == null && validAnchor(root, fact.file())) {
                            resolved = fact.file();
                        }
                        if (resolved != null
                                && validAnchor(root, resolved)
                                && !fact.value().isBlank()) {
                            return null;
                        }
                        String suggestion = suggestFile(fact.file(), inventory);
                        return fact.file() + (suggestion == null ? "" : " (did you mean " + suggestion + "?)");
                    })
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!invalidFiles.isEmpty()) {
                throw new IllegalArgumentException("Invalid source anchors: " + invalidFiles);
            }
        }
    }

    int persist(
            IngestionBatch batch,
            Path root,
            Map<UUID, CtxSurface> existing,
            AssistedResponse.Discovery response,
            IngestionBatchLogService.BatchLogger logger,
            List<String> inventory,
            Set<String> testFiles) {
        int accepted = 0;
        int unknownScope = 0;
        int skippedUnknownRole = 0;
        int skippedTestAnchored = 0;
        for (AssistedResponse.Observation observation : response.surfaces()) {
            unknownScope++;
            List<AssistedResponse.Fact> validFacts = observation.attributes().stream()
                    .map(fact -> {
                        String resolved = resolveFile(fact.file(), inventory);
                        if (resolved == null && validAnchor(root, fact.file())) {
                            resolved = fact.file();
                        }
                        return resolved == null ? fact : new AssistedResponse.Fact(fact.kind(), fact.value(), resolved);
                    })
                    .filter(fact -> fact.kind() != null
                            && validAnchor(root, fact.file())
                            && !fact.value().isBlank())
                    .toList();
            if (validFacts.size() != observation.attributes().size()) {
                List<String> rejectedFiles = observation.attributes().stream()
                        .filter(fact -> fact.kind() == null
                                || !validAnchor(root, fact.file())
                                || fact.value().isBlank())
                        .map(AssistedResponse.Fact::file)
                        .distinct()
                        .toList();
                logger.warn(
                        PHASE,
                        "Rejected observation facts with invalid source anchors for surface " + observation.surfaceId()
                                + ": " + rejectedFiles);
            }
            if (validFacts.isEmpty()) {
                logger.error(
                        PHASE,
                        "Observation has no valid source anchor: surface=" + observation.surfaceId()
                                + ", files="
                                + observation.attributes().stream()
                                        .map(AssistedResponse.Fact::file)
                                        .distinct()
                                        .toList());
                continue;
            }
            List<AssistedResponse.Fact> facts = validFacts.stream()
                    .filter(fact -> !isTestFile(fact.file(), testFiles))
                    .toList();
            if (facts.isEmpty()) {
                skippedTestAnchored++;
                continue;
            }
            CtxSurface surface = observation.surfaceId() == null ? null : existing.get(observation.surfaceId());
            if (observation.surfaceId() != null && surface == null) {
                logger.warn(
                        PHASE,
                        "Treating unknown model surface ID " + observation.surfaceId()
                                + " as a new, source-anchored surface.");
            }
            if (surface != null
                    && surface.getDetectedBy() == SurfaceDetection.CMM
                    && surface.getRole() == SurfaceRole.UNKNOWN) {
                logger.info(
                        PHASE,
                        "Treating grouped CMM candidate " + observation.surfaceId()
                                + " as evidence for a separate model surface.");
                surface = null;
            }
            boolean created = surface == null;
            if (created && (observation.role() == null || observation.role() == SurfaceRole.UNKNOWN)) {
                skippedUnknownRole++;
                continue;
            }
            if (created && facts.stream().noneMatch(fact -> fact.kind() != AttributeKind.FILE)) {
                logger.warn(PHASE, "Skipping new observation with only FILE attributes.");
                continue;
            }
            if (created && observation.description().isBlank()) {
                logger.error(PHASE, "Rejecting new observation without a description.");
                continue;
            }
            if (created) {
                surface = new CtxSurface(
                        batch,
                        batch.getRepository().getId(),
                        batch.getRepository().getTeam().getId(),
                        observation.role(),
                        observation.description(),
                        SurfaceDetection.MODEL);
            }
            List<CtxSurfaceAttribute> rows = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            surface.getAttributeRows()
                    .forEach(row -> seen.add(row.getKind() + "|" + row.getValue() + "|" + row.getDetectedBy()));
            for (AssistedResponse.Fact fact : facts) {
                if (seen.add(fact.kind() + "|" + fact.value() + "|" + SurfaceDetection.MODEL)) {
                    rows.add(new CtxSurfaceAttribute(surface, fact.kind(), fact.value(), SurfaceDetection.MODEL));
                }
                if (seen.add(AttributeKind.FILE + "|" + fact.file() + "|" + SurfaceDetection.MODEL)) {
                    rows.add(new CtxSurfaceAttribute(surface, AttributeKind.FILE, fact.file(), SurfaceDetection.MODEL));
                }
            }
            if (created) {
                rows.forEach(surface::addAttribute);
                GraphSurfaceTranslator.normalizeAttributes(surface);
                surfaceExtractionService.persist(List.of(surface));
            } else {
                if (surface.getDetectedBy() != SurfaceDetection.SYFT) {
                    int remainingBudget = Math.max(
                            0,
                            GraphSurfaceTranslator.MAX_NON_SYFT_ATTRIBUTES
                                    - surface.getAttributeRows().size());
                    if (rows.size() > remainingBudget) {
                        rows = rows.subList(0, remainingBudget);
                    }
                }
                rows.forEach(surface::addAttribute);
                if (!rows.isEmpty()) {
                    ctxSurfaceAttributeRepository.saveAll(rows);
                }
            }
            accepted++;
        }
        if (skippedUnknownRole > 0 || skippedTestAnchored > 0) {
            logger.info(
                    PHASE,
                    "Skipped " + skippedUnknownRole + " UNKNOWN-role observations and " + skippedTestAnchored
                            + " test-anchored observations.");
        }
        logger.info(PHASE, "Model observations with no independent scope classification: " + unknownScope);
        return accepted;
    }

    private static boolean isTestFile(String file, Set<String> testFiles) {
        return testFiles.contains(file) || GraphSurfaceTranslator.isTestPath(file);
    }

    private static boolean isMarkedTest(String metadata) {
        return metadata != null && metadata.contains("\"is_test\":true");
    }

    static String resolveFile(String file, List<String> inventory) {
        if (file == null || file.isBlank()) {
            return null;
        }
        String normalized = file.replace('\\', '/');
        if (inventory.contains(normalized)) {
            return normalized;
        }
        List<String> matches = inventory.stream()
                .filter(path -> path.endsWith("/" + normalized))
                .distinct()
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    static String suggestFile(String file, List<String> inventory) {
        if (file == null || file.isBlank()) {
            return null;
        }
        String normalized = file.replace('\\', '/');
        List<String> suffixMatches = inventory.stream()
                .filter(path -> path.endsWith("/" + normalized))
                .distinct()
                .toList();
        if (!suffixMatches.isEmpty()) {
            return suffixMatches.getFirst();
        }
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
        List<String> basenameMatches = inventory.stream()
                .filter(path -> path.endsWith("/" + basename) || path.equals(basename))
                .distinct()
                .toList();
        return basenameMatches.size() == 1 ? basenameMatches.getFirst() : null;
    }

    private static boolean validAnchor(Path root, String file) {
        if (file.isBlank()) return false;
        try {
            Path path = root.resolve(file).normalize();
            return path.startsWith(root)
                    && Files.isRegularFile(path)
                    && path.toRealPath().startsWith(root.toRealPath());
        } catch (IOException | InvalidPathException e) {
            return false;
        }
    }

    private ChatClient chatClientFor(IngestionBatch batch) {
        Team team = batch.getRepository().getTeam();
        ModelProvider modelProvider = team.getIngestionProvider();
        String modelName = team.getIngestionModel();
        String litellmModelName = litellmProvisioningService.buildLiteLLMModelName(modelProvider, modelName);
        String virtualKey = batch.modelUsageFor(ModelKind.CHAT)
                .map(IngestionModelUsage::getVirtualKey)
                .orElse(null);
        return ChatClient.builder(
                        chatModelFactory.createChatModelViaLiteLLM(modelProvider, litellmModelName, virtualKey, false))
                .build();
    }
}
