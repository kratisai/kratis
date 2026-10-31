package com.kratisai.controlplane.ingestion.parse;

import com.kratisai.controlplane.agentloop.ChatResponseAssembler;
import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.DimensionCategory;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionModelUsage;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
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

    private static final String DIMENSION_RULES = """
            Identify dimensions in three categories: business-domains, technical archetypes, and cross-cutting services.
                - 5-12 Business Domains. What are the core business concerns of this codebase? What does it achieve?
                - 3-8 Technical Archetypes. What are the primary architectural patterns used in this codebase?
                - 2-5 Cross-cutting Services. Support services that span multiple domains or archetypes?
            For each dimension, provide 3-5 glob patterns matching relevant files. Tree paths are relative to the repo
            root:  e.g. src/**/user/*.java or **/user/*.java or **/*Controller.java etc. Avoid ** where no path segment
            exists in the provided filenames (e.g. src/main/user/**/*.java and **/src/main/user/*.java both miss
            src/main/user/UserController.java).
            """;

    private static final int NODE_DIMENSION_INSERT_BATCH_SIZE = 1_000;

    private final CtxDimensionRepository ctxDimensionRepository;
    private final GraphFrontierQuery graphFrontierQuery;
    private final DimensionGraphPropagator dimensionGraphPropagator;
    private final ChatModelFactory chatModelFactory;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final IngestionBatchLogService ingestionBatchLogService;
    private final TreeHelper treeHelper;
    private final String baseCloneDir;

    public DimensionDiscoveryService(
            CtxDimensionRepository ctxDimensionRepository,
            GraphFrontierQuery graphFrontierQuery,
            DimensionGraphPropagator dimensionGraphPropagator,
            ChatModelFactory chatModelFactory,
            LiteLLMProvisioningService litellmProvisioningService,
            IngestionBatchLogService ingestionBatchLogService,
            TreeHelper treeHelper,
            IngestionProperties ingestionProperties) {
        this.ctxDimensionRepository = ctxDimensionRepository;
        this.graphFrontierQuery = graphFrontierQuery;
        this.dimensionGraphPropagator = dimensionGraphPropagator;
        this.chatModelFactory = chatModelFactory;
        this.litellmProvisioningService = litellmProvisioningService;
        this.ingestionBatchLogService = ingestionBatchLogService;
        this.treeHelper = treeHelper;
        this.baseCloneDir = ingestionProperties.getCloneDir();
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

    static String responseText(ChatResponse response) {
        String text = ChatResponseAssembler.answerText(response);
        return text.isBlank() ? null : text;
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

        List<GraphFrontierQuery.FileNode> files = graphFrontierQuery.findFileNodes(batch.getId());

        DimensionDiscoveryResult result = executeDimensionDiscovery(batch, annotatedTree, batchLogger, null);
        requireSufficientDimensions(result, batchLogger);

        List<CtxDimension> dimensions = createDimensionsFromResult(batch, result);
        Map<UUID, Long> seedLabels = buildSeedLabels(files, dimensions);

        // An LLM may emit glob patterns that match no file paths. Persisting the dimensions
        // anyway silently produces empty dimension stats, so give the model one corrective
        // retry before failing the ingestion.
        if (seedLabels.isEmpty()) {
            batchLogger.info(
                    "PREPARE_DIMENSIONS",
                    "Discovered dimensions' glob patterns matched no files; retrying discovery once.");
            result = executeDimensionDiscovery(batch, annotatedTree, batchLogger, GLOB_RETRY_GUIDANCE);
            requireSufficientDimensions(result, batchLogger);
            dimensions = createDimensionsFromResult(batch, result);
            seedLabels = buildSeedLabels(files, dimensions);
        }

        int taggedFiles = persistDimensionsAndTagNodes(batch, dimensions, files, seedLabels, batchLogger);
        if (taggedFiles == 0) {
            throw new DimensionDiscoveryException(
                    "Discovered dimensions' glob patterns matched no files in the repository.", null);
        }
    }

    private Map<UUID, Long> buildSeedLabels(List<GraphFrontierQuery.FileNode> files, List<CtxDimension> dimensions) {
        if (dimensions.size() > Long.SIZE) {
            throw new DimensionDiscoveryException(
                    "Cannot propagate more than " + Long.SIZE + " dimensions; discovered " + dimensions.size(), null);
        }

        Map<UUID, Long> seedLabels = new HashMap<>();
        for (GraphFrontierQuery.FileNode file : files) {
            if (file.path() == null) {
                continue;
            }

            long labels = 0L;
            for (int index = 0; index < dimensions.size(); index++) {
                if (matchesAny(file.path(), dimensions.get(index))) {
                    labels |= 1L << index;
                }
            }
            if (labels != 0L) {
                seedLabels.put(file.id(), labels);
            }
        }
        return seedLabels;
    }

    private boolean matchesAny(String path, CtxDimension dimension) {
        List<String> patterns = dimension.getGlobPatterns();
        if (patterns == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern != null && matchesGlob(path, pattern)) {
                return true;
            }
        }
        return false;
    }

    private void requireSufficientDimensions(
            DimensionDiscoveryResult result, IngestionBatchLogService.BatchLogger batchLogger) {
        int domainCount = sizeOf(result.domains());
        int archetypeCount = sizeOf(result.archetypes());
        int crossCuttingCount = sizeOf(result.crossCutting());
        if (domainCount == 0 || archetypeCount == 0 || crossCuttingCount == 0) {
            batchLogger.info("PREPARE_DIMENSIONS", "Insufficient dimensions discovered.");
            throw new DimensionDiscoveryException(
                    "Insufficient dimensions discovered: " + domainCount
                            + " domains, " + archetypeCount
                            + " archetypes, " + crossCuttingCount
                            + " cross-cutting dimensions",
                    null);
        }
    }

    private static int sizeOf(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static String formatDimensions(DimensionDiscoveryResult result) {
        return """
                Business Domains: %s
                Technical Archetypes: %s
                Cross-Cutting Services: %s
                """.formatted(
                        dimensionNames(result.domains()),
                        dimensionNames(result.archetypes()),
                        dimensionNames(result.crossCutting()));
    }

    private static String dimensionNames(List<DimensionDiscoveryResult.DimensionResult> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return "(none identified)";
        }
        List<String> names = dimensions.stream()
                .map(DimensionDiscoveryResult.DimensionResult::name)
                .filter(Objects::nonNull)
                .toList();
        return names.isEmpty() ? "(none identified)" : String.join(", ", names);
    }

    private int persistDimensionsAndTagNodes(
            IngestionBatch batch,
            List<CtxDimension> dimensionsToSave,
            List<GraphFrontierQuery.FileNode> files,
            Map<UUID, Long> seedLabels,
            IngestionBatchLogService.BatchLogger batchLogger) {

        ctxDimensionRepository.saveAll(dimensionsToSave);
        // Mappings are inserted through JDBC, so the dimension rows must be visible first.
        ctxDimensionRepository.flush();
        logger.debug("Discovered {} dimensions", dimensionsToSave.size());

        Map<UUID, Long> labelsByNode = dimensionGraphPropagator.propagate(batch.getId(), seedLabels);

        int taggedFiles = 0;
        int mappings = 0;
        List<GraphFrontierQuery.NodeDimension> pendingMappings = new ArrayList<>(NODE_DIMENSION_INSERT_BATCH_SIZE);

        for (GraphFrontierQuery.FileNode file : files) {
            long labels = labelsByNode.getOrDefault(file.id(), 0L);
            if (labels == 0L) {
                continue;
            }

            taggedFiles++;
            for (int index = 0; index < dimensionsToSave.size(); index++) {
                if ((labels & (1L << index)) == 0L) {
                    continue;
                }

                pendingMappings.add(new GraphFrontierQuery.NodeDimension(
                        file.id(), dimensionsToSave.get(index).getId()));
                mappings++;
                if (pendingMappings.size() == NODE_DIMENSION_INSERT_BATCH_SIZE) {
                    graphFrontierQuery.insertNodeDimensions(pendingMappings);
                    pendingMappings.clear();
                }
            }
        }
        graphFrontierQuery.insertNodeDimensions(pendingMappings);

        int propagatedFiles = Math.max(0, taggedFiles - seedLabels.size());
        logger.debug("Tagged {} files via globs, {} via graph propagation", seedLabels.size(), propagatedFiles);
        batchLogger.info(
                "PREPARE_DIMENSIONS",
                "Discovered "
                        + dimensionsToSave.size()
                        + " dimensions and tagged "
                        + taggedFiles
                        + " files across "
                        + mappings
                        + " mappings.");
        return taggedFiles;
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
                {rules}

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

        String renderedShot1Prompt = shot1PromptString
                .replace("{tree}", annotatedTree)
                .replace("{rules}", DIMENSION_RULES)
                .replace("{format}", converter.getFormat());
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

                        Annotated File Tree:
                        {tree}

                        Dimension candidates from your initial analysis (the category assignments are authoritative;
                        retain them unless the file contents below contradict them):
                        {initialDimensions}

                        {rules}

                        Your initial rationale: {rationale}
                        How you expected this to resolve ambiguity: {expectedResolution}

                        Actual file contents (first 100 lines of each requested file):
                        {fileContents}

                        Based on this new context, finalize the architectural dimension discovery.
                        You MUST now provide a complete and confident JSON response with 'additionalContextRequest' set to null.
                        Do not request more context.

                        {format}
                        """;

                String renderedShot2Prompt = shot2PromptString
                        .replace("{tree}", annotatedTree)
                        .replace("{initialDimensions}", formatDimensions(result1))
                        .replace("{rules}", DIMENSION_RULES)
                        .replace("{rationale}", contextRequest.rationale() != null ? contextRequest.rationale() : "N/A")
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
}
