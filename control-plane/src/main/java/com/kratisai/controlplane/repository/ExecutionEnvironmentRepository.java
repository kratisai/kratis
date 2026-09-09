package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.ExecutionEnvironment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionEnvironmentRepository extends JpaRepository<ExecutionEnvironment, UUID> {

    List<ExecutionEnvironment> findByTeamId(UUID teamId);

    Optional<ExecutionEnvironment> findByTeamIdAndId(UUID teamId, UUID id);

    Optional<ExecutionEnvironment> findByAuthToken(String authToken);

    Optional<ExecutionEnvironment> findByContainerId(String containerId);
}
