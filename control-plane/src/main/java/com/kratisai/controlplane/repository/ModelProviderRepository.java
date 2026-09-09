package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.ModelProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelProviderRepository extends JpaRepository<ModelProvider, UUID> {

    List<ModelProvider> findByTeamId(UUID teamId);

    Optional<ModelProvider> findByTeamIdAndId(UUID teamId, UUID id);

    boolean existsByTeamIdAndDisplayName(UUID teamId, String displayName);

    Optional<ModelProvider> findFirstByTeamIdAndIsActiveTrue(UUID teamId);
}
