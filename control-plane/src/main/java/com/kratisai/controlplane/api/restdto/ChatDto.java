package com.kratisai.controlplane.api.restdto;

import java.time.Instant;
import java.util.UUID;

public record ChatDto(
        UUID id,
        String title,
        boolean allowAllPermissions,
        TopicStatus status,
        Instant lastActivityAt,
        LastActivityType lastActivityType,
        String lastActivityTargetId) {}
