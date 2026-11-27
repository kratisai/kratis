package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.UsageType;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record UsageLogEntryDto(
        String id,
        Instant timestamp,
        Long durationSeconds,
        String activityTitle,
        String agentName,
        UsageLogStatus status,
        String userEmail,
        Set<ModelUsageDto> models,
        UsageType usageType,
        String targetUrl) {
    public UsageLogEntryDto {
        Objects.requireNonNull(models, "models is required");
        models = Collections.unmodifiableSet(new TreeSet<>(models));
    }
}
