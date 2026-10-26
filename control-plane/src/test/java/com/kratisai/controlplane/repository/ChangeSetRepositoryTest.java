package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.model.ChangeSet;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class ChangeSetRepositoryTest {

    @Autowired
    private ChangeSetRepository changeSetRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    private SandboxExecution execution;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        ChatEntity chat = new ChatEntity(team, ctx.user(), "Repository Test Chat");
        chatRepository.save(chat);

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(team);
        env.setName("sandbox-1");
        env.setType(com.kratisai.controlplane.model.ExecutionEnvironmentType.CONNECTOR);
        env.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.CONNECTED);
        env.setAuthToken("token-1");
        executionEnvironmentRepository.save(env);

        execution = new SandboxExecution();
        execution.setEnvironment(env);
        execution.setChat(chat);
        execution.setStatus(com.kratisai.controlplane.model.SandboxExecutionStatus.RUNNING);
        execution.setStartedAt(Instant.now());
        sandboxExecutionRepository.save(execution);

        entityManager.flush();
    }

    @Test
    void findByExecutionIdOrderBySeqAsc_returnsChangeSetsInOrder() {
        ChangeSet cs1 = new ChangeSet(
                execution, 1, "base-1", "push-1", "branch-1", 101L, "https://pr/101", "main", Instant.now());
        ChangeSet cs2 = new ChangeSet(
                execution,
                2,
                "base-2",
                "push-2",
                "branch-2",
                102L,
                "https://pr/102",
                "main",
                Instant.now().plusSeconds(10));
        entityManager.persist(cs2);
        entityManager.persist(cs1);
        entityManager.flush();
        entityManager.clear();

        List<ChangeSet> results = changeSetRepository.findByExecutionIdOrderBySeqAsc(execution.getId());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getSeq()).isEqualTo(1);
        assertThat(results.get(0).getPrNumber()).isEqualTo(101L);
        assertThat(results.get(1).getSeq()).isEqualTo(2);
        assertThat(results.get(1).getPrNumber()).isEqualTo(102L);
    }
}
