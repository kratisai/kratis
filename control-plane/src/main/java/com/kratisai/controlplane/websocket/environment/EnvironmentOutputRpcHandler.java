package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.event.SandboxExecutionOutputEvent;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Component
public class EnvironmentOutputRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.Output, EnvironmentResponsePayload> {

    private final EnvironmentExecutionGuard executionGuard;
    private final ApplicationEventPublisher eventPublisher;

    public EnvironmentOutputRpcHandler(
            EnvironmentExecutionGuard executionGuard, ApplicationEventPublisher eventPublisher) {
        this.executionGuard = executionGuard;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.Output.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.Output> getPayloadType() {
        return EnvironmentRpcPayload.Output.class;
    }

    @Override
    @Transactional(readOnly = true)
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.Output params) {
        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionGuard.requireExecutionInEnvironment(sessionId, execId);

        UUID teamId = execution.getChat().getTeam().getId();
        eventPublisher.publishEvent(
                new SandboxExecutionOutputEvent(teamId, execution.getId(), params.line() + "\n", params.stream()));

        return Flux.empty();
    }
}
