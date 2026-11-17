package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.TeamWikiModelUsage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamWikiModelUsageRepository extends JpaRepository<TeamWikiModelUsage, UUID> {

    List<TeamWikiModelUsage> findByTeamWikiBatchId(UUID teamWikiBatchId);
}
