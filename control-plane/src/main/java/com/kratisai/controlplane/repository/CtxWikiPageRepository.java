package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CtxWikiPage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CtxWikiPageRepository extends JpaRepository<CtxWikiPage, UUID> {
    List<CtxWikiPage> findByBatchId(UUID batchId);

    List<CtxWikiPage> findByBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(UUID batchId);

    @Query("SELECT p FROM CtxWikiPage p LEFT JOIN FETCH p.parentPage WHERE p.batch.id = :batchId"
            + " ORDER BY p.orderIndex ASC, p.title ASC, p.pageSlug ASC")
    List<CtxWikiPage> findByBatchIdWithParent(@Param("batchId") UUID batchId);

    Optional<CtxWikiPage> findByBatchIdAndId(UUID batchId, UUID id);

    Optional<CtxWikiPage> findByBatchIdAndPageSlug(UUID batchId, String pageSlug);

    List<CtxWikiPage> findByTeamWikiBatchId(UUID teamWikiBatchId);

    List<CtxWikiPage> findByTeamWikiBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(UUID teamWikiBatchId);

    Optional<CtxWikiPage> findByTeamWikiBatchIdAndId(UUID teamWikiBatchId, UUID id);

    Optional<CtxWikiPage> findByTeamWikiBatchIdAndPageSlug(UUID teamWikiBatchId, String pageSlug);

    @Query("SELECT p FROM CtxWikiPage p LEFT JOIN FETCH p.parentPage WHERE p.teamWikiBatch.id = :teamWikiBatchId"
            + " ORDER BY p.orderIndex ASC, p.title ASC, p.pageSlug ASC")
    List<CtxWikiPage> findByTeamWikiBatchIdWithParent(@Param("teamWikiBatchId") UUID teamWikiBatchId);

    @Modifying
    @Query("DELETE FROM CtxWikiPage p WHERE p.batch.id = :batchId")
    void deleteByBatchId(@Param("batchId") UUID batchId);

    @Modifying
    @Query("DELETE FROM CtxWikiPage p WHERE p.teamWikiBatch.id = :teamWikiBatchId")
    void deleteByTeamWikiBatchId(@Param("teamWikiBatchId") UUID teamWikiBatchId);
}
