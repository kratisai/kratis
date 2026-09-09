package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.SandboxExecutionStatus;
import java.time.Instant;

public record UsageLogEntryDto(
        String id,
        Instant timestamp,
        Long durationSeconds,
        String activityTitle,
        String agentName,
        SandboxExecutionStatus status,
        String userEmail,
        String modelIdentifier,
        String usageType, // "CHAT", "EXECUTION", "INGESTION"
        Long totalTokens,
        Double totalSpend) {}
