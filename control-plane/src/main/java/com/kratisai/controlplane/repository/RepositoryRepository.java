package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositoryRepository extends JpaRepository<Repository, UUID> {

    List<Repository> findByTeamId(UUID teamId);

    Optional<Repository> findByTeamIdAndId(UUID teamId, UUID id);

    Optional<Repository> findByTeamIdAndName(UUID teamId, String name);

    boolean existsByTeamIdAndName(UUID teamId, String name);

    List<Repository> findByCredential(RepoCredential credential);
}
