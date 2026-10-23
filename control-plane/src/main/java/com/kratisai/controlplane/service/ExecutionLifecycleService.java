package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
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
    private final SandboxExecutionActivityRepository activityRepository;

    public ExecutionLifecycleService(
            SandboxExecutionRepository sandboxExecutionRepository,
            SandboxExecutionActivityRepository activityRepository) {
        this.sandboxExecutionRepository = Objects.requireNonNull(sandboxExecutionRepository);
        this.activityRepository = Objects.requireNonNull(activityRepository);
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
        return activityRepository.existsByEnvironmentIdAndStatus(environmentId, ActivityStatus.PENDING);
    }
}
