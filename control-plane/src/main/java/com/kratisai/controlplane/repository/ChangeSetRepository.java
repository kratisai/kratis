package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.ChangeSet;
import com.kratisai.controlplane.model.PublishedPrStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ChangeSetRepository extends JpaRepository<ChangeSet, UUID> {
    List<ChangeSet> findByExecutionIdOrderBySeqAsc(UUID executionId);

    @Query("SELECT cs.id FROM ChangeSet cs WHERE cs.prNumber IS NOT NULL"
            + " AND (cs.prStatus IS NULL OR cs.prStatus = :open) AND cs.seq ="
            + " (SELECT MAX(latest.seq) FROM ChangeSet latest WHERE latest.execution = cs.execution)"
            + " AND cs.execution.startedAt >= :cutoff AND cs.execution.repository IS NOT NULL")
    List<UUID> findRecentOpenPullRequestIds(@Param("open") PublishedPrStatus open, @Param("cutoff") Instant cutoff);

    @Query("SELECT cs FROM ChangeSet cs JOIN FETCH cs.execution e JOIN FETCH e.chat c JOIN FETCH c.team"
            + " LEFT JOIN FETCH e.repository r LEFT JOIN FETCH r.credential WHERE cs.id = :id")
    Optional<ChangeSet> findWithPollingGraphById(@Param("id") UUID id);
}
