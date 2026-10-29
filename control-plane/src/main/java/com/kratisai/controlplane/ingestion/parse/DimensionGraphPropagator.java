package com.kratisai.controlplane.ingestion.parse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Propagates dimension labels through the repository graph without materializing it.
 *
 * <p>Labels are bit masks: bit {@code i} represents the dimension at index {@code i}. State is
 * bounded by the nodes reached during the traversal, not by the total number of edges in the
 * batch.
 */
@Component
public class DimensionGraphPropagator {

    static final int MAX_HOPS = 2;

    private static final int EDGE_QUERY_BATCH_SIZE = 1_000;

    private final GraphFrontierQuery graphFrontierQuery;

    public DimensionGraphPropagator(GraphFrontierQuery graphFrontierQuery) {
        this.graphFrontierQuery = graphFrontierQuery;
    }

    /**
     * Returns every label reachable from the seed nodes within {@link #MAX_HOPS}. Non-file
     * intermediates are retained in the result only so their labels can continue propagating;
     * callers filter the final map to file nodes.
     */
    public Map<UUID, Long> propagate(UUID batchId, Map<UUID, Long> seedLabels) {
        Map<UUID, Long> known = new HashMap<>(seedLabels);
        Map<UUID, Long> frontier = new HashMap<>(seedLabels);

        for (int hop = 0; hop < MAX_HOPS && !frontier.isEmpty(); hop++) {
            Map<UUID, Long> next = new HashMap<>();
            List<UUID> frontierIds = new ArrayList<>(frontier.keySet());

            for (int offset = 0; offset < frontierIds.size(); offset += EDGE_QUERY_BATCH_SIZE) {
                List<UUID> chunk =
                        frontierIds.subList(offset, Math.min(offset + EDGE_QUERY_BATCH_SIZE, frontierIds.size()));
                propagateChunk(batchId, frontier, known, next, chunk);
            }

            frontier = next;
        }

        return known;
    }

    private void propagateChunk(
            UUID batchId, Map<UUID, Long> frontier, Map<UUID, Long> known, Map<UUID, Long> next, List<UUID> chunk) {

        for (GraphFrontierQuery.EdgeRef edge : graphFrontierQuery.findIncidentEdges(batchId, chunk)) {
            long labels = frontier.getOrDefault(edge.fromId(), 0L);
            if (labels == 0L) {
                continue;
            }

            long unseen = labels & ~known.getOrDefault(edge.toId(), 0L);
            if (unseen == 0L) {
                continue;
            }

            known.merge(edge.toId(), unseen, (existing, added) -> existing | added);
            next.merge(edge.toId(), unseen, (existing, added) -> existing | added);
        }
    }
}
