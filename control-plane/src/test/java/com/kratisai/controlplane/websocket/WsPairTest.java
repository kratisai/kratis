package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class WsPairTest {

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
