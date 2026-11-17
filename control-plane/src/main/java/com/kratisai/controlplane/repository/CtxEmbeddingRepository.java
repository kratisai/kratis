package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CtxEmbedding;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CtxEmbeddingRepository extends JpaRepository<CtxEmbedding, UUID> {

    @Query(nativeQuery = true, value = """
            SELECT e.* FROM ctx_embeddings e
            WHERE e.team_id = :teamId
              AND e.batch_id = :batchId
              AND e.dim_size = :dimSize
            ORDER BY
              CASE
                WHEN :dimSize = 3072 THEN (e.embedding::halfvec(3072)) <=> cast(:vector as halfvec)
                ELSE e.embedding <=> cast(:vector as vector)
              END
            LIMIT :limit
            """)
    List<CtxEmbedding> findSimilar(
            @Param("teamId") UUID teamId,
            @Param("batchId") UUID batchId,
            @Param("vector") float[] vector,
            @Param("dimSize") int dimSize,
            @Param("limit") int limit);

    @Query(nativeQuery = true, value = """
            SELECT e.* FROM ctx_embeddings e
            WHERE e.team_id = :teamId
              AND e.dim_size = :dimSize
              AND (
                e.batch_id = :batchId
                OR e.team_wiki_batch_id IN (
                  SELECT twb.id FROM team_wiki_batches twb
                  WHERE twb.team_id = :teamId AND twb.is_active = true
                )
              )
            ORDER BY
              CASE
                WHEN :dimSize = 3072 THEN (e.embedding::halfvec(3072)) <=> cast(:vector as halfvec)
                ELSE e.embedding <=> cast(:vector as vector)
              END
            LIMIT :limit
            """)
    List<CtxEmbedding> findSimilarRepoAndTeam(
            @Param("teamId") UUID teamId,
            @Param("batchId") UUID batchId,
            @Param("vector") float[] vector,
            @Param("dimSize") int dimSize,
            @Param("limit") int limit);

    @Query(nativeQuery = true, value = """
            SELECT e.* FROM ctx_embeddings e
            WHERE e.team_id = :teamId
              AND e.dim_size = :dimSize
              AND (
                e.batch_id IN (
                  SELECT ib.id FROM ingestion_batches ib
                  JOIN repositories r ON ib.repository_id = r.id
                  WHERE r.team_id = :teamId AND ib.is_active = true
                )
                OR e.team_wiki_batch_id IN (
                  SELECT twb.id FROM team_wiki_batches twb
                  WHERE twb.team_id = :teamId AND twb.is_active = true
                )
              )
            ORDER BY
              CASE
                WHEN :dimSize = 3072 THEN (e.embedding::halfvec(3072)) <=> cast(:vector as halfvec)
                ELSE e.embedding <=> cast(:vector as vector)
              END
            LIMIT :limit
            """)
    List<CtxEmbedding> findSimilarTeamWide(
            @Param("teamId") UUID teamId,
            @Param("vector") float[] vector,
            @Param("dimSize") int dimSize,
            @Param("limit") int limit);

    @Modifying
    @Query("DELETE FROM CtxEmbedding e WHERE e.batch.id = :batchId")
    void deleteByBatchId(@Param("batchId") UUID batchId);

    @Modifying
    @Query("DELETE FROM CtxEmbedding e WHERE e.teamWikiBatch.id = :teamWikiBatchId")
    void deleteByTeamWikiBatchId(@Param("teamWikiBatchId") UUID teamWikiBatchId);
}
