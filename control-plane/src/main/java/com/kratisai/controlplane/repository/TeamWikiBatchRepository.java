package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.model.TeamWikiTriggerKind;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamWikiBatchRepository extends JpaRepository<TeamWikiBatch, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT twb FROM TeamWikiBatch twb WHERE twb.id = :id")
    Optional<TeamWikiBatch> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT twb FROM TeamWikiBatch twb JOIN FETCH twb.team WHERE twb.id = :id")
    Optional<TeamWikiBatch> findByIdWithTeam(@Param("id") UUID id);

    Optional<TeamWikiBatch> findByTeamIdAndIsActiveTrue(UUID teamId);

    Optional<TeamWikiBatch> findFirstByTeamIdOrderByStartedAtDesc(UUID teamId);

    List<TeamWikiBatch> findByTeamIdOrderByStartedAtDesc(UUID teamId);

    List<TeamWikiBatch> findByStatus(IngestionStatus status);

    @Query("SELECT twb.id FROM TeamWikiBatch twb WHERE twb.status = 'PROCESSING'")
    List<UUID> findProcessingBatchIds();

    boolean existsByTeamIdAndStatusIn(UUID teamId, List<IngestionStatus> statuses);

    List<TeamWikiBatch> findByTeamIdAndTriggerKindAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
            UUID teamId, TeamWikiTriggerKind triggerKind, Instant since);

    @Query(
            "SELECT twb FROM TeamWikiBatch twb JOIN FETCH twb.team WHERE twb.team.id = :teamId AND twb.startedAt BETWEEN :start AND :end")
    List<TeamWikiBatch> findByTeamIdAndStartedAtBetween(
            @Param("teamId") UUID teamId, @Param("start") Instant start, @Param("end") Instant end);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE TeamWikiBatch twb SET twb.isActive = false WHERE twb.team.id = :teamId AND twb.id <> :activeBatchId")
    void deactivateOtherBatches(@Param("teamId") UUID teamId, @Param("activeBatchId") UUID activeBatchId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE TeamWikiBatch twb SET twb.isActive = false WHERE twb.team.id = :teamId AND twb.isActive = true")
    int deactivateAllForTeam(@Param("teamId") UUID teamId);

    @Modifying(clearAutomatically = true)
    @Query(
            "UPDATE TeamWikiBatch twb SET twb.status = 'QUEUED', twb.errorMessage = 'Reset on startup: was orphaned in PROCESSING state' WHERE twb.status = 'PROCESSING'")
    int resetOrphanedProcessingBatches();
}
