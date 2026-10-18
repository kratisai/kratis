package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
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

        ExecutionEnvironment firstEnv = (ExecutionEnvironment) results.getFirst()[0];
        Instant firstAct = (Instant) results.getFirst()[1];
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

    @Test
    void findConnectedEnvironmentsWithLastActivity_includesConnectedWithoutExecutionsUsingConnectedAt() {
        ExecutionEnvironment envNoExec = createEnvironment(team, "Connected No Exec");
        Instant connectedAt = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        envNoExec.setConnectedAt(connectedAt);
        executionEnvironmentRepository.save(envNoExec);

        entityManager.flush();
        entityManager.clear();

        List<Object[]> results = executionEnvironmentRepository.findConnectedEnvironmentsWithLastActivity();

        Object[] envRow = results.stream()
                .filter(r -> ((ExecutionEnvironment) r[0]).getId().equals(envNoExec.getId()))
                .findFirst()
                .orElseThrow();

        assertThat((Instant) envRow[1]).isEqualTo(connectedAt);
        assertThat(((Number) envRow[2]).longValue()).isZero();
    }

    @Test
    void findConnectedEnvironmentsWithLastActivity_usesConnectedAtWhenMoreRecentThanStaleExecutions() {
        ExecutionEnvironment envResumed = createEnvironment(team, "Resumed Env");
        Instant staleExecTime = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant recentConnectedAt = Instant.now().minus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

        SandboxExecution oldExec = createExecution(envResumed);
        oldExec.setStatus(SandboxExecutionStatus.COMPLETED);
        oldExec.setStartedAt(staleExecTime);
        sandboxExecutionRepository.save(oldExec);
        createActivity(oldExec, staleExecTime);

        envResumed.setConnectedAt(recentConnectedAt);
        executionEnvironmentRepository.save(envResumed);

        entityManager.flush();
        entityManager.clear();

        List<Object[]> results = executionEnvironmentRepository.findConnectedEnvironmentsWithLastActivity();

        Object[] envRow = results.stream()
                .filter(r -> ((ExecutionEnvironment) r[0]).getId().equals(envResumed.getId()))
                .findFirst()
                .orElseThrow();

        assertThat((Instant) envRow[1]).isEqualTo(recentConnectedAt);
        assertThat(((Number) envRow[2]).longValue()).isZero();
    }

    @Test
    void findConnectedEnvironmentsWithLastActivity_usesExecutionActivityWhenMoreRecentThanConnectedAt() {
        ExecutionEnvironment envActive = createEnvironment(team, "Active Env");
        Instant olderConnectedAt = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant recentActivityTime = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

        SandboxExecution exec = createExecution(envActive);
        exec.setStatus(SandboxExecutionStatus.RUNNING);
        exec.setStartedAt(olderConnectedAt);
        sandboxExecutionRepository.save(exec);
        createActivity(exec, recentActivityTime);

        envActive.setConnectedAt(olderConnectedAt);
        executionEnvironmentRepository.save(envActive);

        entityManager.flush();
        entityManager.clear();

        List<Object[]> results = executionEnvironmentRepository.findConnectedEnvironmentsWithLastActivity();

        Object[] envRow = results.stream()
                .filter(r -> ((ExecutionEnvironment) r[0]).getId().equals(envActive.getId()))
                .findFirst()
                .orElseThrow();

        assertThat((Instant) envRow[1]).isEqualTo(recentActivityTime);
        assertThat(((Number) envRow[2]).longValue()).isEqualTo(1L);
    }

    @Test
    void findConnectedEnvironmentsWithLastActivity_excludesDisconnectedAndSleepingEnvironments() {
        ExecutionEnvironment disconnectedEnv = createEnvironment(team, "Disconnected Env");
        disconnectedEnv.setStatus(EnvironmentStatus.DISCONNECTED);
        disconnectedEnv.setConnectedAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        executionEnvironmentRepository.save(disconnectedEnv);

        ExecutionEnvironment sleepingEnv = createEnvironment(team, "Sleeping Env");
        sleepingEnv.setStatus(EnvironmentStatus.SLEEPING);
        sleepingEnv.setConnectedAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        executionEnvironmentRepository.save(sleepingEnv);

        entityManager.flush();
        entityManager.clear();

        List<Object[]> results = executionEnvironmentRepository.findConnectedEnvironmentsWithLastActivity();

        List<UUID> returnedIds =
                results.stream().map(r -> ((ExecutionEnvironment) r[0]).getId()).toList();

        assertThat(returnedIds).doesNotContain(disconnectedEnv.getId(), sleepingEnv.getId());
    }
}
