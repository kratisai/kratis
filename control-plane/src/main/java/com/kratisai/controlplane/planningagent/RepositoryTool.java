package com.kratisai.controlplane.planningagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.ingestion.relations.SynopsisFragments;
import com.kratisai.controlplane.model.AttributeKind;
import com.kratisai.controlplane.model.Ceiling;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.CtxRepoRelation;
import com.kratisai.controlplane.model.CtxRepoSynopsis;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Nature;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
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
                    "List all repositories ingested for this team. Returns repository names (required as the 'repoName' parameter for other tools), their ingestion status, and the last commit hash analyzed. Call this first to discover which repositories are available.")
    @Transactional(readOnly = true)
    public List<RepositorySummary> listRepositories(ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        List<Repository> repos = repositoryRepository.findByTeamId(teamId);

        return repos.stream()
                .map(repo -> {
                    var activeBatch = ingestionBatchRepository.findByRepositoryIdAndIsActiveTrue(repo.getId());
                    return new RepositorySummary(
                            repo.getName(),
                            repo.getUrl(),
                            activeBatch.map(b -> b.getStatus().name()).orElse("NOT_INGESTED"),
                            activeBatch.map(IngestionBatch::getCompletedAt).orElse(null),
                            activeBatch.map(IngestionBatch::getCommitHash).orElse(null));
                })
                .toList();
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
        batchResolutionService.resolveRepository(teamId, repoName);

        List<CtxNode> downstream = graphQueryService.getDependencies(teamId, repoName, path, safeDepth);
        List<CtxNode> upstream = graphQueryService.getUsages(teamId, repoName, path, safeDepth);

        return new DependenciesResponse(
                downstream.stream().map(CtxNode::getSourceCodeRef).collect(Collectors.toList()),
                upstream.stream().map(CtxNode::getSourceCodeRef).collect(Collectors.toList()));
    }

    @KratisTool(
            name = "get_related_repos",
            description =
                    "List current cross-repository relations and all embedded surface-pair evidence with batch provenance. Relation direction is relative to repositoryA and repositoryB; evidence direction is relative to its subject and other surfaces. An empty evidence list indicates legacy ungrounded relation data, not evidence of a match. Use get_dependencies for within-repository code dependencies.")
    @Transactional(readOnly = true)
    public List<RelatedRepository> getRelatedRepos(
            @ToolParam(description = "The repository name (from list_repositories)") String repoName,
            ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        Repository repo = batchResolutionService.resolveRepository(teamId, repoName);
        return relationRepository.findCurrentForRepository(teamId, repo.getId()).stream()
                .map(this::relatedRepository)
                .toList();
    }

    private RelatedRepository relatedRepository(CtxRepoRelation relation) {
        return new RelatedRepository(
                relation.getId(),
                relation.getBatchA().getRepository().getName(),
                relation.getBatchB().getRepository().getName(),
                relation.getBatchA().getId(),
                relation.getBatchB().getId(),
                relation.getNature(),
                relation.getDirection(),
                relation.getConfidence(),
                relation.getRationale(),
                relation.getCitations(),
                surfacePairs(relation));
    }

    private List<SurfacePairEvidence> surfacePairs(CtxRepoRelation relation) {
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
        List<SurfacePairEvidence> pairs = new ArrayList<>();
        for (JsonNode match : matches) {
            pairs.add(new SurfacePairEvidence(
                    objectMapper.convertValue(match, Object.class),
                    resolveSurface(relation, match.path("subjectSurfaceId")),
                    resolveSurface(relation, match.path("otherSurfaceId"))));
        }
        return pairs;
    }

    private SurfaceEvidence resolveSurface(CtxRepoRelation relation, JsonNode id) {
        if (!id.isTextual()) {
            return null;
        }
        UUID surfaceId;
        try {
            surfaceId = UUID.fromString(id.textValue());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return surfaceRepository
                .findById(surfaceId)
                .filter(surface -> relation.getTeamId().equals(surface.getTeamId()))
                .filter(surface ->
                        surface.getBatch().getId().equals(relation.getBatchA().getId())
                                || surface.getBatch()
                                        .getId()
                                        .equals(relation.getBatchB().getId()))
                .map(this::surfaceEvidence)
                .orElse(null);
    }

    private SurfaceEvidence surfaceEvidence(CtxSurface surface) {
        CtxRepoSynopsis synopsis =
                synopsisRepository.findByBatchId(surface.getBatch().getId()).orElse(null);
        return new SurfaceEvidence(
                surface.getId(),
                surface.getRepositoryId(),
                surface.getBatch().getId(),
                surface.getRole(),
                surface.getDescription(),
                surface.getDetectedBy(),
                surface.getAttributeRows().stream()
                        .map(a -> new AttributeEvidence(a.getId(), a.getKind(), a.getValue(), a.getDetectedBy()))
                        .toList(),
                synopsis == null ? null : new SynopsisFragments(synopsis.getSynopsis()).render(),
                synopsis == null ? null : synopsis.getCitations());
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
            String name, String url, String ingestionStatus, Instant lastIngestedAt, String commitHash) {}

    public record DependenciesResponse(List<String> downstream, List<String> upstream) {}

    public record RelatedRepository(
            UUID relationId,
            String repositoryA,
            String repositoryB,
            UUID batchAId,
            UUID batchBId,
            Nature nature,
            Direction direction,
            Ceiling confidence,
            String rationale,
            String citations,
            List<SurfacePairEvidence> matches) {}

    public record SurfacePairEvidence(Object evidence, SurfaceEvidence subjectSurface, SurfaceEvidence otherSurface) {}

    public record SurfaceEvidence(
            UUID id,
            UUID repositoryId,
            UUID batchId,
            SurfaceRole role,
            String description,
            SurfaceDetection detectedBy,
            List<AttributeEvidence> attributes,
            String synopsis,
            Map<String, Object> synopsisCitations) {}

    public record AttributeEvidence(UUID id, AttributeKind kind, String value, SurfaceDetection detectedBy) {}

    public record FileSearchResult(String path, List<String> symbols) {}
}
