package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.EnvironmentProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentProviderRepository extends JpaRepository<EnvironmentProvider, UUID> {

    List<EnvironmentProvider> findByTeamId(UUID teamId);

    Optional<EnvironmentProvider> findByTeamIdAndId(UUID teamId, UUID id);
}
