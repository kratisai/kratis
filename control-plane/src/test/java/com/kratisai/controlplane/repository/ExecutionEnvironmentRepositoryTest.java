package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class ExecutionEnvironmentRepositoryTest {

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private SandboxExecutionActivityRepository activityRepository;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private EntityManager entityManager;

    private Team team;
    private ChatEntity chat;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        team = ctx.team();

        chat = new ChatEntity(team, ctx.user(), "Repository Test Chat");
        chatRepository.save(chat);
    }

    private ExecutionEnvironment createEnvironment(Team t, String name) {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(t);
        env.setName(name);
        env.setType(ExecutionEnvironmentType.CONNECTOR);
        env.setStatus(EnvironmentStatus.CONNECTED);
        env.setAuthToken("token-" + name);
        return executionEnvironmentRepository.save(env);
    }

    private SandboxExecution createExecution(ExecutionEnvironment env) {
        SandboxExecution execution = new SandboxExecution();
        execution.setEnvironment(env);
        execution.setChat(chat);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution.setStartedAt(Instant.now());
        return sandboxExecutionRepository.save(execution);
    }

    private SandboxExecutionActivity createActivity(SandboxExecution execution, Instant createdAt) {
        SandboxExecutionActivity activity = new SandboxExecutionActivity(
                execution.getId(),
                1,
                "action-" + createdAt.toEpochMilli(),
                ActivityType.COMMAND,
                ActivityStatus.COMPLETED,
                "command",
                "{}");
        activity.setCreatedAt(createdAt);
        return activityRepository.save(activity);
    }

    @Test
    void findByTeamIdOrderByLatestActivityDesc_ordersRecentActivityFirstAndNullsLast() {
        ExecutionEnvironment envRecent = createEnvironment(team, "Zeta Recent");
        ExecutionEnvironment envOlder = createEnvironment(team, "Alpha Older");
        ExecutionEnvironment envNoExec = createEnvironment(team, "Delta Inactive");
        ExecutionEnvironment envNoAct = createEnvironment(team, "Beta Inactive");

        TestDataFactory.TestContext otherCtx = testDataFactory.createUserAndTeam();
        createEnvironment(otherCtx.team(), "Other Team Env");

        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant timeOlder = base.minus(10, ChronoUnit.MINUTES);
        Instant timeRecent = base.minus(1, ChronoUnit.MINUTES);

        SandboxExecution execOlder = createExecution(envOlder);
        createActivity(execOlder, timeOlder);

        SandboxExecution execRecent = createExecution(envRecent);
        createActivity(execRecent, base.minus(5, ChronoUnit.MINUTES));
        createActivity(execRecent, timeRecent);

        createExecution(envNoAct);

        entityManager.flush();
        entityManager.clear();

        List<Object[]> results = executionEnvironmentRepository.findByTeamIdOrderByLatestActivityDesc(team.getId());

        assertThat(results).hasSize(5);

        ExecutionEnvironment firstEnv = (ExecutionEnvironment) results.get(0)[0];
        Instant firstAct = (Instant) results.get(0)[1];
        assertThat(firstEnv.getId()).isEqualTo(envRecent.getId());
        assertThat(firstAct).isEqualTo(timeRecent);

        ExecutionEnvironment secondEnv = (ExecutionEnvironment) results.get(1)[0];
        Instant secondAct = (Instant) results.get(1)[1];
        assertThat(secondEnv.getId()).isEqualTo(envOlder.getId());
        assertThat(secondAct).isEqualTo(timeOlder);

        ExecutionEnvironment thirdEnv = (ExecutionEnvironment) results.get(2)[0];
        Instant thirdAct = (Instant) results.get(2)[1];
        assertThat(thirdEnv.getId()).isEqualTo(envNoAct.getId());
        assertThat(thirdAct).isNull();

        ExecutionEnvironment fourthEnv = (ExecutionEnvironment) results.get(3)[0];
        Instant fourthAct = (Instant) results.get(3)[1];
        assertThat(fourthEnv.getName()).isEqualTo("Default Sandbox");
        assertThat(fourthAct).isNull();

        ExecutionEnvironment fifthEnv = (ExecutionEnvironment) results.get(4)[0];
        Instant fifthAct = (Instant) results.get(4)[1];
        assertThat(fifthEnv.getId()).isEqualTo(envNoExec.getId());
        assertThat(fifthAct).isNull();
    }
}
