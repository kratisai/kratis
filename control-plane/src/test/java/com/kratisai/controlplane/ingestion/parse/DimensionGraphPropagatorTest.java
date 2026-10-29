package com.kratisai.controlplane.ingestion.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DimensionGraphPropagatorTest {

    private static final long DOMAIN_A = 1L;
    private static final long DOMAIN_B = 1L << 1;

    private final UUID batchId = UUID.randomUUID();
    private final Map<UUID, List<UUID>> adjacency = new HashMap<>();
    private GraphFrontierQuery graphFrontierQuery;
    private DimensionGraphPropagator propagator;

    @BeforeEach
    void setUp() {
        graphFrontierQuery = mock(GraphFrontierQuery.class);
        propagator = new DimensionGraphPropagator(graphFrontierQuery);
        when(graphFrontierQuery.findIncidentEdges(any(UUID.class), anyList())).thenAnswer(invocation -> {
            List<UUID> nodeIds = invocation.getArgument(1);
            List<GraphFrontierQuery.EdgeRef> edges = new ArrayList<>();
            for (UUID nodeId : nodeIds) {
                for (UUID neighbour : adjacency.getOrDefault(nodeId, List.of())) {
                    edges.add(new GraphFrontierQuery.EdgeRef(nodeId, neighbour));
                }
            }
            return edges;
        });
    }

    @Test
    void propagate_reachesNodesWithinTwoHopsOnly() {
        UUID seed = UUID.randomUUID();
        UUID oneHop = UUID.randomUUID();
        UUID twoHops = UUID.randomUUID();
        UUID threeHops = UUID.randomUUID();
        connect(seed, oneHop);
        connect(oneHop, twoHops);
        connect(twoHops, threeHops);

        Map<UUID, Long> labels = propagator.propagate(batchId, Map.of(seed, DOMAIN_A));

        assertThat(labels)
                .containsEntry(seed, DOMAIN_A)
                .containsEntry(oneHop, DOMAIN_A)
                .containsEntry(twoHops, DOMAIN_A)
                .doesNotContainKey(threeHops);
    }

    @Test
    void propagate_carriesMultipleDimensionsToSharedNeighbour() {
        UUID seedA = UUID.randomUUID();
        UUID seedB = UUID.randomUUID();
        UUID shared = UUID.randomUUID();
        connect(seedA, shared);
        connect(seedB, shared);

        Map<UUID, Long> labels = propagator.propagate(batchId, Map.of(seedA, DOMAIN_A, seedB, DOMAIN_B));

        assertThat(labels).containsEntry(shared, DOMAIN_A | DOMAIN_B);
    }

    @Test
    void propagate_keepsLabelsReachedAtDifferentDepths() {
        UUID firstSeed = UUID.randomUUID();
        UUID secondSeed = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        connect(firstSeed, middle);
        connect(middle, secondSeed);

        Map<UUID, Long> labels = propagator.propagate(batchId, Map.of(firstSeed, DOMAIN_A, secondSeed, DOMAIN_B));

        assertThat(labels)
                .containsEntry(firstSeed, DOMAIN_A | DOMAIN_B)
                .containsEntry(middle, DOMAIN_A | DOMAIN_B)
                .containsEntry(secondSeed, DOMAIN_A | DOMAIN_B);
    }

    @Test
    void propagate_doesNotQueryForEmptySeeds() {
        Map<UUID, Long> labels = propagator.propagate(batchId, Map.of());

        assertThat(labels).isEmpty();
        verifyNoInteractions(graphFrontierQuery);
    }

    @Test
    void propagate_queriesFrontierInBoundedChunks() {
        Map<UUID, Long> seeds = new HashMap<>();
        for (int i = 0; i < 2_500; i++) {
            seeds.put(UUID.randomUUID(), DOMAIN_A);
        }

        Map<UUID, Long> labels = propagator.propagate(batchId, seeds);

        assertThat(labels).hasSize(2_500);
        verify(graphFrontierQuery, times(3)).findIncidentEdges(any(UUID.class), anyList());
    }

    private void connect(UUID source, UUID target) {
        adjacency.computeIfAbsent(source, ignored -> new ArrayList<>()).add(target);
        adjacency.computeIfAbsent(target, ignored -> new ArrayList<>()).add(source);
    }
}
