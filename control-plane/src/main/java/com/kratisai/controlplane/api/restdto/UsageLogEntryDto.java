package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.SandboxExecutionStatus;
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
        SandboxExecutionStatus status,
        String userEmail,
        Set<String> modelIdentifiers,
        UsageType usageType,
        Long totalTokens,
        Double totalSpend,
        String targetUrl) {
    public UsageLogEntryDto {
        Objects.requireNonNull(modelIdentifiers, "modelIdentifiers is required");
        TreeSet<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        sorted.addAll(modelIdentifiers);
        modelIdentifiers = Collections.unmodifiableSet(sorted);
    }
}
