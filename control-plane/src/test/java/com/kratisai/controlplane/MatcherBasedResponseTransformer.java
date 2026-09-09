package com.kratisai.controlplane;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformer;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A WireMock {@link ResponseDefinitionTransformer} that holds a list of {@link HttpRequestMatcher}
 * instances and selects responses based on request body content.
 *
 * <p>The transformer iterates through the matchers in order and returns the response from the first
 * matcher that matches the request body. If no matcher matches, it returns a default JSON error
 * response.
 *
 * <p>This transformer is designed to be used with a single low-priority catch-all WireMock stub per
 * endpoint. The transformer handles the actual response selection logic in Java code rather than
 * relying on WireMock's stub matching configuration.
 *
 * <p>Note: The content type is embedded in the response body by the {@link HttpRequestMatcher} via
 * {@link LlmResponseBuilders}. The WireMock stub's original headers are preserved.
 */
public class MatcherBasedResponseTransformer implements ResponseDefinitionTransformerV2 {

    static final String NAME = "matcher-based-response-transformer";
    private static final String DEFAULT_RESPONSE = "{\"error\": \"no matcher matched\"}";
    private static final Logger logger = LoggerFactory.getLogger(MatcherBasedResponseTransformer.class);

    private final List<HttpRequestMatcher> matchers;

    /**
     * Creates a new transformer with the given shared matcher list.
     *
     * @param matchers the list of matchers to evaluate against each request
     */
    public MatcherBasedResponseTransformer(List<HttpRequestMatcher> matchers) {
        this.matchers = matchers;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
        Request request = serveEvent.getRequest();
        String body = request.getBodyAsString();

        logger.info("[MatcherTransformer] Evaluating {} matchers for request to {}", matchers.size(), request.getUrl());
        for (int i = 0; i < matchers.size(); i++) {
            HttpRequestMatcher matcher = matchers.get(i);
            boolean matches = matcher.matches(body);
            logger.info(
                    "[MatcherTransformer] Matcher {} - matches: {}, exhausted: {}, matchCount: {}",
                    i,
                    matches,
                    matcher.isExhausted(),
                    matcher.getMatchCount());
            if (matches) {
                String response = matcher.getResponse(body);
                assert response != null;
                logger.info(
                        "[MatcherTransformer] Matcher {} matched, returning response (first 200 chars): {}",
                        i,
                        response.substring(0, Math.min(200, response.length())));
                return aResponse()
                        .withStatus(200)
                        .withBody(response)
                        .withHeader("Content-Type", matcher.getContentType())
                        .build();
            }
        }

        logger.info("[MatcherTransformer] No matcher matched, returning default response");
        // No matcher matched — return a default OpenAI-compatible response so agents
        // can finish gracefully even when no specific matcher was configured.
        return aResponse()
                .withStatus(200)
                .withBody(DEFAULT_RESPONSE)
                .withHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }
}
