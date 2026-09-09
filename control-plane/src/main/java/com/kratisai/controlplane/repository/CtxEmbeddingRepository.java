package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CtxEmbedding;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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

    void deleteByBatchId(UUID batchId);
}
