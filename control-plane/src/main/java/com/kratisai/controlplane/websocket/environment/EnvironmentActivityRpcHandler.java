package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.event.SandboxExecutionActivityEvent;
import com.kratisai.controlplane.service.ExecutionActivityPersistenceService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Component
public class EnvironmentActivityRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.Activity, EnvironmentResponsePayload> {
    private static final Logger logger = LoggerFactory.getLogger(EnvironmentActivityRpcHandler.class);

    private final EnvironmentExecutionGuard executionGuard;
    private final ExecutionActivityPersistenceService activityPersistenceService;
    private final ApplicationEventPublisher eventPublisher;

    public EnvironmentActivityRpcHandler(
            EnvironmentExecutionGuard executionGuard,
            ExecutionActivityPersistenceService activityPersistenceService,
            ApplicationEventPublisher eventPublisher) {
        this.executionGuard = executionGuard;
        this.activityPersistenceService = activityPersistenceService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.Activity.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.Activity> getPayloadType() {
        return EnvironmentRpcPayload.Activity.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.Activity params) {
        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionGuard.requireExecutionInEnvironment(sessionId, execId);

        UUID teamId = execution.getChat().getTeam().getId();
        activityPersistenceService.recordActivity(
                execution.getId(),
                params.activityType(),
                params.description(),
                params.actionId(),
                params.status(),
                params.detail());
        eventPublisher.publishEvent(new SandboxExecutionActivityEvent(
                teamId,
                execution.getId(),
                params.activityType(),
                params.description(),
                params.actionId(),
                params.status(),
                params.detail()));

        return Flux.empty();
    }
}
