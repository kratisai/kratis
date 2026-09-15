package com.kratisai.controlplane.ingestion.parse;

import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxEdge;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.CtxNodeDimension;
import com.kratisai.controlplane.model.DimensionCategory;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionModelUsage;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxNodeDimensionRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DimensionDiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(DimensionDiscoveryService.class);

    private static final String GLOB_RETRY_GUIDANCE = """
            Your previous attempt produced dimension glob patterns that matched no files \
            in the repository. The annotated tree above shows relative file paths \
            (e.g. 'src/components/Button.tsx'). Rewrite the glob patterns so they match the \
            actual file paths shown in the tree, e.g. 'src/components/**/*.tsx'.
            """;

    private final CtxNodeRepository ctxNodeRepository;
    private final CtxEdgeRepository ctxEdgeRepository;
    private final CtxDimensionRepository ctxDimensionRepository;
    private final CtxNodeDimensionRepository ctxNodeDimensionRepository;
    private final ChatModelFactory chatModelFactory;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final IngestionBatchLogService ingestionBatchLogService;
    private final TreeHelper treeHelper;
    private final String baseCloneDir;

    public DimensionDiscoveryService(
            CtxNodeRepository ctxNodeRepository,
            CtxEdgeRepository ctxEdgeRepository,
            CtxDimensionRepository ctxDimensionRepository,
            CtxNodeDimensionRepository ctxNodeDimensionRepository,
            ChatModelFactory chatModelFactory,
            LiteLLMProvisioningService litellmProvisioningService,
            IngestionBatchLogService ingestionBatchLogService,
            TreeHelper treeHelper,
            @Value("${kratis.ingestion.clone-dir:${java.io.tmpdir}/kratis-ingest}") String baseCloneDir) {
        this.ctxNodeRepository = ctxNodeRepository;
        this.ctxEdgeRepository = ctxEdgeRepository;
        this.ctxDimensionRepository = ctxDimensionRepository;
        this.ctxNodeDimensionRepository = ctxNodeDimensionRepository;
        this.chatModelFactory = chatModelFactory;
        this.litellmProvisioningService = litellmProvisioningService;
        this.ingestionBatchLogService = ingestionBatchLogService;
        this.treeHelper = treeHelper;
        this.baseCloneDir = baseCloneDir;
    }

    private ChatModel getChatModel(IngestionBatch batch) {
        Team team = batch.getRepository().getTeam();
        ModelProvider modelProvider = team.getIngestionProvider();
        String modelName = team.getIngestionModel();
        String litellmModelName = litellmProvisioningService.buildLiteLLMModelName(modelProvider, modelName);
        String virtualKey = batch.modelUsageFor(ModelKind.CHAT)
                .map(IngestionModelUsage::getVirtualKey)
                .orElse(null);
        return chatModelFactory.createChatModelViaLiteLLM(modelProvider, litellmModelName, virtualKey, false);
    }

    // Generation.getOutput() is contractually non-null in Spring AI (JSpecify-annotated,
    // defaulted via requireNonNullElse); SpotBugs cannot see those contracts and assumes nullable.
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    private static String responseText(ChatResponse response) {
        var result = response.getResult();
        return result != null ? result.getOutput().getText() : null;
    }

    public static class DimensionDiscoveryException extends RuntimeException {
        public DimensionDiscoveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Transactional
    public void discoverDimensions(IngestionBatch batch) {
        var batchLogger = new IngestionBatchLogService.BatchLogger(
                batch.getId(), batch.getRepository().getTeam().getId(), ingestionBatchLogService);

        String annotatedTree = treeHelper.buildAnnotatedTree(batch);

        if (annotatedTree.isBlank()) {
            batchLogger.info("PREPARE_DIMENSIONS", "No files found to discover dimensions.");
            return;
        }

        DimensionDiscoveryResult result = executeDimensionDiscovery(batch, annotatedTree, batchLogger, null);
        requireSufficientDimensions(result, batchLogger);

        // An LLM may emit glob patterns that match no file paths. Persisting the dimensions
        // anyway silently produces empty dimension stats, so give the model one corrective
        // retry before failing the ingestion.
        if (taggedFileCount(batch, result) == 0) {
            batchLogger.info(
                    "PREPARE_DIMENSIONS",
                    "Discovered dimensions' glob patterns matched no files; retrying discovery once.");
            result = executeDimensionDiscovery(batch, annotatedTree, batchLogger, GLOB_RETRY_GUIDANCE);
            requireSufficientDimensions(result, batchLogger);
        }

        int taggedFiles = persistDimensionsAndTagNodes(batch, result, batchLogger);
        if (taggedFiles == 0) {
            throw new DimensionDiscoveryException(
                    "Discovered dimensions' glob patterns matched no files in the repository.", null);
        }
    }

    private void requireSufficientDimensions(
            DimensionDiscoveryResult result, IngestionBatchLogService.BatchLogger batchLogger) {
        if (result.domains().isEmpty()
                || result.archetypes().isEmpty()
                || result.crossCutting().isEmpty()) {
            batchLogger.info("PREPARE_DIMENSIONS", "Insufficient dimensions discovered.");
            throw new DimensionDiscoveryException(
                    "Insufficient dimensions discovered: " + result.domains().size()
                            + " domains" + result.archetypes().size()
                            + " archetypes" + result.crossCutting().size()
                            + " cross-cutting dimensions",
                    null);
        }
    }

    private int taggedFileCount(IngestionBatch batch, DimensionDiscoveryResult result) {
        List<CtxNode> nodes = ctxNodeRepository.findByBatchId(batch.getId());
        List<CtxDimension> dimensions = createDimensionsFromResult(batch, result);
        return mapNodesToDimensions(nodes, dimensions).size();
    }

    private DimensionDiscoveryResult executeDimensionDiscovery(
            IngestionBatch batch,
            String annotatedTree,
            IngestionBatchLogService.BatchLogger batchLogger,
            String additionalGuidance) {

        BeanOutputConverter<DimensionDiscoveryResult> converter =
                new BeanOutputConverter<>(DimensionDiscoveryResult.class);

        String shot1PromptString = """
                You are an expert software architect.
                Based on the following annotated repository file tree, deduce the primary architectural dimensions.
                Each line shows the file path followed by a semantic footprint: the primary symbol name, inheritance relationships, and decorators/annotations.
                Identify dimensions in three categories: business-domains, technical archetypes, and cross-cutting services.
                    - 5-12 Business Domains. What are the core business concerns of this codebase? What does it achieve?
                    - 3-8 Technical Archetypes. What are the primary architectural patterns used in this codebase?
                    - 2-5 Cross-cutting Services. Support services that span multiple domains or archetypes?
                For each dimension, provide 3-5 glob patterns matching relevant files. Tree paths are relative to the repo
                root:  e.g. src/**/user/*.java or **/user/*.java or **/*Controller.java etc. Avoid ** where no path segment
                exists in the provided filenames (e.g. src/main/user/**/*.java and **/src/main/user/*.java both miss
                src/main/user/UserController.java).

                If you lack sufficient context to confidently identify these dimensions, you may request additional context by populating the 'additionalContextRequest' field.
                If you do, you MUST provide:
                - 'ambiguousFilePaths': A list of up to 10 specific file paths from the tree that are ambiguous.
                - 'rationale': A clear explanation of why these files are ambiguous and what you need to know.
                - 'certainDimensions': A list of dimension names you are already confident about.
                - 'expectedResolution': Exactly how reading these files will resolve the ambiguity.
                If you are confident, set 'additionalContextRequest' to null.

                Example 1 (Confident):
                Annotated File Tree:
                src/main/java/com/example/user/UserController.java: UserController, @RestController
                src/main/java/com/example/user/UserService.java: UserService, @Service
                src/main/java/com/example/order/OrderController.java: OrderController, @RestController
                src/main/java/com/example/order/OrderService.java: OrderService, @Service
                src/main/java/com/example/security/SecurityConfig.java: SecurityConfig, @Configuration

                Output:
                {
                  "domains": [
                    {"name": "User Management", "globPatterns": ["**/user/**/*.java"]},
                    {"name": "Order Management", "globPatterns": ["**/order/**/*.java"]}
                  ],
                  "archetypes": [
                    {"name": "REST Controllers", "globPatterns": ["*Controller.java"]},
                    {"name": "Business Services", "globPatterns": ["*Service.java"]}
                  ],
                  "crossCutting": [
                    {"name": "Security", "globPatterns": ["**/security/**/*.java", "*Security*.java"]}
                  ],
                  "additionalContextRequest": null
                }

                Example 2 (Requesting Context):
                Annotated File Tree:
                app/components/Button.tsx: Button, React.FC
                app/components/Input.tsx: Input, React.FC
                app/pages/Dashboard.tsx: Dashboard, React.FC
                app/utils/api.ts: fetchApi, function
                app/store/index.ts: store, Redux
                app/core/unknown.ts: Unknown, class

                Output:
                {
                  "domains": [
                    {"name": "Dashboard", "globPatterns": ["app/pages/Dashboard.tsx", "**/dashboard/**/*.tsx"]}
                  ],
                  "archetypes": [
                    {"name": "UI Components", "globPatterns": ["app/components/**/*.tsx"]},
                    {"name": "State Management", "globPatterns": ["app/store/**/*.ts"]}
                  ],
                  "crossCutting": [
                    {"name": "API Utilities", "globPatterns": ["app/utils/api.ts", "**/api/**/*.ts"]}
                  ],
                  "additionalContextRequest": {
                    "ambiguousFilePaths": ["app/core/unknown.ts"],
                    "rationale": "The file 'unknown.ts' has a generic name and I cannot determine its business domain or archetype.",
                    "certainDimensions": ["Dashboard", "UI Components", "State Management", "API Utilities"],
                    "expectedResolution": "Reading the first 100 lines of 'unknown.ts' will reveal its imports and class structure, allowing me to classify it correctly."
                  }
                }

                Actual Task:
                Annotated File Tree:
                {tree}

                {format}
                """;

        String renderedShot1Prompt =
                shot1PromptString.replace("{tree}", annotatedTree).replace("{format}", converter.getFormat());
        if (additionalGuidance != null && !additionalGuidance.isBlank()) {
            renderedShot1Prompt = renderedShot1Prompt + "\n\nAdditional guidance:\n" + additionalGuidance;
        }

        ChatModel chatModel = getChatModel(batch);
        try {
            ChatClient chatClient = ChatClient.builder(chatModel).build();
            ChatResponse shot1Response =
                    chatClient.prompt(renderedShot1Prompt).call().chatResponse();
            if (shot1Response == null) {
                throw new DimensionDiscoveryException("LLM returned null response on first shot", null);
            }
            String response1 = responseText(shot1Response);
            if (response1 == null) {
                throw new DimensionDiscoveryException("LLM returned null response on first shot", null);
            }
            DimensionDiscoveryResult result1 = converter.convert(response1);

            // Check if additional context is requested and valid
            if (result1.additionalContextRequest() != null
                    && result1.additionalContextRequest().ambiguousFilePaths() != null
                    && !result1.additionalContextRequest().ambiguousFilePaths().isEmpty()) {

                DimensionDiscoveryResult.AdditionalContextRequest contextRequest = result1.additionalContextRequest();
                List<String> requestedPaths = contextRequest.ambiguousFilePaths();

                // Limit to max 10 files
                if (requestedPaths.size() > 10) {
                    requestedPaths = requestedPaths.subList(0, 10);
                }

                batchLogger.info(
                        "PREPARE_DIMENSIONS",
                        "LLM requested additional context for " + requestedPaths.size() + " files. Fetching...");

                String fileContents = readAmbiguousFiles(batch, requestedPaths);

                String shot2PromptString = """
                        You previously requested additional context to finalize the architectural dimension discovery.
                        Here is the information you requested:

                        Your initial rationale: {rationale}
                        Dimensions you are already certain of: {certainDimensions}
                        How you expected this to resolve ambiguity: {expectedResolution}

                        Actual file contents (first 100 lines of each requested file):
                        {fileContents}

                        Based on this new context, finalize the architectural dimension discovery.
                        You MUST now provide a complete and confident JSON response with 'additionalContextRequest' set to null.
                        Do not request more context.

                        {format}
                        """;

                String renderedShot2Prompt = shot2PromptString
                        .replace("{rationale}", contextRequest.rationale() != null ? contextRequest.rationale() : "N/A")
                        .replace(
                                "{certainDimensions}",
                                contextRequest.certainDimensions() != null
                                        ? String.join(", ", contextRequest.certainDimensions())
                                        : "None")
                        .replace(
                                "{expectedResolution}",
                                contextRequest.expectedResolution() != null
                                        ? contextRequest.expectedResolution()
                                        : "N/A")
                        .replace("{fileContents}", fileContents)
                        .replace("{format}", converter.getFormat());

                ChatResponse shot2Response =
                        chatClient.prompt(renderedShot2Prompt).call().chatResponse();
                if (shot2Response == null) {
                    throw new DimensionDiscoveryException("LLM returned null response on second shot", null);
                }
                String response2 = responseText(shot2Response);
                if (response2 == null) {
                    throw new DimensionDiscoveryException("LLM returned null response on second shot", null);
                }
                return converter.convert(response2);
            }

            return result1;
        } catch (Exception e) {
            batchLogger.error(
                    "PREPARE_DIMENSIONS", "Failed to generate or parse dimensions from LLM: " + e.getMessage());
            throw new DimensionDiscoveryException("Failed to parse dimension JSON from LLM", e);
        }
    }

    String readAmbiguousFiles(IngestionBatch batch, List<String> filePaths) {
        StringBuilder contents = new StringBuilder();
        Path cloneDir = Paths.get(baseCloneDir, batch.getId().toString());

        for (String filePath : filePaths) {
            Path fullPath = cloneDir.resolve(filePath).normalize();
            // Security check: ensure the resolved path is within the clone directory
            if (!fullPath.startsWith(cloneDir)) {
                logger.warn("Attempted to read file outside clone directory: {}", filePath);
                contents.append("=== File: ").append(filePath).append(" ===\n[ACCESS DENIED]\n\n");
                continue;
            }

            try {
                if (Files.exists(fullPath) && Files.isRegularFile(fullPath)) {
                    List<String> lines = Files.readAllLines(fullPath);
                    int limit = Math.min(100, lines.size());
                    contents.append("=== File: ").append(filePath).append(" ===\n");
                    contents.append(String.join("\n", lines.subList(0, limit))).append("\n\n");
                } else {
                    contents.append("=== File: ").append(filePath).append(" ===\n[FILE NOT FOUND]\n\n");
                }
            } catch (IOException e) {
                logger.warn("Failed to read file {}: {}", filePath, e.getMessage());
                contents.append("=== File: ")
                        .append(filePath)
                        .append(" ===\n[READ ERROR: ")
                        .append(e.getMessage())
                        .append("]\n\n");
            }
        }
        return contents.toString();
    }

    private int persistDimensionsAndTagNodes(
            IngestionBatch batch, DimensionDiscoveryResult result, IngestionBatchLogService.BatchLogger batchLogger) {

        List<CtxNode> nodes = ctxNodeRepository.findByBatchId(batch.getId());
        List<CtxDimension> dimensionsToSave = createDimensionsFromResult(batch, result);
        ctxDimensionRepository.saveAll(dimensionsToSave);
        logger.debug("Discovered {} dimensions", dimensionsToSave.size());

        // Fast glob pass
        Map<CtxNode, Set<CtxDimension>> nodeToDimensions = mapNodesToDimensions(nodes, dimensionsToSave);

        // Constrained graph propagation for untagged files
        int propagatedFiles = propagateDimensions(batch.getId(), nodes, dimensionsToSave, nodeToDimensions);

        // Persist node-dimension mappings
        List<CtxNodeDimension> nodeDimensionsToSave = asCtxEntities(nodeToDimensions);
        ctxNodeDimensionRepository.saveAll(nodeDimensionsToSave);

        logger.debug("Tagged {} files via globs, {} via graph propagation", nodeToDimensions.size(), propagatedFiles);
        batchLogger.info(
                "PREPARE_DIMENSIONS",
                "Discovered "
                        + dimensionsToSave.size()
                        + " dimensions and tagged "
                        + nodeToDimensions.size()
                        + " files.");
        return nodeToDimensions.size();
    }

    public static @NonNull List<CtxDimension> createDimensionsFromResult(
            IngestionBatch batch, DimensionDiscoveryResult result) {
        UUID teamId = batch.getRepository().getTeam().getId();

        List<CtxDimension> dimensionsToSave = new ArrayList<>();

        // Save dimensions
        if (result.domains() != null) {
            for (DimensionDiscoveryResult.DimensionResult group : result.domains()) {
                CtxDimension dim = new CtxDimension(
                        batch, teamId, DimensionCategory.DOMAIN, group.name(), null, group.globPatterns());
                dimensionsToSave.add(dim);
            }
        }
        if (result.archetypes() != null) {
            for (DimensionDiscoveryResult.DimensionResult group : result.archetypes()) {
                CtxDimension dim = new CtxDimension(
                        batch, teamId, DimensionCategory.ARCHETYPE, group.name(), null, group.globPatterns());
                dimensionsToSave.add(dim);
            }
        }
        if (result.crossCutting() != null) {
            for (DimensionDiscoveryResult.DimensionResult group : result.crossCutting()) {
                CtxDimension dim = new CtxDimension(
                        batch, teamId, DimensionCategory.CROSS_CUTTING, group.name(), null, group.globPatterns());
                dimensionsToSave.add(dim);
            }
        }
        return dimensionsToSave;
    }

    public @NonNull Map<CtxNode, Set<CtxDimension>> mapNodesToDimensions(
            List<CtxNode> nodes, List<CtxDimension> dimensionsToSave) {
        Map<CtxNode, Set<CtxDimension>> nodeToDimensions = new HashMap<>();

        for (CtxNode node : nodes) {
            if (NodeType.FILE == node.getNodeType() && node.getPath() != null) {
                Set<CtxDimension> matchedDims = new HashSet<>();
                for (CtxDimension dim : dimensionsToSave) {
                    if (dim.getGlobPatterns() != null) {
                        for (String pattern : dim.getGlobPatterns()) {
                            if (matchesGlob(node.getPath(), pattern)) {
                                matchedDims.add(dim);
                                break;
                            }
                        }
                    }
                }
                if (!matchedDims.isEmpty()) {
                    nodeToDimensions.put(node, matchedDims);
                }
            }
        }
        return nodeToDimensions;
    }

    boolean matchesGlob(String filePath, String pattern) {
        Path path = Paths.get(filePath);
        for (String variant : globPatternVariants(pattern)) {
            try {
                if (FileSystems.getDefault().getPathMatcher("glob:" + variant).matches(path)) {
                    return true;
                }
            } catch (Exception e) {
                logger.warn("Invalid glob pattern: {}", variant, e);
            }
        }
        // Patterns without a directory separator match against the file name only
        if (!pattern.contains("/")) {
            try {
                return FileSystems.getDefault()
                        .getPathMatcher("glob:" + pattern)
                        .matches(path.getFileName());
            } catch (Exception e) {
                logger.warn("Invalid glob pattern: {}", pattern, e);
            }
        }
        return false;
    }

    /**
     * Java's PathMatcher treats a leading or interior double-asterisk wildcard (written per the
     * glob syntax as two asterisks followed by a path separator) as consuming at least one
     * directory component. As a result, LLM-generated patterns with a double-asterisk prefix
     * never match the relative file paths stored for a batch (e.g. "src/game/Game.tsx").
     * Generate variants that let the double-asterisk wildcard match zero or more directories.
     */
    static List<String> globPatternVariants(String pattern) {
        Set<String> variants = new LinkedHashSet<>();
        List<String> bases = new ArrayList<>();
        bases.add(pattern);
        if (pattern.startsWith("**/")) {
            bases.add(pattern.substring(3));
        }
        for (String base : bases) {
            variants.add(base);
            String collapsed = base.replace("/**/", "/");
            if (!collapsed.equals(base)) {
                variants.add(collapsed);
            }
            String trailingExtension = base.replace("/**/*.", "/*.");
            if (!trailingExtension.equals(base)) {
                variants.add(trailingExtension);
            }
            String trailingAny = base.replace("/**/*", "/**");
            if (!trailingAny.equals(base)) {
                variants.add(trailingAny);
            }
        }
        return List.copyOf(variants);
    }

    private int propagateDimensions(
            UUID batchId,
            List<CtxNode> nodes,
            List<CtxDimension> dimensions,
            Map<CtxNode, Set<CtxDimension>> nodeToDimensions) {

        // Build adjacency list for graph propagation
        Map<UUID, List<UUID>> adjacencyList = new HashMap<>();
        Map<UUID, CtxNode> nodeById = new HashMap<>();

        for (CtxNode node : nodes) {
            nodeById.put(node.getId(), node);
            adjacencyList.put(node.getId(), new ArrayList<>());
        }

        List<CtxEdge> edges = ctxEdgeRepository.findByBatchIdWithNodes(batchId);
        for (CtxEdge edge : edges) {
            adjacencyList
                    .get(edge.getSourceNode().getId())
                    .add(edge.getTargetNode().getId());
            adjacencyList
                    .get(edge.getTargetNode().getId())
                    .add(edge.getSourceNode().getId()); // Undirected for propagation
        }

        int propagatedCount = 0;
        int maxHops = 2;

        // Propagate per dimension
        for (CtxDimension dim : dimensions) {
            // Find seed nodes for this dimension
            Set<UUID> seedNodes = new HashSet<>();
            for (Map.Entry<CtxNode, Set<CtxDimension>> entry : nodeToDimensions.entrySet()) {
                if (entry.getValue().contains(dim)) {
                    seedNodes.add(entry.getKey().getId());
                }
            }

            if (seedNodes.isEmpty()) {
                continue;
            }

            // BFS propagation
            Set<UUID> visited = new HashSet<>(seedNodes);
            Queue<UUID> queue = new LinkedList<>(seedNodes);
            Map<UUID, Integer> distances = new HashMap<>();
            for (UUID seed : seedNodes) {
                distances.put(seed, 0);
            }

            while (!queue.isEmpty()) {
                UUID current = queue.poll();
                int currentDist = distances.get(current);

                if (currentDist >= maxHops) {
                    continue;
                }

                for (UUID neighbor : adjacencyList.getOrDefault(current, Collections.emptyList())) {
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        distances.put(neighbor, currentDist + 1);
                        queue.add(neighbor);

                        CtxNode neighborNode = nodeById.get(neighbor);
                        if (neighborNode != null && NodeType.FILE == neighborNode.getNodeType()) {
                            nodeToDimensions
                                    .computeIfAbsent(neighborNode, k -> new HashSet<>())
                                    .add(dim);
                            propagatedCount++;
                        }
                    }
                }
            }
        }

        return propagatedCount;
    }

    public static @NonNull List<CtxNodeDimension> asCtxEntities(Map<CtxNode, Set<CtxDimension>> nodeToDimensions) {
        List<CtxNodeDimension> nodeDimensionsToSave = new ArrayList<>();
        for (Map.Entry<CtxNode, Set<CtxDimension>> entry : nodeToDimensions.entrySet()) {
            for (CtxDimension dim : entry.getValue()) {
                nodeDimensionsToSave.add(
                        new CtxNodeDimension(entry.getKey(), dim, 0.0)); // Ranking will be applied later
            }
        }
        return nodeDimensionsToSave;
    }
}
