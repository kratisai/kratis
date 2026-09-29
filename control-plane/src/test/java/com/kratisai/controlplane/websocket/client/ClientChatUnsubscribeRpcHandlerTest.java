package com.kratisai.controlplane.websocket.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ClientChatUnsubscribeRpcHandlerTest {

    private ClientSessionRegistry sessionRegistry;
    private ClientChatUnsubscribeRpcHandler handler;

    @BeforeEach
    void setUp() {
        sessionRegistry = mock(ClientSessionRegistry.class);
        handler = new ClientChatUnsubscribeRpcHandler(sessionRegistry);
        when(sessionRegistry.getUserId("ws-1"))
                .thenReturn(Optional.of(UUID.randomUUID().toString()));
    }

    @Test
    void handle_validChatId_returnsUnsubscribedResult() {
        UUID chatId = UUID.randomUUID();

        StepVerifier.create(handler.handle("ws-1", 1, new ClientRpcPayload.ChatUnsubscribe(chatId.toString())))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(ClientPayload.ChatSubscriptionResult.class);
                    assertThat(result.status()).isEqualTo("unsubscribed");
                    assertThat(result.chatId()).isEqualTo(chatId);
                })
                .verifyComplete();
    }

    @Test
    void handle_notAuthenticated_throwsNotAuthenticatedError() {
        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(
                        "ws-1",
                        1,
                        new ClientRpcPayload.ChatUnsubscribe(UUID.randomUUID().toString())))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.NOT_AUTHENTICATED));
    }

    @Test
    void handle_blankChatId_throwsInvalidParamsError() {
        assertThatThrownBy(() -> handler.handle("ws-1", 1, new ClientRpcPayload.ChatUnsubscribe("  ")))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS));
    }

    @Test
    void handle_methodNameAndPayloadType_areCorrect() {
        assertThat(handler.getMethodName()).isEqualTo(ClientRpcPayload.ChatUnsubscribe.METHOD);
        assertThat(handler.getPayloadType()).isEqualTo(ClientRpcPayload.ChatUnsubscribe.class);
    }
}
