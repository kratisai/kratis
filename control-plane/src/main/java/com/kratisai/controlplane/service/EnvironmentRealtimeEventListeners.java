package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.HitlResult;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class EnvironmentRealtimeEventListeners {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentRealtimeEventListeners.class);

    private final PendingHitlRegistry pendingHitlRegistry;
    private final EnvironmentRpcClient environmentRpcClient;

    public EnvironmentRealtimeEventListeners(
            PendingHitlRegistry pendingHitlRegistry, EnvironmentRpcClient environmentRpcClient) {
        this.pendingHitlRegistry = pendingHitlRegistry;
        this.environmentRpcClient = environmentRpcClient;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSandboxExecutionHitlResolvedEvent(SandboxExecutionHitlResolvedEvent event) {
        PendingHitlRegistry.PendingHitl pending = pendingHitlRegistry.remove(event.executionId());
        if (pending != null) {
            replyToSidecar(pending, event.executionId(), event.response(), event.optionId(), event.content());
        } else {
            logger.debug(
                    "No pending HITL request in registry for execution {} (already claimed or timed out)",
                    event.executionId());
        }
    }

    public void replyToSidecar(
            PendingHitlRegistry.PendingHitl pending,
            UUID executionId,
            HitlResponse response,
            String optionId,
            Map<String, Object> content) {
        HitlResult result =
                switch (response) {
                    case APPROVED -> HitlResult.approved(optionId);
                    case ANSWERED -> HitlResult.answered(content);
                    case DECLINED, CANCELLED -> HitlResult.cancelled();
                };
        sendToSidecar(pending, executionId, result);
    }

    private void sendToSidecar(PendingHitlRegistry.PendingHitl pending, UUID executionId, HitlResult result) {
        if (pending == null || !pending.session().isOpen()) {
            logger.info("No open sidecar session for HITL reply on execution {}", executionId);
            return;
        }
        try {
            environmentRpcClient.reply(pending.session().getId(), pending.requestId(), result);
            logger.info(
                    "Dispatched HITL response (response={}) back to sidecar for execution {}",
                    result.response(),
                    executionId);
        } catch (Exception e) {
            logger.error("Failed to send HITL response JSON-RPC to sidecar", e);
        }
    }
}
