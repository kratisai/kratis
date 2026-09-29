package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.api.wsdto.EnvironmentHeartbeatStatus;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResultType;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class EnvironmentRpcHandlerTest {

    @Test
    void handle_returnsFluxFromImplementation() {
        EnvironmentResponsePayload expected = new EnvironmentResponsePayload.EnvironmentHeartbeatResult(
                EnvironmentResultType.ENV_HEARTBEAT, EnvironmentHeartbeatStatus.OK);
        EnvironmentRpcHandler<EnvironmentRpcPayload.Heartbeat, EnvironmentResponsePayload> handler =
                new EnvironmentRpcHandler<>() {
                    @Override
                    public String getMethodName() {
                        return "test";
                    }

                    @Override
                    public Class<EnvironmentRpcPayload.Heartbeat> getPayloadType() {
                        return EnvironmentRpcPayload.Heartbeat.class;
                    }

                    @Override
                    public Flux<EnvironmentResponsePayload> handle(
                            String sessionId, Object requestId, EnvironmentRpcPayload.Heartbeat params) {
                        return Flux.just(expected);
                    }
                };

        assertThat(handler.getMethodName()).isEqualTo("test");
        assertThat(handler.getPayloadType()).isEqualTo(EnvironmentRpcPayload.Heartbeat.class);

        StepVerifier.create(handler.handle("ws-1", 1, new EnvironmentRpcPayload.Heartbeat()))
                .expectNext(expected)
                .verifyComplete();
    }
}
