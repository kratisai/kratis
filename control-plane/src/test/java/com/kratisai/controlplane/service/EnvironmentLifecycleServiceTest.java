package com.kratisai.controlplane.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.EnvironmentProvider;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.ExecutionProviderType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnvironmentLifecycleServiceTest {

    private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant LONG_AGO = Instant.now().minus(Duration.ofDays(1));

    @Mock
    private ExecutionEnvironmentRepository environmentRepository;

    @Mock
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Mock
    private ExecutionEnvironmentService executionEnvironmentService;

    @Mock
    private ExecutionLifecycleService executionLifecycleService;

    @Mock
    private SandboxProvider sandboxProvider;

    private SandboxProperties sandboxProperties;

    private EnvironmentLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        sandboxProperties = new SandboxProperties();
        sandboxProperties.getLifecycle().setStartupGrace(Duration.ZERO);
        sandboxProperties.getLifecycle().setSettleWindow(Duration.ZERO);
        sandboxProperties.getLifecycle().setConnectTimeout(Duration.ZERO);
        sandboxProperties.getLifecycle().setIdleTimeout(Duration.ofMinutes(20));
        sandboxProperties.getLifecycle().setRetentionPeriod(Duration.ofDays(30));
        lifecycleService = newService();

        lenient().when(environmentRepository.findByTypeAndStatus(any(), any())).thenReturn(List.of());
        lenient().when(environmentRepository.findExpiredSleeping(any())).thenReturn(List.of());
        lenient()
                .when(environmentRepository.findByTypeAndStatusIn(any(), any()))
                .thenReturn(List.of());
        lenient()
                .when(sandboxExecutionRepository.findByStatusInAndEnvironment_StatusIn(any(), any()))
                .thenReturn(List.of());
        lenient().when(sandboxProvider.listActiveEnvIds()).thenReturn(Set.of());
        lenient().when(sandboxProvider.listRetainedEnvIds()).thenReturn(Set.of());
        lenient().when(sandboxProvider.getProviderType()).thenReturn(ExecutionProviderType.DOCKER);
    }

    private EnvironmentLifecycleService newService() {
        return new EnvironmentLifecycleService(
                environmentRepository,
                sandboxExecutionRepository,
                executionEnvironmentService,
                executionLifecycleService,
                List.of(sandboxProvider),
                sandboxProperties);
    }

    private ExecutionEnvironment sandbox(EnvironmentStatus status) {
        Team team = new Team();
        team.setId(TEAM_ID);

        EnvironmentProvider provider = new EnvironmentProvider();
        provider.setId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        provider.setTeam(team);
        provider.setName("Test Provider");

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(ENV_ID);
        env.setTeam(team);
        env.setName("Sandbox");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(status);
        env.setProvider(provider);
        env.setStateChangedAt(LONG_AGO);
        return env;
    }

    private void givenEnvironments(EnvironmentStatus status, ExecutionEnvironment... envs) {
        when(environmentRepository.findByTypeAndStatus(ExecutionEnvironmentType.SANDBOX, status))
                .thenReturn(List.of(envs));
    }

    @Test
    void sweep_doesNothingDuringSettleWindow() {
        sandboxProperties.getLifecycle().setSettleWindow(Duration.ofHours(1));
        lifecycleService = newService();
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        env.setStateChangedAt(LONG_AGO);
        givenEnvironments(EnvironmentStatus.CONNECTED, env);

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
        verify(sandboxProvider, never()).listActiveEnvIds();
    }

    @Test
    void sweep_suspendsEnvironmentIdleLongerThanTimeout() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        env.setStateChangedAt(Instant.now().minus(Duration.ofHours(2)));
        givenEnvironments(EnvironmentStatus.CONNECTED, env);

        lifecycleService.sweep();

        verify(executionEnvironmentService).sleepEnvironmentInternal(env);
    }

    @Test
    void sweep_suspendsEvenWhenHeartbeatIsStale() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        env.setStateChangedAt(Instant.now().minus(Duration.ofHours(2)));
        env.setLastHeartbeat(LONG_AGO);
        givenEnvironments(EnvironmentStatus.CONNECTED, env);

        lifecycleService.sweep();

        verify(executionEnvironmentService).sleepEnvironmentInternal(env);
    }

    @Test
    void sweep_neverSuspendsEnvironmentWithRunningExecution() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        env.setStateChangedAt(Instant.now().minus(Duration.ofHours(2)));
        givenEnvironments(EnvironmentStatus.CONNECTED, env);
        when(executionLifecycleService.hasRunningExecutions(ENV_ID)).thenReturn(true);

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
    }

    @Test
    void sweep_neverSuspendsEnvironmentWithPendingHitl() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        env.setStateChangedAt(Instant.now().minus(Duration.ofHours(2)));
        givenEnvironments(EnvironmentStatus.CONNECTED, env);
        when(executionLifecycleService.hasPendingHitl(ENV_ID)).thenReturn(true);

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
    }

    @Test
    void sweep_sleepsIdleEnvironmentOncePendingHitlClears() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        env.setStateChangedAt(Instant.now().minus(Duration.ofHours(2)));
        givenEnvironments(EnvironmentStatus.CONNECTED, env);
        when(executionLifecycleService.hasPendingHitl(ENV_ID)).thenReturn(false);

        lifecycleService.sweep();

        verify(executionLifecycleService).hasPendingHitl(ENV_ID);
        verify(executionEnvironmentService).sleepEnvironmentInternal(env);
    }

    @Test
    void sweep_pendingHitlDoesNotBlockLostRuntimeHandling() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        env.setStateChangedAt(Instant.now().minus(Duration.ofHours(2)));
        givenEnvironments(EnvironmentStatus.CONNECTED, env);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));
        when(executionLifecycleService.hasPendingHitl(ENV_ID)).thenReturn(true);
        when(sandboxProvider.isSandboxRunning(ENV_ID)).thenReturn(false);
        when(sandboxProvider.isRetained(ENV_ID)).thenReturn(true);

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
        verify(sandboxProvider).terminateSandbox(ENV_ID);
        verify(executionEnvironmentService).suspendAfterLostRuntime(env);
    }

    @Test
    void sweep_skipsEnvironmentIdleForLessThanTimeout() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        env.setStateChangedAt(Instant.now().minus(Duration.ofMinutes(1)));
        givenEnvironments(EnvironmentStatus.CONNECTED, env);

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
    }

    @Test
    void sweep_expiresSnapshotsPastRetention() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.SLEEPING);
        when(environmentRepository.findExpiredSleeping(any())).thenReturn(List.of(env));

        lifecycleService.sweep();

        verify(executionEnvironmentService).expireEnvironment(env);
    }

    @Test
    void sweep_rule_sleepingWithRuntimeLeftoverIsTerminatedByProvider() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.SLEEPING);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));
        when(sandboxProvider.listActiveEnvIds()).thenReturn(Set.of(ENV_ID));

        lifecycleService.sweep();

        verify(sandboxProvider).terminateSandbox(ENV_ID);
        verify(executionEnvironmentService, never()).reconcilePurgedEnvironment(any());
    }

    @Test
    void sweep_rule_sleepingWithSuspendedStateOnlyIsHealthy() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.SLEEPING);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));
        when(sandboxProvider.listRetainedEnvIds()).thenReturn(Set.of(ENV_ID));

        lifecycleService.sweep();

        verify(sandboxProvider, never()).terminateSandbox(any());
        verify(executionEnvironmentService, never()).reconcilePurgedEnvironment(any());
    }

    @Test
    void sweep_rule_sleepingWithNothingIsMarkedTerminated() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.SLEEPING);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));

        lifecycleService.sweep();

        verify(executionEnvironmentService).reconcilePurgedEnvironment(env);
    }

    @Test
    void sweep_rule_connectedRowWithRuntimeIsHealthy() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));
        when(environmentRepository.findById(ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxProvider.isSandboxRunning(ENV_ID)).thenReturn(true);

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).reconcilePurgedEnvironment(any());
    }

    @Test
    void sweep_lostRuntime_connectedRowWithoutRunnerButSnapshotGoesSleeping() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));
        when(sandboxProvider.isSandboxRunning(ENV_ID)).thenReturn(false);
        when(sandboxProvider.isRetained(ENV_ID)).thenReturn(true);

        lifecycleService.sweep();

        verify(sandboxProvider).terminateSandbox(ENV_ID);
        verify(executionEnvironmentService).suspendAfterLostRuntime(env);
    }

    @Test
    void sweep_rule_connectedRowWithNothingIsMarkedTerminated() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        givenEnvironments(EnvironmentStatus.CONNECTED, env);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));

        lifecycleService.sweep();

        verify(executionEnvironmentService).reconcilePurgedEnvironment(env);
    }

    @Test
    void sweep_rule_failedListingSkipsTheRow() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.SLEEPING);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));
        when(sandboxProvider.listRetainedEnvIds()).thenThrow(new IllegalStateException("docker down"));
        when(sandboxProvider.listActiveEnvIds()).thenThrow(new IllegalStateException("docker down"));

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).reconcilePurgedEnvironment(any());
        verify(sandboxProvider, never()).terminateSandbox(any());
    }

    @Test
    void sweep_rule_rowInsideStartupGraceIsSkipped() {
        sandboxProperties.getLifecycle().setStartupGrace(Duration.ofHours(1));
        lifecycleService = newService();
        ExecutionEnvironment env = sandbox(EnvironmentStatus.PENDING_CONNECT);
        env.setStateChangedAt(Instant.now());
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).reconcilePurgedEnvironment(any());
        verify(sandboxProvider, never()).terminateSandbox(any());
    }

    @Test
    void sweep_rule_terminatedRowWithResourcesIsDestroyed() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.TERMINATED);
        when(environmentRepository.findById(ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxProvider.listActiveEnvIds()).thenReturn(Set.of(ENV_ID));

        lifecycleService.sweep();

        verify(sandboxProvider).terminateSandbox(ENV_ID);
    }

    @Test
    void sweep_lostRuntime_connectedWithSnapshotGoesSleeping() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));
        when(sandboxProvider.isSandboxRunning(ENV_ID)).thenReturn(false);
        when(sandboxProvider.isRetained(ENV_ID)).thenReturn(true);

        lifecycleService.sweep();

        verify(sandboxProvider).terminateSandbox(ENV_ID);
        verify(executionEnvironmentService).suspendAfterLostRuntime(env);
        verify(executionEnvironmentService, never()).reconcilePurgedEnvironment(any());
    }

    @Test
    void sweep_lostRuntime_connectedWithoutSnapshotGoesTerminated() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));
        when(sandboxProvider.isSandboxRunning(ENV_ID)).thenReturn(false);
        when(sandboxProvider.isRetained(ENV_ID)).thenReturn(false);

        lifecycleService.sweep();

        verify(sandboxProvider).terminateSandbox(ENV_ID);
        verify(executionEnvironmentService).reconcilePurgedEnvironment(env);
    }

    @Test
    void sweep_lostRuntime_connectedWithLiveRunnerIsLeftAlone() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));
        when(sandboxProvider.isSandboxRunning(ENV_ID)).thenReturn(true);

        lifecycleService.sweep();

        verify(sandboxProvider, never()).terminateSandbox(any());
        verify(executionEnvironmentService, never()).reconcilePurgedEnvironment(any());
    }

    @Test
    void sweep_oneFailingRowDoesNotStopTheOthers() {
        ExecutionEnvironment failing = sandbox(EnvironmentStatus.CONNECTED);
        failing.setStateChangedAt(Instant.now().minus(Duration.ofHours(2)));
        ExecutionEnvironment healthy = sandbox(EnvironmentStatus.CONNECTED);
        healthy.setId(UUID.fromString("99999999-9999-9999-9999-999999999999"));
        healthy.setStateChangedAt(Instant.now().minus(Duration.ofHours(2)));
        givenEnvironments(EnvironmentStatus.CONNECTED, failing, healthy);
        doThrow(new IllegalStateException("boom"))
                .when(executionEnvironmentService)
                .sleepEnvironmentInternal(failing);

        lifecycleService.sweep();

        verify(executionEnvironmentService).sleepEnvironmentInternal(healthy);
    }

    @Test
    void sweep_rule_terminatedRowWithoutResourcesIsLeftAlone() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.TERMINATED);
        when(environmentRepository.findById(ENV_ID)).thenReturn(Optional.of(env));

        lifecycleService.sweep();

        verify(sandboxProvider, never()).destroy(any());
    }

    @Test
    void sweep_rollsStuckResumeBackToSleeping() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.PENDING_CONNECT);
        givenEnvironments(EnvironmentStatus.PENDING_CONNECT, env);
        when(sandboxProvider.isSandboxRunning(ENV_ID)).thenReturn(false);
        when(sandboxProvider.isRetained(ENV_ID)).thenReturn(true);

        lifecycleService.sweep();

        verify(executionEnvironmentService).rollbackFailedResume(env);
    }

    @Test
    void sweep_leavesPendingEnvironmentWithLiveRuntimeAlone() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.PENDING_CONNECT);
        givenEnvironments(EnvironmentStatus.PENDING_CONNECT, env);
        when(sandboxProvider.isSandboxRunning(ENV_ID)).thenReturn(true);
        when(sandboxProvider.isRetained(ENV_ID)).thenReturn(true);

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).rollbackFailedResume(any());
    }

    @Test
    void sweep_failsExecutionsOnlyOfTerminatedEnvironments() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.TERMINATED);
        SandboxExecution execution = new SandboxExecution();
        execution.setId(UUID.fromString("77777777-7777-7777-7777-777777777777"));
        execution.setEnvironment(env);
        when(sandboxExecutionRepository.findByStatusInAndEnvironment_StatusIn(
                        any(), eq(List.of(EnvironmentStatus.TERMINATED))))
                .thenReturn(List.of(execution));

        lifecycleService.sweep();

        verify(executionEnvironmentService).failStrandedExecution(execution.getId());
    }
}
