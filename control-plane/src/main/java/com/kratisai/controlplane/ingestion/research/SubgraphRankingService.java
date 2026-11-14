package com.kratisai.controlplane.ingestion.research;

import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import java.util.*;
import org.jgrapht.Graph;
import org.jgrapht.alg.scoring.PageRank;
import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubgraphRankingService {

    private static final Logger logger = LoggerFactory.getLogger(SubgraphRankingService.class);

    private static final int EDGE_CHUNK_SIZE = 500;

    private final CtxEdgeRepository ctxEdgeRepository;
    private final CtxDimensionRepository ctxDimensionRepository;
    private final CtxNodeDimensionRepository ctxNodeDimensionRepository;
    private final IngestionBatchLogService ingestionBatchLogService;

    public SubgraphRankingService(
            CtxEdgeRepository ctxEdgeRepository,
            CtxDimensionRepository ctxDimensionRepository,
            CtxNodeDimensionRepository ctxNodeDimensionRepository,
            IngestionBatchLogService ingestionBatchLogService) {
        this.ctxEdgeRepository = ctxEdgeRepository;
        this.ctxDimensionRepository = ctxDimensionRepository;
        this.ctxNodeDimensionRepository = ctxNodeDimensionRepository;
        this.ingestionBatchLogService = ingestionBatchLogService;
    }

    @Transactional
    public void rankDimensions(IngestionBatch batch) {
        var batchLogger = new IngestionBatchLogService.BatchLogger(
                batch.getId(), batch.getRepository().getTeam().getId(), ingestionBatchLogService);

        batchLogger.info("RESEARCH_GRAPH", "Starting subgraph ranking for dimensions...");

        List<CtxDimension> dimensions = ctxDimensionRepository.findByBatchId(batch.getId());
        if (dimensions.isEmpty()) {
            batchLogger.info("RESEARCH_GRAPH", "No dimensions found to rank.");
            return;
        }

        int totalRanked = 0;

        for (CtxDimension dim : dimensions) {
            List<CtxNodeDimension> nodeDims = ctxNodeDimensionRepository.findByDimensionIdWithNode(dim.getId());
            if (nodeDims.isEmpty()) {
                continue;
            }

            Map<String, List<UUID>> fileIdsByPath = new LinkedHashMap<>();
            Set<UUID> nodeIds = new LinkedHashSet<>();
            for (CtxNodeDimension nd : nodeDims) {
                CtxNode node = nd.getNode();
                nodeIds.add(node.getId());
                if (node.getNodeType() == NodeType.FILE
                        && node.getPath() != null
                        && !node.getPath().isBlank()) {
                    fileIdsByPath
                            .computeIfAbsent(node.getPath(), ignored -> new ArrayList<>())
                            .add(node.getId());
                }
            }

            Graph<UUID, DefaultWeightedEdge> subgraph = buildSubgraph(batch.getId(), nodeIds, fileIdsByPath);
            for (CtxNodeDimension nd : nodeDims) {
                if (subgraph.containsVertex(nd.getNode().getId())) {
                    nd.setRankScore(0.0);
                }
            }

            if (subgraph.vertexSet().isEmpty()) {
                continue;
            }

            PageRank<UUID, DefaultWeightedEdge> pageRank = new PageRank<>(new AsUndirectedGraph<>(subgraph));
            Map<UUID, Double> scores = pageRank.getScores();

            for (CtxNodeDimension nd : nodeDims) {
                UUID nodeId = nd.getNode().getId();
                if (scores.containsKey(nodeId)) {
                    nd.setRankScore(scores.get(nodeId));
                    totalRanked++;
                }
            }

            ctxNodeDimensionRepository.saveAll(nodeDims);
        }

        logger.debug("Ranked {} node-dimension mappings", totalRanked);
        batchLogger.info(
                "RESEARCH_GRAPH",
                "Ranked " + totalRanked + " node-dimension mappings across " + dimensions.size() + " dimensions.");
    }

    /**
     * Projects cross-file symbol/module edges onto their owning FILE vertices in bounded path chunks,
     * so languages where CMM attaches CALLS/IMPORTS/IMPLEMENTS to Module, Function, or Interface nodes
     * rank files by actual architectural centrality rather than uniform 1/N scores.
     */
    private Graph<UUID, DefaultWeightedEdge> buildSubgraph(
            UUID batchId, Set<UUID> subgraphVertices, Map<String, List<UUID>> fileIdsByPath) {
        Graph<UUID, DefaultWeightedEdge> subgraph = new DirectedWeightedMultigraph<>(DefaultWeightedEdge.class);
        for (UUID vertex : subgraphVertices) {
            subgraph.addVertex(vertex);
        }
        if (fileIdsByPath.size() < 2) {
            return subgraph;
        }

        List<String> paths = new ArrayList<>(fileIdsByPath.keySet());
        Set<String> allPaths = fileIdsByPath.keySet();
        for (int start = 0; start < paths.size(); start += EDGE_CHUNK_SIZE) {
            List<String> chunk = paths.subList(start, Math.min(start + EDGE_CHUNK_SIZE, paths.size()));
            for (CtxEdgeRepository.PathDependency edge :
                    ctxEdgeRepository.findCrossFileDependenciesByBatchIdAndPaths(batchId, chunk, allPaths)) {
                List<UUID> sources = fileIdsByPath.getOrDefault(edge.getSourcePath(), List.of());
                List<UUID> targets = fileIdsByPath.getOrDefault(edge.getTargetPath(), List.of());
                double weight = edgeWeight(edge.getRelationType());
                for (UUID source : sources) {
                    for (UUID target : targets) {
                        if (source.equals(target)) {
                            continue;
                        }
                        DefaultWeightedEdge subgraphEdge = subgraph.addEdge(source, target);
                        if (subgraphEdge != null) {
                            subgraph.setEdgeWeight(subgraphEdge, weight);
                        }
                    }
                }
            }
        }
        return subgraph;
    }

    private static double edgeWeight(RelationType relationType) {
        return switch (relationType) {
            case INHERITS, IMPLEMENTS -> 3.0;
            case CALLS, IMPORTS, CONFIGURES, TESTS, FILE_CHANGES_WITH -> 2.0;
            case HANDLES, DECORATES, INFRA_MAPS, PUBLISHES, SUBSCRIBES -> 1.5;
            case DEFINES, WRITES, THROWS, SEMANTICALLY_SIMILAR -> 1.0;
        };
    }
}
