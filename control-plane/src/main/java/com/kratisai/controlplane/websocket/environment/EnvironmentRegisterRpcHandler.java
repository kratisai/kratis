package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.EnvironmentRegisterResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.event.EnvironmentRegisteredEvent;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.PendingHitlRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Component
public class EnvironmentRegisterRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.Register, EnvironmentResponsePayload> {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentRegisterRpcHandler.class);
    private static final int SUPPORTED_PROTOCOL_VERSION = 1;

    private final ExecutionEnvironmentRepository executionEnvironmentRepository;
    private final EnvironmentSessionRegistry sessionRegistry;
    private final PendingHitlRegistry pendingHitlRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public EnvironmentRegisterRpcHandler(
            ExecutionEnvironmentRepository executionEnvironmentRepository,
            EnvironmentSessionRegistry sessionRegistry,
            PendingHitlRegistry pendingHitlRegistry,
            ApplicationEventPublisher eventPublisher) {
        this.executionEnvironmentRepository = executionEnvironmentRepository;
        this.sessionRegistry = sessionRegistry;
        this.pendingHitlRegistry = pendingHitlRegistry;
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
            return Flux.just(handleReconnect(sessionId, env, reconnectState));
        }
        return Flux.just(handleInitialRegistration(sessionId, env, params));
    }

    private EnvironmentResponsePayload handleReconnect(
            String sessionId, ExecutionEnvironment env, ReconnectState reconnectState) {
        logger.info("Environment {} reconnecting with session {}", env.getId(), sessionId);

        String oldSessionId = sessionRegistry.replaceSessionForEnvironment(sessionId, env.getId());
        if (oldSessionId != null) {
            logger.info("Replaced old session {} for environment {}", oldSessionId, env.getId());

            int reboundCount = pendingHitlRegistry.rebindBySession(oldSessionId, sessionId);
            if (reboundCount > 0) {
                logger.info("Rebound {} pending HITL requests for environment {}", reboundCount, env.getId());
            }
        }

        env.setStatus(EnvironmentStatus.CONNECTED);
        if (reconnectState.activeAcpSessionId() != null) {
            logger.debug("Reconnect with active ACP session: {}", reconnectState.activeAcpSessionId());
        }
        env.setLastHeartbeat(Instant.now());
        executionEnvironmentRepository.save(env);

        eventPublisher.publishEvent(new TeamEntityChangedEvent(env.getTeam().getId(), TeamEntityType.ENVIRONMENTS));

        return EnvironmentRegisterResult.reconnected(env.getId().toString());
    }

    private EnvironmentResponsePayload handleInitialRegistration(
            String sessionId, ExecutionEnvironment env, EnvironmentRpcPayload.Register params) {
        env.setStatus(EnvironmentStatus.CONNECTED);
        if (params.containerId() != null) {
            env.setContainerId(params.containerId());
        }
        env.setLastHeartbeat(Instant.now());
        executionEnvironmentRepository.save(env);

        sessionRegistry.registerEnvironmentSession(sessionId, env.getId());

        eventPublisher.publishEvent(new TeamEntityChangedEvent(env.getTeam().getId(), TeamEntityType.ENVIRONMENTS));

        eventPublisher.publishEvent(new EnvironmentRegisteredEvent(env.getId()));

        return new EnvironmentRegisterResult(env.getId().toString());
    }
}
