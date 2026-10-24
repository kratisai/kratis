package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SandboxExecutionActivityRepository extends JpaRepository<SandboxExecutionActivity, UUID> {

    List<SandboxExecutionActivity> findByExecutionIdOrderBySequenceAsc(UUID executionId);

    Optional<SandboxExecutionActivity> findByExecutionIdAndActionId(UUID executionId, String actionId);

    Optional<SandboxExecutionActivity> findFirstByExecutionIdAndActionIdOrderBySequenceDesc(
            UUID executionId, String actionId);

    List<SandboxExecutionActivity> findByExecutionIdAndActivityTypeOrderBySequenceAsc(
            UUID executionId, ActivityType activityType);

    List<SandboxExecutionActivity> findByExecutionIdAndStatusOrderBySequenceAsc(
            UUID executionId, ActivityStatus status);

    List<SandboxExecutionActivity> findByExecutionIdAndStatus(UUID executionId, ActivityStatus status);

    boolean existsByExecutionIdAndStatus(UUID executionId, ActivityStatus status);

    List<SandboxExecutionActivity> findByStatusAndCreatedAtBefore(ActivityStatus status, Instant cutoff);

    @Query("SELECT a FROM SandboxExecutionActivity a JOIN SandboxExecution e ON a.executionId = e.id"
            + " WHERE e.environment.id = :environmentId AND a.status = :status")
    List<SandboxExecutionActivity> findByEnvironmentIdAndStatus(
            @Param("environmentId") UUID environmentId, @Param("status") ActivityStatus status);

    @Query("SELECT COUNT(a) > 0 FROM SandboxExecutionActivity a JOIN SandboxExecution e ON a.executionId = e.id"
            + " WHERE e.environment.id = :environmentId AND a.status = :status")
    boolean existsByEnvironmentIdAndStatus(
            @Param("environmentId") UUID environmentId, @Param("status") ActivityStatus status);

    /** An environment outlives its executions; action ids are only unique within one execution. */
    @Query("SELECT a FROM SandboxExecutionActivity a JOIN SandboxExecution e ON a.executionId = e.id"
            + " WHERE e.environment.id = :environmentId AND a.actionId = :actionId"
            + " ORDER BY a.sequence DESC")
    List<SandboxExecutionActivity> findLatestByEnvironmentIdAndActionId(
            @Param("environmentId") UUID environmentId, @Param("actionId") String actionId, Pageable pageable);

    @Query("select coalesce(max(a.sequence), 0) + 1 from SandboxExecutionActivity a where a.executionId = :executionId")
    long nextSequence(@Param("executionId") UUID executionId);
}
