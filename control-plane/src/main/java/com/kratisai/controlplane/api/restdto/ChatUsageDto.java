package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;

@Schema(description = "Aggregate LLM usage for a chat")
public record ChatUsageDto(
        Double totalSpend, Long totalTokens, Long promptTokens, Long completionTokens, Instant usageLastUpdatedAt) {
    public ChatUsageDto {
        Objects.requireNonNull(totalSpend, "totalSpend is required");
        Objects.requireNonNull(totalTokens, "totalTokens is required");
        Objects.requireNonNull(promptTokens, "promptTokens is required");
        Objects.requireNonNull(completionTokens, "completionTokens is required");
    }
}
