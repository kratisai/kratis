package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.event.SandboxExecutionAcpInitializedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

/**
 * Handles {@code env.acp_initialized} JSON-RPC notifications from the sidecar. The notification
 * reports the session established by the synchronous {@code env.launch_acp_agent} handshake; this
 * handler records the session metadata and does not dispatch a prompt.
 */
@Component
public class EnvironmentAcpInitializedRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.AcpInitialized, EnvironmentResponsePayload> {
    private static final Logger logger = LoggerFactory.getLogger(EnvironmentAcpInitializedRpcHandler.class);

    private final EnvironmentExecutionGuard executionGuard;
    private final ApplicationEventPublisher eventPublisher;

    public EnvironmentAcpInitializedRpcHandler(
            EnvironmentExecutionGuard executionGuard, ApplicationEventPublisher eventPublisher) {
        this.executionGuard = executionGuard;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.AcpInitialized.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.AcpInitialized> getPayloadType() {
        return EnvironmentRpcPayload.AcpInitialized.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.AcpInitialized params) {
        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionGuard.requireExecutionInEnvironment(sessionId, execId);

        String acpSessionId = params.sessionId();
        String agentName = params.agentName();
        String agentVersion = params.agentVersion();

        logger.info(
                "ACP initialized for execution {} (sessionId={}, agentName={}, agentVersion={})",
                execution.getId(),
                acpSessionId,
                agentName,
                agentVersion);

        eventPublisher.publishEvent(new SandboxExecutionAcpInitializedEvent(
                execution.getChat().getTeam().getId(), execution.getId(), acpSessionId, agentName, agentVersion));

        return Flux.empty();
    }
}
