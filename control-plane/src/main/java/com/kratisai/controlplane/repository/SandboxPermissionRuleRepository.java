package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.SandboxPermissionAction;
import com.kratisai.controlplane.model.SandboxPermissionRule;
import com.kratisai.controlplane.model.SandboxPermissionRuleType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SandboxPermissionRuleRepository extends JpaRepository<SandboxPermissionRule, UUID> {
    List<SandboxPermissionRule> findByTeamId(UUID teamId);

    List<SandboxPermissionRule> findByTeamIdOrderByCreatedAtDesc(UUID teamId);

    boolean existsByTeamIdAndCommandRootAndRuleTypeAndAction(
            UUID teamId, String commandRoot, SandboxPermissionRuleType ruleType, SandboxPermissionAction action);

    Optional<SandboxPermissionRule> findByIdAndTeamId(UUID id, UUID teamId);
}
