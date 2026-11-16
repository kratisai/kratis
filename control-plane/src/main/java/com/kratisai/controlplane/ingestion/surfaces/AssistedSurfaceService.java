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
import java.util.Locale;
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
            - Synchronous network APIs and endpoints served or called, preserving static paths or runtime-composed
              target expressions, plus HTTP/RPC methods and, when visible, request/response payload or schema names.
            - Runtime asynchronous messaging, event, or bidirectional stream contracts — including domain events,
              commands, or messages sent or received directly or via an imported client library. When messaging uses a
              shared library or SDK, always emit TWO separate observations: (1) a REQUIRES observation for the library
              or package dependency itself (identifying the library and imported boundary symbols), and (2) a separate
              BIDIRECTIONAL observation for the runtime communication contract with the external peer or server
              (inspecting message/event handlers, publishers, subscribers, and payload/event schemas where present,
              recording event, message, payload/schema alongside superclass, event-family, channel, and client/SDK hook
              names as IDENTIFIER attributes, and describing the application-level runtime message/event exchange —
              never framing the runtime messaging surface as a "library" or "hidden infrastructure").
            - Persistent backing stores read or written by the codebase (databases, schemas, tables, caches, buckets,
              shared filesystems), recording concrete store/table/bucket/path identifiers.
            - Deployable artifacts, container definitions, static bundles, and runtime hosting or infrastructure
              provisioning targets owned or targeted by this repository. Describe infrastructure provisioning
              definitions (such as IaC templates, orchestration manifests, or cloud resource declarations creating
              managed databases, caches, brokers, topics, queues, registries, compute hosts, or ingress routes)
              strictly as provisioned infrastructure resources (role PROVIDES) — never describe an infrastructure
              declaration as actively publishing, sending, or receiving runtime messages.

            ATTRIBUTES (each entry in `attributes` has `kind`, `value`, `file`):
            - ENDPOINT: network URLs, route paths, or runtime-composed target address expressions.
            - IDENTIFIER: package/artifact coordinates, service/resource/bucket/queue/topic/store names,
              event/message/payload/schema/channel names, config keys, or client/SDK hook names.
            - METHOD: HTTP/RPC verbs (or client send/receive hook methods where applicable).
            - TECHNOLOGY: frameworks, protocols, runtimes, or cloud resource/platform types.
            - FILE: exact repository-relative source file path (also placed in `fact.file`).

            EXCLUDE:
            - Third-party dependencies already captured by dependency manifests (report a dependency/library surface
              only when it mediates an internal or cross-repository contract within a multi-repository system, and do
              not attach third-party manifest version strings to runtime HTTP/messaging surfaces).
            - Repo-local internals that do not cross repository boundaries: internal classes, helpers, domain models,
              data shapes, local static asset/media catalogs, build/bundling toolchains, self-contained UI or rendering
              engines, individual config flags or credentials, hypothetical future integrations, and local
              developer/test-runner configurations, test-only code, or mocks.

            ROLE (do not guess PROVIDES vs REQUIRES for symmetric or bidirectional concepts):
            - PROVIDES: hosts or serves a synchronous API, publishes a reusable library/package, or declares/provisions
              shared runtime infrastructure or hosting platforms (clusters, registries, load balancers, DNS, CDNs,
              storage buckets, managed databases/brokers) that host workloads.
            - REQUIRES: invokes an external synchronous API, consumes a package dependency, reads/writes a persistent
              backing store, or defines deployable application build/workload manifests (Dockerfiles, container task
              definitions, Kubernetes workload manifests, CI deploy scripts) that require external runtime
              infrastructure, registries, or hosting to run.
            - BIDIRECTIONAL: actively sends and/or receives asynchronous messages, events, commands, or streams, or
              participates in a symmetric boundary contract where PROVIDES/REQUIRES does not apply.
            - UNKNOWN: use when boundary ownership or direction cannot be established from the source.

            TOOLS AND OUTPUT:
            - Focus on external communication, dependency, and deployment interfaces rather than internal domain
              models or internal data shapes; read candidate manifests, specs, entrypoints, and boundary files shown in
              the surface index with read_file. Batch multiple read_file, search_files, and list_files calls in
              parallel per turn (up to ten paths) so discovery finishes well within the turn budget. When a directory
              holds multiple per-service, per-app, or per-environment infrastructure, deployment, or route manifests,
              inspect the sibling files rather than sampling one file.
            - Do not stop at configuration files or dependency manifests: whenever an environment/config variable
              contains a template, placeholder, or external target, or when an imported internal library mediates a
              runtime interface, use search_files(pattern, pathGlob) and read_file on its call sites in source code to
              resolve how the target is constructed, invoked, or used to send/receive messages or events. Use
              list_files(glob) to list filenames in a collapsed directory.
            - Retain distinct contract-identifying values — including each target service/repository, bucket/queue/topic
              name, endpoint path, or domain event type (up to ~15–25 per surface) — while omitting repetitive
              boilerplate. Every surface needs at least one identifying attribute beyond FILE, with surfaceId set to
              null and every fact citing the exact repository-relative file path where it is implemented or wired.
            """;

    private static final int MAX_TURNS = 25;

    static String prompt() {
        return PROMPT;
    }

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
        long startNanos = System.nanoTime();
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
                                logger.warn(PHASE, "Discovery response rejected (retrying): " + e.getMessage());
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
        String elapsed = String.format(Locale.ROOT, "%.1fs", (System.nanoTime() - startNanos) / 1_000_000_000.0);
        logger.info(PHASE, "ASSIST_SURFACES completed in " + elapsed + ": accepted " + accepted + " observations.");
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
            case START -> LOG.debug("{}: calling tool {}", PHASE, safeToolName);
            case COMPLETE -> LOG.debug("{}: tool {} completed", PHASE, safeToolName);
            case ERROR -> {
                LOG.debug("{}: tool {} failed", PHASE, safeToolName);
                batchLogger.warn(PHASE, "Tool execution failed (retrying): " + safeToolName);
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
        logger.debug(
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
        int skippedTestAnchored = 0;
        for (AssistedResponse.Observation observation : response.surfaces()) {
            String summary = observationSummary(observation);
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
                logger.debug(
                        PHASE,
                        "Rejected observation facts with invalid source anchors (" + summary + "): " + rejectedFiles);
            }
            if (validFacts.isEmpty()) {
                logger.debug(
                        PHASE,
                        "Skipped observation with no valid source anchor (" + summary + "), files="
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
                logger.debug(PHASE, "Skipped test-anchored observation (" + summary + ").");
                continue;
            }
            CtxSurface surface = observation.surfaceId() == null ? null : existing.get(observation.surfaceId());
            if (observation.surfaceId() != null && surface == null) {
                logger.debug(
                        PHASE,
                        "Treating unknown model surface ID " + observation.surfaceId() + " (" + summary
                                + ") as a new, source-anchored surface.");
            }
            if (surface != null
                    && surface.getDetectedBy() == SurfaceDetection.CMM
                    && surface.getRole() == SurfaceRole.UNKNOWN) {
                logger.debug(
                        PHASE,
                        "Treating grouped CMM candidate " + observation.surfaceId() + " (" + summary
                                + ") as evidence for a separate model surface.");
                surface = null;
            }
            boolean created = surface == null;
            if (created && facts.stream().noneMatch(fact -> fact.kind() != AttributeKind.FILE)) {
                logger.debug(PHASE, "Skipping new observation with only FILE attributes (" + summary + ").");
                continue;
            }
            if (created && observation.description().isBlank()) {
                logger.debug(
                        PHASE,
                        "Skipping new observation without a description (files="
                                + facts.stream()
                                        .map(AssistedResponse.Fact::file)
                                        .distinct()
                                        .toList()
                                + ").");
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
        if (skippedTestAnchored > 0) {
            logger.info(PHASE, "Skipped " + skippedTestAnchored + " test-anchored observations.");
        }
        return accepted;
    }

    private static String observationSummary(AssistedResponse.Observation observation) {
        String desc =
                observation.description() == null || observation.description().isBlank()
                        ? "(no description)"
                        : observation.description();
        List<String> files = observation.attributes().stream()
                .map(AssistedResponse.Fact::file)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        String role = observation.role() == null ? "null" : observation.role().name();
        return "role=" + role + ", desc=\"" + desc + "\", files=" + files;
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
