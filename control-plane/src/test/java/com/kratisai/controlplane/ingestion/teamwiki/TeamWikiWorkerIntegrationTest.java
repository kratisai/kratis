package com.kratisai.controlplane.ingestion.teamwiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.kratisai.controlplane.ingestion.write.SemanticIndexingService;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.model.TeamWikiBuildMode;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot;
import com.kratisai.controlplane.model.TeamWikiTriggerKind;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.VirtualKeyService;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringIntegrationTest
class TeamWikiWorkerIntegrationTest {

    @Autowired
    private TeamWikiWorker teamWikiWorker;

    @Autowired
    private TeamWikiBatchRepository teamWikiBatchRepository;

    @Autowired
    private LiteLLMProvisioningService litellmProvisioningService;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TeamWikiGenerationService generationService;

    @Autowired
    private SemanticIndexingService semanticIndexingService;

    @Autowired
    private VirtualKeyService virtualKeyService;

    private Team team;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        team = testDataFactory.createDefaultContext("Worker Team", "repo-a").team();
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void runBuild_persistsLiteLLMUsageAndRevokesKeyThroughHibernate() {
        TeamWikiBatch batch = teamWikiBatchRepository.saveAndFlush(new TeamWikiBatch(
                team,
                TeamWikiBuildMode.FULL,
                TeamWikiTriggerKind.MANUAL,
                new TeamWikiSourceSnapshot(List.of(), List.of())));

        String chatAlias =
                litellmProvisioningService.buildLiteLLMModelName(team.getIngestionProvider(), team.getIngestionModel());
        String embAlias =
                litellmProvisioningService.buildLiteLLMModelName(team.getEmbeddingProvider(), team.getEmbeddingModel());

        doReturn("sk-tw-real-test").when(virtualKeyService).generateKey(any(), any());
        doReturn(new TeamWikiGenerationService.GenerationResult(3, 7L))
                .when(generationService)
                .generate(eq(batch.getId()), any());
        doNothing().when(semanticIndexingService).indexTeamWikiBatch(any(), any());
        doReturn(Map.of(
                        chatAlias, new LlmUsageSnapshot(0.42, 3500L, 2500L, 1000L),
                        embAlias, new LlmUsageSnapshot(0.03, 500L, 500L, 0L)))
                .when(virtualKeyService)
                .fetchUsageByModel("sk-tw-real-test");
        doNothing().when(virtualKeyService).revokeKey("sk-tw-real-test");

        teamWikiWorker.runBuild(batch.getId());

        // modelUsage is LAZY: load the batch and read the collection inside one transaction,
        // copying out only detached-safe values.
        record PersistedUsage(double totalSpend, long totalTokens, long promptTokens, long completionTokens) {}
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<PersistedUsage> usages = new TransactionTemplate(transactionManager)
                    .execute(status ->
                            teamWikiBatchRepository.findById(batch.getId()).orElseThrow().getModelUsage().stream()
                                    .map(u -> new PersistedUsage(
                                            u.getTotalSpend(),
                                            u.getTotalTokens(),
                                            u.getPromptTokens(),
                                            u.getCompletionTokens()))
                                    .toList());
            TeamWikiBatch saved =
                    teamWikiBatchRepository.findById(batch.getId()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
            assertThat(saved.isActive()).isTrue();
            assertThat(usages).hasSize(2);
            assertThat(usages.stream().mapToDouble(PersistedUsage::totalSpend).sum())
                    .isCloseTo(0.45, org.assertj.core.data.Offset.offset(0.000001));
            assertThat(usages.stream().mapToLong(PersistedUsage::totalTokens).sum())
                    .isEqualTo(4000L);

            verify(virtualKeyService).revokeKey("sk-tw-real-test");
        });
    }
}
