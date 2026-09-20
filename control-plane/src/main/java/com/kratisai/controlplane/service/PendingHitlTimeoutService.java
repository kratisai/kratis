package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlResolvedResult;
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

    public PendingHitlTimeoutService(
            PendingHitlRegistry pendingHitlRegistry,
            ApplicationEventPublisher eventPublisher,
            EnvironmentRealtimeEventListeners environmentListeners) {
        this.pendingHitlRegistry = pendingHitlRegistry;
        this.eventPublisher = eventPublisher;
        this.environmentListeners = environmentListeners;
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
                }
            });
        }
    }
}
