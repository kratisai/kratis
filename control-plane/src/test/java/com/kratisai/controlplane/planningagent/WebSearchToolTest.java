package com.kratisai.controlplane.planningagent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.kratisai.controlplane.service.TeamService;
import java.net.http.HttpClient;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class WebSearchToolTest {

    @Mock
    private TeamService teamService;

    private WireMockServer wireMockServer;
    private WebSearchTool webSearchTool;
    private UUID teamId;

    @BeforeEach
    void setUp() {
        wireMockServer =
                new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();

        webSearchTool =
                new WebSearchTool(teamService, HttpClient.newHttpClient(), wireMockServer.baseUrl() + "/search");
        teamId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void webSearch_withApiKey_shouldReturnFormattedResults() {
        String apiKey = "tvly-test-key";
        when(teamService.getTavilyApiKey(teamId)).thenReturn(apiKey);

        wireMockServer.stubFor(post(urlEqualTo("/search"))
                .withHeader("Authorization", equalTo("Bearer " + apiKey))
                .withRequestBody(matchingJsonPath("$.query", equalTo("Spring Boot testing")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "results": [
                                    {"title": "Spring Boot Testing Guide", "content": "How to test Spring Boot apps.", "url": "https://example.com/1"},
                                    {"title": "No Url Result", "content": "Content without a url."}
                                  ]
                                }
                                """)));

        ToolContext toolContext = new PlanningContext(teamId, UUID.randomUUID()).toToolContext();

        WebSearchTool.WebSearchResponse response = webSearchTool.webSearch("Spring Boot testing", toolContext);

        assertThat(response).isNotNull();
        assertThat(response.markdownSummary()).contains("Search Results for: Spring Boot testing");
        assertThat(response.markdownSummary()).contains("### Spring Boot Testing Guide");
        assertThat(response.markdownSummary()).contains("How to test Spring Boot apps.");
        assertThat(response.markdownSummary()).contains("Source: https://example.com/1");
        assertThat(response.markdownSummary()).contains("No Url Result");
        assertThat(response.markdownSummary()).contains("---");

        wireMockServer.verify(
                postRequestedFor(urlEqualTo("/search")).withHeader("Authorization", equalTo("Bearer " + apiKey)));
    }

    @Test
    void webSearch_withNoApiKey_shouldReturnUnconfiguredMessage() {
        when(teamService.getTavilyApiKey(teamId)).thenReturn(null);

        ToolContext toolContext = new PlanningContext(teamId, UUID.randomUUID()).toToolContext();

        WebSearchTool.WebSearchResponse response = webSearchTool.webSearch("Java Spring", toolContext);

        assertThat(response).isNotNull();
        assertThat(response.markdownSummary()).contains("Web search is not configured");
        assertThat(response.markdownSummary()).contains("Tavily API key");
    }

    @Test
    void webSearch_withEmptyApiKey_shouldReturnUnconfiguredMessage() {
        when(teamService.getTavilyApiKey(teamId)).thenReturn("");

        ToolContext toolContext = new PlanningContext(teamId, UUID.randomUUID()).toToolContext();

        WebSearchTool.WebSearchResponse response = webSearchTool.webSearch("Java Spring", toolContext);

        assertThat(response).isNotNull();
        assertThat(response.markdownSummary()).contains("Web search is not configured");
    }

    @Test
    void webSearch_whenApiReturnsError_shouldReturnErrorMessage() {
        String apiKey = "tvly-test-key";
        when(teamService.getTavilyApiKey(teamId)).thenReturn(apiKey);

        wireMockServer.stubFor(post(urlEqualTo("/search"))
                .willReturn(aResponse().withStatus(429).withBody("Rate limited")));

        ToolContext toolContext = new PlanningContext(teamId, UUID.randomUUID()).toToolContext();

        WebSearchTool.WebSearchResponse response = webSearchTool.webSearch("Spring Boot testing", toolContext);

        assertThat(response).isNotNull();
        assertThat(response.markdownSummary()).contains("Search failed:");
        assertThat(response.markdownSummary()).contains("429");
    }

    @Test
    void webSearch_whenResponseHasNoResultsField_shouldReturnNoResultsMessage() {
        String apiKey = "tvly-test-key";
        when(teamService.getTavilyApiKey(teamId)).thenReturn(apiKey);

        wireMockServer.stubFor(post(urlEqualTo("/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"query\": \"Spring Boot testing\"}")));

        ToolContext toolContext = new PlanningContext(teamId, UUID.randomUUID()).toToolContext();

        WebSearchTool.WebSearchResponse response = webSearchTool.webSearch("Spring Boot testing", toolContext);

        assertThat(response).isNotNull();
        assertThat(response.markdownSummary()).contains("No results found");
    }

    @Test
    void webSearch_whenResponseIsInvalidJson_shouldReturnParseErrorMessage() {
        String apiKey = "tvly-test-key";
        when(teamService.getTavilyApiKey(teamId)).thenReturn(apiKey);

        wireMockServer.stubFor(post(urlEqualTo("/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("not valid json")));

        ToolContext toolContext = new PlanningContext(teamId, UUID.randomUUID()).toToolContext();

        WebSearchTool.WebSearchResponse response = webSearchTool.webSearch("Spring Boot testing", toolContext);

        assertThat(response).isNotNull();
        assertThat(response.markdownSummary()).contains("Failed to parse search results");
    }
}
