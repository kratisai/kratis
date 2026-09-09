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
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubgraphRankingService {

    private static final Logger logger = LoggerFactory.getLogger(SubgraphRankingService.class);

    private final CtxNodeRepository ctxNodeRepository;
    private final CtxEdgeRepository ctxEdgeRepository;
    private final CtxDimensionRepository ctxDimensionRepository;
    private final CtxNodeDimensionRepository ctxNodeDimensionRepository;
    private final IngestionBatchLogService ingestionBatchLogService;

    public SubgraphRankingService(
            CtxNodeRepository ctxNodeRepository,
            CtxEdgeRepository ctxEdgeRepository,
            CtxDimensionRepository ctxDimensionRepository,
            CtxNodeDimensionRepository ctxNodeDimensionRepository,
            IngestionBatchLogService ingestionBatchLogService) {
        this.ctxNodeRepository = ctxNodeRepository;
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

        List<CtxNode> nodes = ctxNodeRepository.findByBatchId(batch.getId());
        List<CtxEdge> edges = ctxEdgeRepository.findByBatchIdWithNodes(batch.getId());

        // Build global graph for subgraph extraction
        Graph<UUID, DefaultWeightedEdge> globalGraph = buildWeightedGraph(nodes, edges);

        int totalRanked = 0;

        for (CtxDimension dim : dimensions) {
            List<CtxNodeDimension> nodeDims = ctxNodeDimensionRepository.findByDimensionId(dim.getId());
            if (nodeDims.isEmpty()) {
                continue;
            }

            // Extract subgraph
            Set<UUID> subgraphVertices = new HashSet<>();
            for (CtxNodeDimension nd : nodeDims) {
                subgraphVertices.add(nd.getNode().getId());
            }

            Graph<UUID, DefaultWeightedEdge> subgraph = new DirectedWeightedMultigraph<>(DefaultWeightedEdge.class);
            for (UUID vertex : subgraphVertices) {
                subgraph.addVertex(vertex);
            }

            for (UUID source : subgraphVertices) {
                Set<DefaultWeightedEdge> outgoingEdges = globalGraph.outgoingEdgesOf(source);
                for (DefaultWeightedEdge edge : outgoingEdges) {
                    UUID target = globalGraph.getEdgeTarget(edge);
                    if (subgraphVertices.contains(target)) {
                        DefaultWeightedEdge subgraphEdge = subgraph.addEdge(source, target);
                        if (subgraphEdge != null) {
                            subgraph.setEdgeWeight(subgraphEdge, globalGraph.getEdgeWeight(edge));
                        }
                    }
                }
            }

            // Run PageRank on subgraph
            PageRank<UUID, DefaultWeightedEdge> pageRank = new PageRank<>(new AsUndirectedGraph<>(subgraph));
            Map<UUID, Double> scores = pageRank.getScores();

            // Update rank scores
            for (CtxNodeDimension nd : nodeDims) {
                double score = scores.getOrDefault(nd.getNode().getId(), 0.0);
                nd.setRankScore(score);
                totalRanked++;
            }
        }

        ctxNodeDimensionRepository.saveAll(ctxNodeDimensionRepository.findByDimensionIdIn(
                dimensions.stream().map(CtxDimension::getId).toList()));

        logger.debug("Ranked {} node-dimension mappings", totalRanked);
        batchLogger.info(
                "RESEARCH_GRAPH",
                "Ranked " + totalRanked + " node-dimension mappings across " + dimensions.size() + " dimensions.");
    }

    public static @NonNull Graph<UUID, DefaultWeightedEdge> buildWeightedGraph(
            List<CtxNode> nodes, List<CtxEdge> edges) {
        Graph<UUID, DefaultWeightedEdge> globalGraph = new DirectedWeightedMultigraph<>(DefaultWeightedEdge.class);

        // Only add FILE nodes to the graph for PageRank
        for (CtxNode node : nodes) {
            if (node.getNodeType() == NodeType.FILE) {
                globalGraph.addVertex(node.getId());
            }
        }

        for (CtxEdge edge : edges) {
            UUID sourceId = edge.getSourceNode().getId();
            UUID targetId = edge.getTargetNode().getId();

            // Ignore self-referential edges as they don't contribute to PageRank
            if (sourceId.equals(targetId)) {
                continue;
            }

            // Only add edges if both source and target are FILE nodes
            if (globalGraph.containsVertex(sourceId) && globalGraph.containsVertex(targetId)) {
                // Assign descriptive weightings based on the strength of the dependency.
                // Higher weights indicate stronger structural or logical coupling, giving
                // the target node a stronger "vote" of importance in PageRank.
                double weight =
                        switch (edge.getRelationType()) {
                            // Strongest structural coupling (tight coupling, core architectural dependencies)
                            case INHERITS, IMPLEMENTS -> 3.0;
                            // Strong functional/logical dependency (direct usage, co-evolution, or configuration)
                            case CALLS, IMPORTS, CONFIGURES, TESTS, FILE_CHANGES_WITH -> 2.0;
                            // Moderate dependency (indirect or cross-boundary usage)
                            case HANDLES, DECORATES, CROSS_REPO_CALL -> 1.5;
                            // Standard/weak dependency (loose coupling or baseline)
                            case DEFINES, WRITES, THROWS, SEMANTICALLY_SIMILAR -> 1.0;
                        };
                DefaultWeightedEdge e = globalGraph.addEdge(sourceId, targetId);
                if (e != null) {
                    globalGraph.setEdgeWeight(e, weight);
                }
            }
        }
        return globalGraph;
    }
}
