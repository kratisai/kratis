package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SandboxExecutionActivityRepository extends JpaRepository<SandboxExecutionActivity, UUID> {

    List<SandboxExecutionActivity> findByExecutionIdOrderBySequenceAsc(UUID executionId);

    // The same actionId can legitimately back multiple rows (one per activity
    // type — e.g. a THINKING and a MESSAGE chunk sharing the agent's
    // messageId), so actionId lookups must be deterministic "latest wins"
    // reads, never single-result queries.
    // Optional<SandboxExecutionActivity> findByExecutionIdAndActionId(UUID executionId, String actionId);
    Optional<SandboxExecutionActivity> findFirstByExecutionIdAndActionIdAndActivityTypeOrderBySequenceDesc(
            UUID executionId, String actionId, ActivityType activityType);

    Optional<SandboxExecutionActivity> findFirstByExecutionIdAndActionIdOrderBySequenceDesc(
            UUID executionId, String actionId);

    Optional<SandboxExecutionActivity> findFirstByExecutionIdAndStatusOrderBySequenceDesc(
            UUID executionId, ActivityStatus status);

    List<SandboxExecutionActivity> findByExecutionIdAndStatusOrderBySequenceAsc(
            UUID executionId, ActivityStatus status);

    // Includes rows with a NULL actionId: derived ActionIdNot queries would
    // exclude them (NULL <> :actionId is unknown in SQL) and leave standalone
    // activities stuck open.
    @Query("""
            select a from SandboxExecutionActivity a
            where a.executionId = :executionId
              and a.status = :status
              and (a.actionId is null or a.actionId <> :actionId)
            order by a.sequence asc
            """)
    List<SandboxExecutionActivity> findByExecutionIdAndStatusAndOpenActionIdNot(
            @Param("executionId") UUID executionId,
            @Param("status") ActivityStatus status,
            @Param("actionId") String actionId);

    @Query("select coalesce(max(a.sequence), 0) + 1 from SandboxExecutionActivity a where a.executionId = :executionId")
    long nextSequence(@Param("executionId") UUID executionId);
}
