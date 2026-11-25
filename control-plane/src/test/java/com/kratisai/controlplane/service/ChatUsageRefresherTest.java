package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.model.event.ChatTurnCompletedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.TaskScheduler;

class ChatUsageRefresherTest {

    private ChatUsageSessionService usageSessionService;
    private ChatFluxRegistry chatFluxRegistry;
    private TaskScheduler taskScheduler;
    private final List<Runnable> immediateTasks = new ArrayList<>();
    private final List<Runnable> windowTasks = new ArrayList<>();
    private final List<ScheduledFuture<?>> windowFutures = new ArrayList<>();
    private ChatUsageRefresher refresher;
    private UUID chatId;
    private UUID teamId;

    @BeforeEach
    void setUp() {
        usageSessionService = mock(ChatUsageSessionService.class);
        chatFluxRegistry = mock(ChatFluxRegistry.class);
        taskScheduler = mock(TaskScheduler.class);
        LiteLLMProperties properties = new LiteLLMProperties();
        properties.setUsageRefreshInterval(Duration.ofSeconds(15));
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            Instant when = invocation.getArgument(1);
            ScheduledFuture<?> future = Mockito.mock(ScheduledFuture.class);
            if (when.isAfter(Instant.now().plusSeconds(10))) {
                windowTasks.add(task);
                windowFutures.add(future);
            } else {
                immediateTasks.add(task);
            }
            return future;
        });
        refresher = new ChatUsageRefresher(usageSessionService, chatFluxRegistry, taskScheduler, properties);
        chatId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        when(chatFluxRegistry.containsKey(chatId)).thenReturn(true);
    }

    @Test
    void activity_refreshesOnLeadingEdgeAndOnceAfterActiveWindow() {
        refresher.onAgentActivity(chatId);
        assertThat(immediateTasks).hasSize(1);
        assertThat(windowTasks).hasSize(1);
        immediateTasks.getFirst().run();
        verify(usageSessionService).refreshUsageForChat(chatId);

        refresher.onAgentActivity(chatId);
        runWindow(0);
        assertThat(immediateTasks).hasSize(2);
        assertThat(windowTasks).hasSize(2);
        immediateTasks.get(1).run();
        verify(usageSessionService, times(2)).refreshUsageForChat(chatId);
    }

    @Test
    void quietWindow_doesNotRepeatAndTurnCompletionCancelsWindow() {
        refresher.onAgentActivity(chatId);
        runWindow(0);
        assertThat(immediateTasks).hasSize(1);
        refresher.onAgentActivity(chatId);
        refresher.onChatTurnCompleted(new ChatTurnCompletedEvent(teamId, chatId));
        verify(windowFutures.get(1)).cancel(false);
        runWindow(1);
        assertThat(immediateTasks).hasSize(2);
        verify(windowFutures.get(0), never()).cancel(anyBoolean());
    }

    @Test
    void refreshForInactiveChatDoesNothing() {
        when(chatFluxRegistry.containsKey(chatId)).thenReturn(false);
        refresher.onAgentActivity(chatId);
        immediateTasks.getFirst().run();
        verify(usageSessionService, never()).refreshUsageForChat(any());
    }

    @Test
    void refreshFailureDoesNotPropagate() {
        doThrow(new RuntimeException("LiteLLM unavailable"))
                .when(usageSessionService)
                .refreshUsageForChat(chatId);
        refresher.onAgentActivity(chatId);
        immediateTasks.getFirst().run();
        verify(usageSessionService).refreshUsageForChat(chatId);
    }

    @Test
    void cancelAllPendingRefreshesCancelsWindows() {
        refresher.onAgentActivity(chatId);
        refresher.cancelAllPendingRefreshes();
        verify(windowFutures.getFirst()).cancel(false);
        runWindow(0);
        assertThat(immediateTasks).hasSize(1);
    }

    private void runWindow(int index) {
        windowTasks.get(index).run();
    }
}
