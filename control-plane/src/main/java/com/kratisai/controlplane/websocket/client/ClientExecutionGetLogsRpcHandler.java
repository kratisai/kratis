package com.kratisai.controlplane.websocket.client;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.EnvironmentRpcClient;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class ClientExecutionGetLogsRpcHandler
        implements ClientRpcHandler<ClientRpcPayload.ExecutionGetLogs, ClientPayload.ExecutionLogsResult> {

    static final int DEFAULT_TAIL_LINES = 500;
    static final long TIMEOUT_SECONDS = 10;

    private static final Logger logger = LoggerFactory.getLogger(ClientExecutionGetLogsRpcHandler.class);

    private final ClientSessionRegistry sessionRegistry;
    private final SandboxExecutionRepository executionRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final EnvironmentRpcClient environmentRpcClient;

    public ClientExecutionGetLogsRpcHandler(
            ClientSessionRegistry sessionRegistry,
            SandboxExecutionRepository executionRepository,
            TeamMemberRepository teamMemberRepository,
            EnvironmentRpcClient environmentRpcClient) {
        this.sessionRegistry = sessionRegistry;
        this.executionRepository = executionRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.environmentRpcClient = environmentRpcClient;
    }

    @Override
    public String getMethodName() {
        return ClientRpcPayload.ExecutionGetLogs.METHOD;
    }

    @Override
    public Class<ClientRpcPayload.ExecutionGetLogs> getPayloadType() {
        return ClientRpcPayload.ExecutionGetLogs.class;
    }

    @Override
    @Transactional(readOnly = true)
    public Flux<ClientPayload.ExecutionLogsResult> handle(
            String sessionId, Object requestId, ClientRpcPayload.ExecutionGetLogs params) {
        String userId = sessionRegistry
                .getUserId(sessionId)
                .orElseThrow(() -> new RpcErrorException(JsonRpcError.NotAuthenticated()));

        UUID executionId;
        try {
            executionId = UUID.fromString(params.executionId());
        } catch (IllegalArgumentException e) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("executionId must be a valid UUID"));
        }

        SandboxExecution execution = executionRepository
                .findById(executionId)
                .orElseThrow(() -> new RpcErrorException(
                        JsonRpcError.error(JsonRpcErrorCodes.RESOURCE_NOT_FOUND, "Execution not found", null)));

        UUID teamId = execution.getChat().getTeam().getId();
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, UUID.fromString(userId))) {
            throw new RpcErrorException(JsonRpcError.NotAuthorised("Not a member of this team"));
        }

        ExecutionEnvironment environment = execution.getEnvironment();
        if (environment == null) {
            throw new RpcErrorException(
                    JsonRpcError.error(JsonRpcErrorCodes.RESOURCE_NOT_FOUND, "Execution has no environment", null));
        }

        UUID environmentId = environment.getId();
        EnvironmentStatus status = environment.getStatus();
        // Only a connected sidecar has a log to read; every other status is reported as-is.
        if (status != EnvironmentStatus.CONNECTED) {
            return Flux.just(new ClientPayload.ExecutionLogsResult(executionId, environmentId, status, List.of()));
        }
        return fetchLive(environmentId, executionId, params.tailLines());
    }

    private Flux<ClientPayload.ExecutionLogsResult> fetchLive(UUID environmentId, UUID executionId, Integer tailLines) {
        int tail = tailLines != null ? tailLines : DEFAULT_TAIL_LINES;
        // The sidecar round trip blocks, so it runs after the read transaction on a blocking scheduler.
        return Mono.fromCallable(() -> requestLines(environmentId, executionId, tail))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(e -> {
                    logger.warn("Terminal log fetch failed for execution {}: {}", executionId, e.toString());
                    return new RpcErrorException(JsonRpcError.InternalError(e));
                })
                .flux();
    }

    private ClientPayload.ExecutionLogsResult requestLines(UUID environmentId, UUID executionId, int tail)
            throws InterruptedException, TimeoutException {
        EnvironmentConnectorResult.GetLogs result = environmentRpcClient.request(
                environmentId, new EnvironmentRpcPayload.GetLogs(tail), TIMEOUT_SECONDS, TimeUnit.SECONDS);
        List<String> lines = result == null ? List.of() : result.lines();
        return new ClientPayload.ExecutionLogsResult(executionId, environmentId, EnvironmentStatus.CONNECTED, lines);
    }
}
