package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CtxRepoRelation;
import com.kratisai.controlplane.model.Nature;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CtxRepoRelationRepository extends JpaRepository<CtxRepoRelation, UUID> {

    Optional<CtxRepoRelation> findByBatchAIdAndBatchBIdAndNature(UUID batchAId, UUID batchBId, Nature nature);

    @Query("SELECT r FROM CtxRepoRelation r JOIN FETCH r.batchA a JOIN FETCH a.repository "
            + "JOIN FETCH r.batchB b JOIN FETCH b.repository "
            + "WHERE r.teamId = :teamId AND r.state = 'CURRENT' "
            + "AND r.batchA.isActive = true AND r.batchB.isActive = true")
    List<CtxRepoRelation> findCurrentByTeamId(@Param("teamId") UUID teamId);

    @Query("SELECT r FROM CtxRepoRelation r JOIN FETCH r.batchA a JOIN FETCH a.repository "
            + "JOIN FETCH r.batchB b JOIN FETCH b.repository "
            + "WHERE r.teamId = :teamId AND r.state = 'CURRENT' "
            + "AND r.batchA.isActive = true AND r.batchB.isActive = true "
            + "AND (r.repositoryAId = :repositoryId OR r.repositoryBId = :repositoryId)")
    List<CtxRepoRelation> findCurrentForRepository(
            @Param("teamId") UUID teamId, @Param("repositoryId") UUID repositoryId);

    @Modifying
    @Query(
            "UPDATE CtxRepoRelation r SET r.state = 'SUPERSEDED' WHERE r.state = 'CURRENT' AND r.teamId = :teamId AND (r.repositoryAId = :repositoryId OR r.repositoryBId = :repositoryId)")
    int supersedeCurrentForRepository(@Param("teamId") UUID teamId, @Param("repositoryId") UUID repositoryId);

    @Modifying
    @Query("DELETE FROM CtxRepoRelation r WHERE r.batchA.id = :batchId OR r.batchB.id = :batchId")
    void deleteByBatchId(@Param("batchId") UUID batchId);
}
