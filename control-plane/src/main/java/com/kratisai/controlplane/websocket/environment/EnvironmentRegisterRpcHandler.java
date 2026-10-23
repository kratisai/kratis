package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.ActivityDetail;
import com.kratisai.controlplane.api.wsdto.ActivityHitl;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.EnvironmentRegisterResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResolution;
import com.kratisai.controlplane.api.wsdto.HitlResolver;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.event.EnvironmentRegisteredEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.ExecutionActivityPersistenceService;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Component
public class EnvironmentRegisterRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.Register, EnvironmentResponsePayload> {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentRegisterRpcHandler.class);
    private static final int SUPPORTED_PROTOCOL_VERSION = 1;
    private static final int MAX_LOGGED_VERSION_LENGTH = 64;
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");

    private final ExecutionEnvironmentRepository executionEnvironmentRepository;
    private final SandboxExecutionActivityRepository activityRepository;
    private final ExecutionActivityPersistenceService activityPersistenceService;
    private final EnvironmentSessionRegistry sessionRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public EnvironmentRegisterRpcHandler(
            ExecutionEnvironmentRepository executionEnvironmentRepository,
            SandboxExecutionActivityRepository activityRepository,
            ExecutionActivityPersistenceService activityPersistenceService,
            EnvironmentSessionRegistry sessionRegistry,
            ApplicationEventPublisher eventPublisher) {
        this.executionEnvironmentRepository = executionEnvironmentRepository;
        this.activityRepository = activityRepository;
        this.activityPersistenceService = activityPersistenceService;
        this.sessionRegistry = sessionRegistry;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.Register.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.Register> getPayloadType() {
        return EnvironmentRpcPayload.Register.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.Register params) {
        if (params.token() == null || params.token().isBlank()) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("Invalid params: 'token' is required"));
        }

        if (params.protocolVersion() != null && params.protocolVersion() > SUPPORTED_PROTOCOL_VERSION) {
            throw new RpcErrorException(JsonRpcError.error(
                    -32002, "Unsupported protocol version", "Supported version is " + SUPPORTED_PROTOCOL_VERSION));
        }

        String token = params.token();
        Optional<ExecutionEnvironment> envOpt = executionEnvironmentRepository.findByAuthToken(token);
        if (envOpt.isEmpty()) {
            throw new RpcErrorException(JsonRpcError.error(-32001, "Invalid environment token", "Token is invalid"));
        }

        ExecutionEnvironment env = envOpt.get();
        boolean isReconnect = Boolean.TRUE.equals(params.isReconnect());

        if (isReconnect) {
            ReconnectState reconnectState = new ReconnectState(
                    true,
                    params.activeAcpSessionId(),
                    Boolean.TRUE.equals(params.hasActiveAgent()),
                    params.lastEventSequence() != null ? params.lastEventSequence() : 0L,
                    params.pendingHitlIds() != null ? params.pendingHitlIds() : List.of());
            return Flux.just(handleReconnect(sessionId, env, reconnectState, params.connectorVersion()));
        }
        return Flux.just(handleInitialRegistration(sessionId, env, params.connectorVersion()));
    }

    private EnvironmentResponsePayload handleReconnect(
            String sessionId, ExecutionEnvironment env, ReconnectState reconnectState, String connectorVersion) {
        logger.info(
                "Environment {} reconnecting with session {} (connector version {})",
                env.getId(),
                sessionId,
                loggable(connectorVersion));

        String oldSessionId = sessionRegistry.replaceSessionForEnvironment(sessionId, env.getId());
        if (oldSessionId != null) {
            logger.info("Replaced old session {} for environment {}", oldSessionId, env.getId());
        }

        if (env.getStatus() != EnvironmentStatus.CONNECTED) {
            env.setStateChangedAt(Instant.now());
        }
        env.setStatus(EnvironmentStatus.CONNECTED);
        if (reconnectState.activeAcpSessionId() != null) {
            logger.debug("Reconnect with active ACP session: {}", reconnectState.activeAcpSessionId());
        }
        env.setLastHeartbeat(Instant.now());
        executionEnvironmentRepository.save(env);

        reconcilePendingHitl(env, reconnectState.pendingHitlIds());

        eventPublisher.publishEvent(new TeamEntityChangedEvent(env.getTeam().getId(), TeamEntityType.ENVIRONMENTS));

        return EnvironmentRegisterResult.reconnected(env.getId().toString());
    }

    /**
     * The sidecar is the authority on which requests it can still answer. Anything the database
     * still considers PENDING but the sidecar no longer reports is stale; anything the sidecar
     * reports that the database already resolved is replayed to it.
     */
    private void reconcilePendingHitl(ExecutionEnvironment env, List<String> sidecarPendingIds) {
        Set<String> reported = new HashSet<>(sidecarPendingIds != null ? sidecarPendingIds : List.of());
        List<SandboxExecutionActivity> pending =
                activityRepository.findByEnvironmentIdAndStatus(env.getId(), ActivityStatus.PENDING);
        Set<String> pendingActionIds = new HashSet<>();
        for (SandboxExecutionActivity activity : pending) {
            if (activity.getActionId() == null || !reported.contains(activity.getActionId())) {
                activityPersistenceService.cancelActivity(
                        activity, "Sidecar reconnected without this HITL request", HitlResolver.system());
            } else {
                pendingActionIds.add(activity.getActionId());
            }
        }

        for (String actionId : reported) {
            if (pendingActionIds.contains(actionId)) {
                continue;
            }
            Optional<SandboxExecutionActivity> latest =
                    activityRepository
                            .findLatestByEnvironmentIdAndActionId(env.getId(), actionId, PageRequest.of(0, 1))
                            .stream()
                            .findFirst();
            if (latest.isEmpty()) {
                logger.info(
                        "Sidecar reported pending HITL {} for environment {}, but no activity exists",
                        actionId,
                        env.getId());
                continue;
            }
            replayResolved(env, latest.get());
        }
    }

    private void replayResolved(ExecutionEnvironment env, SandboxExecutionActivity activity) {
        if (activity.getStatus() == ActivityStatus.PENDING || activity.getActionId() == null) {
            return;
        }
        ActivityDetail detail = activityPersistenceService.detailOf(activity);
        ActivityHitl hitl = detail != null ? detail.hitl() : null;
        if (hitl == null || hitl.response() == null) {
            return;
        }
        logger.info("Replaying resolved HITL {} to reconnecting environment {}", activity.getActionId(), env.getId());
        eventPublisher.publishEvent(new SandboxExecutionHitlResolvedEvent(
                env.getTeam().getId(),
                new HitlResolution(
                        activity.getExecutionId(),
                        activity.getActionId(),
                        hitl.kind() != null ? hitl.kind() : HitlKind.APPROVAL,
                        hitl.response(),
                        hitl.optionId(),
                        hitl.content(),
                        hitl.resolvedBy() != null ? hitl.resolvedBy() : HitlResolver.system())));
    }

    private EnvironmentResponsePayload handleInitialRegistration(
            String sessionId, ExecutionEnvironment env, String connectorVersion) {
        logger.info(
                "Environment {} registered with session {} (connector version {})",
                env.getId(),
                sessionId,
                loggable(connectorVersion));
        if (env.getStatus() != EnvironmentStatus.CONNECTED) {
            env.setStateChangedAt(Instant.now());
        }
        env.setStatus(EnvironmentStatus.CONNECTED);
        env.setLastHeartbeat(Instant.now());
        executionEnvironmentRepository.save(env);

        boolean alreadyLive = sessionRegistry.getSessionForEnvironment(env.getId()) != null;
        sessionRegistry.registerEnvironmentSession(sessionId, env.getId());

        eventPublisher.publishEvent(new TeamEntityChangedEvent(env.getTeam().getId(), TeamEntityType.ENVIRONMENTS));

        if (alreadyLive) {
            logger.warn(
                    "Environment {} registered a duplicate initial session {}; keeping newest without relaunch",
                    env.getId(),
                    sessionId);
            return EnvironmentRegisterResult.reconnected(env.getId().toString());
        }
        eventPublisher.publishEvent(new EnvironmentRegisteredEvent(env.getId()));

        return new EnvironmentRegisterResult(env.getId().toString());
    }

    // The version is connector-supplied: strip control characters and bound the length before logging.
    private static String loggable(String connectorVersion) {
        if (connectorVersion == null || connectorVersion.isBlank()) {
            return "unknown";
        }
        String cleaned = CONTROL_CHARACTERS.matcher(connectorVersion.trim()).replaceAll("?");
        return cleaned.length() > MAX_LOGGED_VERSION_LENGTH ? cleaned.substring(0, MAX_LOGGED_VERSION_LENGTH) : cleaned;
    }
}
