package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.ApprovalOptionKind;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlRequiredResult;
import com.kratisai.controlplane.api.wsdto.CommandSegment;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.HitlResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.PermissionOption;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.api.wsdto.ToolKind;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.HitlRule;
import com.kratisai.controlplane.model.HitlRuleType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlRequiredEvent;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.HitlRuleRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.HitlRuleService;
import com.kratisai.controlplane.service.PendingHitlRegistry;
import com.kratisai.controlplane.service.command.ShellCommandSplitter;
import java.util.ArrayList;
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
    private final HitlRuleRepository ruleRepository;
    private final ExecutionEnvironmentRepository environmentRepository;
    private final SandboxExecutionRepository executionRepository;
    private final EnvironmentExecutionGuard executionGuard;
    private final PendingHitlRegistry pendingHitlRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public EnvironmentHitlRequestRpcHandler(
            EnvironmentSessionRegistry sessionRegistry,
            HitlRuleRepository ruleRepository,
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
        String toolKind = params.toolKind();

        logger.debug("Checking HITL rules for team {} and command '{}'", teamId, command);

        List<HitlRule> rules = ruleRepository.findByTeamId(teamId);
        Optional<HitlResponse> autoResolution = HitlRuleService.autoResolve(rules, command, toolKind);

        if (autoResolution.isPresent()) {
            if (autoResolution.get() == HitlResponse.DECLINED) {
                logger.info("Command '{}' rejected by DENY HITL rule for team {}", command, teamId);
                return Flux.just(HitlResult.declined());
            }
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

        ExecutionHitlRequiredResult payload = new ExecutionHitlRequiredResult(
                execution.getId(),
                params.hitlId(),
                HitlKind.APPROVAL,
                params.message(),
                command,
                approvalSegments(command, toolKind),
                params.title(),
                toolKind,
                sanitizeOptions(params.options()),
                params.diff(),
                null);

        pendingHitlRegistry.register(execution.getId(), payload, sessionId, requestId, teamId);
        eventPublisher.publishEvent(new SandboxExecutionHitlRequiredEvent(teamId, payload));
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

        ExecutionHitlRequiredResult payload = new ExecutionHitlRequiredResult(
                execution.getId(),
                params.hitlId(),
                HitlKind.QUESTION,
                params.message(),
                null,
                null,
                null,
                null,
                null,
                null,
                params.form());

        pendingHitlRegistry.register(execution.getId(), payload, sessionId, requestId, teamId);
        eventPublisher.publishEvent(new SandboxExecutionHitlRequiredEvent(teamId, payload));
        logger.info(
                "Deferred question '{}' to HITL for execution {} and team {} — response sent when user answers",
                params.hitlId(),
                execution.getId(),
                teamId);
        return Flux.empty();
    }

    /** Unknown tool kinds get no segments, so nothing unrememberable can be persisted. */
    private static List<CommandSegment> approvalSegments(String command, String toolKind) {
        if (ToolKind.isCommandLike(toolKind)) {
            return ShellCommandSplitter.parse(command).toWireSegments();
        }
        String kind = ToolKind.effectiveWireValue(toolKind);
        if (ToolKind.fromWireValue(kind).isEmpty()) {
            return List.of();
        }
        return List.of(new CommandSegment(kind, kind, HitlRuleType.TOOL_KIND));
    }

    /** Strips "always" variants when a "once" variant exists, so persistent memory stays exclusively team rules. */
    static List<PermissionOption> sanitizeOptions(List<PermissionOption> options) {
        if (options == null || options.isEmpty()) {
            return options;
        }
        boolean hasAllowOnce = hasKind(options, ApprovalOptionKind.ALLOW_ONCE);
        boolean hasRejectOnce = hasKind(options, ApprovalOptionKind.REJECT_ONCE);
        List<PermissionOption> sanitized = new ArrayList<>(options.size());
        for (PermissionOption option : options) {
            if (option == null) {
                continue;
            }
            if (option.kind() == ApprovalOptionKind.ALLOW_ALWAYS && hasAllowOnce) {
                continue;
            }
            if (option.kind() == ApprovalOptionKind.REJECT_ALWAYS && hasRejectOnce) {
                continue;
            }
            sanitized.add(option);
        }
        return sanitized;
    }

    private static boolean hasKind(List<PermissionOption> options, ApprovalOptionKind kind) {
        return options.stream().anyMatch(option -> option != null && option.kind() == kind);
    }

    /** Prefers {@code allow_once} so an auto-approval never seeds agent-side session memory. */
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
            if (option.kind() == ApprovalOptionKind.ALLOW_ONCE && firstAllowOnce == null) {
                firstAllowOnce = option.optionId();
            } else if (option.kind() == ApprovalOptionKind.ALLOW_ALWAYS && firstAllowAlways == null) {
                firstAllowAlways = option.optionId();
            }
        }
        if (firstAllowOnce != null) {
            return firstAllowOnce;
        }
        if (firstAllowAlways != null) {
            return firstAllowAlways;
        }
        return first;
    }
}
