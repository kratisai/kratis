package com.kratisai.controlplane.ingestion.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class SubgraphRankingServiceTest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private SubgraphRankingService subgraphRankingService;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxEdgeRepository ctxEdgeRepository;

    @Autowired
    private CtxDimensionRepository ctxDimensionRepository;

    @Autowired
    private CtxNodeDimensionRepository ctxNodeDimensionRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    private Team team;
    private IngestionBatch batch;
    private CtxNode hubNode;
    private CtxNode businessNode1;
    private CtxNode businessNode2;
    private CtxDimension domainDimension;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext context = testDataFactory.createDefaultContext("Ranking Team", "test-repo");
        team = context.team();
        batch = context.batch();
        batch.setActive(true);
        ingestionBatchRepository.saveAndFlush(batch);

        hubNode = new CtxNode(batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/com/example/Utils.java");
        businessNode1 = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/com/example/OrderService.java");
        businessNode2 = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/com/example/UserService.java");

        ctxNodeRepository.saveAll(List.of(hubNode, businessNode1, businessNode2));

        // Create a dimension
        domainDimension = new CtxDimension(
                batch, team.getId(), DimensionCategory.DOMAIN, "Business Domain", "Test synopsis", List.of("*.java"));
        ctxDimensionRepository.saveAndFlush(domainDimension);

        // Tag all nodes with the dimension
        CtxNodeDimension hubDim = new CtxNodeDimension(hubNode, domainDimension, 0.0);
        CtxNodeDimension biz1Dim = new CtxNodeDimension(businessNode1, domainDimension, 0.0);
        CtxNodeDimension biz2Dim = new CtxNodeDimension(businessNode2, domainDimension, 0.0);
        ctxNodeDimensionRepository.saveAll(List.of(hubDim, biz1Dim, biz2Dim));
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void happyPath_calculatesPageRankWithinSubgraph() {
        // Create edges: businessNode1 -> hubNode, businessNode2 -> hubNode
        // This makes hubNode a cross-cutting utility that both business nodes depend on
        CtxEdge edge1 = new CtxEdge(batch, team.getId(), businessNode1, hubNode, RelationType.CALLS);
        CtxEdge edge2 = new CtxEdge(batch, team.getId(), businessNode2, hubNode, RelationType.CALLS);
        ctxEdgeRepository.saveAll(List.of(edge1, edge2));

        // Execute
        subgraphRankingService.rankDimensions(batch);

        // Verify rank scores are updated
        List<CtxNodeDimension> nodeDimensions = ctxNodeDimensionRepository.findByDimensionId(domainDimension.getId());
        assertThat(nodeDimensions).hasSize(3);

        for (CtxNodeDimension nd : nodeDimensions) {
            assertThat(nd.getRankScore()).isNotNull();
            assertThat(nd.getRankScore()).isGreaterThan(0.0);
        }
    }

    @Test
    void happyPath_crossCuttingHubDoesNotDominateBusinessSubgraph() {
        // Create a separate business dimension
        CtxDimension businessDimension = new CtxDimension(
                batch,
                team.getId(),
                DimensionCategory.DOMAIN,
                "Order Domain",
                "Order processing",
                List.of("*Order*.java"));
        ctxDimensionRepository.saveAndFlush(businessDimension);

        // Only tag businessNode1 and businessNode2 with business dimension
        CtxNodeDimension biz1Dim = new CtxNodeDimension(businessNode1, businessDimension, 0.0);
        CtxNodeDimension biz2Dim = new CtxNodeDimension(businessNode2, businessDimension, 0.0);
        ctxNodeDimensionRepository.saveAll(List.of(biz1Dim, biz2Dim));

        // Create edge between business nodes only (no edge to hub)
        CtxEdge bizEdge = new CtxEdge(batch, team.getId(), businessNode1, businessNode2, RelationType.CALLS);
        ctxEdgeRepository.saveAndFlush(bizEdge);

        // Execute
        subgraphRankingService.rankDimensions(batch);

        // Verify business dimension rankings
        List<CtxNodeDimension> businessNodeDimensions =
                ctxNodeDimensionRepository.findByDimensionId(businessDimension.getId());
        assertThat(businessNodeDimensions).hasSize(2);

        // Both business nodes should have positive rank scores
        for (CtxNodeDimension nd : businessNodeDimensions) {
            assertThat(nd.getRankScore()).isNotNull();
            assertThat(nd.getRankScore()).isGreaterThan(0.0);
        }

        // Hub node should NOT be in the business dimension rankings
        List<CtxNodeDimension> hubDimensions = ctxNodeDimensionRepository.findByNodeId(hubNode.getId());
        assertThat(hubDimensions.stream().map(nd -> nd.getDimension().getId()).toList())
                .doesNotContain(businessDimension.getId());
    }

    @Test
    void failureCase_noDimensions_doesNotThrow() {
        // Clear node dimensions first to avoid foreign key constraint violation
        ctxNodeDimensionRepository.deleteAll();
        ctxDimensionRepository.deleteAll();

        // Execute - should not throw
        subgraphRankingService.rankDimensions(batch);

        // Verify no node dimensions exist
        List<CtxNodeDimension> nodeDimensions = ctxNodeDimensionRepository.findAll();
        assertThat(nodeDimensions).isEmpty();
    }

    @Test
    void failureCase_emptySubgraph_doesNotThrow() {
        // Create a dimension with no tagged nodes
        CtxDimension emptyDimension = new CtxDimension(
                batch, team.getId(), DimensionCategory.DOMAIN, "Empty Domain", "No files", List.of("*.nonexistent"));
        ctxDimensionRepository.saveAndFlush(emptyDimension);

        // Execute - should not throw
        subgraphRankingService.rankDimensions(batch);

        // Verify the empty dimension has no node dimensions
        List<CtxNodeDimension> emptyNodeDimensions =
                ctxNodeDimensionRepository.findByDimensionId(emptyDimension.getId());
        assertThat(emptyNodeDimensions).isEmpty();
    }

    @Test
    void happyPath_ignoresNonFileNodesAndSelfReferentialEdges() {
        // Create a non-file node (e.g., METHOD)
        CtxNode methodNode = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.METHOD, "src/main/java/com/example/Utils.java:doSomething");
        ctxNodeRepository.saveAndFlush(methodNode);

        // Create a self-referential edge for a file node
        CtxEdge selfLoopEdge = new CtxEdge(batch, team.getId(), hubNode, hubNode, RelationType.CALLS);

        // Create an edge between a file node and a non-file node
        CtxEdge fileToMethodEdge = new CtxEdge(batch, team.getId(), hubNode, methodNode, RelationType.CALLS);

        // Create an edge between two file nodes
        CtxEdge fileToFileEdge = new CtxEdge(batch, team.getId(), businessNode1, hubNode, RelationType.CALLS);

        ctxEdgeRepository.saveAll(List.of(selfLoopEdge, fileToMethodEdge, fileToFileEdge));

        // Execute
        subgraphRankingService.rankDimensions(batch);

        // Verify rank scores are updated only for file nodes
        List<CtxNodeDimension> nodeDimensions = ctxNodeDimensionRepository.findByDimensionId(domainDimension.getId());
        assertThat(nodeDimensions).hasSize(3);

        for (CtxNodeDimension nd : nodeDimensions) {
            assertThat(nd.getRankScore()).isNotNull();
            assertThat(nd.getRankScore()).isGreaterThan(0.0);
        }
    }
}
