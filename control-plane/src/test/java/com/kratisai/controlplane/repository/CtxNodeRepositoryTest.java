package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
class CtxNodeRepositoryTest {

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TeamRepository teamRepository;

    private IngestionBatch batch;
    private UUID teamId;
    private Team team;

    @BeforeEach
    void setUp() {
        team = new Team("Test Team", "test-team");
        team = teamRepository.save(team);
        teamId = team.getId();

        Repository repo = new Repository();
        repo.setName("test-repo");
        repo.setUrl("https://example.com/repo.git");
        repo.setBranch("main");
        repo.setTeam(team);
        repo = repositoryRepository.save(repo);

        batch = new IngestionBatch(repo);
        batch = ingestionBatchRepository.save(batch);
    }

    @Test
    @Transactional
    void shouldSaveNodeWithLongSymbolName() {
        String longSymbolName = "com.example.very.long.package.name.WithDeeplyNestedGenericsAndMultipleParametersClass$"
                + "InnerAnonymousClass1.lambda$veryLongMethodNameWithManyGenericParametersAndDetailedTypeInformation1234567890$"
                + "MoreDeeplyNestedNamespaceOrTemplateInstantiationTypeNameWhichExceeds255CharactersLengthRequirementWithoutAnyIssues("
                + "java.util.Map<java.lang.String, java.util.List<com.example.DTO>>, java.util.concurrent.CompletableFuture<java.lang.Void>)";

        assertThat(longSymbolName.length()).isGreaterThan(255);

        CtxNode node = new CtxNode(batch, teamId, "test-repo", NodeType.METHOD, "src/Test.java");
        node.setSymbolName(longSymbolName);

        CtxNode saved = ctxNodeRepository.saveAndFlush(node);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getSymbolName()).isEqualTo(longSymbolName);
    }

    @Test
    @Transactional
    void searchByBatchIdAndQuery_matchesPathAndSymbolCaseInsensitively() {
        saveNode("src/payment/PaymentService.java", "PaymentService", NodeType.CLASS);
        saveNode("src/payment/PaymentService.java", "processPayment", NodeType.METHOD);
        saveNode("src/payment/PaymentGateway.java", "PaymentGateway", NodeType.INTERFACE);
        saveNode("src/booking/Booking.java", "Booking", NodeType.CLASS);

        assertThat(ctxNodeRepository.searchByBatchIdAndQuery(batch.getId(), "PAYMENT", null).stream()
                        .map(CtxNode::getPath)
                        .distinct()
                        .toList())
                .containsExactly("src/payment/PaymentGateway.java", "src/payment/PaymentService.java");
    }

    @Test
    @Transactional
    void searchByBatchIdAndQuery_matchesSymbolName() {
        saveNode("src/payment/PaymentService.java", "PaymentService", NodeType.CLASS);
        saveNode("src/booking/Booking.java", "Booking", NodeType.CLASS);

        assertThat(ctxNodeRepository.searchByBatchIdAndQuery(batch.getId(), "processPayment", null))
                .isEmpty();

        CtxNode method = new CtxNode(batch, teamId, "test-repo", NodeType.METHOD, "src/payment/PaymentService.java");
        method.setSymbolName("processPayment");
        ctxNodeRepository.saveAndFlush(method);

        assertThat(ctxNodeRepository.searchByBatchIdAndQuery(batch.getId(), "processPayment", null))
                .extracting(CtxNode::getPath)
                .containsExactly("src/payment/PaymentService.java");
    }

    @Test
    @Transactional
    void searchByBatchIdAndQuery_filtersByNodeType() {
        saveNode("src/OrderService.java", "OrderService", NodeType.CLASS);
        saveNode("src/OrderService.java", "createOrder", NodeType.METHOD);

        assertThat(ctxNodeRepository.searchByBatchIdAndQuery(batch.getId(), "OrderService", NodeType.CLASS))
                .extracting(CtxNode::getNodeType)
                .containsExactly(NodeType.CLASS);
        assertThat(ctxNodeRepository.searchByBatchIdAndQuery(batch.getId(), "OrderService", NodeType.FUNCTION))
                .isEmpty();
    }

    @Test
    @Transactional
    void searchByBatchIdAndQuery_treatsWildcardsLiterally() {
        saveNode("src/user_service/UserService.java", "UserService", NodeType.CLASS);
        saveNode("src/userXservice/UserXService.java", "UserXService", NodeType.CLASS);

        assertThat(ctxNodeRepository.searchByBatchIdAndQuery(batch.getId(), "user_service", null))
                .extracting(CtxNode::getPath)
                .containsExactly("src/user_service/UserService.java");

        assertThat(ctxNodeRepository.searchByBatchIdAndQuery(batch.getId(), "user%service", null))
                .isEmpty();
    }

    @Test
    @Transactional
    void searchByBatchIdAndQuery_ignoresNodesFromOtherBatches() {
        saveNode("src/payment/PaymentService.java", "PaymentService", NodeType.CLASS);

        Repository otherRepo = new Repository();
        otherRepo.setName("other-repo");
        otherRepo.setUrl("https://example.com/other.git");
        otherRepo.setBranch("main");
        otherRepo.setTeam(team);
        otherRepo = repositoryRepository.save(otherRepo);
        IngestionBatch otherBatch = new IngestionBatch(otherRepo);
        otherBatch = ingestionBatchRepository.save(otherBatch);
        CtxNode otherNode =
                new CtxNode(otherBatch, teamId, "other-repo", NodeType.CLASS, "src/payment/PaymentService.java");
        otherNode.setSymbolName("PaymentService");
        ctxNodeRepository.saveAndFlush(otherNode);

        assertThat(ctxNodeRepository.searchByBatchIdAndQuery(batch.getId(), "PaymentService", null))
                .extracting(CtxNode::getPath)
                .containsExactly("src/payment/PaymentService.java");
    }

    private void saveNode(String path, String symbolName, NodeType nodeType) {
        CtxNode node = new CtxNode(batch, teamId, "test-repo", nodeType, path);
        node.setSymbolName(symbolName);
        ctxNodeRepository.saveAndFlush(node);
    }
}
