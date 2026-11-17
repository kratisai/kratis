package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.TeamWikiBatchLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamWikiBatchLogRepository extends JpaRepository<TeamWikiBatchLog, UUID> {

    List<TeamWikiBatchLog> findByTeamWikiBatchIdOrderByCreatedAtAsc(UUID teamWikiBatchId);

    @Modifying
    @Query("DELETE FROM TeamWikiBatchLog l WHERE l.teamWikiBatch.id = :teamWikiBatchId")
    void deleteByTeamWikiBatchId(@Param("teamWikiBatchId") UUID teamWikiBatchId);
}
