package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

class LiteLLMClientConfigTest {

    @Test
    void liteLLMClient_stopsWaitingAtTheConfiguredReadTimeout() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model/info", exchange -> {
            try {
                Thread.sleep(3_000);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The client already gave up and closed the connection.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            LiteLLMProperties properties = propertiesFor(server, Duration.ofMillis(300));
            LiteLLMClient client = new LiteLLMClientConfig().liteLLMClient(properties);

            long startedAt = System.nanoTime();
            assertThatThrownBy(client::listModels).isInstanceOf(RestClientException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(2_500));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void liteLLMClient_readsModelsFromTheProxy() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model/info", exchange -> {
            byte[] body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LiteLLMClient client =
                    new LiteLLMClientConfig().liteLLMClient(propertiesFor(server, Duration.ofSeconds(5)));

            assertThat(client.listModels().data()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private static LiteLLMProperties propertiesFor(HttpServer server, Duration readTimeout) {
        LiteLLMProperties properties = new LiteLLMProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(readTimeout);
        return properties;
    }
}
