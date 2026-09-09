package com.kratisai.controlplane.websocket.client;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.SubscriptionRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class ClientSubscribeRpcHandler
        implements ClientRpcHandler<ClientRpcPayload.Subscribe, ClientPayload.SubscriptionResult> {

    private static final Logger logger = LoggerFactory.getLogger(ClientSubscribeRpcHandler.class);

    private final ClientSessionRegistry sessionRegistry;
    private final SubscriptionRegistry subscriptionRegistry;
    private final TeamMemberRepository teamMemberRepository;

    public ClientSubscribeRpcHandler(
            ClientSessionRegistry sessionRegistry,
            SubscriptionRegistry subscriptionRegistry,
            TeamMemberRepository teamMemberRepository) {
        this.sessionRegistry = sessionRegistry;
        this.subscriptionRegistry = subscriptionRegistry;
        this.teamMemberRepository = teamMemberRepository;
    }

    @Override
    public String getMethodName() {
        return ClientRpcPayload.Subscribe.METHOD;
    }

    @Override
    public Class<ClientRpcPayload.Subscribe> getPayloadType() {
        return ClientRpcPayload.Subscribe.class;
    }

    @Override
    public Flux<ClientPayload.SubscriptionResult> handle(
            String sessionId, Object requestId, ClientRpcPayload.Subscribe params) {
        String userId = sessionRegistry
                .getUserId(sessionId)
                .orElseThrow(() -> new RpcErrorException(JsonRpcError.NotAuthenticated()));

        if (params.teamId() == null || params.teamId().isBlank()) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("Missing required field: teamId"));
        }

        String teamIdStr = params.teamId();
        UUID teamId;
        try {
            teamId = UUID.fromString(teamIdStr);
        } catch (IllegalArgumentException e) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("Invalid teamId format"));
        }

        UUID userIdUuid = UUID.fromString(userId);
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userIdUuid)) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("User is not a member of the specified team"));
        }

        boolean added = subscriptionRegistry.subscribe(sessionId, teamId);
        String message = added ? "Subscribed to team " + teamIdStr : "Already subscribed to team " + teamIdStr;

        logger.debug("Session {} subscribed to team {}: {}", sessionId, teamIdStr, message);
        return Flux.just(new ClientPayload.SubscriptionResult(teamIdStr, message));
    }
}
