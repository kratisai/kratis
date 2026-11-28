package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.service.SandboxPublishService;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

/**
 * Handles {@code env.preview}: the sidecar reports a sandbox-local port serving an application.
 * The control plane owns the address translation, records the preview in the task activity log,
 * broadcasts it to the web client, and returns the control-plane-origin URL the user opens.
 */
@Component
public class EnvironmentPreviewRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.Preview, EnvironmentResponsePayload> {

    private final EnvironmentExecutionGuard executionGuard;
    private final SandboxPublishService publishService;

    public EnvironmentPreviewRpcHandler(
            EnvironmentExecutionGuard executionGuard, SandboxPublishService publishService) {
        this.executionGuard = executionGuard;
        this.publishService = publishService;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.Preview.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.Preview> getPayloadType() {
        return EnvironmentRpcPayload.Preview.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.Preview params) {
        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionGuard.requireExecutionInEnvironment(sessionId, execId);
        return Flux.just(publishService.publishPreview(execution, params));
    }
}
