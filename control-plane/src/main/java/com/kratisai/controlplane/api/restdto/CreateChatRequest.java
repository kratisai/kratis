package com.kratisai.controlplane.api.restdto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.UUID;

public record CreateChatRequest(
        @JsonProperty("teamId") UUID teamId,
        @JsonProperty("providerId") UUID providerId,
        @JsonProperty("modelName") String modelName,
        @JsonProperty("message") String message) {
    public CreateChatRequest {
        Objects.requireNonNull(teamId, "teamId is required");
        Objects.requireNonNull(providerId, "providerId is required");
        Objects.requireNonNull(modelName, "modelName is required");
        Objects.requireNonNull(message, "message is required");
        if (modelName.isBlank()) throw new IllegalArgumentException("modelName must not be blank");
        if (message.isBlank()) throw new IllegalArgumentException("message must not be blank");
    }
}
