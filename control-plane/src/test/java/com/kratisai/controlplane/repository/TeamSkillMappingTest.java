package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamSkill;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class TeamSkillMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamSkillRepository teamSkillRepository;

    @Test
    void createReadUpdateAndTimestamps() {
        Team team = teamRepository.save(new Team("Skills Team", "Team with skills"));
        TeamSkill skill = teamSkill(team, "lint", true);
        teamSkillRepository.save(skill);
        entityManager.flush();

        assertThat(skill.getId()).isNotNull();
        assertThat(skill.getCreatedAt()).isNotNull();
        assertThat(skill.getUpdatedAt()).isNotNull();

        TeamSkill loaded =
                teamSkillRepository.findByTeamIdAndName(team.getId(), "lint").orElseThrow();
        assertThat(loaded.getDescription()).isEqualTo("Run lint");
        assertThat(loaded.isEnabled()).isTrue();

        loaded.setDescription("Run all linters");
        entityManager.flush();
        entityManager.clear();

        TeamSkill updated = teamSkillRepository
                .findByTeamIdAndId(team.getId(), loaded.getId())
                .orElseThrow();
        assertThat(updated.getDescription()).isEqualTo("Run all linters");
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(updated.getCreatedAt());
    }

    @Test
    void duplicateNameForSameTeamViolatesUniqueConstraint() {
        Team team = teamRepository.save(new Team("Duplicate Team", "Duplicate skills"));
        teamSkillRepository.save(teamSkill(team, "deploy", true));
        entityManager.flush();

        teamSkillRepository.save(teamSkill(team, "deploy", true));
        assertThatThrownBy(entityManager::flush).isInstanceOf(PersistenceException.class);
    }

    @Test
    void identicalNameAcrossDifferentTeamsIsAllowed() {
        Team teamA = teamRepository.save(new Team("Team A", "First"));
        Team teamB = teamRepository.save(new Team("Team B", "Second"));

        teamSkillRepository.save(teamSkill(teamA, "lint", true));
        teamSkillRepository.save(teamSkill(teamB, "lint", true));
        entityManager.flush();

        assertThat(teamSkillRepository.findByTeamId(teamA.getId())).hasSize(1);
        assertThat(teamSkillRepository.findByTeamId(teamB.getId())).hasSize(1);
    }

    @Test
    void deletingTeamCascadesToSkills() {
        Team team = teamRepository.save(new Team("Cascade Team", "Cascade"));
        TeamSkill skill = teamSkillRepository.save(teamSkill(team, "cascade", true));
        entityManager.flush();
        UUID skillId = skill.getId();
        UUID teamId = team.getId();

        entityManager.flush();
        entityManager.clear();
        entityManager
                .createNativeQuery("DELETE FROM teams WHERE id = :id")
                .setParameter("id", teamId)
                .executeUpdate();
        entityManager.clear();

        assertThat(teamSkillRepository.findById(skillId)).isEmpty();
        assertThat(teamSkillRepository.findByTeamId(teamId)).isEmpty();
    }

    @Test
    void enabledQueryExcludesDisabledSkills() {
        Team team = teamRepository.save(new Team("Enabled Team", "Enabled query"));
        teamSkillRepository.save(teamSkill(team, "enabled", true));
        teamSkillRepository.save(teamSkill(team, "disabled", false));
        entityManager.flush();

        assertThat(teamSkillRepository.findByTeamIdAndEnabledTrue(team.getId()))
                .extracting(TeamSkill::getName)
                .containsExactly("enabled");
    }

    private static TeamSkill teamSkill(Team team, String name, boolean enabled) {
        TeamSkill skill = new TeamSkill();
        skill.setTeam(team);
        skill.setName(name);
        skill.setDescription("Run lint");
        skill.setBlobPath("skills/teams/" + team.getId() + "/" + name + ".tar.gz");
        skill.setChecksumSha256("a".repeat(64));
        skill.setEnabled(enabled);
        return skill;
    }
}
