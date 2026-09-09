package com.kratisai.controlplane.websocket.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.SubscriptionRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ClientTeamUnsubscribeRpcHandlerTest {

    private ClientSessionRegistry sessionRegistry;
    private SubscriptionRegistry subscriptionRegistry;
    private ClientTeamUnsubscribeRpcHandler handler;

    @BeforeEach
    void setUp() {
        sessionRegistry = mock(ClientSessionRegistry.class);
        subscriptionRegistry = mock(SubscriptionRegistry.class);
        handler = new ClientTeamUnsubscribeRpcHandler(sessionRegistry, subscriptionRegistry);
        when(sessionRegistry.getUserId("ws-1"))
                .thenReturn(Optional.of(UUID.randomUUID().toString()));
    }

    @Test
    void handle_unsubscribedTeam_returnsUnsubscribedResult() {
        UUID teamId = UUID.randomUUID();
        when(subscriptionRegistry.unsubscribe("ws-1", teamId)).thenReturn(true);

        StepVerifier.create(handler.handle("ws-1", null, new ClientRpcPayload.Unsubscribe(teamId.toString())))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(ClientPayload.SubscriptionResult.class);
                    assertThat(result.channel()).isEqualTo(teamId.toString());
                    assertThat(result.message()).contains("Unsubscribed");
                })
                .verifyComplete();
        verify(subscriptionRegistry).unsubscribe("ws-1", teamId);
    }

    @Test
    void handle_notSubscribedTeam_returnsNotSubscribedResult() {
        UUID teamId = UUID.randomUUID();
        when(subscriptionRegistry.unsubscribe("ws-1", teamId)).thenReturn(false);

        StepVerifier.create(handler.handle("ws-1", null, new ClientRpcPayload.Unsubscribe(teamId.toString())))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(ClientPayload.SubscriptionResult.class);
                    assertThat(result.channel()).isEqualTo(teamId.toString());
                    assertThat(result.message()).contains("Not subscribed");
                })
                .verifyComplete();
    }

    @Test
    void handle_notAuthenticated_throwsNotAuthenticatedError() {
        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(
                        "ws-1",
                        null,
                        new ClientRpcPayload.Unsubscribe(UUID.randomUUID().toString())))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.NOT_AUTHENTICATED));
    }

    @Test
    void handle_blankTeamId_throwsInvalidParamsError() {
        assertThatThrownBy(() -> handler.handle("ws-1", null, new ClientRpcPayload.Unsubscribe("  ")))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS));
    }

    @Test
    void handle_invalidTeamIdFormat_throwsInvalidParamsError() {
        assertThatThrownBy(() -> handler.handle("ws-1", null, new ClientRpcPayload.Unsubscribe("not-a-uuid")))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS));
    }
}
