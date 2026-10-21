package com.kratisai.controlplane.websocket.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.service.ChatSubscriptionRegistry;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ClientExecutionUnsubscribeRpcHandlerTest {

    private ClientSessionRegistry sessionRegistry;
    private ChatSubscriptionRegistry chatSubscriptionRegistry;
    private ClientExecutionUnsubscribeRpcHandler handler;

    @BeforeEach
    void setUp() {
        sessionRegistry = mock(ClientSessionRegistry.class);
        chatSubscriptionRegistry = new ChatSubscriptionRegistry();
        handler = new ClientExecutionUnsubscribeRpcHandler(sessionRegistry, chatSubscriptionRegistry);
        when(sessionRegistry.getUserId("ws-1"))
                .thenReturn(Optional.of(UUID.randomUUID().toString()));
    }

    @Test
    void handle_validExecutionId_returnsUnsubscribedResultAndClearsRegistry() {
        UUID executionId = UUID.randomUUID();
        chatSubscriptionRegistry.subscribeExecution("ws-1", executionId);

        StepVerifier.create(
                        handler.handle("ws-1", 1, new ClientRpcPayload.ExecutionUnsubscribe(executionId.toString())))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo("unsubscribed");
                    assertThat(result.executionId()).isEqualTo(executionId);
                })
                .verifyComplete();

        assertThat(chatSubscriptionRegistry.isExecutionSubscribed("ws-1", executionId))
                .isFalse();
    }

    @Test
    void handle_notAuthenticated_throws() {
        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(
                        "ws-1",
                        1,
                        new ClientRpcPayload.ExecutionUnsubscribe(
                                UUID.randomUUID().toString())))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.NOT_AUTHENTICATED));
    }

    @Test
    void handle_invalidExecutionId_throws() {
        assertThatThrownBy(() -> handler.handle("ws-1", 1, new ClientRpcPayload.ExecutionUnsubscribe("nope")))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS));
    }

    @Test
    void handle_methodNameAndPayloadType_areCorrect() {
        assertThat(handler.getMethodName()).isEqualTo(ClientRpcPayload.ExecutionUnsubscribe.METHOD);
        assertThat(handler.getPayloadType()).isEqualTo(ClientRpcPayload.ExecutionUnsubscribe.class);
    }

    @Test
    void result_hasExecutionSubscriptionType() {
        assertThat(ClientPayload.ExecutionSubscriptionResult.class).isNotNull();
    }
}
