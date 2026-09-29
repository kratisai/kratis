package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class JsonRpcInboundTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializeParams_readsTypedRecord() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("token", "abc");

        Object result = JsonRpcInbound.deserializeParams(mapper, params, ClientRpcPayload.Auth.class);

        assertThat(result).isInstanceOf(ClientRpcPayload.Auth.class);
        assertThat(((ClientRpcPayload.Auth) result).token()).isEqualTo("abc");
    }

    @Test
    void deserializeParams_noParamsPayload_acceptsMissingParams() throws Exception {
        Object result = JsonRpcInbound.deserializeParams(mapper, null, EnvironmentRpcPayload.Heartbeat.class);

        assertThat(result).isInstanceOf(EnvironmentRpcPayload.Heartbeat.class);
    }

    @Test
    void deserializeParams_missingRequiredParams_throws() {
        assertThatThrownBy(() -> JsonRpcInbound.deserializeParams(mapper, null, ClientRpcPayload.Auth.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required params");
    }

    @Test
    void invoke_returnsHandlerFlux() {
        StepVerifier.create(JsonRpcInbound.invoke(() -> Flux.just("ok")))
                .expectNext("ok")
                .verifyComplete();
    }

    @Test
    void invoke_nullHandlerResult_becomesEmpty() {
        StepVerifier.create(JsonRpcInbound.invoke(() -> null)).verifyComplete();
    }

    @Test
    void invoke_propagatesRpcError() {
        RpcErrorException error = new RpcErrorException(JsonRpcError.InvalidParams("bad"));
        StepVerifier.create(JsonRpcInbound.invoke(() -> {
                    throw error;
                }))
                .expectErrorSatisfies(thrown -> assertThat(thrown).isSameAs(error))
                .verify();
    }
}
