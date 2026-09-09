package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.Team;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class TeamMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TeamRepository teamRepository;

    @Test
    void testTeamEntityMapping() {
        // Create and save a team
        Team team = new Team("Test Team", "A test team");
        teamRepository.save(team);
        entityManager.flush();

        // Verify save
        assertThat(team.getId()).isNotNull();
        assertThat(team.getCreatedAt()).isNotNull();
        assertThat(team.getUpdatedAt()).isNotNull();

        // Verify find
        Team found = teamRepository.findById(team.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("Test Team");
        assertThat(found.getDescription()).isEqualTo("A test team");

        // Verify update triggers updatedAt
        found.setName("Updated Team");
        entityManager.flush();
        assertThat(found.getUpdatedAt()).isNotNull();

        // Verify delete
        teamRepository.delete(found);
        entityManager.flush();
        assertThat(teamRepository.findById(team.getId())).isEmpty();
    }
}
