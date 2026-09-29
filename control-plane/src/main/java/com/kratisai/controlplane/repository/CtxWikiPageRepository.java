package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CtxWikiPage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CtxWikiPageRepository extends JpaRepository<CtxWikiPage, UUID> {
    List<CtxWikiPage> findByBatchId(UUID batchId);

    List<CtxWikiPage> findByBatchIdAndParentPageIsNullOrderByOrderIndexAsc(UUID batchId);

    List<CtxWikiPage> findByBatchIdAndParentPageIdOrderByOrderIndexAsc(UUID batchId, UUID parentPageId);

    Optional<CtxWikiPage> findByBatchIdAndId(UUID batchId, UUID id);

    Optional<CtxWikiPage> findByBatchIdAndPageSlug(UUID batchId, String pageSlug);

    void deleteByBatchId(UUID batchId);
}
