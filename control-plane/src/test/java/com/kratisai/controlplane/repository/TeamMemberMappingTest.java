package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamMember;
import com.kratisai.controlplane.model.User;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class TeamMemberMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Test
    void testTeamMemberEntityMapping() {
        // Create user and team first
        User user = new User("member@example.com", "hashed-password", "Member User");
        userRepository.save(user);

        Team team = new Team("Member Team", "Team with members");
        teamRepository.save(team);
        entityManager.flush();

        // Create and save team member
        TeamMember member = new TeamMember(user, team, "owner");
        teamMemberRepository.save(member);
        entityManager.flush();

        // Verify save
        assertThat(member.getId()).isNotNull();
        assertThat(member.getRole()).isEqualTo("owner");

        // Verify findByTeamId
        List<TeamMember> teamMembers = teamMemberRepository.findByTeamId(team.getId());
        assertThat(teamMembers).hasSize(1);
        assertThat(teamMembers.getFirst().getUser().getEmail()).isEqualTo("member@example.com");

        // Verify findByUserId
        List<TeamMember> userMembers = teamMemberRepository.findByUserId(user.getId());
        assertThat(userMembers).hasSize(1);

        // Verify findByTeamIdAndUserId
        Optional<TeamMember> found = teamMemberRepository.findByTeamIdAndUserId(team.getId(), user.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getRole()).isEqualTo("owner");

        // Verify existsByTeamIdAndUserId
        assertThat(teamMemberRepository.existsByTeamIdAndUserId(team.getId(), user.getId()))
                .isTrue();

        // Verify delete
        teamMemberRepository.deleteByTeamIdAndUserId(team.getId(), user.getId());
        entityManager.flush();
        assertThat(teamMemberRepository.findByTeamId(team.getId())).isEmpty();
    }
}
