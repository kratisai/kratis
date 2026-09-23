package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.UserRepository;
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
                activityPersistenceService,
                "ws://localhost:8080/ws/env",
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
        env.setContainerId("test-container-123");
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
    void terminateEnvironment_withNoRunningExecution_terminatesSandboxDirectly() {
        ExecutionEnvironment env = createSandboxEnvironment();
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of());
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        executionEnvironmentService.terminateEnvironment(USER_ID, TEAM_ID, ENV_ID);

        verify(sandboxProvider).terminateSandbox("test-container-123");
        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.DISCONNECTED);
        assertThat(env.getContainerId()).isNull();
        verify(executionEnvironmentRepository).save(env);
        verify(eventPublisher).publishEvent(any(TeamEntityChangedEvent.class));
    }

    @Test
    void terminateEnvironment_withRunningExecutionAndConnectedEnvironment_terminatesGracefully() {
        ExecutionEnvironment env = createSandboxEnvironment();
        SandboxExecution execution = createRunningExecution(env);

        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(execution));
        when(sessionRegistry.getSessionForEnvironment(ENV_ID)).thenReturn(webSocketSession);
        when(webSocketSession.isOpen()).thenReturn(true);
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        doNothing().when(sandboxExecutionService).terminateExecution(EXECUTION_ID);

        SandboxExecution completedExecution = createRunningExecution(env);
        completedExecution.setStatus(SandboxExecutionStatus.COMPLETED);
        completedExecution.setCompletedAt(Instant.now());
        when(sandboxExecutionRepository.findById(EXECUTION_ID))
                .thenReturn(Optional.of(execution), Optional.of(completedExecution));

        executionEnvironmentService.terminateEnvironment(USER_ID, TEAM_ID, ENV_ID);

        verify(sandboxExecutionService).terminateExecution(EXECUTION_ID);
        verify(sandboxProvider).terminateSandbox("test-container-123");
        verify(virtualKeyService, never()).revokeKey(any());
    }

    @Test
    void terminateEnvironment_withRunningExecutionAndDisconnectedEnvironment_forceTerminates() {
        ExecutionEnvironment env = createSandboxEnvironment();
        SandboxExecution execution = createRunningExecution(env);

        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(execution));
        when(sessionRegistry.getSessionForEnvironment(ENV_ID)).thenReturn(null);
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        executionEnvironmentService.terminateEnvironment(USER_ID, TEAM_ID, ENV_ID);

        verify(sandboxExecutionService, never()).terminateExecution(any());
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
        assertThat(execution.getCompletedAt()).isNotNull();
        verify(sandboxExecutionRepository).save(execution);
        verify(sandboxProvider).terminateSandbox("test-container-123");
    }

    @Test
    void terminateEnvironment_withTimeout_forceTerminatesAndRevokesKey() {
        ExecutionEnvironment env = createSandboxEnvironment();
        SandboxExecution execution = createRunningExecution(env);
        execution.setVirtualKey("sk-virtual-key-123");

        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(execution));
        when(sessionRegistry.getSessionForEnvironment(ENV_ID)).thenReturn(webSocketSession);
        when(webSocketSession.isOpen()).thenReturn(true);
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        doNothing().when(sandboxExecutionService).terminateExecution(EXECUTION_ID);

        SandboxExecution stillRunning = createRunningExecution(env);
        stillRunning.setVirtualKey("sk-virtual-key-123");
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(stillRunning));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(stillRunning);

        executionEnvironmentService.terminateEnvironment(USER_ID, TEAM_ID, ENV_ID);

        verify(sandboxExecutionService).terminateExecution(EXECUTION_ID);
        verify(virtualKeyService).revokeKey(stillRunning.getVirtualKey());
        assertThat(stillRunning.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
        verify(sandboxProvider).terminateSandbox("test-container-123");
    }

    @Test
    void terminateEnvironment_withPendingPermission_cancelsPermissionOnForceTerminate() {
        ExecutionEnvironment env = createSandboxEnvironment();
        SandboxExecution execution = createRunningExecution(env);

        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(execution));
        when(sessionRegistry.getSessionForEnvironment(ENV_ID)).thenReturn(null);
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        executionEnvironmentService.terminateEnvironment(USER_ID, TEAM_ID, ENV_ID);

        verify(sandboxExecutionService).cancelPendingPermissions(execution, "environment terminated");
    }

    @Test
    void terminateEnvironment_forceTerminate_publishesExecutionCompleteEvent() {
        ExecutionEnvironment env = createSandboxEnvironment();
        SandboxExecution execution = createRunningExecution(env);

        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(execution));
        when(sessionRegistry.getSessionForEnvironment(ENV_ID)).thenReturn(null);
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        executionEnvironmentService.terminateEnvironment(USER_ID, TEAM_ID, ENV_ID);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeast(3)).publishEvent(eventCaptor.capture());

        List<Object> events = eventCaptor.getAllValues();
        boolean hasCompleteEvent = events.stream().anyMatch(SandboxExecutionCompleteEvent.class::isInstance);
        boolean hasStatusChangedEvent = events.stream().anyMatch(ExecutionStatusChangedEvent.class::isInstance);
        boolean hasTeamChangedEnvEvent = events.stream()
                .filter(TeamEntityChangedEvent.class::isInstance)
                .map(TeamEntityChangedEvent.class::cast)
                .anyMatch(e -> e.type() == TeamEntityType.ENVIRONMENTS);

        assertThat(hasCompleteEvent).isTrue();
        assertThat(hasStatusChangedEvent).isTrue();
        assertThat(hasTeamChangedEnvEvent).isTrue();

        ExecutionStatusChangedEvent statusEvent = events.stream()
                .filter(ExecutionStatusChangedEvent.class::isInstance)
                .map(ExecutionStatusChangedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(statusEvent.teamId()).isEqualTo(TEAM_ID);
        assertThat(statusEvent.chatId()).isEqualTo(UUID.fromString("66666666-6666-6666-6666-666666666666"));
        assertThat(statusEvent.executionId()).isEqualTo(EXECUTION_ID);

        SandboxExecutionCompleteEvent completeEvent = events.stream()
                .filter(SandboxExecutionCompleteEvent.class::isInstance)
                .map(SandboxExecutionCompleteEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(completeEvent.status()).isEqualTo(SandboxExecutionStatus.FAILED);
        assertThat(completeEvent.executionId()).isEqualTo(EXECUTION_ID);
    }

    @Test
    void terminateEnvironment_withGracefulTerminateError_forceTerminates() {
        ExecutionEnvironment env = createSandboxEnvironment();
        SandboxExecution execution = createRunningExecution(env);

        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(execution));
        when(sessionRegistry.getSessionForEnvironment(ENV_ID)).thenReturn(webSocketSession);
        when(webSocketSession.isOpen()).thenReturn(true);
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Execution is not running"))
                .when(sandboxExecutionService)
                .terminateExecution(EXECUTION_ID);
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);

        executionEnvironmentService.terminateEnvironment(USER_ID, TEAM_ID, ENV_ID);

        verify(sandboxExecutionService).terminateExecution(EXECUTION_ID);
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
        verify(sandboxProvider).terminateSandbox("test-container-123");
    }

    @Test
    void terminateEnvironment_withVirtualKeyRevocationError_continuesTermination() {
        ExecutionEnvironment env = createSandboxEnvironment();
        SandboxExecution execution = createRunningExecution(env);
        execution.setVirtualKey("sk-virtual-key-123");

        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(execution));
        when(sessionRegistry.getSessionForEnvironment(ENV_ID)).thenReturn(null);
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        doThrow(new RuntimeException("LiteLLM unavailable"))
                .when(virtualKeyService)
                .revokeKey(execution.getVirtualKey());

        executionEnvironmentService.terminateEnvironment(USER_ID, TEAM_ID, ENV_ID);

        verify(virtualKeyService).revokeKey(execution.getVirtualKey());
        verify(sandboxProvider).terminateSandbox("test-container-123");
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
    }

    @Test
    void terminateEnvironment_teardownFailure_keepsExecutionTerminalAndEvents() {
        ExecutionEnvironment env = createSandboxEnvironment();
        SandboxExecution execution = createRunningExecution(env);

        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(execution));
        when(sessionRegistry.getSessionForEnvironment(ENV_ID)).thenReturn(null);
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenThrow(new IllegalArgumentException("provider unavailable"));

        assertThatThrownBy(() -> executionEnvironmentService.terminateEnvironment(USER_ID, TEAM_ID, ENV_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Failed to terminate environment");

        // The execution termination commits in its own REQUIRES_NEW transaction before the
        // container teardown, so a teardown failure can never leave the execution RUNNING with
        // no terminal events for the UI.
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeast(2)).publishEvent(eventCaptor.capture());
        boolean hasCompleteEvent =
                eventCaptor.getAllValues().stream().anyMatch(SandboxExecutionCompleteEvent.class::isInstance);
        boolean hasStatusChangedEvent =
                eventCaptor.getAllValues().stream().anyMatch(ExecutionStatusChangedEvent.class::isInstance);
        assertThat(hasCompleteEvent).isTrue();
        assertThat(hasStatusChangedEvent).isTrue();
    }

    @Test
    void terminateEnvironment_notSandboxType_throwsBadRequest() {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(ENV_ID);
        env.setType(ExecutionEnvironmentType.CONNECTOR);
        env.setStatus(EnvironmentStatus.CONNECTED);

        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.of(env));

        assertThatThrownBy(() -> executionEnvironmentService.terminateEnvironment(USER_ID, TEAM_ID, ENV_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not a running sandbox");
    }

    @Test
    void terminateEnvironment_notTeamMember_throwsForbidden() {
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> executionEnvironmentService.terminateEnvironment(USER_ID, TEAM_ID, ENV_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void terminateEnvironment_environmentNotFound_throwsNotFound() {
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENV_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executionEnvironmentService.terminateEnvironment(USER_ID, TEAM_ID, ENV_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
