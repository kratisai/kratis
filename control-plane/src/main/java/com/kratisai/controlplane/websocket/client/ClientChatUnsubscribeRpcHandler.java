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
public class ClientChatUnsubscribeRpcHandler
        implements ClientRpcHandler<ClientRpcPayload.ChatUnsubscribe, ClientPayload.ChatSubscriptionResult> {

    private static final Logger logger = LoggerFactory.getLogger(ClientChatUnsubscribeRpcHandler.class);

    private final ClientSessionRegistry sessionRegistry;
    private final ChatSubscriptionRegistry chatSubscriptionRegistry;

    public ClientChatUnsubscribeRpcHandler(
            ClientSessionRegistry sessionRegistry, ChatSubscriptionRegistry chatSubscriptionRegistry) {
        this.sessionRegistry = sessionRegistry;
        this.chatSubscriptionRegistry = chatSubscriptionRegistry;
    }

    @Override
    public String getMethodName() {
        return ClientRpcPayload.ChatUnsubscribe.METHOD;
    }

    @Override
    public Class<ClientRpcPayload.ChatUnsubscribe> getPayloadType() {
        return ClientRpcPayload.ChatUnsubscribe.class;
    }

    @Override
    public Flux<ClientPayload.ChatSubscriptionResult> handle(
            String sessionId, Object requestId, ClientRpcPayload.ChatUnsubscribe params) {
        sessionRegistry.getUserId(sessionId).orElseThrow(() -> new RpcErrorException(JsonRpcError.NotAuthenticated()));

        if (params.chatId() == null || params.chatId().isBlank()) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("chatId is required"));
        }

        UUID chatId;
        try {
            chatId = UUID.fromString(params.chatId());
        } catch (IllegalArgumentException e) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("chatId must be a valid UUID"));
        }
        chatSubscriptionRegistry.unsubscribeChat(sessionId, chatId);
        logger.debug("Client session {} unsubscribed from chat {}", sessionId, chatId);
        return Flux.just(new ClientPayload.ChatSubscriptionResult("unsubscribed", chatId));
    }
}
