package com.kratisai.controlplane.ingestion.teamwiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.ingestion.write.SemanticIndexingService;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.model.TeamWikiBuildMode;
import com.kratisai.controlplane.model.TeamWikiModelUsage;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot;
import com.kratisai.controlplane.model.TeamWikiTriggerKind;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.repository.TeamWikiModelUsageRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringIntegrationTest
class TeamWikiWorkerIntegrationTest {

    @Autowired
    private TeamWikiWorker teamWikiWorker;

    @Autowired
    private TeamWikiBatchRepository teamWikiBatchRepository;

    @Autowired
    private TeamWikiModelUsageRepository teamWikiModelUsageRepository;

    @Autowired
    private LiteLLMProvisioningService litellmProvisioningService;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @MockitoBean
    private TeamWikiGenerationService generationService;

    @MockitoBean
    private SemanticIndexingService semanticIndexingService;

    @MockitoBean
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

        when(virtualKeyService.generateKey(any(), any())).thenReturn("sk-tw-real-test");
        when(generationService.generate(eq(batch.getId()), any()))
                .thenReturn(new TeamWikiGenerationService.GenerationResult(3, 7L));
        when(virtualKeyService.fetchUsageByModel("sk-tw-real-test"))
                .thenReturn(Map.of(
                        chatAlias, new LlmUsageSnapshot(0.42, 3500L, 2500L, 1000L),
                        embAlias, new LlmUsageSnapshot(0.03, 500L, 500L, 0L)));

        teamWikiWorker.runBuild(batch.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            TeamWikiBatch saved =
                    teamWikiBatchRepository.findById(batch.getId()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
            assertThat(saved.isActive()).isTrue();

            List<TeamWikiModelUsage> usages = teamWikiModelUsageRepository.findByTeamWikiBatchId(batch.getId());
            assertThat(usages).hasSize(2);
            TeamWikiModelUsage chatUsage = usages.stream()
                    .filter(u -> u.getModelKind() == ModelKind.CHAT)
                    .findFirst()
                    .orElseThrow();
            assertThat(chatUsage.getTotalSpend()).isEqualTo(0.42);
            assertThat(chatUsage.getTotalTokens()).isEqualTo(3500L);
            assertThat(chatUsage.getPromptTokens()).isEqualTo(2500L);
            assertThat(chatUsage.getCompletionTokens()).isEqualTo(1000L);

            verify(virtualKeyService).revokeKey("sk-tw-real-test");
        });
    }
}
