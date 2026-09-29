package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.NodeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CtxNodeRepository extends JpaRepository<CtxNode, UUID> {

    @Query(value = """
        WITH RECURSIVE downstream(id, batch_id, team_id, repo_name, node_type, path, metadata, symbol_name, archetype_group, depth) AS (
            SELECT n.id, n.batch_id, n.team_id, n.repo_name, n.node_type, n.path, n.metadata, n.symbol_name, n.archetype_group, 0 as depth
            FROM ctx_nodes n
            JOIN ingestion_batches b ON n.batch_id = b.id
            WHERE n.repo_name = :repoName AND n.path = :path AND n.team_id = :teamId AND b.is_active = true

            UNION

            SELECT target.id, target.batch_id, target.team_id, target.repo_name, target.node_type, target.path, target.metadata, target.symbol_name, target.archetype_group, d.depth + 1
            FROM ctx_edges e
            JOIN downstream d ON e.source_node_id = d.id
            JOIN ctx_nodes target ON e.target_node_id = target.id
            WHERE d.depth < :maxDepth
        )
        SELECT id, batch_id, team_id, repo_name, node_type, path, metadata, symbol_name, archetype_group
        FROM downstream
        WHERE depth > 0
        """, nativeQuery = true)
    List<CtxNode> findDownstreamDependencies(
            @Param("teamId") UUID teamId,
            @Param("repoName") String repoName,
            @Param("path") String path,
            @Param("maxDepth") int maxDepth);

    @Query(value = """
        WITH RECURSIVE upstream(id, batch_id, team_id, repo_name, node_type, path, metadata, symbol_name, archetype_group, depth) AS (
            SELECT n.id, n.batch_id, n.team_id, n.repo_name, n.node_type, n.path, n.metadata, n.symbol_name, n.archetype_group, 0 as depth
            FROM ctx_nodes n
            JOIN ingestion_batches b ON n.batch_id = b.id
            WHERE n.repo_name = :repoName AND n.path = :path AND n.team_id = :teamId AND b.is_active = true

            UNION

            SELECT source.id, source.batch_id, source.team_id, source.repo_name, source.node_type, source.path, source.metadata, source.symbol_name, source.archetype_group, u.depth + 1
            FROM ctx_edges e
            JOIN upstream u ON e.target_node_id = u.id
            JOIN ctx_nodes source ON e.source_node_id = source.id
            WHERE u.depth < :maxDepth
        )
        SELECT id, batch_id, team_id, repo_name, node_type, path, metadata, symbol_name, archetype_group
        FROM upstream
        WHERE depth > 0
        """, nativeQuery = true)
    List<CtxNode> findUpstreamUsages(
            @Param("teamId") UUID teamId,
            @Param("repoName") String repoName,
            @Param("path") String path,
            @Param("maxDepth") int maxDepth);

    @Query("""
        SELECT n FROM CtxNode n
        JOIN IngestionBatch b ON n.batch.id = b.id
        WHERE n.teamId = :teamId
          AND n.repoName != :currentRepoName
          AND n.symbolName = :path
          AND b.isActive = true
        """)
    List<CtxNode> findTargetCrossRepoNodes(
            @Param("teamId") UUID teamId, @Param("currentRepoName") String currentRepoName, @Param("path") String path);

    List<CtxNode> findByBatchId(UUID batchId);

    @Query("""
        SELECT n FROM CtxNode n
        WHERE n.batch.id = :batchId
          AND (LOCATE(LOWER(:query), LOWER(n.path)) > 0
               OR (n.symbolName IS NOT NULL AND LOCATE(LOWER(:query), LOWER(n.symbolName)) > 0))
          AND (:nodeType IS NULL OR n.nodeType = :nodeType)
        ORDER BY n.path
        """)
    List<CtxNode> searchByBatchIdAndQuery(
            @Param("batchId") UUID batchId, @Param("query") String query, @Param("nodeType") NodeType nodeType);

    @Query("SELECT n.nodeType, COUNT(n) FROM CtxNode n WHERE n.batch.id = :batchId GROUP BY n.nodeType")
    List<Object[]> countNodesByType(@Param("batchId") UUID batchId);

    @Query(
            "SELECT n FROM CtxNode n WHERE n.batch.id = :batchId AND n.nodeType IN (com.kratisai.controlplane.model.NodeType.CLASS, com.kratisai.controlplane.model.NodeType.INTERFACE, com.kratisai.controlplane.model.NodeType.FUNCTION)")
    List<CtxNode> findSymbolChildrenByBatchId(@Param("batchId") UUID batchId);

    List<CtxNode> findByBatchIdAndPath(UUID batchId, String path);

    @Modifying
    @Query("DELETE FROM CtxNode n WHERE n.batch.id = :batchId")
    void deleteByBatchId(@Param("batchId") UUID batchId);
}
