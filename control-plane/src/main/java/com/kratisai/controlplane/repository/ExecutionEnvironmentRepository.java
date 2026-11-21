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
            SET e.status = :next, e.lastActivityAt = :now
            WHERE e.id = :id AND e.status = :expected AND e.lastActivityAt = :expectedLastActivityAt
            """)
    int transitionIfUnchanged(
            @Param("id") UUID id,
            @Param("expected") EnvironmentStatus expected,
            @Param("expectedLastActivityAt") Instant expectedLastActivityAt,
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
              AND e.lastActivityAt < :cutoff
            """)
    List<ExecutionEnvironment> findExpiredSleeping(@Param("cutoff") Instant cutoff);

    /**
     * Quiet activity touch: only a CONNECTED row that has been idle past the throttle cutoff
     * gets its clock moved, so read paths never write more than once per throttle window and
     * never compete with a lifecycle transition.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ExecutionEnvironment e
            SET e.lastActivityAt = :now
            WHERE e.id = :id
              AND e.status = com.kratisai.controlplane.model.EnvironmentStatus.CONNECTED
              AND e.lastActivityAt <= :throttleCutoff
            """)
    int touchActiveEnvironmentIfElapsed(
            @Param("id") UUID id, @Param("now") Instant now, @Param("throttleCutoff") Instant throttleCutoff);

    /**
     * CAS on the SLEEPING edge: takes the transition only if both status and activity clock still
     * match the snapshot the sweep suspended from. A touch committed between the snapshot and the
     * suspend makes this write 0 rows, so live user activity is never overwritten by a stale
     * suspend arriving late.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ExecutionEnvironment e
            SET e.status = :next, e.lastActivityAt = :now
            WHERE e.id = :id
              AND e.status = :expected
              AND e.lastActivityAt = :expectedLastActivityAt
            """)
    int transitionIfStatusAndActivityUnchanged(
            @Param("id") UUID id,
            @Param("expected") EnvironmentStatus expected,
            @Param("expectedLastActivityAt") Instant expectedLastActivityAt,
            @Param("next") EnvironmentStatus next,
            @Param("now") Instant now);
}
