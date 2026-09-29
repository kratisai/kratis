package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.DimensionCategory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CtxDimensionRepository extends JpaRepository<CtxDimension, UUID> {

    List<CtxDimension> findByBatchId(UUID batchId);

    List<CtxDimension> findByBatchIdAndCategory(UUID batchId, DimensionCategory category);

    @Modifying
    // Batch delete avoids Spring's heavier fetch-then-delete which triggers JPA lifecycle events
    @Query("DELETE FROM CtxDimension d WHERE d.batch.id = :batchId")
    void deleteByBatchId(@Param("batchId") UUID batchId);
}
