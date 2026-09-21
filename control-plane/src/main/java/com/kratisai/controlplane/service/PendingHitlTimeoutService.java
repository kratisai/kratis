package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlResolvedResult;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PendingHitlTimeoutService {

    private static final Logger logger = LoggerFactory.getLogger(PendingHitlTimeoutService.class);

    private final PendingHitlRegistry pendingHitlRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final EnvironmentRealtimeEventListeners environmentListeners;
    private final SandboxExecutionService sandboxExecutionService;

    public PendingHitlTimeoutService(
            PendingHitlRegistry pendingHitlRegistry,
            ApplicationEventPublisher eventPublisher,
            EnvironmentRealtimeEventListeners environmentListeners,
            SandboxExecutionService sandboxExecutionService) {
        this.pendingHitlRegistry = pendingHitlRegistry;
        this.eventPublisher = eventPublisher;
        this.environmentListeners = environmentListeners;
        this.sandboxExecutionService = sandboxExecutionService;
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void cleanupExpiredHitl() {
        Map<UUID, PendingHitlRegistry.PendingHitl> expired = pendingHitlRegistry.removeExpired();

        for (Map.Entry<UUID, PendingHitlRegistry.PendingHitl> entry : expired.entrySet()) {
            UUID executionId = entry.getKey();
            PendingHitlRegistry.PendingHitl request = entry.getValue();

            logger.info(
                    "Timing out pending HITL request for execution {} (hitlId='{}', kind={}, team={})",
                    executionId,
                    request.request().hitlId(),
                    request.request().kind(),
                    request.teamId());

            eventPublisher.publishEvent(new SandboxExecutionHitlResolvedEvent(
                    request.teamId(),
                    new ExecutionHitlResolvedResult(
                            executionId,
                            request.request().hitlId(),
                            request.request().kind(),
                            HitlResponse.CANCELLED,
                            null,
                            null,
                            null,
                            "System (timeout)")));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    environmentListeners.replyToSidecar(request, executionId, HitlResponse.CANCELLED, null, null);
                    try {
                        sandboxExecutionService.dispatchSystemSteering(executionId, timeoutPrompt(request));
                    } catch (Exception e) {
                        logger.warn("Failed to dispatch timeout steering for execution {}", executionId, e);
                    }
                }
            });
        }
    }

    static String timeoutPrompt(PendingHitlRegistry.PendingHitl pending) {
        String subject = pending.request().kind() == HitlKind.QUESTION ? "question" : "approval";
        String message = pending.request().message();
        String quoted = message == null || message.isBlank() ? "" : " (\"" + abbreviate(message, 200) + "\")";
        return ("System notice: the %s request%s timed out because no response was received; nobody was available "
                        + "to answer, so this is not a rejection. Continue the task without performing that action, "
                        + "or choose a safe alternative if it was essential.")
                .formatted(subject, quoted);
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
