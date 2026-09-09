package com.kratisai.controlplane.planningagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.service.TeamService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WebSearchTool {

    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String TAVILY_API_URL = "https://api.tavily.com/search";
    private static final HttpClient DEFAULT_HTTP_CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final TeamService teamService;
    private final HttpClient httpClient;
    private final String tavilyApiUrl;

    @Autowired
    public WebSearchTool(TeamService teamService) {
        this(teamService, DEFAULT_HTTP_CLIENT, TAVILY_API_URL);
    }

    WebSearchTool(TeamService teamService, HttpClient httpClient, String tavilyApiUrl) {
        this.teamService = teamService;
        this.httpClient = httpClient;
        this.tavilyApiUrl = tavilyApiUrl;
    }

    public record WebSearchResponse(
            @ToolParam(description = "Condensed markdown summary of search results")
            String markdownSummary) {}

    @Tool(name = "web_search", description = "Search the web for information formatted as a condensed summary")
    public WebSearchTool.WebSearchResponse webSearch(
            @ToolParam(description = "The search query string") String query, ToolContext toolContext) {
        UUID teamId = (UUID) toolContext.getContext().get("teamId");
        logger.info("Executing web search for team {} query: {}", teamId, query);

        // Look up the Tavily API key for the team
        String tavilyApiKey = teamService.getTavilyApiKey(teamId);

        if (tavilyApiKey == null || tavilyApiKey.isEmpty()) {
            return new WebSearchResponse("## Search Results for: "
                    + query
                    + "\n\n"
                    + "Web search is not configured for this team. Please add a Tavily API key in Team Settings.");
        }

        try {
            String response = executeTavilySearch(tavilyApiKey, query);
            String summary = formatTavilyResponse(response, query);
            return new WebSearchResponse(summary);
        } catch (Exception e) {
            logger.error("Web search failed for query: {}", query, e);
            return new WebSearchResponse(
                    "## Search Results for: " + query + "\n\n" + "Search failed: " + e.getMessage());
        }
    }

    private String executeTavilySearch(String apiKey, String query) throws Exception {
        String requestBody = objectMapper.writeValueAsString(new TavilySearchRequest(query, 5, "advanced"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tavilyApiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Tavily API returned status: " + response.statusCode() + " - " + response.body());
        }

        return response.body();
    }

    private String formatTavilyResponse(String jsonResponse, String query) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode results = root.get("results");

            StringBuilder sb = new StringBuilder();
            sb.append("## Search Results for: ").append(query).append("\n\n");

            if (results != null && results.isArray()) {
                for (JsonNode result : results) {
                    String title = result.has("title") ? result.get("title").asText() : "No title";
                    String content =
                            result.has("content") ? result.get("content").asText() : "";
                    String url = result.has("url") ? result.get("url").asText() : "";

                    sb.append("### ").append(title).append("\n\n");
                    sb.append(content).append("\n\n");
                    if (!url.isEmpty()) {
                        sb.append("Source: ").append(url).append("\n\n");
                    }
                    sb.append("---\n\n");
                }
            } else {
                sb.append("No results found.\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "## Search Results for: " + query + "\n\nFailed to parse search results: " + e.getMessage();
        }
    }

    /** Request body for Tavily API. */
    private record TavilySearchRequest(String query, int maxResults, String searchDepth) {}
}
