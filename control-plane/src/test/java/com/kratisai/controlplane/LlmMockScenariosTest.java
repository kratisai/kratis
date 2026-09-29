package com.kratisai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LlmMockScenarios}. Uses plain JUnit — no Spring context needed. */
class LlmMockScenariosTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    private WireMockLlmServer wireMockLlmServer;

    @BeforeEach
    void setUp() {
        wireMockLlmServer = new WireMockLlmServer();
    }

    @AfterEach
    void tearDown() {
        if (wireMockLlmServer != null) {
            wireMockLlmServer.stop();
        }
    }

    @Test
    void openAiCompat_shouldServeModelsList() throws Exception {
        LlmMockScenarios.openAiCompat(wireMockLlmServer);

        HttpResponse<String> response = get("/v1/models");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("object").asText()).isEqualTo("list");
        assertThat(json.get("data").isArray()).isTrue();
    }

    @Test
    void mistral_shouldServeModelsListAndUserInfo() throws Exception {
        LlmMockScenarios.mistral(wireMockLlmServer);

        HttpResponse<String> userInfo = get("/v1/users/me");

        assertThat(get("/v1/models").statusCode()).isEqualTo(200);
        assertThat(userInfo.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(userInfo.body()).get("email").asText()).isEqualTo("mock@kratis.ai");
    }

    @Test
    void gemini_shouldServeGeminiModelsList() throws Exception {
        LlmMockScenarios.gemini(wireMockLlmServer);

        HttpResponse<String> response = get("/gemini/v1beta/models");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.body()).get("models").isArray())
                .isTrue();
    }

    @Test
    void scenarios_shouldOnlyRegisterTheirOwnStubs() throws Exception {
        LlmMockScenarios.openAiCompat(wireMockLlmServer);

        assertThat(get("/v1/users/me").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMockLlmServer.getBaseUrl() + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
