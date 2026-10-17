package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.ExecutionActivityPersistenceService;
import com.kratisai.controlplane.service.SandboxExecutionService;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Component
public class EnvironmentCompleteRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.Complete, EnvironmentResponsePayload> {
    private static final Logger logger = LoggerFactory.getLogger(EnvironmentCompleteRpcHandler.class);

    private final SandboxExecutionRepository executionRepository;
    private final EnvironmentExecutionGuard executionGuard;
    private final ApplicationEventPublisher eventPublisher;
    private final SandboxExecutionService sandboxExecutionService;
    private final ExecutionActivityPersistenceService activityPersistenceService;

    public EnvironmentCompleteRpcHandler(
            SandboxExecutionRepository executionRepository,
            EnvironmentExecutionGuard executionGuard,
            ApplicationEventPublisher eventPublisher,
            SandboxExecutionService sandboxExecutionService,
            ExecutionActivityPersistenceService activityPersistenceService) {
        this.executionRepository = executionRepository;
        this.executionGuard = executionGuard;
        this.eventPublisher = eventPublisher;
        this.sandboxExecutionService = sandboxExecutionService;
        this.activityPersistenceService = activityPersistenceService;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.Complete.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.Complete> getPayloadType() {
        return EnvironmentRpcPayload.Complete.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.Complete params) {
        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionGuard.requireExecutionInEnvironment(sessionId, execId);

        if (execution.getStatus() == SandboxExecutionStatus.COMPLETED
                || execution.getStatus() == SandboxExecutionStatus.FAILED) {
            // The execution was already settled (e.g. halted). A late env.complete must not reopen it.
            logger.warn("Ignoring env.complete for execution {} already settled as {}", execId, execution.getStatus());
            return Flux.empty();
        }

        int exitCode = params.exitCode();
        boolean deliberateStop = Boolean.TRUE.equals(params.terminated());
        boolean failed = exitCode != 0 && !deliberateStop;
        execution.setExitCode(exitCode);
        if (failed) {
            execution.setStatus(SandboxExecutionStatus.FAILED);
            execution.setCompletedAt(Instant.now());
        } else {
            // A pause is not a completion: the execution stays IDLE.
            execution.setStatus(SandboxExecutionStatus.IDLE);
        }

        executionRepository.save(execution);

        // Any pending permission/elicitation request is moot once the agent process has stopped.
        sandboxExecutionService.cancelPendingPermissions(execution, "execution completed");
        sandboxExecutionService.forgetActivePrompt(execution.getId());

        UUID teamId = execution.getChat().getTeam().getId();

        // Persist a faithful ERROR activity for any abnormal termination so the activity log
        // records the agent's own error message. A deliberate stop reports a non-zero exit code
        // when the process is signalled, but is an expected pause, not an agent failure.
        if (failed) {
            activityPersistenceService.recordExecutionError(execution.getId(), params.reason(), exitCode);
        }

        eventPublisher.publishEvent(new SandboxExecutionCompleteEvent(
                teamId, execution.getId(), exitCode, execution.getStatus(), params.reason()));

        eventPublisher.publishEvent(
                new ExecutionStatusChangedEvent(teamId, execution.getChat().getId(), execution.getId()));

        return Flux.empty();
    }
}
