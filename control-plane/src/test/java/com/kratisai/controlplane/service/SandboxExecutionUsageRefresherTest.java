package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.ResourcelessTransactionManager;
import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.LlmUsage;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionAcpInitializedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionActivityEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionOutputEvent;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SandboxExecutionUsageRefresherTest {

    @Mock
    private SandboxExecutionRepository executionRepository;

    @Mock
    private VirtualKeyService virtualKeyService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private SandboxExecution execution;

    @Mock
    private ChatEntity chat;

    @Mock
    private Team team;

    /** Leading-edge refreshes scheduled at (approximately) now. */
    private final List<Runnable> immediateTasks = new ArrayList<>();

    /** Trailing window tasks scheduled at now + refresh interval. */
    private final List<Runnable> windowTasks = new ArrayList<>();

    private final List<Instant> windowTimes = new ArrayList<>();
    private final List<ScheduledFuture<?>> windowFutures = new ArrayList<>();

    private SandboxExecutionUsageRefresher refresher;
    private LlmUsage usage;
    private UUID teamId;
    private UUID chatId;
    private UUID executionId;

    @BeforeEach
    void setUp() {
        LiteLLMProperties properties = new LiteLLMProperties();
        properties.setUsageRefreshInterval(Duration.ofSeconds(15));
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            Instant when = invocation.getArgument(1);
            ScheduledFuture<?> future = Mockito.mock(ScheduledFuture.class);
            if (when.isAfter(Instant.now().plusSeconds(10))) {
                windowTasks.add(task);
                windowTimes.add(when);
                windowFutures.add(future);
            } else {
                immediateTasks.add(task);
            }
            return future;
        });
        refresher = new SandboxExecutionUsageRefresher(
                new ResourcelessTransactionManager(),
                executionRepository,
                virtualKeyService,
                eventPublisher,
                taskScheduler,
                properties);

        teamId = UUID.randomUUID();
        chatId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        usage = LlmUsage.withKey("sk-test-key");
        when(team.getId()).thenReturn(teamId);
        when(chat.getId()).thenReturn(chatId);
        when(chat.getTeam()).thenReturn(team);
        when(execution.getChat()).thenReturn(chat);
        when(execution.getUsage()).thenReturn(usage);
        when(executionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.of(execution));
    }

    private void stubRunningExecution() {
        when(execution.getStatus()).thenReturn(SandboxExecutionStatus.RUNNING);
        when(virtualKeyService.fetchUsage("sk-test-key")).thenReturn(new LlmUsageSnapshot(0.42, 1000L, 600L, 400L));
    }

    private SandboxExecutionActivityEvent activityEvent() {
        return new SandboxExecutionActivityEvent(teamId, executionId, null, "running", "tool-call-1", null, null);
    }

    private void runImmediateTask(int index) {
        immediateTasks.get(index).run();
    }

    private void runWindowTask(int index) {
        windowTasks.get(index).run();
    }

    @Test
    void activityEvent_refreshesImmediatelyAndPublishesStatusChangedEvent() {
        stubRunningExecution();
        Instant before = Instant.now();

        refresher.onSandboxExecutionActivityEvent(activityEvent());

        // Leading edge: the refresh is dispatched right away and a trailing window is opened.
        assertThat(immediateTasks).hasSize(1);
        assertThat(windowTasks).hasSize(1);
        assertThat(Duration.between(before, windowTimes.getFirst()))
                .isBetween(Duration.ofSeconds(14), Duration.ofSeconds(16));

        runImmediateTask(0);

        verify(virtualKeyService).fetchUsage("sk-test-key");
        verify(executionRepository).save(execution);
        assertThat(usage.getTotalSpend()).isEqualTo(0.42);
        assertThat(usage.getTotalTokens()).isEqualTo(1000L);
        assertThat(usage.getUsageLastUpdatedAt()).isNotNull();

        ArgumentCaptor<ExecutionStatusChangedEvent> captor = ArgumentCaptor.forClass(ExecutionStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ExecutionStatusChangedEvent event = captor.getValue();
        assertThat(event.teamId()).isEqualTo(teamId);
        assertThat(event.chatId()).isEqualTo(chatId);
        assertThat(event.executionId()).isEqualTo(executionId);
    }

    @Test
    void quietWindow_doesNotRefreshAgainOrReopen() {
        stubRunningExecution();

        refresher.onSandboxExecutionActivityEvent(activityEvent());
        runImmediateTask(0);

        // No activity arrived while the window was open: the trailing refresh must not run and
        // no new window is opened.
        runWindowTask(0);

        verify(virtualKeyService, Mockito.times(1)).fetchUsage("sk-test-key");
        assertThat(windowTasks).hasSize(1);
        assertThat(immediateTasks).hasSize(1);
    }

    @Test
    void activityDuringWindow_runsTrailingRefreshAndReopensWindow() {
        stubRunningExecution();

        refresher.onSandboxExecutionActivityEvent(activityEvent());
        refresher.onSandboxExecutionActivityEvent(activityEvent());
        refresher.onSandboxExecutionOutputEvent(new SandboxExecutionOutputEvent(teamId, executionId, "line\n", null));

        // Activity while the window is open only sets the flag; the window is never postponed.
        assertThat(immediateTasks).hasSize(1);
        assertThat(windowTasks).hasSize(1);
        verify(windowFutures.getFirst(), never()).cancel(anyBoolean());

        // The leading-edge refresh runs, then the window elapses with the flag set: the trailing
        // refresh runs and the window reopens.
        runImmediateTask(0);
        runWindowTask(0);
        assertThat(immediateTasks).hasSize(2);
        assertThat(windowTasks).hasSize(2);

        runImmediateTask(1);
        verify(virtualKeyService, Mockito.times(2)).fetchUsage("sk-test-key");
    }

    @Test
    void outputAndAcpInitializedEvents_alsoTriggerRefresh() {
        stubRunningExecution();

        refresher.onSandboxExecutionOutputEvent(new SandboxExecutionOutputEvent(teamId, executionId, "line\n", null));
        assertThat(immediateTasks).hasSize(1);

        // Quiet window: trailing edge does not run, so the next event opens a fresh leading edge.
        runWindowTask(0);
        assertThat(immediateTasks).hasSize(1);

        refresher.onSandboxExecutionAcpInitializedEvent(
                new SandboxExecutionAcpInitializedEvent(teamId, executionId, "sess-1", "goose", "1.0"));

        assertThat(immediateTasks).hasSize(2);
        assertThat(windowTasks).hasSize(2);
    }

    @Test
    void completeEvent_cancelsPendingWindow() {
        stubRunningExecution();
        refresher.onSandboxExecutionActivityEvent(activityEvent());
        assertThat(windowTasks).hasSize(1);
        verify(windowFutures.getFirst(), never()).cancel(anyBoolean());

        // The completion transaction commits the terminal status before the complete event fires,
        // so the already-dispatched refresh skips the fetch via the status guard.
        when(execution.getStatus()).thenReturn(SandboxExecutionStatus.COMPLETED);
        refresher.onSandboxExecutionCompleteEvent(
                new SandboxExecutionCompleteEvent(teamId, executionId, 0, SandboxExecutionStatus.COMPLETED));

        verify(windowFutures.getFirst()).cancel(false);
        runImmediateTask(0);
        verify(virtualKeyService, never()).fetchUsage(any());
    }

    @Test
    void executionWithoutVirtualKey_skipsFetch() {
        usage.setVirtualKey(null);
        when(execution.getStatus()).thenReturn(SandboxExecutionStatus.RUNNING);

        refresher.onSandboxExecutionActivityEvent(activityEvent());
        runImmediateTask(0);

        verify(virtualKeyService, never()).fetchUsage(any());
        verify(executionRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void fetchFailure_doesNotPublish() {
        when(execution.getStatus()).thenReturn(SandboxExecutionStatus.RUNNING);
        when(virtualKeyService.fetchUsage("sk-test-key")).thenThrow(new RuntimeException("litellm down"));

        refresher.onSandboxExecutionActivityEvent(activityEvent());
        runImmediateTask(0);

        verify(eventPublisher, never()).publishEvent(any());
        verify(executionRepository, never()).save(any());
    }

    @Test
    void refreshForMissingExecution_doesNothing() {
        when(executionRepository.findByIdForUpdate(executionId)).thenReturn(Optional.empty());

        refresher.onSandboxExecutionActivityEvent(activityEvent());
        runImmediateTask(0);

        verify(virtualKeyService, never()).fetchUsage(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
