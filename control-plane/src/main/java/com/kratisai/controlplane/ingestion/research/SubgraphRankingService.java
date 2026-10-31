package com.kratisai.controlplane.ingestion.research;

import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import java.util.*;
import java.util.stream.Collectors;
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
            List<CtxNodeDimension> nodeDims = ctxNodeDimensionRepository.findByDimensionId(dim.getId());
            if (nodeDims.isEmpty()) {
                continue;
            }

            Set<UUID> nodeIds =
                    nodeDims.stream().map(nd -> nd.getNode().getId()).collect(Collectors.toSet());

            // Subgraph is freed for GC once ranking this dimension is complete
            Graph<UUID, DefaultWeightedEdge> subgraph = buildSubgraph(batch.getId(), nodeIds);
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
     * Builds one dimension's ranking graph from bounded source-id chunks, so parser output several times
     * the current repository size never has to be resident as a single JPA result set or global graph.
     */
    private Graph<UUID, DefaultWeightedEdge> buildSubgraph(UUID batchId, Set<UUID> subgraphVertices) {
        Graph<UUID, DefaultWeightedEdge> subgraph = new DirectedWeightedMultigraph<>(DefaultWeightedEdge.class);
        for (UUID vertex : subgraphVertices) {
            subgraph.addVertex(vertex);
        }

        List<UUID> sources = new ArrayList<>(subgraphVertices);
        for (int start = 0; start < sources.size(); start += EDGE_CHUNK_SIZE) {
            List<UUID> chunk = sources.subList(start, Math.min(start + EDGE_CHUNK_SIZE, sources.size()));
            for (CtxEdgeRepository.FileDependency edge :
                    ctxEdgeRepository.findFileDependenciesByBatchIdAndSourceIds(batchId, chunk)) {
                UUID source = edge.getSourceId();
                UUID target = edge.getTargetId();
                if (source.equals(target) || !subgraphVertices.contains(target)) {
                    continue;
                }
                DefaultWeightedEdge subgraphEdge = subgraph.addEdge(source, target);
                if (subgraphEdge != null) {
                    subgraph.setEdgeWeight(subgraphEdge, edgeWeight(edge.getRelationType()));
                }
            }
        }
        return subgraph;
    }

    private static double edgeWeight(RelationType relationType) {
        return switch (relationType) {
            case INHERITS, IMPLEMENTS -> 3.0;
            case CALLS, IMPORTS, CONFIGURES, TESTS, FILE_CHANGES_WITH -> 2.0;
            case HANDLES, DECORATES, CROSS_REPO_CALL -> 1.5;
            case DEFINES, WRITES, THROWS, SEMANTICALLY_SIMILAR -> 1.0;
        };
    }
}
