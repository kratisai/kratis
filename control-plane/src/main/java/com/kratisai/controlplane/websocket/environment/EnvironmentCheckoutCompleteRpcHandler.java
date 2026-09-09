package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.CheckoutStatus;
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
 * Handles {@code env.checkout_complete} JSON-RPC notifications from the sidecar. Resumes execution
 * of the sandbox command if cloning succeeded, or fails the execution if cloning failed.
 */
@Component
public class EnvironmentCheckoutCompleteRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.CheckoutComplete, EnvironmentResponsePayload> {
    private static final Logger logger = LoggerFactory.getLogger(EnvironmentCheckoutCompleteRpcHandler.class);

    private final EnvironmentExecutionGuard executionGuard;
    private final SandboxExecutionService sandboxExecutionService;

    public EnvironmentCheckoutCompleteRpcHandler(
            EnvironmentExecutionGuard executionGuard, SandboxExecutionService sandboxExecutionService) {
        this.executionGuard = executionGuard;
        this.sandboxExecutionService = sandboxExecutionService;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.CheckoutComplete.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.CheckoutComplete> getPayloadType() {
        return EnvironmentRpcPayload.CheckoutComplete.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.CheckoutComplete params) {
        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionGuard.requireExecutionInEnvironment(sessionId, execId);

        UUID executionId = execution.getId();
        if (params.status() == CheckoutStatus.SUCCESS) {
            logger.info(
                    "Checkout complete for execution {}: cloned successfully at commit {}",
                    executionId,
                    params.commitHash());
            sandboxExecutionService.resumeAfterCheckout(executionId);
        } else {
            logger.error("Checkout failed for execution {}: {}", executionId, params.error());
            sandboxExecutionService.failExecution(executionId);
        }

        return Flux.empty();
    }
}
