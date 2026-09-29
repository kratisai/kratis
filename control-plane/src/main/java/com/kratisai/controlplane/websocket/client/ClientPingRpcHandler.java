package com.kratisai.controlplane.websocket.client;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class ClientPingRpcHandler implements ClientRpcHandler<ClientRpcPayload.Ping, ClientPayload.PingResult> {

    @Override
    public String getMethodName() {
        return ClientRpcPayload.Ping.METHOD;
    }

    @Override
    public Class<ClientRpcPayload.Ping> getPayloadType() {
        return ClientRpcPayload.Ping.class;
    }

    @Override
    public Flux<ClientPayload.PingResult> handle(String sessionId, Object requestId, ClientRpcPayload.Ping params) {
        return Flux.just(new ClientPayload.PingResult());
    }
}
