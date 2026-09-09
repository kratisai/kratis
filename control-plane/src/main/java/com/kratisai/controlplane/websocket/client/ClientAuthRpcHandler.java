package com.kratisai.controlplane.websocket.client;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class ClientAuthRpcHandler implements ClientRpcHandler<ClientRpcPayload.Auth, ClientPayload.AuthResult> {

    private static final Logger logger = LoggerFactory.getLogger(ClientAuthRpcHandler.class);

    private final JwtService jwtService;
    private final ClientSessionRegistry sessionRegistry;

    public ClientAuthRpcHandler(JwtService jwtService, ClientSessionRegistry sessionRegistry) {
        this.jwtService = jwtService;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public String getMethodName() {
        return ClientRpcPayload.Auth.METHOD;
    }

    @Override
    public Class<ClientRpcPayload.Auth> getPayloadType() {
        return ClientRpcPayload.Auth.class;
    }

    @Override
    public Flux<ClientPayload.AuthResult> handle(String sessionId, Object requestId, ClientRpcPayload.Auth params) {
        if (params.token() == null || params.token().isBlank()) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("Token is required"));
        }
        String token = params.token();
        if (!jwtService.isTokenValid(token)) {
            throw new RpcErrorException(JsonRpcError.InvalidToken());
        }

        String userId = jwtService.getUserIdFromToken(token).toString();
        sessionRegistry.authenticateSession(sessionId, userId);
        logger.info("User {} authenticated via client WebSocket", userId);

        return Flux.just(new ClientPayload.AuthResult(userId));
    }
}
