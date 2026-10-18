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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionEnvironmentRepository extends JpaRepository<ExecutionEnvironment, UUID> {

    List<ExecutionEnvironment> findByTeamId(UUID teamId);

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
            SELECT e,
                   GREATEST(MAX(CASE WHEN a.createdAt IS NOT NULL THEN a.createdAt ELSE x.startedAt END), e.connectedAt),
                   MAX(CASE WHEN x.status = com.kratisai.controlplane.model.SandboxExecutionStatus.RUNNING
                            THEN 1 ELSE 0 END)
            FROM ExecutionEnvironment e
            LEFT JOIN SandboxExecution x ON x.environment = e
            LEFT JOIN SandboxExecutionActivity a ON a.executionId = x.id
            WHERE e.status = com.kratisai.controlplane.model.EnvironmentStatus.CONNECTED
            GROUP BY e
            """)
    List<Object[]> findConnectedEnvironmentsWithLastActivity();

    @Query("""
            SELECT e FROM ExecutionEnvironment e
            WHERE e.status = com.kratisai.controlplane.model.EnvironmentStatus.SLEEPING
              AND (e.suspendedAt IS NULL OR e.suspendedAt < :cutoff)
            """)
    List<ExecutionEnvironment> findExpiredSleeping(@Param("cutoff") Instant cutoff);
}
