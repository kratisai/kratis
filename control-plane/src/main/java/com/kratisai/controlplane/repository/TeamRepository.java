package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.Team;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    @Query("SELECT t FROM Team t LEFT JOIN FETCH t.ingestionProvider LEFT JOIN FETCH t.embeddingProvider")
    List<Team> findAllWithIngestionAndEmbeddingProviders();
}
