package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.EnvironmentHeartbeatResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Component
public class EnvironmentHeartbeatRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.Heartbeat, EnvironmentHeartbeatResult> {

    private final ExecutionEnvironmentRepository executionEnvironmentRepository;
    private final EnvironmentSessionRegistry sessionRegistry;

    public EnvironmentHeartbeatRpcHandler(
            ExecutionEnvironmentRepository executionEnvironmentRepository, EnvironmentSessionRegistry sessionRegistry) {
        this.executionEnvironmentRepository = executionEnvironmentRepository;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.Heartbeat.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.Heartbeat> getPayloadType() {
        return EnvironmentRpcPayload.Heartbeat.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentHeartbeatResult> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.Heartbeat params) {
        Optional<UUID> envIdOpt = sessionRegistry.getEnvironmentId(sessionId);
        if (envIdOpt.isEmpty()) {
            throw new RpcErrorException(
                    JsonRpcError.error(-32001, "Not registered as environment session", "Session not registered"));
        }

        UUID envId = envIdOpt.get();
        Optional<ExecutionEnvironment> envOpt = executionEnvironmentRepository.findById(envId);
        if (envOpt.isEmpty()) {
            throw new RpcErrorException(
                    JsonRpcError.error(-32001, "Environment not found", "Environment not found in database"));
        }

        ExecutionEnvironment env = envOpt.get();
        env.setLastHeartbeat(Instant.now());
        executionEnvironmentRepository.save(env);

        return Flux.just(new EnvironmentHeartbeatResult());
    }
}
