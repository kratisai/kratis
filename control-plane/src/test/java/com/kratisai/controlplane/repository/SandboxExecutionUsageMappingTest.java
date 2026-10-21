package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.model.AgentHarness;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionUsage;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class SandboxExecutionUsageMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private ChatRepository chatRepository;

    private SandboxExecution createExecution() {
        TestDataFactory.TestContext context = testDataFactory.createDefaultContext();
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(context.team());
        env.setName("Usage Sandbox");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.PENDING_CONNECT);
        executionEnvironmentRepository.save(env);

        ChatEntity chat = new ChatEntity(context.team(), context.user(), "Usage Chat");
        chatRepository.save(chat);
        entityManager.flush();

        SandboxExecution execution = new SandboxExecution();
        execution.setEnvironment(env);
        execution.setChat(chat);
        execution.setHarness(AgentHarness.valueOf("OPENCODE"));
        execution.setTaskPrompt("Test task");
        return execution;
    }

    @Test
    void savesUsageSegmentsViaCascadeAndAggregatesAcrossResumes() {
        SandboxExecution execution = createExecution();
        SandboxExecutionUsage first = execution.startUsage("sk-first", "gpt-4o");
        first.apply(new LlmUsageSnapshot(0.5, 100L, 60L, 40L));
        first.setRevokedAt(Instant.now());

        SandboxExecutionUsage resumed = execution.startUsage("sk-second", "gpt-4o");
        resumed.apply(new LlmUsageSnapshot(0.25, 50L, 30L, 20L));
        sandboxExecutionRepository.saveAndFlush(execution);

        entityManager.flush();
        entityManager.clear();

        SandboxExecution reloaded =
                sandboxExecutionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getUsages()).hasSize(2);
        assertThat(reloaded.getActiveUsage().getVirtualKey()).isEqualTo("sk-second");
        assertThat(reloaded.getVirtualKey()).isEqualTo("sk-second");
        assertThat(reloaded.getTotalSpend()).isEqualTo(0.75);
        assertThat(reloaded.getTotalTokens()).isEqualTo(150L);
        assertThat(reloaded.getPromptTokens()).isEqualTo(90L);
        assertThat(reloaded.getCompletionTokens()).isEqualTo(60L);
        assertThat(reloaded.getUsageLastUpdatedAt()).isNotNull();
    }

    @Test
    void activeUsage_isNullWhenEverySegmentIsRevoked() {
        SandboxExecution execution = createExecution();
        SandboxExecutionUsage usage = execution.startUsage("sk-only", null);
        usage.setRevokedAt(Instant.now());
        assertThat(execution.getActiveUsage()).isNull();
        // Falls back to the most recent segment so a completed execution still reports its key.
        assertThat(execution.getVirtualKey()).isEqualTo("sk-only");
    }

    @Test
    void clearingUsages_orphanRemovesRows() {
        SandboxExecution execution = createExecution();
        execution.startUsage("sk-1", null);
        SandboxExecution saved = sandboxExecutionRepository.saveAndFlush(execution);

        entityManager.flush();
        entityManager.clear();

        SandboxExecution reloaded =
                sandboxExecutionRepository.findById(saved.getId()).orElseThrow();
        reloaded.getUsages().clear();
        sandboxExecutionRepository.saveAndFlush(reloaded);
        entityManager.flush();

        assertThat(entityManager
                        .createQuery("select count(u) from SandboxExecutionUsage u", Long.class)
                        .getSingleResult())
                .isZero();
    }

    @Test
    void deletingExecution_cascadesToUsage() {
        SandboxExecution execution = createExecution();
        execution.startUsage("sk-1", null);
        sandboxExecutionRepository.saveAndFlush(execution);
        UUID executionId = execution.getId();

        sandboxExecutionRepository.deleteById(executionId);
        sandboxExecutionRepository.flush();

        assertThat(entityManager
                        .createQuery("select count(u) from SandboxExecutionUsage u", Long.class)
                        .getSingleResult())
                .isZero();
    }

    @Test
    void findActiveVirtualKeysByExecutionId_returnsOnlyUnrevokedSegments() {
        SandboxExecution execution = createExecution();
        execution.startUsage("sk-old", null).setRevokedAt(Instant.now());
        execution.startUsage("sk-new", null);
        sandboxExecutionRepository.saveAndFlush(execution);

        assertThat(sandboxExecutionRepository.findActiveVirtualKeysByExecutionId(execution.getId()))
                .containsExactly("sk-new");
    }

    @Test
    void sumTotalSpend_sumsAcrossEverySegment() {
        SandboxExecution first = createExecution();
        first.startUsage("sk-a", null).apply(new LlmUsageSnapshot(1.5, 100L, 80L, 20L));
        sandboxExecutionRepository.saveAndFlush(first);

        SandboxExecution second = createExecution();
        second.startUsage("sk-b", null).apply(new LlmUsageSnapshot(0.75, 50L, 50L, 0L));
        sandboxExecutionRepository.saveAndFlush(second);

        assertThat(sandboxExecutionRepository.sumTotalSpend()).isEqualTo(2.25);
    }
}
