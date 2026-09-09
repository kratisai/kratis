package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.IngestionBatchLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IngestionBatchLogRepository extends JpaRepository<IngestionBatchLog, UUID> {
    List<IngestionBatchLog> findByBatchIdOrderByCreatedAtAsc(UUID batchId);

    void deleteByBatchId(UUID batchId);
}
