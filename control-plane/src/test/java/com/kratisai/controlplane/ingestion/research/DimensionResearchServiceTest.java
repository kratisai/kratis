package com.kratisai.controlplane.ingestion.research;

import static com.kratisai.controlplane.FakeChatModelMatchers.ingestionPipelineMatchers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import com.kratisai.controlplane.repository.CtxNodeDimensionRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringIntegrationTest
class DimensionResearchServiceTest {

    @Autowired
    private DimensionResearchService dimensionResearchService;

    @Autowired
    private CtxDimensionRepository ctxDimensionRepository;

    @Autowired
    private CtxNodeDimensionRepository ctxNodeDimensionRepository;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private FakeChatModel fakeChatModel;

    private IngestionBatch batch;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        fakeChatModel.reset();
        fakeChatModel.addMatchers(ingestionPipelineMatchers());
        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext("Test Team", "test-repo");
        batch = ctx.batch();
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void shouldGenerateSynopsisForMultipleDimensionsInParallel() {
        // Create 3 dimensions to verify parallel execution and persistence
        CtxDimension dim1 = new CtxDimension(
                batch,
                batch.getRepository().getTeam().getId(),
                DimensionCategory.DOMAIN,
                "User Management",
                null,
                List.of("*User*.java"));
        ctxDimensionRepository.saveAndFlush(dim1);

        CtxDimension dim2 = new CtxDimension(
                batch,
                batch.getRepository().getTeam().getId(),
                DimensionCategory.ARCHETYPE,
                "Controller",
                null,
                List.of("*Controller*.java"));
        ctxDimensionRepository.saveAndFlush(dim2);

        CtxDimension dim3 = new CtxDimension(
                batch,
                batch.getRepository().getTeam().getId(),
                DimensionCategory.CROSS_CUTTING,
                "Utils",
                null,
                List.of("*Util*.java"));
        ctxDimensionRepository.saveAndFlush(dim3);

        // Create nodes and node dimensions for dim1
        CtxNode node1 = new CtxNode();
        node1.setBatch(batch);
        node1.setTeamId(batch.getRepository().getTeam().getId());
        node1.setRepoName(batch.getRepository().getName());
        node1.setNodeType(NodeType.FILE);
        node1.setPath("src/main/java/UserService.java");
        ctxNodeRepository.saveAndFlush(node1);
        ctxNodeDimensionRepository.saveAndFlush(new CtxNodeDimension(node1, dim1, 0.95));

        // Create nodes and node dimensions for dim2
        CtxNode node2 = new CtxNode();
        node2.setBatch(batch);
        node2.setTeamId(batch.getRepository().getTeam().getId());
        node2.setRepoName(batch.getRepository().getName());
        node2.setNodeType(NodeType.FILE);
        node2.setPath("src/main/java/UserController.java");
        ctxNodeRepository.saveAndFlush(node2);
        ctxNodeDimensionRepository.saveAndFlush(new CtxNodeDimension(node2, dim2, 0.90));

        // Create nodes and node dimensions for dim3
        CtxNode node3 = new CtxNode();
        node3.setBatch(batch);
        node3.setTeamId(batch.getRepository().getTeam().getId());
        node3.setRepoName(batch.getRepository().getName());
        node3.setNodeType(NodeType.FILE);
        node3.setPath("src/main/java/StringUtils.java");
        ctxNodeRepository.saveAndFlush(node3);
        ctxNodeDimensionRepository.saveAndFlush(new CtxNodeDimension(node3, dim3, 0.85));

        // Execute research (uses virtual threads for parallel execution)
        dimensionResearchService.researchDimensions(batch);

        // Assert all dimensions have been updated with a non-blank synopsis
        List<CtxDimension> updatedDimensions = ctxDimensionRepository.findByBatchId(batch.getId());
        assertThat(updatedDimensions).hasSize(3);
        assertThat(updatedDimensions)
                .allMatch(d -> d.getSynopsis() != null && !d.getSynopsis().isBlank());
    }

    @Test
    void shouldThrowExceptionWhenNoDimensionsFound() {
        assertThatThrownBy(() -> dimensionResearchService.researchDimensions(batch))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No dimensions found for batch.");
    }

    /**
     * Regression test for a bug where {@code team.getIngestionProvider()} returned
     * an uninitialized Hibernate lazy proxy for {@link ModelProvider}.
     */
    @Test
    void shouldInitializeLazyIngestionProviderProxyBeforeDispatchingDimensionResearch() {
        CtxDimension dim1 = new CtxDimension(
                batch,
                batch.getRepository().getTeam().getId(),
                DimensionCategory.DOMAIN,
                "User Management",
                null,
                List.of("*User*.java"));
        ctxDimensionRepository.saveAndFlush(dim1);

        CtxNode node1 = new CtxNode();
        node1.setBatch(batch);
        node1.setTeamId(batch.getRepository().getTeam().getId());
        node1.setRepoName(batch.getRepository().getName());
        node1.setNodeType(NodeType.FILE);
        node1.setPath("src/main/java/UserService.java");
        ctxNodeRepository.saveAndFlush(node1);
        ctxNodeDimensionRepository.saveAndFlush(new CtxNodeDimension(node1, dim1, 0.95));

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ModelProvider[] providerHolder = new ModelProvider[1];

        transactionTemplate.execute(status -> {
            // this repo returns a LAZY provider.
            IngestionBatch freshBatch = ingestionBatchRepository
                    .findByIdWithRepositoryAndTeam(batch.getId())
                    .orElseThrow();
            ModelProvider provider = freshBatch.getRepository().getTeam().getIngestionProvider();
            providerHolder[0] = provider;

            assertThat(Hibernate.isInitialized(provider))
                    .as("ingestionProvider should still be an uninitialized lazy proxy at this point")
                    .isFalse();

            dimensionResearchService.researchDimensions(freshBatch);

            assertThat(Hibernate.isInitialized(provider))
                    .as("DimensionResearchService must eagerly initialize the shared ModelProvider "
                            + "proxy before dispatching dimension research to virtual threads, "
                            + "otherwise concurrent first-access from multiple threads can race "
                            + "Hibernate's proxy initialization and observe a partially-hydrated entity")
                    .isTrue();
            return null;
        });

        List<CtxDimension> updatedDimensions = ctxDimensionRepository.findByBatchId(batch.getId());
        assertThat(updatedDimensions).hasSize(1);
        assertThat(updatedDimensions.get(0).getSynopsis()).isNotBlank();
    }
}
