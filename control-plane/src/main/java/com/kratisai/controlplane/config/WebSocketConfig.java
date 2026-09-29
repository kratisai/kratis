package com.kratisai.controlplane.config;

import com.kratisai.controlplane.websocket.client.ClientWebSocketHandler;
import com.kratisai.controlplane.websocket.environment.EnvironmentWebSocketHandler;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** Max single-frame size accepted by the Tomcat WebSocket container (inbound and outbound). */
    public static final int MAX_TEXT_BUFFER_SIZE = 16 * 1024 * 1024;

    public static final int MAX_BINARY_BUFFER_SIZE = 16 * 1024 * 1024;

    /**
     * Max unsent outbound bytes the {@code ConcurrentWebSocketSessionDecorator} may queue while a
     * send is slow. Sized to the max frame so one large agent/canvas message can wait without
     * terminating the session; larger backlogs still force-close (unreliable client).
     */
    public static final int CONCURRENT_SEND_BUFFER_SIZE = MAX_TEXT_BUFFER_SIZE;

    /** Max time a concurrent send may block before the decorator closes the session. */
    public static final int CONCURRENT_SEND_TIME_LIMIT_MS = 20_000;

    private static final long MAX_SESSION_IDLE_TIMEOUT = 600_000L;

    private final EnvironmentWebSocketHandler environmentWebSocketHandler;
    private final ClientWebSocketHandler clientWebSocketHandler;
    private final String[] clientAllowedOrigins;
    private final String[] envAllowedOrigins;

    public WebSocketConfig(
            EnvironmentWebSocketHandler environmentWebSocketHandler,
            ClientWebSocketHandler clientWebSocketHandler,
            @Value("${kratis.security.client-allowed-origins:${kratis.security.allowed-origins:*}}")
                    String clientAllowedOriginsProperty,
            @Value("${kratis.security.env-allowed-origins:*}") String envAllowedOriginsProperty) {
        this.environmentWebSocketHandler = environmentWebSocketHandler;
        this.clientWebSocketHandler = clientWebSocketHandler;
        this.clientAllowedOrigins = parseOrigins(clientAllowedOriginsProperty);
        this.envAllowedOrigins = parseOrigins(envAllowedOriginsProperty);
    }

    private static String[] parseOrigins(String originsProperty) {
        String[] parsed = Arrays.stream(originsProperty.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        return parsed.length > 0 ? parsed : new String[] {"*"};
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(environmentWebSocketHandler, "/ws/env").setAllowedOrigins(envAllowedOrigins);
        registry.addHandler(clientWebSocketHandler, "/ws/client").setAllowedOrigins(clientAllowedOrigins);
    }

    /** Tomcat defaults are ~8KB; agents/canvas need multi-MB frames. */
    @Bean
    @Lazy(false)
    public ServletServerContainerFactoryBean servletServerContainerFactoryBean() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_BUFFER_SIZE);
        container.setMaxBinaryMessageBufferSize(MAX_BINARY_BUFFER_SIZE);
        container.setMaxSessionIdleTimeout(MAX_SESSION_IDLE_TIMEOUT);
        return container;
    }
}
