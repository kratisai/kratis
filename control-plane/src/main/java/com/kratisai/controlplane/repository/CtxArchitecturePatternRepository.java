package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CtxArchitecturePattern;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CtxArchitecturePatternRepository extends JpaRepository<CtxArchitecturePattern, UUID> {

    List<CtxArchitecturePattern> findByBatchId(UUID batchId);

    @Query(
            "SELECT DISTINCT p FROM CtxArchitecturePattern p LEFT JOIN FETCH p.exemplarNodes WHERE p.batch.id = :batchId")
    List<CtxArchitecturePattern> findByBatchIdWithExemplarNodes(@Param("batchId") UUID batchId);

    void deleteByBatchId(UUID batchId);
}
