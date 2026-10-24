package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class WebSocketFixtureTest {

    @Test
    void isNotClosedBeforeConnectionCloses() {
        ClientWebSocketFixture fixture = new ClientWebSocketFixture("token", null);

        assertThat(fixture.isClosed()).isFalse();
        assertThat(fixture.closeStatus()).isEmpty();
    }

    @Test
    void recordsCloseStatusWhenServerClosesConnection() {
        ClientWebSocketFixture fixture = new ClientWebSocketFixture("token", null);

        fixture.afterConnectionClosed(mock(WebSocketSession.class), CloseStatus.SERVER_ERROR);

        assertThat(fixture.isClosed()).isTrue();
        assertThat(fixture.closeStatus()).contains(CloseStatus.SERVER_ERROR);
    }
}
