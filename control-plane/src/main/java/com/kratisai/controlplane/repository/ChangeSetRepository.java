package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.ChangeSet;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChangeSetRepository extends JpaRepository<ChangeSet, UUID> {
    List<ChangeSet> findByExecutionIdOrderBySeqAsc(UUID executionId);
}
