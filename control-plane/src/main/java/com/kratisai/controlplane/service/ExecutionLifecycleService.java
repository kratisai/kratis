package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Answers whether an environment still has RUNNING or HITL-waiting executions. Execution status
 * changes only on agent turn boundaries and user actions, never on silence.
 */
@Service
@ConditionalOnProperty(value = "kratis.sandbox.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionLifecycleService {

    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final PendingHitlRegistry pendingHitlRegistry;

    public ExecutionLifecycleService(
            SandboxExecutionRepository sandboxExecutionRepository, PendingHitlRegistry pendingHitlRegistry) {
        this.sandboxExecutionRepository = Objects.requireNonNull(sandboxExecutionRepository);
        this.pendingHitlRegistry = Objects.requireNonNull(pendingHitlRegistry);
    }

    public boolean hasRunningExecutions(UUID environmentId) {
        if (environmentId == null) {
            return false;
        }
        return sandboxExecutionRepository.existsByEnvironmentIdAndStatus(environmentId, SandboxExecutionStatus.RUNNING);
    }

    public boolean hasPendingHitl(UUID environmentId) {
        if (environmentId == null) {
            return false;
        }
        return sandboxExecutionRepository
                .findByEnvironmentIdAndStatusIn(
                        environmentId, List.of(SandboxExecutionStatus.RUNNING, SandboxExecutionStatus.IDLE))
                .stream()
                .anyMatch(execution -> pendingHitlRegistry.hasPendingForExecution(execution.getId()));
    }
}
