package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class GraphQueryServiceTest {

    @Autowired
    private GraphQueryService graphQueryService;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxEdgeRepository ctxEdgeRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private Team team;
    private Repository repository;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext();
        team = ctx.team();
        repository = ctx.repository();

        IngestionBatch batch = ctx.batch();
        batch.setActive(true);
        ingestionBatchRepository.saveAndFlush(batch);

        CtxNode nodeA = new CtxNode(batch, team.getId(), repository.getName(), NodeType.FILE, "A");
        CtxNode nodeB = new CtxNode(batch, team.getId(), repository.getName(), NodeType.FILE, "B");
        CtxNode nodeC = new CtxNode(batch, team.getId(), repository.getName(), NodeType.FILE, "C");

        ctxNodeRepository.saveAllAndFlush(List.of(nodeA, nodeB, nodeC));

        // Edges: A -> B, B -> C
        CtxEdge edge1 = new CtxEdge(batch, team.getId(), nodeA, nodeB, RelationType.IMPORTS);
        CtxEdge edge2 = new CtxEdge(batch, team.getId(), nodeB, nodeC, RelationType.IMPORTS);

        ctxEdgeRepository.saveAllAndFlush(List.of(edge1, edge2));
    }

    @Test
    void shouldFindDownstreamDependencies() {
        List<CtxNode> depsFromA = graphQueryService.getDependencies(team.getId(), repository.getName(), "A", 3);
        assertThat(depsFromA).hasSize(2);
        assertThat(depsFromA).extracting(CtxNode::getPath).containsExactlyInAnyOrder("B", "C");

        List<CtxNode> depsFromB = graphQueryService.getDependencies(team.getId(), repository.getName(), "B", 3);
        assertThat(depsFromB).hasSize(1);
        assertThat(depsFromB).extracting(CtxNode::getPath).containsExactly("C");

        List<CtxNode> depsFromC = graphQueryService.getDependencies(team.getId(), repository.getName(), "C", 3);
        assertThat(depsFromC).isEmpty();
    }

    @Test
    void shouldFindDownstreamDependenciesWithDepthLimit() {
        List<CtxNode> depsFromA = graphQueryService.getDependencies(team.getId(), repository.getName(), "A", 1);
        assertThat(depsFromA).hasSize(1);
        assertThat(depsFromA).extracting(CtxNode::getPath).containsExactly("B");
    }

    @Test
    void shouldFindUpstreamUsages() {
        List<CtxNode> usagesOfC = graphQueryService.getUsages(team.getId(), repository.getName(), "C", 3);
        assertThat(usagesOfC).hasSize(2);
        assertThat(usagesOfC).extracting(CtxNode::getPath).containsExactlyInAnyOrder("B", "A");

        List<CtxNode> usagesOfB = graphQueryService.getUsages(team.getId(), repository.getName(), "B", 3);
        assertThat(usagesOfB).hasSize(1);
        assertThat(usagesOfB).extracting(CtxNode::getPath).containsExactly("A");

        List<CtxNode> usagesOfA = graphQueryService.getUsages(team.getId(), repository.getName(), "A", 3);
        assertThat(usagesOfA).isEmpty();
    }
}
