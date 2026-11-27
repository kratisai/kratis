package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
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

        if (execution.getStatus() == SandboxExecutionStatus.COMPLETED) {
            // The execution was already settled (e.g. halted). A late env.complete must not reopen it.
            logger.warn("Ignoring env.complete for execution {} already settled as {}", execId, execution.getStatus());
            return Flux.empty();
        }

        int exitCode = params.exitCode();
        boolean deliberateStop = Boolean.TRUE.equals(params.terminated());
        boolean unexpectedProcessExit = !deliberateStop;
        EnvironmentStatus envStatusBeforeComplete =
                execution.getEnvironment() != null ? execution.getEnvironment().getStatus() : null;
        execution.setExitCode(exitCode);
        execution.setStatus(SandboxExecutionStatus.IDLE);
        execution.setCompletedAt(Instant.now());

        executionRepository.save(execution);

        // Any pending permission/elicitation request is moot once the agent process has stopped.
        sandboxExecutionService.cancelPendingPermissions(execution, "execution completed");
        sandboxExecutionService.forgetActivePrompt(execution.getId());

        UUID teamId = execution.getChat().getTeam().getId();

        if (unexpectedProcessExit) {
            String reason = params.reason() != null && !params.reason().isBlank()
                    ? params.reason()
                    : "Agent process exited unexpectedly (exit code " + exitCode + ")";
            activityPersistenceService.recordExecutionError(execution.getId(), reason, exitCode);
            // Only relaunch when an already-CONNECTED agent crashed; exits during PENDING_CONNECT
            // come from an in-flight launchAgent call that handles its own failure.
            if (execution.getEnvironment() != null && envStatusBeforeComplete == EnvironmentStatus.CONNECTED) {
                if (execution.getEnvironment().getType() == ExecutionEnvironmentType.SANDBOX) {
                    execution.getEnvironment().setStatus(EnvironmentStatus.PENDING_CONNECT);
                    execution.getEnvironment().setLastActivityAt(Instant.now());
                    eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.ENVIRONMENTS));
                }
                sandboxExecutionService.relaunchAgentAfterCrash(execution.getId());
            }
        }

        eventPublisher.publishEvent(new SandboxExecutionCompleteEvent(
                teamId, execution.getId(), exitCode, execution.getStatus(), params.reason(), unexpectedProcessExit));

        eventPublisher.publishEvent(
                new ExecutionStatusChangedEvent(teamId, execution.getChat().getId(), execution.getId()));

        return Flux.empty();
    }
}
