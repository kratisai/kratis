package com.kratisai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link WireMockLlmServer}. Uses plain JUnit — no Spring context needed. */
class WireMockLlmServerTest {

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
    void constructor_shouldStartServerAndReturnBaseUrl() {
        String url = wireMockLlmServer.getBaseUrl();

        assertThat(url).isNotNull();
        assertThat(url).startsWith("http://localhost:");
        assertThat(URI.create(url).getPort()).isPositive();
    }

    @Test
    void getBaseUrl_shouldBeStable() {
        String firstCall = wireMockLlmServer.getBaseUrl();
        String secondCall = wireMockLlmServer.getBaseUrl();

        assertThat(firstCall).isSameAs(secondCall);
    }

    @Test
    void stop_shouldShutdownServer() {
        String baseUrl = wireMockLlmServer.getBaseUrl();
        wireMockLlmServer.stop();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        assertThatThrownBy(() -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOfAny(
                        java.net.ConnectException.class,
                        java.net.http.HttpTimeoutException.class,
                        java.io.IOException.class);
    }

    @Test
    void unmatchedPost_shouldReturnNoMatcherMatchedError() throws Exception {
        String response = postChatCompletion("{}");
        JsonNode json = objectMapper.readTree(response);

        assertThat(json.get("error").asText()).contains("no matcher matched");
    }

    @Test
    void stubGet_shouldReturnJsonForPreflightRequests() throws Exception {
        wireMockLlmServer.stubGet("/v1/models", LlmResponseBuilders.openAiModelsList());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMockLlmServer.getBaseUrl() + "/v1/models"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(objectMapper.readTree(response.body()).get("data").isArray()).isTrue();
    }

    @Test
    void stubGet_shouldMatchPathWithQueryString() throws Exception {
        wireMockLlmServer.stubGet("/v1/models", LlmResponseBuilders.openAiModelsList());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMockLlmServer.getBaseUrl() + "/v1/models?limit=10"))
                .GET()
                .build();

        assertThat(httpClient
                        .send(request, HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    void unregisteredGet_shouldReturn404() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMockLlmServer.getBaseUrl() + "/v1/models"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void addMatcher_shouldMatchRequestByBodyContent() throws Exception {
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("hello world")
                .jsonResponse(LlmResponseBuilders.openAiText("matched hello world"))
                .build());

        String response = postChatCompletion("{\"messages\":[{\"role\":\"user\",\"content\":\"hello world\"}]}");
        JsonNode json = objectMapper.readTree(response);

        assertThat(json.get("choices").get(0).get("message").get("content").asText())
                .isEqualTo("matched hello world");
    }

    @Test
    void addMatcher_shouldReturnToolCallResponse() throws Exception {
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("do something")
                .jsonResponse(LlmResponseBuilders.openAiToolCall("my_tool", "{\"key\":\"value\"}"))
                .build());

        String response = postChatCompletion("{\"messages\":[{\"role\":\"user\",\"content\":\"do something\"}]}");
        JsonNode json = objectMapper.readTree(response);

        assertThat(json.get("choices").get(0).get("finish_reason").asText()).isEqualTo("tool_calls");
        JsonNode toolCalls = json.get("choices").get(0).get("message").get("tool_calls");
        assertThat(toolCalls.isArray()).isTrue();
        assertThat(toolCalls.get(0).get("function").get("name").asText()).isEqualTo("my_tool");
        assertThat(toolCalls.get(0).get("function").get("arguments").asText()).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void addMatcher_firstMatchWins() throws Exception {
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("test")
                .jsonResponse(LlmResponseBuilders.openAiText("first matcher"))
                .maxMatches(1)
                .build());

        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("test")
                .jsonResponse(LlmResponseBuilders.openAiText("second matcher"))
                .build());

        String response = postChatCompletion("{\"content\":\"test\"}");
        JsonNode json = objectMapper.readTree(response);

