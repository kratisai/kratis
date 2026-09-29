package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.HitlRule;
import com.kratisai.controlplane.model.HitlRuleAction;
import com.kratisai.controlplane.model.HitlRuleType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HitlRuleRepository extends JpaRepository<HitlRule, UUID> {
    List<HitlRule> findByTeamId(UUID teamId);

    List<HitlRule> findByTeamIdOrderByCreatedAtDesc(UUID teamId);

    boolean existsByTeamIdAndCommandRootAndRuleTypeAndAction(
            UUID teamId, String commandRoot, HitlRuleType ruleType, HitlRuleAction action);

    Optional<HitlRule> findByIdAndTeamId(UUID id, UUID teamId);
}
