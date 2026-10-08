package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.AgentHarness;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionDiffSnapshot;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class ExecutionDiffSnapshotMappingTest {

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

    @Autowired
    private ExecutionDiffSnapshotRepository diffSnapshotRepository;

    private SandboxExecution createExecution() {
        TestDataFactory.TestContext context = testDataFactory.createDefaultContext();

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(context.team());
        env.setName("Test Sandbox");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        executionEnvironmentRepository.save(env);

        ChatEntity chat = new ChatEntity(context.team(), context.user(), "Test Chat");
        chatRepository.save(chat);

        SandboxExecution execution = new SandboxExecution();
        execution.setEnvironment(env);
        execution.setChat(chat);
        execution.setHarness(AgentHarness.valueOf("OPENCODE"));
        execution.setTaskPrompt("Test task");
        execution.setModelProvider(context.provider());
        execution.setModelName("gpt-4o");
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        return sandboxExecutionRepository.save(execution);
    }

    @Test
    void saveAndFind_persistsSnapshotCorrectly() {
        SandboxExecution execution = createExecution();
        String summaryJson =
                "{\"files\":[{\"path\":\"README.md\",\"status\":\"MODIFIED\",\"additions\":5,\"deletions\":1}]}";

        ExecutionDiffSnapshot snapshot = new ExecutionDiffSnapshot(
                execution.getId(), "base-sha-123", "head-sha-456", 5, 1, summaryJson, "digest-1");
        diffSnapshotRepository.save(snapshot);
        entityManager.flush();
        entityManager.clear();

        Optional<ExecutionDiffSnapshot> retrieved = diffSnapshotRepository.findByExecutionId(execution.getId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getBaseCommit()).isEqualTo("base-sha-123");
        assertThat(retrieved.get().getHeadCommit()).isEqualTo("head-sha-456");
        assertThat(retrieved.get().getTotalAdditions()).isEqualTo(5);
        assertThat(retrieved.get().getTotalDeletions()).isEqualTo(1);
        assertThat(retrieved.get().getSummaryJson()).contains("\"README.md\"");
        assertThat(retrieved.get().getManifestDigest()).isEqualTo("digest-1");
        assertThat(retrieved.get().getCreatedAt()).isNotNull();
        assertThat(retrieved.get().getUpdatedAt()).isNotNull();
    }

    @Test
    void uniqueConstraint_duplicateExecutionId_throwsException() {
        SandboxExecution execution = createExecution();

        ExecutionDiffSnapshot s1 = new ExecutionDiffSnapshot(execution.getId(), "b1", "h1", 1, 0, "{}", "digest-1");
        diffSnapshotRepository.save(s1);
        entityManager.flush();

        ExecutionDiffSnapshot s2 = new ExecutionDiffSnapshot(execution.getId(), "b2", "h2", 2, 0, "{}", "digest-2");
        diffSnapshotRepository.save(s2);

        assertThatThrownBy(() -> entityManager.flush())
                .isInstanceOfAny(
                        DataIntegrityViolationException.class,
                        org.hibernate.exception.ConstraintViolationException.class);
    }

    @Test
    void deleteByExecutionId_removesSnapshot() {
        SandboxExecution execution = createExecution();
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "b1", "h1", 1, 0, "{}", "digest-1");
        diffSnapshotRepository.save(snapshot);
        entityManager.flush();

        diffSnapshotRepository.deleteByExecutionId(execution.getId());
        entityManager.flush();

        assertThat(diffSnapshotRepository.findByExecutionId(execution.getId())).isEmpty();
    }

    @Test
    void cascadeDelete_whenExecutionDeleted_snapshotRemoved() {
        SandboxExecution execution = createExecution();
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "b1", "h1", 1, 0, "{}", "digest-1");
        diffSnapshotRepository.save(snapshot);
        entityManager.flush();

        sandboxExecutionRepository.delete(execution);
        entityManager.flush();

        assertThat(diffSnapshotRepository.findByExecutionId(execution.getId())).isEmpty();
    }
}
