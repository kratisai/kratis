package com.kratisai.controlplane.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PostHogCaptureClientTest {

    private static final String ENDPOINT = "https://us.i.posthog.com/capture/";

    @Test
    void capturePostsPostHogPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PostHogCaptureClient client = new PostHogCaptureClient(ENDPOINT, builder.build());

        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.api_key").value(PostHogCaptureClient.API_KEY))
                .andExpect(jsonPath("$.event").value("kratis_daily_usage"))
                .andExpect(jsonPath("$.distinct_id").value("install-123"))
                .andExpect(jsonPath("$.properties.version").value("1.2.3"))
                .andExpect(jsonPath("$.properties.team_count").value(1))
                .andExpect(jsonPath("$.properties.planning_cost").value(1.5))
                .andRespond(withStatus(HttpStatus.OK));

        client.capture(new PostHogCaptureRequest(
                "kratis_daily_usage",
                "install-123",
                Map.of("version", "1.2.3", "team_count", 1L, "planning_cost", 1.5)));

        server.verify();
    }

    @Test
    void captureSwallowsHttpErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PostHogCaptureClient client = new PostHogCaptureClient(ENDPOINT, builder.build());

        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatCode(() -> client.capture(new PostHogCaptureRequest("kratis_daily_usage", "install-1", Map.of())))
                .doesNotThrowAnyException();

        server.verify();
    }

    @Test
    void captureSwallowsTransportErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PostHogCaptureClient client = new PostHogCaptureClient(ENDPOINT, builder.build());

        server.expect(requestTo(ENDPOINT)).andRespond(withException(new IOException("connection refused")));

        assertThatCode(() -> client.capture(new PostHogCaptureRequest("kratis_daily_usage", "install-1", Map.of())))
                .doesNotThrowAnyException();

        server.verify();
    }
}
