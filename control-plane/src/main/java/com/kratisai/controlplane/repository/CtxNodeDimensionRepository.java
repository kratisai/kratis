package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CtxNodeDimension;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CtxNodeDimensionRepository extends JpaRepository<CtxNodeDimension, UUID> {

    List<CtxNodeDimension> findByDimensionId(UUID dimensionId);

    @Query("SELECT nd FROM CtxNodeDimension nd JOIN FETCH nd.node WHERE nd.dimension.id = :dimensionId")
    List<CtxNodeDimension> findByDimensionIdWithNode(@Param("dimensionId") UUID dimensionId);

    List<CtxNodeDimension> findByDimensionIdIn(List<UUID> dimensionIds);

    List<CtxNodeDimension> findByNodeId(UUID nodeId);

    @Query("SELECT nd FROM CtxNodeDimension nd JOIN FETCH nd.dimension WHERE nd.node.id = :nodeId")
    List<CtxNodeDimension> findByNodeIdWithDimension(@Param("nodeId") UUID nodeId);

    @Modifying
    // Batch delete avoids Spring's heavier fetch-then-delete which triggers JPA lifecycle events
    @Query("DELETE FROM CtxNodeDimension nd WHERE nd.node.batch.id = :batchId")
    void deleteByBatchId(@Param("batchId") UUID batchId);

    @Query("""
            SELECT d.name, d.category, d.synopsis, COUNT(DISTINCT nd.node.id)
            FROM CtxNodeDimension nd
            JOIN nd.dimension d
            WHERE d.batch.id = :batchId
            GROUP BY d.name, d.category, d.synopsis
            """)
    List<Object[]> countNodesByDimension(@Param("batchId") UUID batchId);

    // Ordered by rank score (descending) within each dimension so callers can take the top-N
    // distinct file paths per dimension without a separate per-dimension query.
    @Query("""
            SELECT d.name, d.category, n.path, nd.rankScore
            FROM CtxNodeDimension nd
            JOIN nd.dimension d
            JOIN nd.node n
            WHERE d.batch.id = :batchId
            ORDER BY d.name ASC, d.category ASC, nd.rankScore DESC
            """)
    List<Object[]> findRankedFilePathsByDimension(@Param("batchId") UUID batchId);
}
