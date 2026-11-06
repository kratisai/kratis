package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.TeamSkill;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamSkillRepository extends JpaRepository<TeamSkill, UUID> {

    List<TeamSkill> findByTeamId(UUID teamId);

    List<TeamSkill> findByTeamIdAndEnabledTrue(UUID teamId);

    Optional<TeamSkill> findByTeamIdAndName(UUID teamId, String name);

    Optional<TeamSkill> findByTeamIdAndId(UUID teamId, UUID id);
}
