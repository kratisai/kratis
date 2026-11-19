package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngestionBatchRepository extends JpaRepository<IngestionBatch, UUID> {

    @Query("SELECT ib FROM IngestionBatch ib JOIN FETCH ib.repository r JOIN FETCH r.team WHERE ib.id = :id")
    Optional<IngestionBatch> findByIdWithRepositoryAndTeam(@Param("id") UUID id);

    List<IngestionBatch> findByRepositoryId(UUID repositoryId);

    @Query("SELECT ib FROM IngestionBatch ib WHERE ib.repository.team.id = :teamId AND ib.isActive = true")
    List<IngestionBatch> findActiveByTeamId(@Param("teamId") UUID teamId);

    Optional<IngestionBatch> findByRepositoryIdAndIsActiveTrue(UUID repositoryId);

    Optional<IngestionBatch> findFirstByRepositoryIdOrderByStartedAtDesc(UUID repositoryId);

    List<IngestionBatch> findByStatus(IngestionStatus status);

    @Modifying(clearAutomatically = true)
    @Query(
            "UPDATE IngestionBatch ib SET ib.isActive = false WHERE ib.repository.id = :repositoryId AND ib.id <> :activeBatchId")
    void deactivateOtherBatches(@Param("repositoryId") UUID repositoryId, @Param("activeBatchId") UUID activeBatchId);

    @Modifying(clearAutomatically = true)
    @Query(
            "UPDATE IngestionBatch ib SET ib.status = 'QUEUED', ib.errorMessage = 'Reset on startup: was orphaned in PROCESSING state' WHERE ib.id = :batchId AND ib.status = 'PROCESSING'")
    int resetOrphanedProcessingBatch(@Param("batchId") UUID batchId);

    @Modifying(clearAutomatically = true)
    @Query(
            "UPDATE IngestionBatch ib SET ib.status = 'FAILED', ib.errorMessage = :errorMessage, ib.completedAt = :completedAt, ib.isActive = false WHERE ib.id = :batchId AND ib.status = 'PROCESSING'")
    int failOrphanedProcessingBatch(
            @Param("batchId") UUID batchId,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") Instant completedAt);

    /**
     * Calculate queue position: count how many QUEUED batches were started before this one. Returns
     * 0 if this batch is the first in the queue.
     */
    @Query(
            "SELECT COUNT(ib) FROM IngestionBatch ib WHERE ib.status = 'QUEUED' AND ib.startedAt < :startedAt AND ib.id <> :batchId")
    int countQueuedBatchesBefore(@Param("startedAt") Instant startedAt, @Param("batchId") UUID batchId);

    @Query("SELECT ib.id FROM IngestionBatch ib WHERE ib.status = 'PROCESSING'")
    List<UUID> findProcessingBatchIds();

    boolean existsByRepositoryTeamIdAndStatusIn(UUID teamId, List<IngestionStatus> statuses);

    List<IngestionBatch> findByRepositoryTeamIdAndStatusIn(UUID teamId, List<IngestionStatus> statuses);
}
