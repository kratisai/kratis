package com.kratisai.controlplane.websocket.client;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.SubscriptionRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class ClientTeamUnsubscribeRpcHandler
        implements ClientRpcHandler<ClientRpcPayload.Unsubscribe, ClientPayload.SubscriptionResult> {

    private static final Logger logger = LoggerFactory.getLogger(ClientTeamUnsubscribeRpcHandler.class);

    private final ClientSessionRegistry sessionRegistry;
    private final SubscriptionRegistry subscriptionRegistry;

    public ClientTeamUnsubscribeRpcHandler(
            ClientSessionRegistry sessionRegistry, SubscriptionRegistry subscriptionRegistry) {
        this.sessionRegistry = sessionRegistry;
        this.subscriptionRegistry = subscriptionRegistry;
    }

    @Override
    public String getMethodName() {
        return ClientRpcPayload.Unsubscribe.METHOD;
    }

    @Override
    public Class<ClientRpcPayload.Unsubscribe> getPayloadType() {
        return ClientRpcPayload.Unsubscribe.class;
    }

    @Override
    public Flux<ClientPayload.SubscriptionResult> handle(
            String sessionId, Object requestId, ClientRpcPayload.Unsubscribe params) {
        sessionRegistry.getUserId(sessionId).orElseThrow(() -> new RpcErrorException(JsonRpcError.NotAuthenticated()));

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

        boolean removed = subscriptionRegistry.unsubscribe(sessionId, teamId);
        String message = removed ? "Unsubscribed from team " + teamIdStr : "Not subscribed to team " + teamIdStr;

        logger.debug("Session {} unsubscribed from team {}: {}", sessionId, teamIdStr, message);
        return Flux.just(new ClientPayload.SubscriptionResult(teamIdStr, message));
    }
}
