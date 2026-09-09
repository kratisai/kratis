package com.kratisai.controlplane.service;

import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionAcpInitializedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionActivityEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionOutputEvent;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Event-driven LLM usage refresher for running sandbox executions. Agent change events trigger a
 * LiteLLM usage query using a leading-edge throttle with a trailing catch-up: the first event
 * refreshes immediately (so usage appears promptly when a run starts) and opens a window; activity
 * during the window sets a flag, and when the window elapses we refresh again only if the flag is
 * set, keeping the window alive during continuous work. The refresh publishes
 * {@link ExecutionStatusChangedEvent} inside a transaction; the UI fan-out listens AFTER_COMMIT.
 * Completion is handled by {@link SandboxExecutionService} (final fetch before key revocation);
 * this class only cancels its pending refresh then.
 */
@Component
public class SandboxExecutionUsageRefresher {

    private static final Logger logger = LoggerFactory.getLogger(SandboxExecutionUsageRefresher.class);

    private final SandboxExecutionRepository executionRepository;
    private final VirtualKeyService virtualKeyService;
    private final ApplicationEventPublisher eventPublisher;
    private final TaskScheduler taskScheduler;
    private final TransactionTemplate transactionTemplate;
    private final Duration refreshInterval;

    /** Trailing window task per execution; a refresh runs at most once per window. */
    private final ConcurrentMap<UUID, ScheduledFuture<?>> pendingRefreshWindows = new ConcurrentHashMap<>();

    /** Set when activity arrives while an execution's window is open, requesting a trailing refresh. */
    private final ConcurrentMap<UUID, Boolean> refreshRequestedDuringWindow = new ConcurrentHashMap<>();

    public SandboxExecutionUsageRefresher(
            PlatformTransactionManager transactionManager,
            SandboxExecutionRepository executionRepository,
            VirtualKeyService virtualKeyService,
            ApplicationEventPublisher eventPublisher,
            TaskScheduler taskScheduler,
            LiteLLMProperties litellmProperties) {
        this.executionRepository = executionRepository;
        this.virtualKeyService = virtualKeyService;
        this.eventPublisher = eventPublisher;
        this.taskScheduler = taskScheduler;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.refreshInterval = litellmProperties.getUsageRefreshInterval();
    }

    @EventListener
    public void onSandboxExecutionActivityEvent(SandboxExecutionActivityEvent event) {
        scheduleRefresh(event.executionId());
    }

    @EventListener
    public void onSandboxExecutionOutputEvent(SandboxExecutionOutputEvent event) {
        scheduleRefresh(event.executionId());
    }

    @EventListener
    public void onSandboxExecutionAcpInitializedEvent(SandboxExecutionAcpInitializedEvent event) {
        scheduleRefresh(event.executionId());
    }

    @EventListener
    public void onSandboxExecutionCompleteEvent(SandboxExecutionCompleteEvent event) {
        // The final fetch and broadcast run in SandboxExecutionService before key revocation; a
        // pending refresh would race the revoke, so cancel it.
        ScheduledFuture<?> pending = pendingRefreshWindows.remove(event.executionId());
        if (pending != null) {
            pending.cancel(false);
        }
        refreshRequestedDuringWindow.remove(event.executionId());
    }

    private void scheduleRefresh(UUID executionId) {
        // Leading edge: if no window is open, refresh immediately so usage appears right away and
        // open a trailing window. Activity while the window is open just sets the flag — it never
        // postpones the window, so continuous agent work still yields periodic refreshes.
        if (pendingRefreshWindows.containsKey(executionId)) {
            refreshRequestedDuringWindow.put(executionId, Boolean.TRUE);
            return;
        }

        runRefresh(executionId);
        scheduleWindow(executionId);
    }

    private void scheduleWindow(UUID executionId) {
        ScheduledFuture<?> window = taskScheduler.schedule(
                () -> onRefreshWindowElapsed(executionId), Instant.now().plus(refreshInterval));
        pendingRefreshWindows.put(executionId, window);
    }

    private void onRefreshWindowElapsed(UUID executionId) {
        pendingRefreshWindows.remove(executionId);
        // Trailing catch-up: refresh once more and reopen the window only if activity arrived
        // while it was open; otherwise the run has gone quiet and we stop polling.
        if (Boolean.TRUE.equals(refreshRequestedDuringWindow.remove(executionId))) {
            runRefresh(executionId);
            scheduleWindow(executionId);
        }
    }

    private void runRefresh(UUID executionId) {
        // Run off the caller thread: the trigger events are fired inside request/transaction
        // threads, and the refresh performs a blocking LiteLLM call in its own transaction.
        taskScheduler.schedule(() -> refresh(executionId), Instant.now());
    }

    private void refresh(UUID executionId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                SandboxExecution execution =
                        executionRepository.findByIdForUpdate(executionId).orElse(null);
                if (execution == null
                        || (execution.getStatus() != SandboxExecutionStatus.RUNNING
                                && execution.getStatus() != SandboxExecutionStatus.IDLE)
                        || execution.getUsage().getVirtualKey() == null) {
                    return;
                }
                LlmUsageSnapshot snapshot =
                        virtualKeyService.fetchUsage(execution.getUsage().getVirtualKey());
                execution.getUsage().apply(snapshot);
                executionRepository.save(execution);
                eventPublisher.publishEvent(new ExecutionStatusChangedEvent(
                        execution.getChat().getTeam().getId(),
                        execution.getChat().getId(),
                        executionId));
            });
        } catch (Exception e) {
            logger.warn("Failed to refresh LLM usage for execution {}: {}", executionId, e.getMessage());
        }
    }
}
