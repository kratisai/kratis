package com.kratisai.controlplane.ingestion.teamwiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.ingestion.write.SemanticIndexingService;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.model.TeamWikiBuildMode;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot;
import com.kratisai.controlplane.model.TeamWikiTriggerKind;
import com.kratisai.controlplane.repository.CtxEmbeddingRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.VirtualKeyService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class TeamWikiWorkerTest {

    @Mock
    private TeamWikiBatchRepository teamWikiBatchRepository;

    @Mock
    private CtxWikiPageRepository ctxWikiPageRepository;

    @Mock
    private CtxEmbeddingRepository ctxEmbeddingRepository;

    @Mock
    private TeamWikiBatchLogService batchLogService;

    @Mock
    private TeamWikiGenerationService generationService;

    @Mock
    private SemanticIndexingService semanticIndexingService;

    @Mock
    private VirtualKeyService virtualKeyService;

    @Mock
    private LiteLLMProvisioningService litellmProvisioningService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private PlatformTransactionManager transactionManager;

    private TeamWikiWorker worker;
    private UUID batchId;
    private TeamWikiBatch batch;

    @BeforeEach
    void setUp() {
        LiteLLMProperties props = new LiteLLMProperties();
        props.setUsageFinalizeDelay(Duration.ofMillis(10));
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        worker = new TeamWikiWorker(
                teamWikiBatchRepository,
                ctxWikiPageRepository,
                ctxEmbeddingRepository,
                batchLogService,
                generationService,
                semanticIndexingService,
                virtualKeyService,
                litellmProvisioningService,
                props,
                eventPublisher,
                transactionManager);

        Team team = new Team();
        team.setId(UUID.randomUUID());
        team.setName("Alpha Team");
        ModelProvider provider = new ModelProvider();
        provider.setId(UUID.randomUUID());
        team.setIngestionProvider(provider);
        team.setIngestionModel("gpt-4o");
        team.setEmbeddingProvider(provider);
        team.setEmbeddingModel("text-embedding-3-small");

        batchId = UUID.randomUUID();
        batch = new TeamWikiBatch(
                team,
                TeamWikiBuildMode.FULL,
                TeamWikiTriggerKind.AUTOMATIC,
                new TeamWikiSourceSnapshot(List.of(), List.of()));
        batch.setId(batchId);
        when(teamWikiBatchRepository.findByIdWithTeam(batchId)).thenReturn(Optional.of(batch));
    }

    @Test
    void runBuild_succeeds_activatesBatch_recordsUsage_andRevokesKey() {
        when(litellmProvisioningService.buildLiteLLMModelName(any(), eq("gpt-4o")))
                .thenReturn("openai/gpt-4o");
        when(litellmProvisioningService.buildLiteLLMModelName(any(), eq("text-embedding-3-small")))
                .thenReturn("openai/text-embedding-3-small");
        when(litellmProvisioningService.buildVirtualKeyAlias(any(), eq(batchId)))
                .thenReturn("tw-key");
        when(virtualKeyService.generateKey(eq("tw-key"), any())).thenReturn("sk-virt-123");
        when(generationService.generate(eq(batchId), any()))
                .thenReturn(new TeamWikiGenerationService.GenerationResult(5, 12L));
        when(virtualKeyService.fetchUsageByModel("sk-virt-123"))
                .thenReturn(Map.of(
                        "openai/gpt-4o", new LlmUsageSnapshot(0.12, 1000L, 700L, 300L),
                        "openai/text-embedding-3-small", new LlmUsageSnapshot(0.01, 200L, 200L, 0L)));

        worker.runBuild(batchId);

        assertThat(batch.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
        assertThat(batch.isActive()).isTrue();
        assertThat(batch.getTotalToolCalls()).isEqualTo(12L);
        assertThat(batch.modelUsageFor(ModelKind.CHAT).orElseThrow().getTotalSpend())
                .isEqualTo(0.12);
        verify(semanticIndexingService).indexTeamWikiBatch(eq(batch), any());
        verify(teamWikiBatchRepository).deactivateOtherBatches(batch.getTeam().getId(), batchId);
        verify(virtualKeyService).revokeKey("sk-virt-123");
    }

    @Test
    void runBuild_onFailure_marksFailedAndStillRevokesKey() {
        when(litellmProvisioningService.buildLiteLLMModelName(any(), eq("gpt-4o")))
                .thenReturn("openai/gpt-4o");
        when(litellmProvisioningService.buildLiteLLMModelName(any(), eq("text-embedding-3-small")))
                .thenReturn("openai/text-embedding-3-small");
        when(litellmProvisioningService.buildVirtualKeyAlias(any(), eq(batchId)))
                .thenReturn("tw-key");
        when(virtualKeyService.generateKey(eq("tw-key"), any())).thenReturn("sk-virt-fail");
        doThrow(new RuntimeException("LLM unavailable")).when(generationService).generate(eq(batchId), any());
        when(virtualKeyService.fetchUsageByModel("sk-virt-fail")).thenReturn(Map.of());

        worker.runBuild(batchId);

        assertThat(batch.getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(batch.isActive()).isFalse();
        assertThat(batch.getErrorMessage()).contains("LLM unavailable");
        verify(ctxEmbeddingRepository).deleteByTeamWikiBatchId(batchId);
        verify(ctxWikiPageRepository).deleteByTeamWikiBatchId(batchId);
        verify(virtualKeyService).revokeKey("sk-virt-fail");
    }
}
