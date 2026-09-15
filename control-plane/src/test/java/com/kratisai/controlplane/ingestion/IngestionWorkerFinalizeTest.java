package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionModelUsage;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.VirtualKeyService;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class IngestionWorkerFinalizeTest {

    @Mock
    private IngestionBatchRepository ingestionBatchRepository;

    @Mock
    private VirtualKeyService virtualKeyService;

    @Mock
    private LiteLLMProvisioningService liteLLMProvisioningService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private LiteLLMProperties liteLLMProperties;
    private IngestionWorker worker;

    private IngestionBatch batch;

    @BeforeEach
    void setUp() {
        liteLLMProperties = new LiteLLMProperties();
        liteLLMProperties.setUsageFinalizeDelay(Duration.ofSeconds(5));
        IngestionUsageTracker usageTracker = new IngestionUsageTracker();
        worker = new IngestionWorker(
                ingestionBatchRepository,
                null,
                null,
                null,
                null,
                transactionManager,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                virtualKeyService,
                liteLLMProvisioningService,
                liteLLMProperties,
                usageTracker);

        Repository repository = new Repository();
        batch = new IngestionBatch(repository);
        batch.setId(UUID.randomUUID());
    }

    @Test
    void appliesBothAliasesAndRevokesKey() throws Exception {
        allowInlineTransactions();
        addUsageRow(ModelKind.CHAT, "chat-model", "chat-alias", "sk-test");
        addUsageRow(ModelKind.EMBEDDING, "embed-model", "embed-alias", "sk-test");
        when(ingestionBatchRepository.findByIdWithRepositoryAndTeam(batch.getId()))
                .thenReturn(Optional.of(batch));
        when(virtualKeyService.fetchUsageByModel("sk-test"))
                .thenReturn(Map.of(
                        "chat-alias", new LlmUsageSnapshot(0.01, 100L, 80L, 20L),
                        "embed-alias", new LlmUsageSnapshot(0.002, 50L, 50L, 0L)));

        invokeFinalize(batch.getId());

        assertThat(chatRow().getTotalTokens()).isEqualTo(100L);
        assertThat(chatRow().getTotalSpend()).isEqualTo(0.01);
        assertThat(embedRow().getTotalTokens()).isEqualTo(50L);
        verify(virtualKeyService).revokeKey("sk-test");
    }

    @Test
    void missingAliasKeepsZeroAndStillRevokes() throws Exception {
        allowInlineTransactions();
        addUsageRow(ModelKind.CHAT, "chat-model", "chat-alias", "sk-test");
        addUsageRow(ModelKind.EMBEDDING, "embed-model", "embed-alias", "sk-test");
        liteLLMProperties.setUsageFinalizeDelay(Duration.ZERO);
        when(ingestionBatchRepository.findByIdWithRepositoryAndTeam(batch.getId()))
                .thenReturn(Optional.of(batch));
        when(virtualKeyService.fetchUsageByModel("sk-test"))
                .thenReturn(Map.of("chat-alias", new LlmUsageSnapshot(0.01, 100L, 80L, 20L)));

        invokeFinalize(batch.getId());

        assertThat(chatRow().getTotalTokens()).isEqualTo(100L);
        assertThat(embedRow().getTotalTokens()).isZero();
        verify(virtualKeyService).revokeKey("sk-test");
    }

    @Test
    void nullAliasRowIsSkipped() throws Exception {
        allowInlineTransactions();
        addUsageRow(ModelKind.CHAT, "chat-model", "chat-alias", "sk-test");
        IngestionModelUsage orphan = new IngestionModelUsage(batch, ModelKind.EMBEDDING, "embed-model", null);
        batch.getModelUsage().add(orphan);
        when(ingestionBatchRepository.findByIdWithRepositoryAndTeam(batch.getId()))
                .thenReturn(Optional.of(batch));
        when(virtualKeyService.fetchUsageByModel("sk-test"))
                .thenReturn(Map.of("chat-alias", new LlmUsageSnapshot(0.01, 100L, 80L, 20L)));

        invokeFinalize(batch.getId());

        assertThat(orphan.getTotalTokens()).isZero();
        verify(virtualKeyService).revokeKey("sk-test");
    }

    @Test
    void fetchFailureLeavesRowsAndStillRevokes() throws Exception {
        addUsageRow(ModelKind.CHAT, "chat-model", "chat-alias", "sk-test");
        when(ingestionBatchRepository.findByIdWithRepositoryAndTeam(batch.getId()))
                .thenReturn(Optional.of(batch));
        when(virtualKeyService.fetchUsageByModel("sk-test")).thenThrow(new RuntimeException("LiteLLM down"));

        invokeFinalize(batch.getId());

        assertThat(chatRow().getTotalTokens()).isZero();
        verify(virtualKeyService).revokeKey("sk-test");
    }

    @Test
    void revokeFailureIsSwallowed() throws Exception {
        allowInlineTransactions();
        addUsageRow(ModelKind.CHAT, "chat-model", "chat-alias", "sk-test");
        when(ingestionBatchRepository.findByIdWithRepositoryAndTeam(batch.getId()))
                .thenReturn(Optional.of(batch));
        when(virtualKeyService.fetchUsageByModel("sk-test"))
                .thenReturn(Map.of("chat-alias", new LlmUsageSnapshot(0.01, 100L, 80L, 20L)));
        org.mockito.Mockito.doThrow(new RuntimeException("revoke failed"))
                .when(virtualKeyService)
                .revokeKey("sk-test");

        invokeFinalize(batch.getId());

        assertThat(chatRow().getTotalTokens()).isEqualTo(100L);
    }

    @Test
    void missingTokenSkipsFetchAndRevoke() throws Exception {
        when(ingestionBatchRepository.findByIdWithRepositoryAndTeam(batch.getId()))
                .thenReturn(Optional.of(batch));

        invokeFinalize(batch.getId());

        verify(virtualKeyService, never()).fetchUsageByModel(any());
        verify(virtualKeyService, never()).revokeKey(any());
    }

    @Test
    void loadFailureReturnsWithoutRevoke() throws Exception {
        when(ingestionBatchRepository.findByIdWithRepositoryAndTeam(batch.getId()))
                .thenThrow(new RuntimeException("db down"));

        invokeFinalize(batch.getId());

        verify(virtualKeyService, never()).revokeKey(any());
    }

    @Test
    void interruptDuringPollStillRevokesAndTerminates() throws Exception {
        addUsageRow(ModelKind.CHAT, "chat-model", "chat-alias", "sk-test");
        addUsageRow(ModelKind.EMBEDDING, "embed-model", "embed-alias", "sk-test");
        liteLLMProperties.setUsageFinalizeDelay(Duration.ofMinutes(5));
        when(ingestionBatchRepository.findByIdWithRepositoryAndTeam(batch.getId()))
                .thenReturn(Optional.of(batch));
        when(virtualKeyService.fetchUsageByModel("sk-test")).thenReturn(Map.of());

        Thread workerThread = new Thread(() -> {
            try {
                invokeFinalize(batch.getId());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        workerThread.start();
        Thread.sleep(1500);
        workerThread.interrupt();
        workerThread.join(10000);

        assertThat(workerThread.isAlive()).isFalse();
        verify(virtualKeyService).revokeKey("sk-test");
    }

    // The worker builds its own TransactionTemplate around the manager; let the real
    // template drive the lifecycle inline with a no-op commit.
    private void allowInlineTransactions() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        org.mockito.Mockito.doNothing()
                .when(transactionManager)
                .commit(any(org.springframework.transaction.TransactionStatus.class));
    }

    private void addUsageRow(ModelKind kind, String model, String alias, String key) {
        IngestionModelUsage row = new IngestionModelUsage(batch, kind, model, alias);
        row.setVirtualKey(key);
        batch.getModelUsage().add(row);
    }

    private IngestionModelUsage chatRow() {
        return batch.modelUsageFor(ModelKind.CHAT).orElseThrow();
    }

    private IngestionModelUsage embedRow() {
        return batch.modelUsageFor(ModelKind.EMBEDDING).orElseThrow();
    }

    private void invokeFinalize(UUID batchId) throws Exception {
        var method = IngestionWorker.class.getDeclaredMethod("finalizeBatchUsageAndRevokeKey", UUID.class);
        method.setAccessible(true);
        method.invoke(worker, batchId);
    }
}
