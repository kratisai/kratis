package com.kratisai.controlplane;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.kratisai.controlplane.config.LiteLLMProperties;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WireMock-based LLM API mock server for E2E tests. All POST traffic is routed through a single
 * catch-all stub to {@link MatcherBasedResponseTransformer}, which selects responses via {@link
 * HttpRequestMatcher} instances registered through {@link #addMatcher}.
 */
public final class WireMockLlmServer {

    private static final Logger logger = LoggerFactory.getLogger(WireMockLlmServer.class);

    private final WireMockServer wireMockServer;
    private final String baseUrl;
    private final List<HttpRequestMatcher> matchers = new CopyOnWriteArrayList<>();

    public WireMockLlmServer() {
        MatcherBasedResponseTransformer responseTransformer = new MatcherBasedResponseTransformer(matchers);
        this.wireMockServer = new WireMockServer(
                WireMockConfiguration.wireMockConfig().dynamicPort().extensions(responseTransformer));
        this.wireMockServer.start();

        // Add request listener to log all requests and responses (matched and unmatched)
        this.wireMockServer.addMockServiceRequestListener((request, response) -> {
            String method = request.getMethod().getName();
            String url = request.getUrl();
            String requestBody = request.getBodyAsString();
            int statusCode = response.getStatus();
            String responseBody = response.getBodyAsString();

            // Check if this was an unmatched request (WireMock returns 404 for unmatched)
            boolean unmatched = statusCode == 404;
            String matchStatus = unmatched ? "UNMATCHED" : "MATCHED";

            logger.info(
                    "[WireMock] {} {} {} → {} ({})\n  Request body: {}\n  Response body: {}",
                    matchStatus,
                    method,
                    url,
                    statusCode,
                    response.getStatusMessage(),
                    requestBody,
                    responseBody);
        });

        this.baseUrl = "http://localhost:" + wireMockServer.port();

        // Register a catch-all stub that delegates to the matcher-based transformer
        wireMockServer.stubFor(WireMock.post(WireMock.urlMatching(".*"))
                .atPriority(100)
                .willReturn(WireMock.aResponse().withTransformers(MatcherBasedResponseTransformer.NAME)));

        logger.info("WireMock LLM server started at {}", baseUrl);
    }

    public void stop() {
        if (wireMockServer != null) {
            logger.info("Stopping WireMock LLM server...");
            wireMockServer.stop();
        }
    }

    public void addMatcher(HttpRequestMatcher matcher) {
        matchers.add(matcher);
    }

    public void addMatchers(List<HttpRequestMatcher> newMatchers) {
        matchers.addAll(newMatchers);
    }

    public void stubGet(String urlPath, String jsonBody) {
        wireMockServer.stubFor(WireMock.get(WireMock.urlPathEqualTo(urlPath))
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(jsonBody)));
    }

    public int getTotalMatchedRequests() {
        return matchers.stream().mapToInt(HttpRequestMatcher::getMatchCount).sum();
    }

    public int countPostRequests(String path) {
        return wireMockServer
                .findAll(WireMock.postRequestedFor(WireMock.urlEqualTo(path)))
                .size();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public int getPort() {
        return wireMockServer.port();
    }

    /**
     * Returns a Docker-accessible base URL using the WireMock port. This URL is
     * reachable from the control-plane and from inside sandbox containers.
     */
    public String getBaseUrl(LiteLLMProperties liteLLMProperties) {
        return "http://" + java.net.URI.create(liteLLMProperties.getBaseUrl()).getHost() + ":" + wireMockServer.port();
    }
}
