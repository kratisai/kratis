package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.ExecutionDiffSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ExecutionDiffSnapshotRepository extends JpaRepository<ExecutionDiffSnapshot, UUID> {

    Optional<ExecutionDiffSnapshot> findByExecutionId(UUID executionId);

    void deleteByExecutionId(UUID executionId);
}
