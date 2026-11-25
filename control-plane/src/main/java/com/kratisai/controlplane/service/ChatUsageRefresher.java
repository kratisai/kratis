package com.kratisai.controlplane.service;

import com.kratisai.controlplane.agentloop.AgentActivityListener;
import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.model.event.ChatTurnCompletedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "kratis.litellm.chat-usage-refresh",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ChatUsageRefresher implements AgentActivityListener {

    private static final Logger logger = LoggerFactory.getLogger(ChatUsageRefresher.class);

    private final ChatUsageSessionService chatUsageSessionService;
    private final ChatFluxRegistry chatFluxRegistry;
    private final TaskScheduler taskScheduler;
    private final Duration refreshInterval;
    private final ConcurrentMap<UUID, ScheduledFuture<?>> pendingRefreshWindows = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Boolean> refreshRequestedDuringWindow = new ConcurrentHashMap<>();

    public ChatUsageRefresher(
            ChatUsageSessionService chatUsageSessionService,
            ChatFluxRegistry chatFluxRegistry,
            @Qualifier("usageRefreshScheduler") TaskScheduler taskScheduler,
            LiteLLMProperties litellmProperties) {
        this.chatUsageSessionService = chatUsageSessionService;
        this.chatFluxRegistry = chatFluxRegistry;
        this.taskScheduler = taskScheduler;
        this.refreshInterval = litellmProperties.getUsageRefreshInterval();
    }

    @Override
    public void onAgentActivity(UUID chatId) {
        scheduleRefresh(chatId);
    }

    @EventListener
    public void onChatTurnCompleted(ChatTurnCompletedEvent event) {
        cancelPendingRefresh(event.chatId());
    }

    public void cancelAllPendingRefreshes() {
        for (UUID chatId : List.copyOf(pendingRefreshWindows.keySet())) {
            cancelPendingRefresh(chatId);
        }
    }

    private void cancelPendingRefresh(UUID chatId) {
        ScheduledFuture<?> pending = pendingRefreshWindows.remove(chatId);
        if (pending != null) {
            pending.cancel(false);
        }
        refreshRequestedDuringWindow.remove(chatId);
    }

    private void scheduleRefresh(UUID chatId) {
        if (pendingRefreshWindows.containsKey(chatId)) {
            refreshRequestedDuringWindow.put(chatId, Boolean.TRUE);
            return;
        }
        runRefresh(chatId);
        scheduleWindow(chatId);
    }

    private void scheduleWindow(UUID chatId) {
        ScheduledFuture<?> window = taskScheduler.schedule(
                () -> onRefreshWindowElapsed(chatId), Instant.now().plus(refreshInterval));
        pendingRefreshWindows.put(chatId, window);
    }

    private void onRefreshWindowElapsed(UUID chatId) {
        pendingRefreshWindows.remove(chatId);
        if (Boolean.TRUE.equals(refreshRequestedDuringWindow.remove(chatId))) {
            runRefresh(chatId);
            scheduleWindow(chatId);
        }
    }

    private void runRefresh(UUID chatId) {
        taskScheduler.schedule(() -> refresh(chatId), Instant.now());
    }

    private void refresh(UUID chatId) {
        if (!chatFluxRegistry.containsKey(chatId)) {
            return;
        }
        try {
            chatUsageSessionService.refreshUsageForChat(chatId);
        } catch (Exception e) {
            logger.warn("Failed to refresh LLM usage for chat {}: {}", chatId, e.getMessage());
        }
    }
}
