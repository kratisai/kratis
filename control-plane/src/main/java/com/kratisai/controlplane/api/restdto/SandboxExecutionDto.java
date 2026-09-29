package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.AgentHarness;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import java.time.Instant;
import java.util.UUID;

public record SandboxExecutionDto(
        UUID id,
        UUID chatId,
        Integer exitCode,
        SandboxExecutionStatus status,
        Instant startedAt,
        Instant completedAt,
        UUID repositoryId,
        AgentHarness harness,
        String taskPrompt,
        Long totalTokens,
        Long promptTokens,
        Long completionTokens,
        Double totalSpend,
        Instant usageLastUpdatedAt) {}
