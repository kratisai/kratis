package com.kratisai.controlplane.api.restdto;

import java.time.Instant;

public record IngestionBatchLogDto(
        String id, String batchId, String level, String step, String message, Instant createdAt) {}
