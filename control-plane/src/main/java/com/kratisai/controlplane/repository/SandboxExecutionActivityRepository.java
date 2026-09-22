package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SandboxExecutionActivityRepository extends JpaRepository<SandboxExecutionActivity, UUID> {

    List<SandboxExecutionActivity> findByExecutionIdOrderBySequenceAsc(UUID executionId);

    Optional<SandboxExecutionActivity> findByExecutionIdAndActionId(UUID executionId, String actionId);

    Optional<SandboxExecutionActivity> findFirstByExecutionIdAndActionIdOrderBySequenceDesc(
            UUID executionId, String actionId);

    List<SandboxExecutionActivity> findByExecutionIdAndStatusOrderBySequenceAsc(
            UUID executionId, ActivityStatus status);

    @Query("select coalesce(max(a.sequence), 0) + 1 from SandboxExecutionActivity a where a.executionId = :executionId")
    long nextSequence(@Param("executionId") UUID executionId);
}
