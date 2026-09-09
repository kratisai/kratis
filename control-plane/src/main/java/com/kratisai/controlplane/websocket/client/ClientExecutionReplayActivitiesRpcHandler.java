package com.kratisai.controlplane.websocket.client;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.ExecutionActivityPersistenceService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Component
public class ClientExecutionReplayActivitiesRpcHandler
        implements ClientRpcHandler<ClientRpcPayload.ExecutionReplayActivities, ClientPayload> {

    private static final Logger logger = LoggerFactory.getLogger(ClientExecutionReplayActivitiesRpcHandler.class);

    private final ClientSessionRegistry sessionRegistry;
    private final SandboxExecutionRepository executionRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final ExecutionActivityPersistenceService activityPersistenceService;

    public ClientExecutionReplayActivitiesRpcHandler(
            ClientSessionRegistry sessionRegistry,
            SandboxExecutionRepository executionRepository,
            TeamMemberRepository teamMemberRepository,
            ExecutionActivityPersistenceService activityPersistenceService) {
        this.sessionRegistry = sessionRegistry;
        this.executionRepository = executionRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.activityPersistenceService = activityPersistenceService;
    }

    @Override
    public String getMethodName() {
        return ClientRpcPayload.ExecutionReplayActivities.METHOD;
    }

    @Override
    public Class<ClientRpcPayload.ExecutionReplayActivities> getPayloadType() {
        return ClientRpcPayload.ExecutionReplayActivities.class;
    }

    @Override
    @Transactional(readOnly = true)
    public Flux<ClientPayload> handle(
            String sessionId, Object requestId, ClientRpcPayload.ExecutionReplayActivities params) {
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
        UUID userIdUuid = UUID.fromString(userId);
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userIdUuid)) {
            throw new RpcErrorException(JsonRpcError.NotAuthorised("Not a member of this team"));
        }

        List<SandboxExecutionActivity> activities = activityPersistenceService.getActivities(executionId);

        Flux<ClientPayload> activityFlux = Flux.fromIterable(activities)
                .map(activity -> new ClientPayload.ExecutionActivityResult(
                        executionId,
                        activity.getActivityType(),
                        activity.getDescription(),
                        activity.getActionId(),
                        activity.getStatus(),
                        activityPersistenceService.detailOf(activity)));

        Flux<ClientPayload> completeFlux =
                Flux.just(new ClientPayload.ExecutionReplayCompleteResult(executionId, activities.size()));

        logger.debug(
                "Replaying {} activities for execution {} to client session {}",
                activities.size(),
                executionId,
                sessionId);

        return Flux.concat(activityFlux, completeFlux);
    }
}
