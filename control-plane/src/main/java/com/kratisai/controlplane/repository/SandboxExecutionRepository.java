package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SandboxExecutionRepository extends JpaRepository<SandboxExecution, UUID> {
    List<SandboxExecution> findByChatIdOrderByStartedAtAsc(UUID chatId);

    List<SandboxExecution> findByEnvironmentIdAndStatus(UUID environmentId, SandboxExecutionStatus status);

    List<SandboxExecution> findByEnvironmentIdAndStatusIn(
            UUID environmentId, Collection<SandboxExecutionStatus> statuses);

    Optional<SandboxExecution> findFirstByEnvironmentIdOrderByStartedAtDesc(UUID environmentId);

    List<SandboxExecution> findByStatusInAndEnvironment_StatusIn(
            Collection<SandboxExecutionStatus> statuses, Collection<EnvironmentStatus> environmentStatuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from SandboxExecution e where e.id = :id")
    Optional<SandboxExecution> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT COALESCE(SUM(u.totalSpend), 0.0) FROM SandboxExecutionUsage u")
    double sumTotalSpend();

    @Query("SELECT u.virtualKey FROM SandboxExecutionUsage u"
            + " WHERE u.execution.environment.id = :environmentId AND u.revokedAt IS NULL"
            + " ORDER BY u.startedAt DESC")
    List<String> findActiveVirtualKeysByEnvironmentId(@Param("environmentId") UUID environmentId);

    @Query("SELECT u.virtualKey FROM SandboxExecutionUsage u"
            + " WHERE u.execution.id = :executionId AND u.revokedAt IS NULL"
            + " ORDER BY u.startedAt DESC")
    List<String> findActiveVirtualKeysByExecutionId(@Param("executionId") UUID executionId);

    @Modifying
    @Query(
            value = "UPDATE sandbox_executions SET new_repo_credential_id = :credentialId WHERE id = :executionId",
            nativeQuery = true)
    int updateNewRepoCredential(@Param("executionId") UUID executionId, @Param("credentialId") UUID credentialId);
}
