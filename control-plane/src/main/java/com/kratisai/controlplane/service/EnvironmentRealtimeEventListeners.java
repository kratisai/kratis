package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.HitlResolution;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.WebSocketSession;

@Component
public class EnvironmentRealtimeEventListeners {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentRealtimeEventListeners.class);

    private final SandboxExecutionRepository executionRepository;
    private final EnvironmentSessionRegistry sessionRegistry;
    private final EnvironmentRpcClient environmentRpcClient;

    public EnvironmentRealtimeEventListeners(
            SandboxExecutionRepository executionRepository,
            EnvironmentSessionRegistry sessionRegistry,
            EnvironmentRpcClient environmentRpcClient) {
        this.executionRepository = executionRepository;
        this.sessionRegistry = sessionRegistry;
        this.environmentRpcClient = environmentRpcClient;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSandboxExecutionHitlResolvedEvent(SandboxExecutionHitlResolvedEvent event) {
        HitlResolution result = event.result();
        UUID environmentId = executionRepository
                .findEnvironmentIdByExecutionId(result.executionId())
                .orElse(null);
        if (environmentId == null) {
            logger.debug(
                    "No environment for resolved HITL execution {} actionId='{}'",
                    result.executionId(),
                    result.actionId());
            return;
        }
        WebSocketSession session = sessionRegistry.getSessionForEnvironment(environmentId);
        if (session == null || !session.isOpen()) {
            logger.info(
                    "No open sidecar session for HITL resolution on execution {} (environment {})",
                    result.executionId(),
                    environmentId);
            return;
        }
        try {
            environmentRpcClient.send(
                    environmentId,
                    new EnvironmentRpcPayload.HitlResolved(
                            result.actionId(), result.response(), result.optionId(), result.content()));
            logger.info(
                    "Dispatched env.hitl_resolved (response={}) to sidecar for execution {}",
                    result.response(),
                    result.executionId());
        } catch (Exception e) {
            logger.error("Failed to dispatch env.hitl_resolved to sidecar", e);
        }
    }
}
