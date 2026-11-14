package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.IngestionBatchLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IngestionBatchLogRepository extends JpaRepository<IngestionBatchLog, UUID> {
    List<IngestionBatchLog> findByBatchIdOrderByCreatedAtAsc(UUID batchId);

    @Modifying
    @Query("DELETE FROM IngestionBatchLog l WHERE l.batch.id = :batchId")
    void deleteByBatchId(@Param("batchId") UUID batchId);
}
