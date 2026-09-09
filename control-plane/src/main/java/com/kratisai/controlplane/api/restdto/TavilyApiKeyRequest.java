package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request payload for updating Tavily API key")
public record TavilyApiKeyRequest(
        @Schema(description = "Tavily API key for web search integration")
        String tavilyApiKey) {}
