package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CtxSurfaceAttribute;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CtxSurfaceAttributeRepository extends JpaRepository<CtxSurfaceAttribute, UUID> {

    List<CtxSurfaceAttribute> findBySurfaceId(@Param("surfaceId") UUID surfaceId);

    @Query("SELECT a FROM CtxSurfaceAttribute a WHERE a.surface.batch.id = :batchId")
    List<CtxSurfaceAttribute> findByBatchId(@Param("batchId") UUID batchId);

    @Query("SELECT a.surface.id, COUNT(a) FROM CtxSurfaceAttribute a "
            + "WHERE a.surface.batch.id = :batchId GROUP BY a.surface.id")
    List<Object[]> countByBatchIdGroupedBySurface(@Param("batchId") UUID batchId);
}
