package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.OutputStream;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.event.SandboxExecutionOutputEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

/**
 * Handles {@code env.sidecar_error} notifications so connector-side failures are visible to the
 * control plane and users. The executionId is optional (connection-level failures may predate any
 * execution); when present and resolvable the error is echoed into the execution's stderr stream.
 */
@Component
public class EnvironmentSidecarErrorRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.SidecarError, EnvironmentResponsePayload> {
    private static final Logger logger = LoggerFactory.getLogger(EnvironmentSidecarErrorRpcHandler.class);

    private final EnvironmentExecutionGuard executionGuard;
    private final ApplicationEventPublisher eventPublisher;

    public EnvironmentSidecarErrorRpcHandler(
            EnvironmentExecutionGuard executionGuard, ApplicationEventPublisher eventPublisher) {
        this.executionGuard = executionGuard;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.SidecarError.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.SidecarError> getPayloadType() {
        return EnvironmentRpcPayload.SidecarError.class;
    }

    @Override
    @Transactional(readOnly = true)
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.SidecarError params) {
        logger.warn(
                "Sidecar reported error: kind={} message={} executionId={} closeCode={} closeReason={}",
                params.kind(),
                params.message(),
                params.executionId(),
                params.closeCode(),
                params.closeReason());

        if (params.executionId() != null) {
            try {
                UUID execId = UUID.fromString(params.executionId());
                SandboxExecution execution = executionGuard.requireExecutionInEnvironment(sessionId, execId);
                eventPublisher.publishEvent(new SandboxExecutionOutputEvent(
                        execution.getChat().getTeam().getId(),
                        execution.getId(),
                        "[Kratis] sidecar error (" + params.kind().getValue() + "): " + params.message() + "\n",
                        OutputStream.STDERR));
            } catch (Exception e) {
                logger.debug(
                        "Sidecar error references unknown/invalid execution {}; logging only", params.executionId());
            }
        }
        return Flux.empty();
    }
}
