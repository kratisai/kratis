package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.ApprovalOptionKind;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.HitlResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.PermissionOption;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxPermissionRule;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlRequiredEvent;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.SandboxPermissionRuleRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.PendingHitlRegistry;
import com.kratisai.controlplane.service.SandboxPermissionService;
import com.kratisai.controlplane.service.SandboxPermissionService.RuleDecision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Component
public class EnvironmentHitlRequestRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.HitlRequest, EnvironmentResponsePayload> {
    private static final Logger logger = LoggerFactory.getLogger(EnvironmentHitlRequestRpcHandler.class);

    private final EnvironmentSessionRegistry sessionRegistry;
    private final SandboxPermissionRuleRepository ruleRepository;
    private final ExecutionEnvironmentRepository environmentRepository;
    private final SandboxExecutionRepository executionRepository;
    private final EnvironmentExecutionGuard executionGuard;
    private final PendingHitlRegistry pendingHitlRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public EnvironmentHitlRequestRpcHandler(
            EnvironmentSessionRegistry sessionRegistry,
            SandboxPermissionRuleRepository ruleRepository,
            ExecutionEnvironmentRepository environmentRepository,
            SandboxExecutionRepository executionRepository,
            EnvironmentExecutionGuard executionGuard,
            PendingHitlRegistry pendingHitlRegistry,
            ApplicationEventPublisher eventPublisher) {
        this.sessionRegistry = sessionRegistry;
        this.ruleRepository = ruleRepository;
        this.environmentRepository = environmentRepository;
        this.executionRepository = executionRepository;
        this.executionGuard = executionGuard;
        this.pendingHitlRegistry = pendingHitlRegistry;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.HitlRequest.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.HitlRequest> getPayloadType() {
        return EnvironmentRpcPayload.HitlRequest.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.HitlRequest params) {
        logger.info("Received env.hitl_request from session {} with request id {}", sessionId, requestId);

        Optional<UUID> envIdOpt = sessionRegistry.getEnvironmentId(sessionId);
        if (envIdOpt.isEmpty()) {
            logger.warn("Received env.hitl_request from unmapped session: {}", sessionId);
            throw new RpcErrorException(
                    JsonRpcError.error(-32001, "Registration required", "Environment session not registered"));
        }

        UUID envId = envIdOpt.get();
        Optional<ExecutionEnvironment> envOpt = environmentRepository.findById(envId);
        if (envOpt.isEmpty()) {
            throw new RpcErrorException(
                    JsonRpcError.error(-32002, "Environment not found", "Environment not found in repository"));
        }
        ExecutionEnvironment env = envOpt.get();
        UUID teamId = env.getTeam().getId();

        return switch (params.kind()) {
            case APPROVAL -> handleApproval(sessionId, requestId, params, envId, teamId);
            case QUESTION -> handleQuestion(sessionId, requestId, params, envId, teamId);
        };
    }

    private Flux<EnvironmentResponsePayload> handleApproval(
            String sessionId, Object requestId, EnvironmentRpcPayload.HitlRequest params, UUID envId, UUID teamId) {
        if (params.command() == null || params.command().isBlank()) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("Invalid params: 'command' is required"));
        }

        String command = params.command();

        logger.debug("Checking permission rules for team {} and command '{}'", teamId, command);

        List<SandboxPermissionRule> rules = ruleRepository.findByTeamId(teamId);
        RuleDecision decision = SandboxPermissionService.evaluate(rules, command);

        if (decision == RuleDecision.DENY) {
            logger.info("Command '{}' rejected by DENY permission rule for team {}", command, teamId);
            return Flux.just(HitlResult.declined());
        }

        if (decision == RuleDecision.ALLOW) {
            String optionId = selectAllowOptionId(params.options());
            if (optionId == null) {
                logger.warn(
                        "Auto-approved command '{}' but no allow option was offered — treating as cancelled", command);
                return Flux.just(HitlResult.cancelled());
            }
            logger.info(
                    "Auto-approved command '{}' for session {} (request id {}), optionId={}",
                    command,
                    sessionId,
                    requestId,
                    optionId);
            return Flux.just(HitlResult.approved(optionId));
        }

        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionRepository.findById(execId).orElse(null);
        if (execution == null) {
            logger.warn("Execution {} not found for environment {} — rejecting command '{}'", execId, envId, command);
            return Flux.just(HitlResult.cancelled());
        }
        executionGuard.verifyExecutionInEnvironment(execution, envId);

        pendingHitlRegistry.register(
                execution.getId(),
                HitlKind.APPROVAL,
                sessionId,
                requestId,
                params.hitlId(),
                params.message(),
                command,
                params.title(),
                params.toolKind(),
                params.options(),
                params.diff(),
                null,
                Instant.now(),
                teamId);

        eventPublisher.publishEvent(new SandboxExecutionHitlRequiredEvent(
                teamId,
                execution.getId(),
                params.hitlId(),
                params.message(),
                HitlKind.APPROVAL,
                command,
                params.title(),
                params.toolKind(),
                params.options(),
                params.diff(),
                null));
        logger.info(
                "Deferred command '{}' to HITL for execution {} and team {} — response sent when user resolves",
                command,
                execution.getId(),
                teamId);
        return Flux.empty();
    }

    private Flux<EnvironmentResponsePayload> handleQuestion(
            String sessionId, Object requestId, EnvironmentRpcPayload.HitlRequest params, UUID envId, UUID teamId) {
        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionRepository.findById(execId).orElse(null);
        if (execution == null) {
            logger.warn(
                    "Execution {} not found for environment {} — cancelling question '{}'",
                    execId,
                    envId,
                    params.hitlId());
            return Flux.just(HitlResult.cancelled());
        }
        executionGuard.verifyExecutionInEnvironment(execution, envId);

        pendingHitlRegistry.register(
                execution.getId(),
                HitlKind.QUESTION,
                sessionId,
                requestId,
                params.hitlId(),
                params.message(),
                null,
                null,
                null,
                null,
                null,
                params.form(),
                Instant.now(),
                teamId);

        eventPublisher.publishEvent(new SandboxExecutionHitlRequiredEvent(
                teamId,
                execution.getId(),
                params.hitlId(),
                params.message(),
                HitlKind.QUESTION,
                null,
                null,
                null,
                null,
                null,
                params.form()));
        logger.info(
                "Deferred question '{}' to HITL for execution {} and team {} — response sent when user answers",
                params.hitlId(),
                execution.getId(),
                teamId);
        return Flux.empty();
    }

    /**
     * Picks the best allow option for an auto-approval: prefer {@code allow_always} (a rule match is
     * a persistent allow), then {@code allow_once}, then any option.
     */
    private static String selectAllowOptionId(List<PermissionOption> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        String firstAllowAlways = null;
        String firstAllowOnce = null;
        String first = null;
        for (PermissionOption option : options) {
            if (option == null) {
                continue;
            }
            if (first == null) {
                first = option.optionId();
            }
            if (option.kind() == ApprovalOptionKind.ALLOW_ALWAYS && firstAllowAlways == null) {
                firstAllowAlways = option.optionId();
            } else if (option.kind() == ApprovalOptionKind.ALLOW_ONCE && firstAllowOnce == null) {
                firstAllowOnce = option.optionId();
            }
        }
        if (firstAllowAlways != null) {
            return firstAllowAlways;
        }
        if (firstAllowOnce != null) {
            return firstAllowOnce;
        }
        return first;
    }
}
