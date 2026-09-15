package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.IngestionModelUsage;
import com.kratisai.controlplane.model.ModelKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngestionModelUsageRepository extends JpaRepository<IngestionModelUsage, UUID> {

    List<IngestionModelUsage> findByBatchId(UUID batchId);

    Optional<IngestionModelUsage> findByBatchIdAndModelKind(UUID batchId, ModelKind modelKind);

    @Query("SELECT COALESCE(SUM(mu.totalSpend), 0) FROM IngestionModelUsage mu")
    double sumTotalSpend();

    // Team of the usage row's batch, limited to the summary window: one derived query
    // would join through batch.repository.team.id and compare startedAt, but Spring Data
    // cannot express that traversal as a method name, hence the explicit JPQL.
    @Query("""
            SELECT mu FROM IngestionModelUsage mu
            WHERE mu.batch.repository.team.id = :teamId
              AND mu.batch.startedAt >= :start
              AND mu.batch.startedAt <= :end
            """)
    List<IngestionModelUsage> findByTeamIdAndStartedAtBetween(
            @Param("teamId") UUID teamId, @Param("start") Instant start, @Param("end") Instant end);
}