        assertThat(json.get("choices").get(0).get("message").get("content").asText())
                .isEqualTo("first matcher");
    }

    @Test
    void addMatcher_maxMatchesShouldExhaust() throws Exception {
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("test")
                .jsonResponse(LlmResponseBuilders.openAiText("limited matcher"))
                .maxMatches(1)
                .build());

        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("test")
                .jsonResponse(LlmResponseBuilders.openAiText("catch-all"))
                .build());

        String response1 = postChatCompletion("{\"content\":\"test\"}");
        JsonNode json1 = objectMapper.readTree(response1);
        assertThat(json1.get("choices").get(0).get("message").get("content").asText())
                .isEqualTo("limited matcher");

        String response2 = postChatCompletion("{\"content\":\"test\"}");
        JsonNode json2 = objectMapper.readTree(response2);
        assertThat(json2.get("choices").get(0).get("message").get("content").asText())
                .isEqualTo("catch-all");
    }

    @Test
    void addMatcher_catchAllMatchesWhenNoSpecificMatcherMatches() throws Exception {
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("specific")
                .jsonResponse(LlmResponseBuilders.openAiText("specific response"))
                .build());

        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .jsonResponse(LlmResponseBuilders.openAiText("catch-all response"))
                .build());

        String response = postChatCompletion("{\"content\":\"something else\"}");
        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("choices").get(0).get("message").get("content").asText())
                .isEqualTo("catch-all response");
    }

    @Test
    void addMatchers_shouldAddMultipleMatchers() throws Exception {
        wireMockLlmServer.addMatchers(List.of(
                HttpRequestMatcher.builder()
                        .contains("first")
                        .jsonResponse(LlmResponseBuilders.openAiText("first"))
                        .maxMatches(1)
                        .build(),
                HttpRequestMatcher.builder()
                        .contains("second")
                        .jsonResponse(LlmResponseBuilders.openAiText("second"))
                        .maxMatches(1)
                        .build()));

        String response1 = postChatCompletion("{\"content\":\"first\"}");
        assertThat(objectMapper
                        .readTree(response1)
                        .get("choices")
                        .get(0)
                        .get("message")
                        .get("content")
                        .asText())
                .isEqualTo("first");

        String response2 = postChatCompletion("{\"content\":\"second\"}");
        assertThat(objectMapper
                        .readTree(response2)
                        .get("choices")
                        .get(0)
                        .get("message")
                        .get("content")
                        .asText())
                .isEqualTo("second");
    }

    @Test
    void addMatcher_sseResponse_shouldSetContentTypeEventStream() throws Exception {
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("stream me")
                .sseResponse(LlmResponseBuilders.openAiSseText("hello"))
                .build());

        HttpResponse<String> response = postChatCompletionWithResponse("{\"content\":\"stream me\"}");

        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        assertThat(response.body()).contains("data:");
        assertThat(response.body()).contains("[DONE]");
    }

    @Test
    void addMatcher_jsonResponse_shouldSetContentTypeJson() throws Exception {
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("json me")
                .jsonResponse(LlmResponseBuilders.openAiText("hello"))
                .build());

        HttpResponse<String> response = postChatCompletionWithResponse("{\"content\":\"json me\"}");

        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
    }

    @Test
    void addMatcher_sseToolCall_shouldHaveCorrectFinishReasonPlacement() throws Exception {
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("tool call stream")
                .sseResponse(LlmResponseBuilders.openAiSseToolCall("my_tool", "{\"key\":\"value\"}"))
                .build());

        String body = postChatCompletion("{\"content\":\"tool call stream\"}");

        String[] lines = body.split("\n\n");
        assertThat(lines).hasSizeGreaterThanOrEqualTo(3);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode chunk1 = mapper.readTree(lines[0].replace("data: ", ""));
        JsonNode chunk2 = mapper.readTree(lines[1].replace("data: ", ""));

        JsonNode choice1 = chunk1.get("choices").get(0);
        assertThat(choice1.get("finish_reason").isNull()).isTrue();
        assertThat(choice1.get("delta").get("tool_calls").get(0).get("id").asText())
                .isNotEmpty();
        assertThat(choice1.get("delta")
                        .get("tool_calls")
                        .get(0)
                        .get("function")
                        .get("name")
                        .asText())
                .isEqualTo("my_tool");
        assertThat(choice1.get("delta")
                        .get("tool_calls")
                        .get(0)
                        .get("function")
                        .get("arguments")
                        .asText())
                .isEqualTo("{\"key\":\"value\"}");

        JsonNode choice2 = chunk2.get("choices").get(0);
        assertThat(choice2.get("finish_reason").asText()).isEqualTo("tool_calls");

        assertThat(lines[lines.length - 1]).isEqualTo("data: [DONE]");
    }

    private String postChatCompletion(String body) throws Exception {
        String baseUrl = wireMockLlmServer.getBaseUrl();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private HttpResponse<String> postChatCompletionWithResponse(String body) throws Exception {
        String baseUrl = wireMockLlmServer.getBaseUrl();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
