package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.service.SandboxExecutionService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

/**
 * Handles {@code env.acp_prompt_complete} JSON-RPC notifications from the sidecar. This notification
 * is sent when a session/prompt completes with a stopReason. The handler maps the stopReason to an
 * execution status (COMPLETED or FAILED) and persists the result. The sidecar agent remains alive
 * and can receive more prompts or be cancelled via env.acp_cancel.
 */
@Component
public class EnvironmentAcpPromptCompleteRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.AcpPromptComplete, EnvironmentResponsePayload> {
    private static final Logger logger = LoggerFactory.getLogger(EnvironmentAcpPromptCompleteRpcHandler.class);

    private final EnvironmentExecutionGuard executionGuard;
    private final SandboxExecutionService sandboxExecutionService;

    public EnvironmentAcpPromptCompleteRpcHandler(
            EnvironmentExecutionGuard executionGuard, SandboxExecutionService sandboxExecutionService) {
        this.executionGuard = executionGuard;
        this.sandboxExecutionService = sandboxExecutionService;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.AcpPromptComplete.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.AcpPromptComplete> getPayloadType() {
        return EnvironmentRpcPayload.AcpPromptComplete.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.AcpPromptComplete params) {
        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionGuard.requireExecutionInEnvironment(sessionId, execId);

        var stopReason = params.stopReason();
        String acpSessionId = params.sessionId();

        logger.info(
                "ACP prompt complete for execution {} (sessionId={}, stopReason={}). Completing execution.",
                execution.getId(),
                acpSessionId,
                stopReason);

        sandboxExecutionService.completeAcpPrompt(execution.getId(), stopReason);

        return Flux.empty();
    }
}
