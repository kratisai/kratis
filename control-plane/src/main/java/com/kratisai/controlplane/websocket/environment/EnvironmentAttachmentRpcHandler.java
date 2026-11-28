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
 * Handles {@code env.attachment}: the sidecar uploads an image or markdown file with simple
 * metadata. The control plane validates the kind and content type, stores the bytes in blob
 * storage under an unguessable id, records the attachment in the task activity log, broadcasts it
 * to the web client, and returns the inline view URL.
 */
@Component
public class EnvironmentAttachmentRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.Attachment, EnvironmentResponsePayload> {

    private final EnvironmentExecutionGuard executionGuard;
    private final SandboxPublishService publishService;

    public EnvironmentAttachmentRpcHandler(
            EnvironmentExecutionGuard executionGuard, SandboxPublishService publishService) {
        this.executionGuard = executionGuard;
        this.publishService = publishService;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.Attachment.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.Attachment> getPayloadType() {
        return EnvironmentRpcPayload.Attachment.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.Attachment params) {
        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionGuard.requireExecutionInEnvironment(sessionId, execId);
        return Flux.just(publishService.publishAttachment(execution, params));
    }
}
