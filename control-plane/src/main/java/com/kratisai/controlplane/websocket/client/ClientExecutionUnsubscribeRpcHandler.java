package com.kratisai.controlplane.websocket.client;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.service.ChatSubscriptionRegistry;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class ClientExecutionUnsubscribeRpcHandler
        implements ClientRpcHandler<ClientRpcPayload.ExecutionUnsubscribe, ClientPayload.ExecutionSubscriptionResult> {

    private static final Logger logger = LoggerFactory.getLogger(ClientExecutionUnsubscribeRpcHandler.class);

    private final ClientSessionRegistry sessionRegistry;
    private final ChatSubscriptionRegistry chatSubscriptionRegistry;

    public ClientExecutionUnsubscribeRpcHandler(
            ClientSessionRegistry sessionRegistry, ChatSubscriptionRegistry chatSubscriptionRegistry) {
        this.sessionRegistry = sessionRegistry;
        this.chatSubscriptionRegistry = chatSubscriptionRegistry;
    }

    @Override
    public String getMethodName() {
        return ClientRpcPayload.ExecutionUnsubscribe.METHOD;
    }

    @Override
    public Class<ClientRpcPayload.ExecutionUnsubscribe> getPayloadType() {
        return ClientRpcPayload.ExecutionUnsubscribe.class;
    }

    @Override
    public Flux<ClientPayload.ExecutionSubscriptionResult> handle(
            String sessionId, Object requestId, ClientRpcPayload.ExecutionUnsubscribe params) {
        sessionRegistry.getUserId(sessionId).orElseThrow(() -> new RpcErrorException(JsonRpcError.NotAuthenticated()));

        UUID executionId;
        try {
            executionId = UUID.fromString(params.executionId());
        } catch (IllegalArgumentException e) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("executionId must be a valid UUID"));
        }

        chatSubscriptionRegistry.unsubscribeExecution(sessionId, executionId);
        logger.debug("Client session {} unsubscribed from execution {}", sessionId, executionId);
        return Flux.just(new ClientPayload.ExecutionSubscriptionResult("unsubscribed", executionId));
    }
}
