package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.RepoCredential;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepoCredentialRepository extends JpaRepository<RepoCredential, UUID> {

    List<RepoCredential> findByTeamId(UUID teamId);

    Optional<RepoCredential> findByTeamIdAndId(UUID teamId, UUID id);
}
