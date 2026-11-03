package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionEnvironmentRepository extends JpaRepository<ExecutionEnvironment, UUID> {

    List<ExecutionEnvironment> findByTeamId(UUID teamId);

    List<ExecutionEnvironment> findByTypeAndStatus(ExecutionEnvironmentType type, EnvironmentStatus status);

    /**
     * Transitions only if nobody changed the row since it was read. A connector registering as
     * CONNECTED changes both fields, so the sweep must not overwrite it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ExecutionEnvironment e
            SET e.status = :next, e.stateChangedAt = :now
            WHERE e.id = :id AND e.status = :expected AND e.stateChangedAt = :expectedStateChangedAt
            """)
    int transitionIfUnchanged(
            @Param("id") UUID id,
            @Param("expected") EnvironmentStatus expected,
            @Param("expectedStateChangedAt") Instant expectedStateChangedAt,
            @Param("next") EnvironmentStatus next,
            @Param("now") Instant now);

    /** Environments the control plane believes hold recoverable sandbox resources. */
    List<ExecutionEnvironment> findByTypeAndStatusIn(
            ExecutionEnvironmentType type, Collection<EnvironmentStatus> statuses);

    @Query("""
            SELECT e, MAX(a.createdAt)
            FROM ExecutionEnvironment e
            LEFT JOIN SandboxExecution x ON x.environment = e
            LEFT JOIN SandboxExecutionActivity a ON a.executionId = x.id
            WHERE e.team.id = :teamId
            GROUP BY e
            ORDER BY
                CASE WHEN MAX(a.createdAt) IS NULL THEN 1 ELSE 0 END ASC,
                MAX(a.createdAt) DESC,
                e.name ASC
    """)
    List<Object[]> findByTeamIdOrderByLatestActivityDesc(@Param("teamId") UUID teamId);

    Optional<ExecutionEnvironment> findByTeamIdAndId(UUID teamId, UUID id);

    Optional<ExecutionEnvironment> findByAuthToken(String authToken);

    @Query("""
            SELECT e FROM ExecutionEnvironment e
            WHERE e.status = com.kratisai.controlplane.model.EnvironmentStatus.SLEEPING
              AND e.stateChangedAt < :cutoff
            """)
    List<ExecutionEnvironment> findExpiredSleeping(@Param("cutoff") Instant cutoff);
}
