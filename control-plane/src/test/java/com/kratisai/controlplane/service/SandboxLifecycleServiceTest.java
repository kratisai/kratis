package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
class SandboxLifecycleServiceTest {

    private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private ExecutionEnvironmentRepository environmentRepository;

    @Mock
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Mock
    private ExecutionEnvironmentService executionEnvironmentService;

    @Mock
    private SandboxProvider sandboxProvider;

    private SandboxProperties sandboxProperties;

    private SandboxLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        sandboxProperties = new SandboxProperties();
        sandboxProperties.getLifecycle().setStartupGrace(Duration.ZERO);
        sandboxProperties.getLifecycle().setIdleTimeout(Duration.ofMinutes(20));
        sandboxProperties.getLifecycle().setRetentionPeriod(Duration.ofDays(30));
        lifecycleService = new SandboxLifecycleService(
                environmentRepository,
                sandboxExecutionRepository,
                executionEnvironmentService,
                List.of(sandboxProvider),
                sandboxProperties);

        lenient()
                .when(environmentRepository.findConnectedEnvironmentsWithLastActivity())
                .thenReturn(List.of());
        lenient().when(environmentRepository.findExpiredSleeping(any())).thenReturn(List.of());
        lenient()
                .when(environmentRepository.findByTypeAndStatusIn(any(), any()))
                .thenReturn(List.of());
        lenient()
                .when(sandboxExecutionRepository.findByStatusInAndEnvironment_StatusIn(any(), any()))
                .thenReturn(List.of());
        lenient().when(sandboxProvider.listActiveEnvIds()).thenReturn(Set.of());
        lenient().when(sandboxProvider.listSnapshotEnvIds()).thenReturn(Set.of());
        lenient().when(sandboxProvider.getProviderType()).thenReturn(ExecutionProviderType.DOCKER);
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
        return env;
    }

    @Test
    void sweep_destroysOrphanRuntimeOnlyAfterGrace() {
        sandboxProperties.getLifecycle().setStartupGrace(Duration.ofHours(1));
        lifecycleService = new SandboxLifecycleService(
                environmentRepository,
                sandboxExecutionRepository,
                executionEnvironmentService,
                List.of(sandboxProvider),
                sandboxProperties);
        when(sandboxProvider.listActiveEnvIds()).thenReturn(Set.of(ENV_ID));
        when(environmentRepository.findById(ENV_ID)).thenReturn(Optional.empty());

        lifecycleService.sweep();
        verify(sandboxProvider, never()).destroy(any());

        lifecycleService.sweep();
        verify(sandboxProvider, never()).destroy(any());
    }

    @Test
    void sweep_destroysOrphanRuntimeOnceGraceExpired() {
        when(sandboxProvider.listActiveEnvIds()).thenReturn(Set.of(ENV_ID));
        when(environmentRepository.findById(ENV_ID)).thenReturn(Optional.empty());

        lifecycleService.sweep();
        lifecycleService.sweep();

        verify(sandboxProvider).captureSidecarLogs(ENV_ID);
        verify(sandboxProvider).destroy(ENV_ID);
        verify(executionEnvironmentService, never()).reapEnvironment(any());
    }

    @Test
    void sweep_reapsMismatchedRuntimeThroughEnvironmentService() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.PENDING_CONNECT);
        when(sandboxProvider.listActiveEnvIds()).thenReturn(Set.of(ENV_ID));
        when(environmentRepository.findById(ENV_ID)).thenReturn(Optional.of(env));

        lifecycleService.sweep();
        verify(executionEnvironmentService, never()).reapEnvironment(any());

        lifecycleService.sweep();
        verify(executionEnvironmentService).reapEnvironment(env);
    }

    @Test
    void sweep_neverReapsHealthyConnectedSandbox() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        when(sandboxProvider.listActiveEnvIds()).thenReturn(Set.of(ENV_ID));
        when(environmentRepository.findById(ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxProvider.isSandboxRunning(ENV_ID)).thenReturn(true);

        lifecycleService.sweep();
        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).reapEnvironment(any());
        verify(sandboxProvider, never()).destroy(any());
    }

    @Test
    void sweep_reapsConnectedSandboxWhoseRunnerStopped() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        when(sandboxProvider.listActiveEnvIds()).thenReturn(Set.of(ENV_ID));
        when(environmentRepository.findById(ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxProvider.isSandboxRunning(ENV_ID)).thenReturn(false);

        lifecycleService.sweep();
        lifecycleService.sweep();

        verify(executionEnvironmentService).reapEnvironment(env);
    }

    @Test
    void sweep_suspendsIdleSandbox() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        when(environmentRepository.findConnectedEnvironmentsWithLastActivity())
                .thenReturn(List.<Object[]>of(new Object[] {env, Instant.now().minus(Duration.ofHours(2)), 0L}));
        when(executionEnvironmentService.isSandboxRunning(env)).thenReturn(true);

        lifecycleService.sweep();

        verify(executionEnvironmentService).sleepEnvironmentInternal(env);
    }

    @Test
    void sweep_marksStoppedSandboxDisconnectedInsteadOfSuspending() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        when(environmentRepository.findConnectedEnvironmentsWithLastActivity())
                .thenReturn(List.<Object[]>of(new Object[] {env, Instant.now().minus(Duration.ofHours(2)), 0L}));
        when(executionEnvironmentService.isSandboxRunning(env)).thenReturn(false);

        lifecycleService.sweep();

        verify(executionEnvironmentService).reconcileStoppedContainer(env);
        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
    }

    @Test
    void sweep_skipsSandboxWithRunningExecution() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        when(environmentRepository.findConnectedEnvironmentsWithLastActivity())
                .thenReturn(List.<Object[]>of(new Object[] {env, Instant.now().minus(Duration.ofHours(2)), 1L}));

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
    }

    @Test
    void sweep_skipsRecentlyActiveSandbox() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        when(environmentRepository.findConnectedEnvironmentsWithLastActivity())
                .thenReturn(List.<Object[]>of(new Object[] {env, Instant.now().minus(Duration.ofMinutes(1)), 0L}));

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
    }

    @Test
    void sweep_skipsResumedSandboxWithRecentConnectionActivity() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        // Resumed sandbox with fresh connection activity (1 minute ago) despite older historical executions
        when(environmentRepository.findConnectedEnvironmentsWithLastActivity())
                .thenReturn(List.<Object[]>of(new Object[] {env, Instant.now().minus(Duration.ofMinutes(1)), 0L}));

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
    }

    @Test
    void sweep_suspendsResumedSandboxWhenIdleTimeoutExceededAfterReconnect() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        // Resumed sandbox where connection activity was 2 hours ago and no further activity occurred
        when(environmentRepository.findConnectedEnvironmentsWithLastActivity())
                .thenReturn(List.<Object[]>of(new Object[] {env, Instant.now().minus(Duration.ofHours(2)), 0L}));
        when(executionEnvironmentService.isSandboxRunning(env)).thenReturn(true);

        lifecycleService.sweep();

        verify(executionEnvironmentService).sleepEnvironmentInternal(env);
    }

    @Test
    void sweep_skipsConnectedSandboxWithNoExecutionsWhenConnectedRecently() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        // Connected sandbox with no executions whose lastActivity matches recent connectedAt
        when(environmentRepository.findConnectedEnvironmentsWithLastActivity())
                .thenReturn(List.<Object[]>of(new Object[] {env, Instant.now().minus(Duration.ofMinutes(5)), 0L}));

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
    }

    @Test
    void sweep_suspendsConnectedSandboxWithNoExecutionsAfterIdleTimeout() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        // Connected sandbox with no executions whose lastActivity matches old connectedAt
        when(environmentRepository.findConnectedEnvironmentsWithLastActivity())
                .thenReturn(List.<Object[]>of(new Object[] {env, Instant.now().minus(Duration.ofHours(2)), 0L}));
        when(executionEnvironmentService.isSandboxRunning(env)).thenReturn(true);

        lifecycleService.sweep();

        verify(executionEnvironmentService).sleepEnvironmentInternal(env);
    }

    @Test
    void sweep_skipsNonSandboxEnvironment() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        env.setType(ExecutionEnvironmentType.CONNECTOR);
        when(environmentRepository.findConnectedEnvironmentsWithLastActivity())
                .thenReturn(List.<Object[]>of(new Object[] {env, Instant.now().minus(Duration.ofHours(2)), 0L}));

        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
    }

    @Test
    void sweep_expiresStaleSnapshots() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.SLEEPING);
        when(environmentRepository.findExpiredSleeping(any())).thenReturn(List.of(env));

        lifecycleService.sweep();

        verify(executionEnvironmentService).expireEnvironment(env);
    }

    @Test
    void sweep_prunesOrphanNetworksEachCycle() {
        lifecycleService.sweep();

        verify(sandboxProvider).pruneOrphanNetworks(eq(Duration.ZERO));
    }

    @Test
    void sweep_clearsGraceTrackingWhenRuntimeDisappears() {
        when(sandboxProvider.listActiveEnvIds()).thenReturn(Set.of(ENV_ID));
        when(environmentRepository.findById(ENV_ID)).thenReturn(Optional.empty());

        lifecycleService.sweep();
        assertThat(sandboxProvider.listActiveEnvIds()).containsExactly(ENV_ID);

        when(sandboxProvider.listActiveEnvIds()).thenReturn(Set.of());
        lifecycleService.sweep();

        // The tracked orphan vanished; a later re-appearance must wait out the grace again.
        when(sandboxProvider.listActiveEnvIds()).thenReturn(Set.of(ENV_ID));
        lifecycleService.sweep();
        verify(sandboxProvider, never()).destroy(any());
    }

    @Test
    void sweep_reconcilesPurgedSandboxWithoutRuntimeOrSnapshot() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.SLEEPING);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));

        lifecycleService.sweep();
        verify(executionEnvironmentService, never()).reconcilePurgedEnvironment(any());

        lifecycleService.sweep();
        verify(executionEnvironmentService).reconcilePurgedEnvironment(env);
    }

    @Test
    void sweep_keepsPurgedCandidateWithResumableSnapshot() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.SLEEPING);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));
        when(sandboxProvider.listSnapshotEnvIds()).thenReturn(Set.of(ENV_ID));

        lifecycleService.sweep();
        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).reconcilePurgedEnvironment(any());
    }

    @Test
    void sweep_keepsPurgedCandidateWithRuntime() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.CONNECTED);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));
        when(environmentRepository.findById(ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxProvider.listActiveEnvIds()).thenReturn(Set.of(ENV_ID));

        lifecycleService.sweep();
        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).reconcilePurgedEnvironment(any());
    }

    @Test
    void sweep_doesNotReconcilePurgedSandboxWithinStartupGrace() {
        sandboxProperties.getLifecycle().setStartupGrace(Duration.ofHours(1));
        lifecycleService = new SandboxLifecycleService(
                environmentRepository,
                sandboxExecutionRepository,
                executionEnvironmentService,
                List.of(sandboxProvider),
                sandboxProperties);
        ExecutionEnvironment env = sandbox(EnvironmentStatus.SLEEPING);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));

        lifecycleService.sweep();
        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).reconcilePurgedEnvironment(any());
    }

    @Test
    void sweep_skipsPurgedCandidateWithoutProvider() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.SLEEPING);
        env.setProvider(null);
        when(environmentRepository.findByTypeAndStatusIn(any(), any())).thenReturn(List.of(env));

        lifecycleService.sweep();
        lifecycleService.sweep();

        verify(executionEnvironmentService, never()).reconcilePurgedEnvironment(any());
    }

    @Test
    void sweep_failsExecutionStrandedByDeadEnvironmentAfterGrace() {
        ExecutionEnvironment env = sandbox(EnvironmentStatus.DISCONNECTED);
        SandboxExecution execution = new SandboxExecution();
        execution.setId(UUID.fromString("77777777-7777-7777-7777-777777777777"));
        execution.setEnvironment(env);
        when(sandboxExecutionRepository.findByStatusInAndEnvironment_StatusIn(any(), any()))
                .thenReturn(List.of(execution));

        lifecycleService.sweep();
        verify(executionEnvironmentService, never()).failStrandedExecution(any());

        lifecycleService.sweep();
        verify(executionEnvironmentService).failStrandedExecution(execution.getId());
    }
}
