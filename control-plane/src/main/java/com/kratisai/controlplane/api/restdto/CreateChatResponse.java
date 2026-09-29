package com.kratisai.controlplane.api.restdto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record CreateChatResponse(
        @JsonProperty("id") UUID id,
        @JsonProperty("createdAt") String createdAt) {}
