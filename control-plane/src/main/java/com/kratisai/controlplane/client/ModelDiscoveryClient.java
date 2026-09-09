package com.kratisai.controlplane.client;

import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface ModelDiscoveryClient {

    @GetExchange(accept = "application/json")
    String getModels(URI uri, @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader);

    @GetExchange(accept = "application/json")
    String getModels(URI uri);

    /**
     * Anthropic's model discovery endpoint requires the API key in the {@code x-api-key} header
     * plus the {@code anthropic-version} header.
     */
    @GetExchange(accept = "application/json")
    String getModelsWithXApiKey(
            URI uri,
            @RequestHeader("x-api-key") String apiKey,
            @RequestHeader("anthropic-version") String anthropicVersion);

    /**
     * Azure OpenAI's model discovery endpoint requires the API key in the {@code api-key} header.
     */
    @GetExchange(accept = "application/json")
    String getModelsWithApiKeyHeader(URI uri, @RequestHeader("api-key") String apiKey);
}
