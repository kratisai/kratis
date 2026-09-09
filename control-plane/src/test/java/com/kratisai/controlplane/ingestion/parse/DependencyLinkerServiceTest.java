package com.kratisai.controlplane.ingestion.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.service.GraphQueryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
class DependencyLinkerServiceTest {

    @Autowired
    private DependencyLinkerService dependencyLinkerService;

    @Autowired
    private GraphQueryService graphQueryService;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxEdgeRepository ctxEdgeRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private Team team;
    private Repository repoGateway;
    private Repository repoPayment;
    private IngestionBatch batchPayment;
    private IngestionBatch batchGateway;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext();
        team = ctx.team();

        // Repo B (Payment Service)
        repoPayment = ctx.repository();
        repoPayment.setName("payment-service");
        repositoryRepository.saveAndFlush(repoPayment);

        batchPayment = ctx.batch();
        batchPayment.setActive(true);
        ingestionBatchRepository.saveAndFlush(batchPayment);

        // Repo A (Web Gateway)
        repoGateway = new Repository(
                "web-gateway", "https://github.com/kratisai/web-gateway.git", "main", RepositoryType.GENERIC);
        repoGateway.setTeam(team);
        repositoryRepository.saveAndFlush(repoGateway);

        batchGateway = new IngestionBatch(repoGateway);
        batchGateway.setActive(true);
        ingestionBatchRepository.saveAndFlush(batchGateway);
    }

    @Test
    @Transactional
    void shouldSuccessfullyResolveCrossRepositoryDependencies() {
        // 1. In Repo B, we expose a Payment REST Endpoint
        CtxNode endpointNode = new CtxNode(
                batchPayment, team.getId(), repoPayment.getName(), NodeType.ENDPOINT, "src/PaymentController.ts");
        endpointNode.setSymbolName("/api/v1/payments");
        endpointNode.setMetadata("{\"method\":\"POST\"}");
        ctxNodeRepository.saveAndFlush(endpointNode);

        // 2. In Repo A, we have a Gateway file that calls the Payment REST Client
        CtxNode gatewayFileNode =
                new CtxNode(batchGateway, team.getId(), repoGateway.getName(), NodeType.FILE, "src/Gateway.ts");
        ctxNodeRepository.saveAndFlush(gatewayFileNode);

        CtxNode feignClientNode =
                new CtxNode(batchGateway, team.getId(), repoGateway.getName(), NodeType.CLIENT, "src/Gateway.ts");
        feignClientNode.setSymbolName("/api/v1/payments");
        ctxNodeRepository.saveAndFlush(feignClientNode);

        // Connect Gateway File -> REST Client internally in Repo A
        CtxEdge internalEdge =
                new CtxEdge(batchGateway, team.getId(), gatewayFileNode, feignClientNode, RelationType.CALLS);
        ctxEdgeRepository.saveAndFlush(internalEdge);

        // Crucial Step: The lists in batch must contain these nodes because prepareLinks
        // reads them from batchGateway.getNodes().
        batchGateway.getNodes().add(gatewayFileNode);
        batchGateway.getNodes().add(feignClientNode);

        // 3. Trigger the Dependency Linker on Repo A's completed batch
        dependencyLinkerService.prepareLinks(batchGateway);

        // 4. Assert a cross-repository edge was successfully persisted
        List<CtxEdge> edges = ctxEdgeRepository.findAll();
        // Should have 2 edges now: 1 internal (calls) + 1 cross-repo (cross_repo_call)
        assertThat(edges).hasSize(2);

        CtxEdge crossEdge = edges.stream()
                .filter(e -> RelationType.CROSS_REPO_CALL.equals(e.getRelationType()))
                .findFirst()
                .orElseThrow();
        assertThat(crossEdge.getSourceNode().getId()).isEqualTo(feignClientNode.getId());
        assertThat(crossEdge.getTargetNode().getId()).isEqualTo(endpointNode.getId());
        assertThat(crossEdge.getSourceNode().getRepoName()).isEqualTo("web-gateway");
        assertThat(crossEdge.getTargetNode().getRepoName()).isEqualTo("payment-service");

        // 5. Test Unified Architecture Querying crossing repo boundaries!
        // Run getDependencies starting from Repo A "src/Gateway.ts"
        List<CtxNode> dependencies =
                graphQueryService.getDependencies(team.getId(), repoGateway.getName(), "src/Gateway.ts", 3);

        // Dependencies should recursively find the client in Repo A AND the endpoint in Repo B!
        List<String> distinctPaths =
                dependencies.stream().map(CtxNode::getPath).distinct().toList();
        assertThat(distinctPaths).hasSize(2);
        assertThat(distinctPaths).containsExactlyInAnyOrder("src/Gateway.ts", "src/PaymentController.ts");

        List<String> distinctRepos =
                dependencies.stream().map(CtxNode::getRepoName).distinct().toList();
        assertThat(distinctRepos).hasSize(2);
        assertThat(distinctRepos).containsExactlyInAnyOrder("web-gateway", "payment-service");
    }
}
