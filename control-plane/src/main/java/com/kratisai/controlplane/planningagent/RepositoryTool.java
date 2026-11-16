package com.kratisai.controlplane.planningagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.ingestion.relations.SynopsisFragments;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.CtxRepoRelationRepository;
import com.kratisai.controlplane.repository.CtxRepoSynopsisRepository;
import com.kratisai.controlplane.repository.CtxSurfaceRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.service.BatchResolutionService;
import com.kratisai.controlplane.service.GraphQueryService;
import com.kratisai.controlplane.service.RemoteFileReaderService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RepositoryTool {

    private static final int MAX_SEARCH_LIMIT = 200;
    private static final int MAX_SYMBOLS_PER_FILE = 10;

    private final RepositoryRepository repositoryRepository;
    private final IngestionBatchRepository ingestionBatchRepository;
    private final RemoteFileReaderService remoteFileReaderService;
    private final BatchResolutionService batchResolutionService;
    private final GraphQueryService graphQueryService;
    private final CtxNodeRepository ctxNodeRepository;
    private final CtxRepoRelationRepository relationRepository;
    private final CtxSurfaceRepository surfaceRepository;
    private final CtxRepoSynopsisRepository synopsisRepository;
    private final ObjectMapper objectMapper;

    public RepositoryTool(
            RepositoryRepository repositoryRepository,
            IngestionBatchRepository ingestionBatchRepository,
            RemoteFileReaderService remoteFileReaderService,
            BatchResolutionService batchResolutionService,
            GraphQueryService graphQueryService,
            CtxNodeRepository ctxNodeRepository,
            CtxRepoRelationRepository relationRepository,
            CtxSurfaceRepository surfaceRepository,
            CtxRepoSynopsisRepository synopsisRepository,
            ObjectMapper objectMapper) {
        this.repositoryRepository = repositoryRepository;
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.remoteFileReaderService = remoteFileReaderService;
        this.batchResolutionService = batchResolutionService;
        this.graphQueryService = graphQueryService;
        this.ctxNodeRepository = ctxNodeRepository;
        this.relationRepository = relationRepository;
        this.surfaceRepository = surfaceRepository;
        this.synopsisRepository = synopsisRepository;
        this.objectMapper = objectMapper;
    }

    @KratisTool(
            name = "list_repositories",
            description =
                    "List all repositories ingested for this team. Returns repository names (required as the 'repoName' parameter for other tools), ingestion status, last commit, a compact synopsis, and cross-repository relation lines rendered as readable sentences (for example 'BookingService HOSTS PortalUi'). Call this first to discover which repositories are available.")
    @Transactional(readOnly = true)
    public List<RepositorySummary> listRepositories(ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        List<Repository> repos = repositoryRepository.findByTeamId(teamId);
        Map<UUID, IngestionBatch> activeBatches = ingestionBatchRepository.findActiveByTeamId(teamId).stream()
                .collect(Collectors.toMap(
                        batch -> batch.getRepository().getId(), batch -> batch, (first, second) -> first));
        Map<UUID, String> synopses = synopsesByBatchId(
                activeBatches.values().stream().map(IngestionBatch::getId).toList());
        List<CtxRepoRelation> allRelations = relationRepository.findCurrentByTeamId(teamId);

        return repos.stream()
                .map(repo -> {
                    IngestionBatch activeBatch = activeBatches.get(repo.getId());
                    return new RepositorySummary(
                            repo.getName(),
                            repo.getUrl(),
                            activeBatch == null
                                    ? "NOT_INGESTED"
                                    : activeBatch.getStatus().name(),
                            activeBatch == null ? null : activeBatch.getCompletedAt(),
                            activeBatch == null ? null : activeBatch.getCommitHash(),
                            activeBatch == null ? null : synopses.get(activeBatch.getId()),
                            compactRelatedRepos(allRelations, repo.getId()));
                })
                .toList();
    }

    private Map<UUID, String> synopsesByBatchId(List<UUID> batchIds) {
        if (batchIds.isEmpty()) {
            return Map.of();
        }
        return synopsisRepository.findByBatchIdIn(batchIds).stream()
                .collect(Collectors.toMap(
                        synopsis -> synopsis.getBatch().getId(),
                        synopsis -> new SynopsisFragments(synopsis.getSynopsis()).render(),
                        (first, second) -> first));
    }

    @KratisTool(
            name = "read_remote_file",
            description =
                    "Fetch the current source code of a specific file directly from the remote GitHub repository. The file is fetched on-demand at query time. Use this when you need to read actual implementation code — for example, after identifying relevant files via list_dimensions or get_dependencies. Provide the file path relative to the repository root (e.g., 'src/main/java/com/example/Service.java').")
    @Transactional(readOnly = true)
    public String readRemoteFile(
            @ToolParam(description = "The repository name (from list_repositories)") String repoName,
            @ToolParam(description = "File path relative to repository root") String filePath,
            ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        Repository repo = batchResolutionService.resolveRepository(teamId, repoName);
        return remoteFileReaderService.readFile(teamId, repoName, filePath, repo.getBranch());
    }

    @KratisTool(
            name = "get_dependencies",
            description =
                    "Trace the within-repository dependency graph for a file or symbol. Returns downstream dependencies (code that this file/symbol calls, imports, or extends) and upstream usages (code that depends on this file/symbol). Use get_related_repos for cross-repository relations and evidence. Depth controls traversal distance (1-5, recommended 2-3).")
    @Transactional(readOnly = true)
    public DependenciesResponse getDependencies(
            @ToolParam(description = "The repository name") String repoName,
            @ToolParam(description = "File path or symbol reference (e.g., 'src/Foo.java' or 'src/Foo.java:doBar')")
                    String path,
            @ToolParam(description = "Traversal depth (1-5)") int depth,
            ToolContext toolContext) {

        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        int safeDepth = Math.clamp(depth, 1, 5);

        // Validate repository exists
        Repository repo = batchResolutionService.resolveRepository(teamId, repoName);

        List<CtxNode> downstream = graphQueryService.getDependencies(teamId, repoName, path, safeDepth);
        List<CtxNode> upstream = graphQueryService.getUsages(teamId, repoName, path, safeDepth);

        return new DependenciesResponse(
                downstream.stream().map(CtxNode::getSourceCodeRef).collect(Collectors.toList()),
                upstream.stream().map(CtxNode::getSourceCodeRef).collect(Collectors.toList()),
                crossRepoLinks(teamId, repo.getId(), path));
    }

    @KratisTool(
            name = "get_related_repos",
            description =
                    "List current cross-repository relations grouped by peer repository, including each peer repository's synopsis and ingestion status. Each relation reports the current repository's file anchors, a readable relation sentence naming the acting side (for example 'PortalUi CALLS BookingService'), and the rationale. Use get_dependencies for within-repository code dependencies.")
    @Transactional(readOnly = true)
    public List<RelatedRepoSummary> getRelatedRepos(
            @ToolParam(description = "The repository name (from list_repositories)") String repoName,
            ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        Repository repo = batchResolutionService.resolveRepository(teamId, repoName);
        List<CtxRepoRelation> relations = relationRepository.findCurrentForRepository(teamId, repo.getId());

        Map<String, IngestionBatch> otherBatches = new LinkedHashMap<>();
        Map<String, List<RepoRelationSummary>> grouped = new LinkedHashMap<>();

        for (CtxRepoRelation relation : relations) {
            boolean repoIsA = relation.getBatchA().getRepository().getId().equals(repo.getId());
            IngestionBatch otherBatch = repoIsA ? relation.getBatchB() : relation.getBatchA();
            IngestionBatch currentBatch = repoIsA ? relation.getBatchA() : relation.getBatchB();
            String otherName = otherBatch.getRepository().getName();

            otherBatches.putIfAbsent(otherName, otherBatch);
            List<String> currentRepoFiles = extractFilesForBatch(relation, currentBatch.getId());
            grouped.computeIfAbsent(otherName, ignored -> new ArrayList<>())
                    .add(new RepoRelationSummary(
                            currentRepoFiles, otherName, formatRelation(relation), relation.getRationale()));
        }

        return grouped.entrySet().stream()
                .map(entry -> {
                    IngestionBatch otherBatch = otherBatches.get(entry.getKey());
                    return new RelatedRepoSummary(
                            entry.getKey(),
                            renderSynopsis(otherBatch.getId()),
                            otherBatch.getStatus().name(),
                            entry.getValue());
                })
                .toList();
    }

    private String renderSynopsis(UUID batchId) {
        return synopsisRepository
                .findByBatchId(batchId)
                .map(s -> new SynopsisFragments(s.getSynopsis()).render())
                .orElse(null);
    }

    private List<String> compactRelatedRepos(List<CtxRepoRelation> allRelations, UUID repoId) {
        return allRelations.stream()
                .filter(relation -> relation.getBatchA().getRepository().getId().equals(repoId)
                        || relation.getBatchB().getRepository().getId().equals(repoId))
                .map(RepositoryTool::formatRelation)
                .distinct()
                .toList();
    }

    private static String formatRelation(CtxRepoRelation relation) {
        return renderRelation(
                relation.getBatchA().getRepository().getName(),
                relation.getBatchB().getRepository().getName(),
                relation.getNature(),
                relation.getDirection());
    }

    /**
     * Direction names the upstream side: A_TO_B means A serves, publishes, or hosts B. CALLS and
     * BUILDS_AGAINST are performed by the downstream side, so their operands are swapped.
     */
    static String renderRelation(String nameA, String nameB, Nature nature, Direction direction) {
        boolean verbIsPerformedByDownstream =
                switch (nature) {
                    case CALLS, BUILDS_AGAINST -> true;
                    case MESSAGES, HOSTS, SHARES_STATE, PEER -> false;
                };
        return switch (direction) {
            case A_TO_B ->
                verbIsPerformedByDownstream ? nameB + " " + nature + " " + nameA : nameA + " " + nature + " " + nameB;
            case B_TO_A ->
                verbIsPerformedByDownstream ? nameA + " " + nature + " " + nameB : nameB + " " + nature + " " + nameA;
            case BIDIRECTIONAL, UNKNOWN -> nameA + " <-> " + nameB + " (" + nature + ")";
        };
    }

    private List<CrossRepoLink> crossRepoLinks(UUID teamId, UUID repoId, String path) {
        int colonIdx = path.indexOf(':');
        String filePath = colonIdx >= 0 ? path.substring(0, colonIdx) : path;
        List<CrossRepoLink> links = new ArrayList<>();
        for (CtxRepoRelation relation : relationRepository.findCurrentForRepository(teamId, repoId)) {
            boolean repoIsA = relation.getBatchA().getRepository().getId().equals(repoId);
            IngestionBatch currentBatch = repoIsA ? relation.getBatchA() : relation.getBatchB();
            IngestionBatch otherBatch = repoIsA ? relation.getBatchB() : relation.getBatchA();
            List<String> currentFiles = extractFilesForBatch(relation, currentBatch.getId());
            boolean matchesFile =
                    currentFiles.stream().anyMatch(f -> f.equals(filePath) || f.startsWith(filePath + ":"));
            if (matchesFile) {
                links.add(new CrossRepoLink(
                        otherBatch.getRepository().getName(),
                        relation.getNature(),
                        relation.getConfidence(),
                        extractFilesForBatch(relation, otherBatch.getId())));
            }
        }
        return links;
    }

    private List<String> extractFilesForBatch(CtxRepoRelation relation, UUID batchId) {
        if (relation.getCitations() == null) {
            return List.of();
        }
        JsonNode citations;
        try {
            citations = objectMapper.readTree(relation.getCitations());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid relation citations for " + relation.getId(), e);
        }
        JsonNode matches = citations == null ? null : citations.get("matches");
        if (matches == null || !matches.isArray()) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        for (JsonNode match : matches) {
            collectSurfaceFiles(relation, batchId, match.path("subjectSurfaceId"), files);
            collectSurfaceFiles(relation, batchId, match.path("otherSurfaceId"), files);
        }
        return files.stream().distinct().toList();
    }

    private void collectSurfaceFiles(CtxRepoRelation relation, UUID batchId, JsonNode id, List<String> files) {
        if (!id.isTextual()) {
            return;
        }
        UUID surfaceId;
        try {
            surfaceId = UUID.fromString(id.textValue());
        } catch (IllegalArgumentException e) {
            return;
        }
        surfaceRepository
                .findById(surfaceId)
                .filter(surface -> relation.getTeamId().equals(surface.getTeamId()))
                .filter(surface -> surface.getBatch().getId().equals(batchId))
                .ifPresent(surface -> surface.getAttributeRows().stream()
                        .filter(a -> a.getKind() == AttributeKind.FILE)
                        .map(CtxSurfaceAttribute::getValue)
                        .forEach(files::add));
    }

    @KratisTool(
            name = "search_files",
            description =
                    "Search the ingested repository's file system for file paths and symbols. Matches case-insensitively against both file paths (e.g. 'src/main/java') and symbol names (classes, functions, interfaces, endpoints). Returns distinct file paths together with the matching symbols found in each. Use this to discover which files to read via read_remote_file — for example search_files for 'PaymentService', 'controller', or 'security'. Returns at most 'limit' distinct files.")
    @Transactional(readOnly = true)
    public List<FileSearchResult> searchFiles(
            @ToolParam(description = "The repository name (from list_repositories)") String repoName,
            @ToolParam(description = "Case-insensitive substring to match against file paths or symbol names")
                    String query,
            @ToolParam(
                            description =
                                    "Optional node type filter (FILE, CLASS, INTERFACE, METHOD, FUNCTION, FIELD, VARIABLE, MODULE, PROJECT)")
                    NodeType nodeType,
            @ToolParam(description = "Maximum number of distinct files to return (1-200, default 50)") Integer limit,
            ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }
        IngestionBatch batch = batchResolutionService.resolveActiveBatch(teamId, repoName);
        int safeLimit = limit == null ? 50 : Math.clamp(limit, 1, MAX_SEARCH_LIMIT);

        List<CtxNode> matches = ctxNodeRepository.searchByBatchIdAndQuery(batch.getId(), query.trim(), nodeType);

        Map<String, List<CtxNode>> byPath = matches.stream()
                .collect(Collectors.groupingBy(CtxNode::getPath, LinkedHashMap::new, Collectors.toList()));

        return byPath.entrySet().stream()
                .limit(safeLimit)
                .map(entry -> new FileSearchResult(
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(CtxNode::getSymbolName)
                                .filter(Objects::nonNull)
                                .distinct()
                                .limit(MAX_SYMBOLS_PER_FILE)
                                .toList()))
                .toList();
    }

    public record RepositorySummary(
            String name,
            String url,
            String ingestionStatus,
            Instant lastIngestedAt,
            String commitHash,
            String synopsis,
            List<String> relatedRepos) {}

    public record DependenciesResponse(
            List<String> downstream, List<String> upstream, List<CrossRepoLink> crossRepoLinks) {}

    public record CrossRepoLink(String peerRepo, Nature nature, Ceiling confidence, List<String> peerFiles) {}

    public record RelatedRepoSummary(
            String otherRepoName, String synopsis, String ingestionStatus, List<RepoRelationSummary> relations) {}

    public record RepoRelationSummary(
            List<String> currentRepoFiles, String otherRepoName, String relation, String rationale) {}

    public record FileSearchResult(String path, List<String> symbols) {}
}
