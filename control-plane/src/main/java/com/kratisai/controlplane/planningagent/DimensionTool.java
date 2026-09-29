package com.kratisai.controlplane.planningagent;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.model.CtxArchitecturePattern;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.CtxNodeDimension;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.repository.CtxArchitecturePatternRepository;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import com.kratisai.controlplane.repository.CtxNodeDimensionRepository;
import com.kratisai.controlplane.service.BatchResolutionService;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DimensionTool {

    private final CtxDimensionRepository ctxDimensionRepository;
    private final CtxNodeDimensionRepository ctxNodeDimensionRepository;
    private final CtxArchitecturePatternRepository ctxArchitecturePatternRepository;
    private final BatchResolutionService batchResolutionService;

    public DimensionTool(
            CtxDimensionRepository ctxDimensionRepository,
            CtxNodeDimensionRepository ctxNodeDimensionRepository,
            CtxArchitecturePatternRepository ctxArchitecturePatternRepository,
            BatchResolutionService batchResolutionService) {
        this.ctxDimensionRepository = ctxDimensionRepository;
        this.ctxNodeDimensionRepository = ctxNodeDimensionRepository;
        this.ctxArchitecturePatternRepository = ctxArchitecturePatternRepository;
        this.batchResolutionService = batchResolutionService;
    }

    @KratisTool(
            name = "list_dimensions",
            description =
                    "Get a structural overview of a repository's codebase. Returns all discovered code dimensions organized into three categories: business domains (what the system does), technical archetypes (structural layers like controllers, repositories), and cross-cutting concerns (logging, config, testing). Each dimension includes a synopsis. Use this first to understand the codebase layout, then use get_dimension if required for detail on a specific area.")
    @Transactional(readOnly = true)
    public List<DimensionOverview> listDimensions(
            @ToolParam(description = "The repository name (from list_repositories)") String repoName,
            ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        IngestionBatch batch = batchResolutionService.resolveActiveBatch(teamId, repoName);

        List<CtxDimension> dimensions = ctxDimensionRepository.findByBatchId(batch.getId());

        return dimensions.stream()
                .map(dim ->
                        new DimensionOverview(dim.getName(), dim.getCategory().name(), dim.getSynopsis()))
                .toList();
    }

    @KratisTool(
            name = "get_dimension",
            description =
                    "Get detailed information about a specific code dimension, including ALL files ranked by their importance within that dimension's subgraph (PageRank). Use this (if needed) after list_dimensions to drill into a specific domain or archetype and identify the most important files to read.")
    @Transactional(readOnly = true)
    public DimensionDetail getDimension(
            @ToolParam(description = "The repository name") String repoName,
            @ToolParam(description = "The exact dimension name (from list_dimensions)") String dimensionName,
            ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        IngestionBatch batch = batchResolutionService.resolveActiveBatch(teamId, repoName);

        List<CtxDimension> dimensions = ctxDimensionRepository.findByBatchId(batch.getId());
        CtxDimension dimension = dimensions.stream()
                .filter(d -> d.getName().equalsIgnoreCase(dimensionName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Dimension '" + dimensionName + "' not found in repository '" + repoName + "'"));

        List<CtxNodeDimension> nodeDims = ctxNodeDimensionRepository.findByDimensionIdWithNode(dimension.getId());
        List<RankedFile> files = nodeDims.stream()
                .filter(nd -> nd.getRankScore() != null)
                .sorted(Comparator.comparing(CtxNodeDimension::getRankScore).reversed())
                .map(nd -> {
                    CtxNode node = nd.getNode();
                    return new RankedFile(node.getPath(), node.getSymbolName(), nd.getRankScore());
                })
                .toList();

        return new DimensionDetail(dimension.getName(), dimension.getCategory().name(), dimension.getSynopsis(), files);
    }

    @KratisTool(
            name = "list_architecture_patterns",
            description =
                    "List the architecture patterns discovered in a repository. Patterns describe the coding conventions and structural designs used in the codebase (e.g., 'Hexagonal Architecture', 'CQRS for Event Processing'). Each pattern includes a description and example files that demonstrate the pattern. Use this to ensure proposed changes follow established conventions.")
    @Transactional(readOnly = true)
    public List<PatternSummary> listArchitecturePatterns(
            @ToolParam(description = "The repository name") String repoName, ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        IngestionBatch batch = batchResolutionService.resolveActiveBatch(teamId, repoName);

        List<CtxArchitecturePattern> patterns =
                ctxArchitecturePatternRepository.findByBatchIdWithExemplarNodes(batch.getId());

        return patterns.stream()
                .map(p -> new PatternSummary(
                        p.getName(),
                        p.getDescription(),
                        p.getExemplarNodes().stream()
                                .map(n -> n.getPath() + (n.getSymbolName() != null ? ":" + n.getSymbolName() : ""))
                                .toList()))
                .toList();
    }

    public record DimensionOverview(String name, String category, String synopsis) {}

    public record DimensionDetail(String name, String category, String synopsis, List<RankedFile> files) {}

    public record RankedFile(String path, String symbolName, double rankScore) {}

    public record PatternSummary(String name, String description, List<String> exemplarFiles) {}
}
