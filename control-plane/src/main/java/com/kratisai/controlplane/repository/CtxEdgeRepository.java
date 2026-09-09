package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CtxEdge;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CtxEdgeRepository extends JpaRepository<CtxEdge, UUID> {
    @Query("SELECT e FROM CtxEdge e JOIN FETCH e.sourceNode JOIN FETCH e.targetNode WHERE e.batch.id = :batchId")
    List<CtxEdge> findByBatchIdWithNodes(@Param("batchId") UUID batchId);

    @Query("SELECT e.relationType, COUNT(e) FROM CtxEdge e WHERE e.batch.id = :batchId GROUP BY e.relationType")
    List<Object[]> countEdgesByType(@Param("batchId") UUID batchId);

    @Query(
            "SELECT e FROM CtxEdge e JOIN FETCH e.sourceNode JOIN FETCH e.targetNode WHERE e.batch.id = :batchId AND e.relationType IN (com.kratisai.controlplane.model.RelationType.INHERITS, com.kratisai.controlplane.model.RelationType.IMPLEMENTS)")
    List<CtxEdge> findInheritanceEdgesByBatchId(@Param("batchId") UUID batchId);

    void deleteByBatchId(UUID batchId);
}
