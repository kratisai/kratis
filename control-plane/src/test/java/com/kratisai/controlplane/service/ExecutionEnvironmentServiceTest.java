package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.ExecutionEnvironmentDto;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.model.event.EnvironmentDisconnectedEvent;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class ExecutionEnvironmentServiceTest {

    @Mock
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SandboxOrchestratorService sandboxOrchestratorService;

    @Mock
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private VirtualKeyService virtualKeyService;

    @Mock
    private SandboxExecutionService sandboxExecutionService;

    @Mock
    private SandboxProvisioningService sandboxProvisioningService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private SandboxProvider sandboxProvider;

    @Mock
    private WebSocketSession webSocketSession;

    @Mock
    private SandboxExecutionActivityRepository activityRepository;

    private ExecutionEnvironmentService executionEnvironmentService;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ENV_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID EXECUTION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @BeforeEach
    void setUp() {
        ExecutionActivityPersistenceService activityPersistenceService = new ExecutionActivityPersistenceService(
                activityRepository, sandboxExecutionRepository, new ObjectMapper(), eventPublisher);
        SandboxProperties sandboxProperties = new SandboxProperties();
        sandboxProperties.setConnectUrl("ws://localhost:8080/ws/env");
        executionEnvironmentService = new ExecutionEnvironmentService(
                executionEnvironmentRepository,
                teamMemberRepository,
                teamRepository,
                userRepository,
                eventPublisher,
                sandboxOrchestratorService,
                sandboxExecutionRepository,
                sessionRegistry,
                virtualKeyService,
                sandboxExecutionService,
                sandboxProvisioningService,
                activityPersistenceService,
                sandboxProperties,
                transactionManager);
    }

    private ExecutionEnvironment createSandboxEnvironment() {
        Team team = new Team();
        team.setId(TEAM_ID);

        EnvironmentProvider provider = new EnvironmentProvider();
        provider.setId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        provider.setTeam(team);
        provider.setName("Test Provider");

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(ENV_ID);
        env.setTeam(team);
        env.setName("Test Sandbox");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.CONNECTED);
        env.setProvider(provider);
        return env;
    }

    private SandboxExecution createRunningExecution(ExecutionEnvironment env) {
        Team team = new Team();
        team.setId(TEAM_ID);

        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.fromString("66666666-6666-6666-6666-666666666666"));
        chat.setTeam(team);

        SandboxExecution execution = new SandboxExecution();
        execution.setId(EXECUTION_ID);
        execution.setEnvironment(env);
        execution.setChat(chat);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution.setStartedAt(Instant.now());
        return execution;
    }

    @Test
    void sleepEnvironment_suspendsProviderAndMarksSleeping() {
        ExecutionEnvironment env = createSandboxEnvironment();
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of());
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        executionEnvironmentService.sleepEnvironmentInternal(env);

        verify(sandboxProvider).suspend(ENV_ID);
        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.SLEEPING);
        assertThat(env.getSuspendedAt()).isNotNull();
        verify(executionEnvironmentRepository).save(env);
        verify(eventPublisher).publishEvent(any(TeamEntityChangedEvent.class));
    }

    @Test
    void sleepEnvironment_terminatesRunningExecutionBeforeSuspend() {
        ExecutionEnvironment env = createSandboxEnvironment();
        SandboxExecution running = createRunningExecution(env);
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(running));
        when(sessionRegistry.getSessionForEnvironment(ENV_ID)).thenReturn(webSocketSession);
        when(webSocketSession.isOpen()).thenReturn(true);
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);
        doNothing().when(sandboxExecutionService).terminateExecution(EXECUTION_ID);
        SandboxExecution completed = createRunningExecution(env);
        completed.setStatus(SandboxExecutionStatus.COMPLETED);
        completed.setCompletedAt(Instant.now());
        when(sandboxExecutionRepository.findById(EXECUTION_ID))
                .thenReturn(Optional.of(running), Optional.of(completed));

        executionEnvironmentService.sleepEnvironmentInternal(env);

        verify(sandboxExecutionService).terminateExecution(EXECUTION_ID);
        verify(sandboxProvider).suspend(ENV_ID);
        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.SLEEPING);
    }

    @Test
    void sleepEnvironment_recordsSleepActivityAndPublishesStatusChange() {
        ExecutionEnvironment env = createSandboxEnvironment();
        SandboxExecution execution = createRunningExecution(env);
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of());
        when(sandboxExecutionRepository.findFirstByEnvironmentIdOrderByStartedAtDesc(ENV_ID))
                .thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(activityRepository.nextSequence(EXECUTION_ID)).thenReturn(1L);
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        executionEnvironmentService.sleepEnvironmentInternal(env);

        ArgumentCaptor<SandboxExecutionActivity> activityCaptor =
                ArgumentCaptor.forClass(SandboxExecutionActivity.class);
        verify(activityRepository).save(activityCaptor.capture());
        assertThat(activityCaptor.getValue().getDescription()).isEqualTo("Agent has gone to sleep");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anyMatch(ExecutionStatusChangedEvent.class::isInstance);
    }

    @Test
    void sleepEnvironment_withSuspendedProviderFailure_returns500AndKeepsEnvironment() {
        ExecutionEnvironment env = createSandboxEnvironment();
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of());
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);
        doThrow(new IllegalStateException("No runner container found"))
                .when(sandboxProvider)
                .suspend(ENV_ID);

        assertThatThrownBy(() -> executionEnvironmentService.sleepEnvironmentInternal(env))
                .isInstanceOfSatisfying(
                        org.springframework.web.server.ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(500));
        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.CONNECTED);
        assertThat(env.getSuspendedAt()).isNull();
    }

    @Test
    void sleepEnvironment_withAlreadySleepingEnvironment_returns400() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setStatus(EnvironmentStatus.SLEEPING);

        assertThatThrownBy(() -> executionEnvironmentService.sleepEnvironmentInternal(env))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(sandboxOrchestratorService);
    }

    @Test
    void sleepEnvironment_withConnectorEnvironment_returns400() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setType(ExecutionEnvironmentType.CONNECTOR);

        assertThatThrownBy(() -> executionEnvironmentService.sleepEnvironmentInternal(env))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(sandboxOrchestratorService);
    }

    @Test
    void isSandboxRunning_delegatesToProvider() {
        ExecutionEnvironment env = createSandboxEnvironment();
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);
        when(sandboxProvider.isSandboxRunning(ENV_ID)).thenReturn(true);

        assertThat(executionEnvironmentService.isSandboxRunning(env)).isTrue();
    }

    @Test
    void isSandboxRunning_withoutProvider_isFalse() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setProvider(null);

        assertThat(executionEnvironmentService.isSandboxRunning(env)).isFalse();
        verifyNoInteractions(sandboxOrchestratorService);
    }

    @Test
    void handleStoppedContainer_marksDisconnected() {
        ExecutionEnvironment env = createSandboxEnvironment();

        executionEnvironmentService.reconcileStoppedContainer(env);

        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.DISCONNECTED);
        verify(executionEnvironmentRepository).save(env);
        verify(eventPublisher).publishEvent(any(TeamEntityChangedEvent.class));
    }

    @Test
    void handleStoppedContainer_settlesExecutionStrandedByTheDeadSandboxAsUnknown() {
        ExecutionEnvironment env = createSandboxEnvironment();
        SandboxExecution execution = createRunningExecution(env);
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(execution));
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(activityRepository.findByExecutionIdAndActionId(EXECUTION_ID, "execution-error"))
                .thenReturn(Optional.empty());
        when(activityRepository.nextSequence(EXECUTION_ID)).thenReturn(1L);

        executionEnvironmentService.reconcileStoppedContainer(env);

        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.DISCONNECTED);
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.UNKNOWN);
        assertThat(execution.getCompletedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(ExecutionStatusChangedEvent.class));
    }

    @Test
    void failStrandedExecution_settlesExecutionLeftByDeadEnvironmentAsUnknown() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        SandboxExecution execution = createRunningExecution(env);
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(activityRepository.findByExecutionIdAndActionId(EXECUTION_ID, "execution-error"))
                .thenReturn(Optional.empty());
        when(activityRepository.nextSequence(EXECUTION_ID)).thenReturn(1L);

        executionEnvironmentService.failStrandedExecution(EXECUTION_ID);

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.UNKNOWN);
        assertThat(execution.getCompletedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(SandboxExecutionCompleteEvent.class));
        verify(eventPublisher).publishEvent(any(ExecutionStatusChangedEvent.class));
    }

    @Test
    void resumeEnvironment_delegatesReprovisioningAndMarksPendingConnect() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setStatus(EnvironmentStatus.SLEEPING);
        env.setAuthToken("test-token");
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));

        ExecutionEnvironmentDto dto = executionEnvironmentService.resumeEnvironment(USER_ID, TEAM_ID, ENV_ID);

        assertThat(dto.status()).isEqualTo(EnvironmentStatus.PENDING_CONNECT);
        verify(sandboxProvisioningService).reprovisionEnvironment(env);
        verify(executionEnvironmentRepository).save(env);
        verify(eventPublisher).publishEvent(any(TeamEntityChangedEvent.class));
    }

    @Test
    void resumeEnvironment_publishesExecutionStatusChangeForLatestExecution() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setStatus(EnvironmentStatus.SLEEPING);
        env.setAuthToken("test-token");
        SandboxExecution execution = createRunningExecution(env);
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxExecutionRepository.findFirstByEnvironmentIdOrderByStartedAtDesc(ENV_ID))
                .thenReturn(Optional.of(execution));

        executionEnvironmentService.resumeEnvironment(USER_ID, TEAM_ID, ENV_ID);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anyMatch(ExecutionStatusChangedEvent.class::isInstance);
    }

    @Test
    void resumeEnvironment_fromTerminatedEnvironment_returns410() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setStatus(EnvironmentStatus.TERMINATED);
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));

        assertThatThrownBy(() -> executionEnvironmentService.resumeEnvironment(USER_ID, TEAM_ID, ENV_ID))
                .isInstanceOfSatisfying(
                        org.springframework.web.server.ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(410));
        verifyNoInteractions(sandboxProvisioningService);
    }

    @Test
    void resumeEnvironment_fromConnectedEnvironment_returns400() {
        ExecutionEnvironment env = createSandboxEnvironment();
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));

        assertThatThrownBy(() -> executionEnvironmentService.resumeEnvironment(USER_ID, TEAM_ID, ENV_ID))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(sandboxProvisioningService);
    }

    @Test
    void handleEnvironmentDisconnected_keepsSleepingStatus() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setStatus(EnvironmentStatus.SLEEPING);
        when(executionEnvironmentRepository.findById(ENV_ID)).thenReturn(Optional.of(env));

        executionEnvironmentService.handleEnvironmentDisconnected(new EnvironmentDisconnectedEvent(ENV_ID));

        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.SLEEPING);
        verify(executionEnvironmentRepository, never()).save(any(ExecutionEnvironment.class));
    }

    @Test
    void handleEnvironmentDisconnected_downgradesConnectedEnvironment() {
        ExecutionEnvironment env = createSandboxEnvironment();
        when(executionEnvironmentRepository.findById(ENV_ID)).thenReturn(Optional.of(env));

        executionEnvironmentService.handleEnvironmentDisconnected(new EnvironmentDisconnectedEvent(ENV_ID));

        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.DISCONNECTED);
        verify(executionEnvironmentRepository).save(env);
    }

    @Test
    void listEnvironments_returnsEnvironmentsWithLastActivity() {
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);

        ExecutionEnvironment env1 = createSandboxEnvironment();
        ExecutionEnvironment env2 = createSandboxEnvironment();
        env2.setId(UUID.randomUUID());
        env2.setName("Inactive Sandbox");

        Instant recent = Instant.now();
        List<Object[]> rows = List.of(new Object[] {env1, recent}, new Object[] {env2, null});

        when(executionEnvironmentRepository.findByTeamIdOrderByLatestActivityDesc(TEAM_ID))
                .thenReturn(rows);
        SandboxExecution active = createRunningExecution(env1);
        when(sandboxExecutionRepository.findFirstByEnvironmentIdOrderByStartedAtDesc(env1.getId()))
                .thenReturn(Optional.of(active));

        var result = executionEnvironmentService.listEnvironments(USER_ID, TEAM_ID);

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().id()).isEqualTo(env1.getId());
        assertThat(result.getFirst().lastActivity()).isEqualTo(recent);
        assertThat(result.getFirst().activeExecutionId()).isEqualTo(active.getId());
        assertThat(result.getFirst().activeChatId()).isEqualTo(active.getChat().getId());
        assertThat(result.get(1).id()).isEqualTo(env2.getId());
        assertThat(result.get(1).lastActivity()).isNull();
        assertThat(result.get(1).activeExecutionId()).isNull();
        assertThat(result.get(1).activeChatId()).isNull();
    }

    @Test
    void listEnvironments_notTeamMember_throwsForbidden() {
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> executionEnvironmentService.listEnvironments(USER_ID, TEAM_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void reapEnvironment_snapshotsRuntimeAndReturnsToSleeping() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setStatus(EnvironmentStatus.PENDING_CONNECT);
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        executionEnvironmentService.reapEnvironment(env);

        verify(sandboxProvider).captureSidecarLogs(ENV_ID);
        verify(sandboxProvider).suspend(ENV_ID);
        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.SLEEPING);
        assertThat(env.getSuspendedAt()).isNotNull();
        verify(executionEnvironmentRepository).save(env);
        verify(eventPublisher).publishEvent(any(TeamEntityChangedEvent.class));
    }

    @Test
    void reapEnvironment_withoutSnapshotDestroysRuntime() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setStatus(EnvironmentStatus.PENDING_CONNECT);
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);
        doThrow(new IllegalStateException("No runner container found"))
                .when(sandboxProvider)
                .suspend(ENV_ID);

        executionEnvironmentService.reapEnvironment(env);

        verify(sandboxProvider).destroy(ENV_ID);
        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.DISCONNECTED);
        verify(executionEnvironmentRepository).save(env);
    }

    @Test
    void reapEnvironment_withSnapshotFallsBackToResumableState() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setStatus(EnvironmentStatus.PENDING_CONNECT);
        env.setSuspendedAt(Instant.now().minusSeconds(60));
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);
        doThrow(new IllegalStateException("No runner container found"))
                .when(sandboxProvider)
                .suspend(ENV_ID);

        executionEnvironmentService.reapEnvironment(env);

        verify(sandboxProvider).terminateSandbox(ENV_ID);
        verify(sandboxProvider, never()).destroy(any());
        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.SLEEPING);
    }

    @Test
    void reapEnvironment_neverResurrectsTerminatedEnvironment() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setStatus(EnvironmentStatus.TERMINATED);
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        executionEnvironmentService.reapEnvironment(env);

        verify(sandboxProvider).destroy(ENV_ID);
        verify(sandboxProvider, never()).suspend(any());
        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.TERMINATED);
        verify(executionEnvironmentRepository, never()).save(any(ExecutionEnvironment.class));
    }

    @Test
    void expireEnvironment_destroysSnapshotAndMarksTerminated() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setStatus(EnvironmentStatus.SLEEPING);
        env.setSuspendedAt(Instant.now().minus(Duration.ofDays(60)));
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        executionEnvironmentService.expireEnvironment(env);

        verify(sandboxProvider).destroy(ENV_ID);
        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.TERMINATED);
        assertThat(env.getSuspendedAt()).isNull();
        verify(executionEnvironmentRepository).save(env);
        verify(eventPublisher).publishEvent(any(TeamEntityChangedEvent.class));
    }

    @Test
    void reconcilePurgedEnvironment_marksTerminatedAndRecordsPurgedActivity() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setStatus(EnvironmentStatus.SLEEPING);
        env.setSuspendedAt(Instant.now());
        SandboxExecution execution = createRunningExecution(env);
        when(sandboxExecutionRepository.findFirstByEnvironmentIdOrderByStartedAtDesc(ENV_ID))
                .thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(activityRepository.findByExecutionIdAndActionId(EXECUTION_ID, "sandbox-purged"))
                .thenReturn(Optional.empty());
        when(activityRepository.nextSequence(EXECUTION_ID)).thenReturn(1L);

        executionEnvironmentService.reconcilePurgedEnvironment(env);

        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.TERMINATED);
        assertThat(env.getSuspendedAt()).isNull();
        verify(executionEnvironmentRepository).save(env);
        verify(eventPublisher).publishEvent(any(TeamEntityChangedEvent.class));
        verify(eventPublisher).publishEvent(any(ExecutionStatusChangedEvent.class));

        ArgumentCaptor<SandboxExecutionActivity> activityCaptor =
                ArgumentCaptor.forClass(SandboxExecutionActivity.class);
        verify(activityRepository).save(activityCaptor.capture());
        assertThat(activityCaptor.getValue().getDescription())
                .isEqualTo("Sandbox resources found to have been unexpectedly purged");
        assertThat(activityCaptor.getValue().getStatus()).isEqualTo(ActivityStatus.FAILED);
        assertThat(activityCaptor.getValue().getActivityType()).isEqualTo(ActivityType.ERROR);
    }

    @Test
    void reconcilePurgedEnvironment_withoutExecution_marksTerminatedOnly() {
        ExecutionEnvironment env = createSandboxEnvironment();
        env.setStatus(EnvironmentStatus.SLEEPING);
        env.setSuspendedAt(Instant.now());
        when(sandboxExecutionRepository.findFirstByEnvironmentIdOrderByStartedAtDesc(ENV_ID))
                .thenReturn(Optional.empty());

        executionEnvironmentService.reconcilePurgedEnvironment(env);

        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.TERMINATED);
        assertThat(env.getSuspendedAt()).isNull();
        verify(executionEnvironmentRepository).save(env);
        verify(eventPublisher).publishEvent(any(TeamEntityChangedEvent.class));
        verify(activityRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(ExecutionStatusChangedEvent.class));
    }
}
