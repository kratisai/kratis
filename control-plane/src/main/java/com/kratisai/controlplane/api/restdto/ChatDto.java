package com.kratisai.controlplane.api.restdto;

import java.time.Instant;
import java.util.UUID;

public record ChatDto(
        UUID id,
        UUID teamId,
        String title,
        String createdByDisplayName,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        Double totalSpend,
        Long totalTokens,
        Long promptTokens,
        Long completionTokens,
        Instant usageLastUpdatedAt) {}
