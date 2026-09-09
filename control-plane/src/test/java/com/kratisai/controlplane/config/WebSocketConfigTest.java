package com.kratisai.controlplane.config;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.websocket.client.ClientWebSocketHandler;
import com.kratisai.controlplane.websocket.environment.EnvironmentWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

class WebSocketConfigTest {

    @Test
    void registerWebSocketHandlers_withWildcardOrigin_configuresWildcard() {
        EnvironmentWebSocketHandler envHandler = mock(EnvironmentWebSocketHandler.class);
        ClientWebSocketHandler clientHandler = mock(ClientWebSocketHandler.class);
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration envRegistration = mock(WebSocketHandlerRegistration.class);
        WebSocketHandlerRegistration clientRegistration = mock(WebSocketHandlerRegistration.class);

        when(registry.addHandler(eq(envHandler), eq("/ws/env"))).thenReturn(envRegistration);
        when(registry.addHandler(eq(clientHandler), eq("/ws/client"))).thenReturn(clientRegistration);

        WebSocketConfig config = new WebSocketConfig(envHandler, clientHandler, "*", "*");
        config.registerWebSocketHandlers(registry);

        verify(envRegistration).setAllowedOrigins(new String[] {"*"});
        verify(clientRegistration).setAllowedOrigins(new String[] {"*"});
    }

    @Test
    void registerWebSocketHandlers_withDistinctClientAndEnvOrigins_configuresSeparately() {
        EnvironmentWebSocketHandler envHandler = mock(EnvironmentWebSocketHandler.class);
        ClientWebSocketHandler clientHandler = mock(ClientWebSocketHandler.class);
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration envRegistration = mock(WebSocketHandlerRegistration.class);
        WebSocketHandlerRegistration clientRegistration = mock(WebSocketHandlerRegistration.class);

        when(registry.addHandler(eq(envHandler), eq("/ws/env"))).thenReturn(envRegistration);
        when(registry.addHandler(eq(clientHandler), eq("/ws/client"))).thenReturn(clientRegistration);

        WebSocketConfig config = new WebSocketConfig(
                envHandler,
                clientHandler,
                " https://kratis.example.com , http://localhost:5173 ",
                " http://sidecar.internal ");
        config.registerWebSocketHandlers(registry);

        verify(clientRegistration)
                .setAllowedOrigins(new String[] {"https://kratis.example.com", "http://localhost:5173"});
        verify(envRegistration).setAllowedOrigins(new String[] {"http://sidecar.internal"});
    }

    @Test
    void registerWebSocketHandlers_withEmptyOrigin_defaultsToWildcard() {
        EnvironmentWebSocketHandler envHandler = mock(EnvironmentWebSocketHandler.class);
        ClientWebSocketHandler clientHandler = mock(ClientWebSocketHandler.class);
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration envRegistration = mock(WebSocketHandlerRegistration.class);
        WebSocketHandlerRegistration clientRegistration = mock(WebSocketHandlerRegistration.class);

        when(registry.addHandler(eq(envHandler), eq("/ws/env"))).thenReturn(envRegistration);
        when(registry.addHandler(eq(clientHandler), eq("/ws/client"))).thenReturn(clientRegistration);

        WebSocketConfig config = new WebSocketConfig(envHandler, clientHandler, "", "");
        config.registerWebSocketHandlers(registry);

        verify(envRegistration).setAllowedOrigins(new String[] {"*"});
        verify(clientRegistration).setAllowedOrigins(new String[] {"*"});
    }
}
