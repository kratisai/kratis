package com.kratisai.controlplane.client;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class PostHogCaptureClient implements PostHogClient {

    public static final String API_KEY = "phc_o3jHiovSo4s59gR4BoMy9GUkw96BLHFXX2tWQXYEdYVw";

    private static final Logger logger = LoggerFactory.getLogger(PostHogCaptureClient.class);

    private final RestClient restClient;
    private final String endpoint;

    public PostHogCaptureClient(String endpoint, long httpTimeoutMs) {
        this(endpoint, restClient(httpTimeoutMs));
    }

    PostHogCaptureClient(String endpoint, RestClient restClient) {
        this.endpoint = endpoint;
        this.restClient = restClient;
    }

    private static RestClient restClient(long httpTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(httpTimeoutMs));
        requestFactory.setReadTimeout(Math.toIntExact(httpTimeoutMs));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public void capture(PostHogCaptureRequest request) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("api_key", API_KEY);
            body.put("event", request.event());
            body.put("distinct_id", request.distinctId());
            body.put("properties", request.properties());
            restClient
                    .post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            logger.debug(
                    "Posted usage telemetry event '{}' for installation {}", request.event(), request.distinctId());
        } catch (RestClientException e) {
            logger.warn("Failed to post usage telemetry event '{}': {}", request.event(), e.getMessage());
        }
    }
}
