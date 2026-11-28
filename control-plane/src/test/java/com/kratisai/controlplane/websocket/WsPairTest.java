package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

class WsPairTest {

    @Test
    void webSocketClientUsesConnectBudgetForHandshakeIo() {
        StandardWebSocketClient client = WsPair.webSocketClient();

        assertThat(client.getUserProperties())
                .containsEntry(
                        "org.apache.tomcat.websocket.IO_TIMEOUT_MS",
                        String.valueOf(TimeUnit.SECONDS.toMillis(WsPair.CONNECT_TIMEOUT_SECONDS)));
    }

    @Test
    void clientSubscriptionTimeoutFailsInsteadOfProceeding() throws Exception {
        ClientWebSocketFixture clientFixture = mock(ClientWebSocketFixture.class);
        when(clientFixture.awaitSubscription(WsPair.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .thenReturn(false);

        assertThatThrownBy(() -> WsPair.awaitClientSubscribe(clientFixture))
                .isInstanceOf(TimeoutException.class)
                .hasMessage("Client WebSocket subscription was not acknowledged");
    }

    @Test
    void closeClosesOpenSessions() throws Exception {
        WebSocketSession client = mock(WebSocketSession.class);
        WebSocketSession sidecar = mock(WebSocketSession.class);
        when(client.isOpen()).thenReturn(true);
        when(sidecar.isOpen()).thenReturn(true);
        try (WsPair pair = new WsPair(null, null, client, sidecar)) {
            assertThat(pair.clientSession()).isSameAs(client);
            assertThat(pair.sidecarSession()).isSameAs(sidecar);
        }
        verify(client).close();
        verify(sidecar).close();
    }
}
