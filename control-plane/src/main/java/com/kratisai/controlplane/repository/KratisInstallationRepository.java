package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.KratisInstallation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KratisInstallationRepository extends JpaRepository<KratisInstallation, UUID> {

    Optional<KratisInstallation> findFirstByOrderByCreatedAtAsc();
}
