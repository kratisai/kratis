package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ExecutionLifecycleServiceTest {

    private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EXECUTION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Mock
    private ExecutionActivityPersistenceService activityPersistenceService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private PendingHitlRegistry pendingHitlRegistry;

    private ExecutionLifecycleService service;
    private SandboxExecution execution;
    private ExecutionEnvironment environment;

    @BeforeEach
    void setUp() {
        SandboxProperties properties = new SandboxProperties();
        properties.getLifecycle().setActivityTimeout(Duration.ofMinutes(5));
        properties.getLifecycle().setSettleWindow(Duration.ZERO);
        service = new ExecutionLifecycleService(
                sandboxExecutionRepository,
                activityPersistenceService,
                eventPublisher,
                pendingHitlRegistry,
                properties);

        Team team = new Team();
        team.setId(UUID.randomUUID());
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());
        chat.setTeam(team);
        environment = new ExecutionEnvironment();
        environment.setId(ENV_ID);
        environment.setStateChangedAt(Instant.now().minus(Duration.ofHours(1)));
        execution = new SandboxExecution();
        execution.setId(EXECUTION_ID);
        execution.setChat(chat);
        execution.setEnvironment(environment);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
    }

    private void givenRunningExecutionLastActiveAt(Instant lastActivity) {
        when(sandboxExecutionRepository.findRunningExecutionsWithLastActivity())
                .thenReturn(List.<Object[]>of(new Object[] {execution, lastActivity}));
    }

    @Test
    void sweep_movesExecutionQuietPastActivityTimeoutToIdle() {
        givenRunningExecutionLastActiveAt(Instant.now().minus(Duration.ofMinutes(10)));

        service.sweep();

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.IDLE);
        verify(sandboxExecutionRepository).save(execution);
        verify(activityPersistenceService)
                .recordActivity(
                        eq(EXECUTION_ID),
                        eq(ActivityType.MESSAGE),
                        any(),
                        eq(null),
                        eq(ActivityStatus.COMPLETED),
                        eq(null));
        assertThat(environment.getStateChangedAt()).isAfter(Instant.now().minusSeconds(30));
        verify(eventPublisher).publishEvent(any(ExecutionStatusChangedEvent.class));
    }

    @Test
    void sweep_skipsSettleWhenServiceJustBooted() {
        SandboxProperties fresh = new SandboxProperties();
        fresh.getLifecycle().setActivityTimeout(Duration.ofMinutes(5));
        fresh.getLifecycle().setSettleWindow(Duration.ofHours(1));
        ExecutionLifecycleService gated = new ExecutionLifecycleService(
                sandboxExecutionRepository, activityPersistenceService, eventPublisher, pendingHitlRegistry, fresh);
        gated.sweep();

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.RUNNING);
        verify(sandboxExecutionRepository, never()).save(any());
    }

    @Test
    void sweep_keepsExecutionWithPendingHitlRunning() {
        givenRunningExecutionLastActiveAt(Instant.now().minus(Duration.ofMinutes(10)));
        when(pendingHitlRegistry.hasPendingForExecution(EXECUTION_ID)).thenReturn(true);

        service.sweep();

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.RUNNING);
        verify(sandboxExecutionRepository, never()).save(any());
    }

    @Test
    void sweep_hasPendingHitlForEnvironment() {
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(execution));
        when(pendingHitlRegistry.hasPendingForExecution(EXECUTION_ID)).thenReturn(true);

        assertThat(service.hasPendingHitl(ENV_ID)).isTrue();
        assertThat(service.hasPendingHitl(null)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasPendingHitl_queriesRunningAndIdleExecutions() {
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of());
        ArgumentCaptor<Collection<SandboxExecutionStatus>> statuses = ArgumentCaptor.forClass(Collection.class);

        assertThat(service.hasPendingHitl(ENV_ID)).isFalse();

        verify(sandboxExecutionRepository).findByEnvironmentIdAndStatusIn(eq(ENV_ID), statuses.capture());
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(SandboxExecutionStatus.RUNNING, SandboxExecutionStatus.IDLE);
    }

    @Test
    void hasPendingHitl_falseWhenNoExecutionHasPendingRequest() {
        SandboxExecution idle = new SandboxExecution();
        idle.setId(UUID.randomUUID());
        idle.setChat(execution.getChat());
        idle.setEnvironment(environment);
        idle.setStatus(SandboxExecutionStatus.IDLE);
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(execution, idle));
        when(pendingHitlRegistry.hasPendingForExecution(any())).thenReturn(false);

        assertThat(service.hasPendingHitl(ENV_ID)).isFalse();

        verify(pendingHitlRegistry).hasPendingForExecution(EXECUTION_ID);
        verify(pendingHitlRegistry).hasPendingForExecution(idle.getId());
    }

    @Test
    void hasPendingHitl_trueWhenAnyExecutionHasPendingRequest() {
        SandboxExecution idle = new SandboxExecution();
        idle.setId(UUID.randomUUID());
        idle.setChat(execution.getChat());
        idle.setEnvironment(environment);
        idle.setStatus(SandboxExecutionStatus.IDLE);
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(execution, idle));
        when(pendingHitlRegistry.hasPendingForExecution(EXECUTION_ID)).thenReturn(false);
        when(pendingHitlRegistry.hasPendingForExecution(idle.getId())).thenReturn(true);

        assertThat(service.hasPendingHitl(ENV_ID)).isTrue();
    }

    @Test
    void sweep_leavesRecentlyActiveExecutionRunning() {
        givenRunningExecutionLastActiveAt(Instant.now().minus(Duration.ofMinutes(1)));

        service.sweep();

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.RUNNING);
        verify(sandboxExecutionRepository, never()).save(any());
    }

    @Test
    void hasRunningExecutions_delegatesToRepository() {
        when(sandboxExecutionRepository.existsByEnvironmentIdAndStatus(ENV_ID, SandboxExecutionStatus.RUNNING))
                .thenReturn(true);

        assertThat(service.hasRunningExecutions(ENV_ID)).isTrue();
        assertThat(service.hasRunningExecutions(null)).isFalse();
    }
}
