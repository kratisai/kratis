package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.ExecutionEnvironment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionEnvironmentRepository extends JpaRepository<ExecutionEnvironment, UUID> {

    List<ExecutionEnvironment> findByTeamId(UUID teamId);

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

    Optional<ExecutionEnvironment> findByContainerId(String containerId);
}
