package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.SurfaceDetection;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CtxSurfaceRepository extends JpaRepository<CtxSurface, UUID> {
    @Query("SELECT s FROM CtxSurface s WHERE s.batch.id = :batchId")
    List<CtxSurface> findByBatchId(@Param("batchId") UUID batchId);

    @Query("SELECT s FROM CtxSurface s WHERE s.batch.id = :batchId AND s.detectedBy = :detectedBy")
    List<CtxSurface> findByBatchIdAndDetectedBy(
            @Param("batchId") UUID batchId, @Param("detectedBy") SurfaceDetection detectedBy);

    @Query("SELECT s FROM CtxSurface s WHERE s.batch.id IN :batchIds")
    List<CtxSurface> findByBatchIdIn(@Param("batchIds") Collection<UUID> batchIds);

    @Modifying
    @Query("DELETE FROM CtxSurface s WHERE s.batch.id = :batchId")
    void deleteByBatchId(@Param("batchId") UUID batchId);
}
