package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.AgentHarness;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Active time is reported as two fields so a client can tick a live count without the server
 * republishing: {@code previousActiveSeconds} is the total of closed RUNNING segments, and, while
 * the status is RUNNING, {@code activeSince} is the start of the open segment. The live total is
 * {@code previousActiveSeconds + (now - activeSince)}.
 */
public record SandboxExecutionDto(
        UUID id,
        UUID chatId,
        Integer exitCode,
        SandboxExecutionStatus status,
        Instant startedAt,
        Instant completedAt,
        Long previousActiveSeconds,
        Instant activeSince,
        UUID repositoryId,
        AgentHarness harness,
        String taskPrompt,
        Long totalTokens,
        Long promptTokens,
        Long completionTokens,
        Double totalSpend,
        Instant usageLastUpdatedAt,
        UUID environmentId,
        EnvironmentStatus environmentStatus) {}
