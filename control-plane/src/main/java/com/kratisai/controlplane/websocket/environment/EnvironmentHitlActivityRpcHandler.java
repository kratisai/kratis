package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.ActivityKind;
import com.kratisai.controlplane.api.wsdto.ApprovalOptionKind;
import com.kratisai.controlplane.api.wsdto.CommandSegment;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.HitlActivityResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlRequestSnapshot;
import com.kratisai.controlplane.api.wsdto.HitlResolution;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.PermissionOption;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.HitlRule;
import com.kratisai.controlplane.model.HitlRuleType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlRequiredEvent;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.HitlRuleRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.ExecutionActivityPersistenceService;
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
public class EnvironmentHitlActivityRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.HitlActivity, EnvironmentResponsePayload> {
    private static final Logger logger = LoggerFactory.getLogger(EnvironmentHitlActivityRpcHandler.class);
    private static final String RULE_RESOLVER = "Remembered rule";

    private final EnvironmentSessionRegistry sessionRegistry;
    private final HitlRuleRepository ruleRepository;
    private final ExecutionEnvironmentRepository environmentRepository;
    private final SandboxExecutionRepository executionRepository;
    private final EnvironmentExecutionGuard executionGuard;
    private final PendingHitlRegistry pendingHitlRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final ExecutionActivityPersistenceService activityPersistenceService;

    public EnvironmentHitlActivityRpcHandler(
            EnvironmentSessionRegistry sessionRegistry,
            HitlRuleRepository ruleRepository,
            ExecutionEnvironmentRepository environmentRepository,
            SandboxExecutionRepository executionRepository,
            EnvironmentExecutionGuard executionGuard,
            PendingHitlRegistry pendingHitlRegistry,
            ApplicationEventPublisher eventPublisher,
            ExecutionActivityPersistenceService activityPersistenceService) {
        this.sessionRegistry = sessionRegistry;
        this.ruleRepository = ruleRepository;
        this.environmentRepository = environmentRepository;
        this.executionRepository = executionRepository;
        this.executionGuard = executionGuard;
        this.pendingHitlRegistry = pendingHitlRegistry;
        this.eventPublisher = eventPublisher;
        this.activityPersistenceService = activityPersistenceService;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.HitlActivity.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.HitlActivity> getPayloadType() {
        return EnvironmentRpcPayload.HitlActivity.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.HitlActivity params) {
        logger.info("Received env.hitl_activity from session {} with request id {}", sessionId, requestId);

        Optional<UUID> envIdOpt = sessionRegistry.getEnvironmentId(sessionId);
        if (envIdOpt.isEmpty()) {
            logger.warn("Received env.hitl_activity from unmapped session: {}", sessionId);
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
            String sessionId, Object requestId, EnvironmentRpcPayload.HitlActivity params, UUID envId, UUID teamId) {
        if (params.command() == null || params.command().isBlank()) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("Invalid params: 'command' is required"));
        }

        String command = params.command();
        String toolKind = params.toolKind();

        logger.debug("Checking HITL rules for team {} and command '{}'", teamId, command);

        List<HitlRule> rules = ruleRepository.findByTeamId(teamId);
        Optional<HitlResponse> autoResolution = HitlRuleService.autoResolve(rules, command, toolKind);

        String allowOptionId = null;
        if (autoResolution.isPresent() && autoResolution.get() != HitlResponse.DECLINED) {
            allowOptionId = selectAllowOptionId(params.options());
            if (allowOptionId == null) {
                logger.warn(
                        "Auto-approved command '{}' but no allow option was offered — treating as cancelled", command);
                return Flux.just(HitlActivityResult.cancelled());
            }
        }

        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionRepository.findById(execId).orElse(null);
        if (execution == null) {
            logger.warn("Execution {} not found for environment {} — rejecting command '{}'", execId, envId, command);
            return Flux.just(HitlActivityResult.cancelled());
        }
        executionGuard.verifyExecutionInEnvironment(execution, envId);

        HitlRequestSnapshot payload = new HitlRequestSnapshot(
                execution.getId(),
                params.hitlId(),
                HitlKind.APPROVAL,
                params.message(),
                command,
                approvalSegments(command, toolKind, rules),
                params.title(),
                toolKind,
                sanitizeOptions(params.options()),
                params.diff(),
                null);

        if (autoResolution.isPresent()) {
            if (autoResolution.get() == HitlResponse.DECLINED) {
                logger.info("Command '{}' rejected by DENY HITL rule for team {}", command, teamId);
                recordRuleResolution(teamId, payload, HitlResponse.DECLINED, null);
                return Flux.just(HitlActivityResult.declined());
            }
            logger.info(
                    "Auto-approved command '{}' for session {} (request id {}), optionId={}",
                    command,
                    sessionId,
                    requestId,
                    allowOptionId);
            recordRuleResolution(teamId, payload, HitlResponse.APPROVED, allowOptionId);
            return Flux.just(HitlActivityResult.approved(allowOptionId));
        }

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
            String sessionId, Object requestId, EnvironmentRpcPayload.HitlActivity params, UUID envId, UUID teamId) {
        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionRepository.findById(execId).orElse(null);
        if (execution == null) {
            logger.warn(
                    "Execution {} not found for environment {} — cancelling question '{}'",
                    execId,
                    envId,
                    params.hitlId());
            return Flux.just(HitlActivityResult.cancelled());
        }
        executionGuard.verifyExecutionInEnvironment(execution, envId);

        HitlRequestSnapshot payload = new HitlRequestSnapshot(
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

    /** The activity row records the rule decision directly; the UI never sees an awaiting-human state for it. */
    private void recordRuleResolution(
            UUID teamId, HitlRequestSnapshot request, HitlResponse response, String optionId) {
        activityPersistenceService.recordAutoResolved(
                teamId,
                request,
                new HitlResolution(
                        request.executionId(),
                        request.hitlId(),
                        HitlKind.APPROVAL,
                        response,
                        optionId,
                        null,
                        null,
                        RULE_RESOLVER));
    }

    /** Unknown tool kinds get no segments, so nothing unrememberable can be persisted. */
    private static List<CommandSegment> approvalSegments(String command, String toolKind, List<HitlRule> rules) {
        if (ActivityKind.isCommandLike(toolKind)) {
            var parse = ShellCommandSplitter.parse(command);
            return parse.uniqueRoots().stream()
                    .map(segment -> {
                        Boolean preApproved = null;
                        if (HitlRuleService.anyAllowMatches(rules, segment.text())) {
                            preApproved = true;
                        }
                        return new CommandSegment(
                                segment.text(), segment.suggestedRoot(), HitlRuleType.PREFIX_WILD, preApproved);
                    })
                    .toList();
        }
        String kind = ActivityKind.effectiveWireValue(toolKind);
        if (ActivityKind.fromWireValue(kind).isEmpty()) {
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
