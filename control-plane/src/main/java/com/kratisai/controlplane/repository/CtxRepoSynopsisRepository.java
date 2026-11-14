package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CtxRepoSynopsis;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CtxRepoSynopsisRepository extends JpaRepository<CtxRepoSynopsis, UUID> {

    Optional<CtxRepoSynopsis> findByBatchId(UUID batchId);

    @Query("SELECT s FROM CtxRepoSynopsis s WHERE s.batch.id IN :batchIds")
    List<CtxRepoSynopsis> findByBatchIdIn(@Param("batchIds") Collection<UUID> batchIds);

    @Modifying
    @Query("DELETE FROM CtxRepoSynopsis s WHERE s.batch.id = :batchId")
    void deleteByBatchId(@Param("batchId") UUID batchId);
}
