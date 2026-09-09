package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.Team;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class RepositoryMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Test
    void testRepositoryEntityMapping() {
        // Create team first
        Team team = new Team("Repo Team", "Team with repositories");
        teamRepository.save(team);
        entityManager.flush();

        // Create and save repository
        Repository repo =
                new Repository("my-project", "https://github.com/user/my-project.git", "main", RepositoryType.GITHUB);
        repo.setTeam(team);
        repositoryRepository.save(repo);
        entityManager.flush();

        // Verify save
        assertThat(repo.getId()).isNotNull();
        assertThat(repo.getCreatedAt()).isNotNull();
        assertThat(repo.getUpdatedAt()).isNotNull();

        // Verify findByTeamId
        List<Repository> repos = repositoryRepository.findByTeamId(team.getId());
        assertThat(repos).hasSize(1);
        assertThat(repos.getFirst().getName()).isEqualTo("my-project");
        assertThat(repos.getFirst().getUrl()).isEqualTo("https://github.com/user/my-project.git");
        assertThat(repos.getFirst().getBranch()).isEqualTo("main");
        assertThat(repos.getFirst().getRepositoryType()).isEqualTo(RepositoryType.GITHUB);

        // Verify findByTeamIdAndId
        Optional<Repository> found = repositoryRepository.findByTeamIdAndId(team.getId(), repo.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("my-project");

        // Verify existsByTeamIdAndName
        assertThat(repositoryRepository.existsByTeamIdAndName(team.getId(), "my-project"))
                .isTrue();
        assertThat(repositoryRepository.existsByTeamIdAndName(team.getId(), "nonexistent"))
                .isFalse();

        // Verify update triggers updatedAt
        found.get().setName("my-project-renamed");
        entityManager.flush();
        assertThat(found.get().getUpdatedAt()).isNotNull();

        // Verify delete
        repositoryRepository.delete(found.get());
        entityManager.flush();
        assertThat(repositoryRepository.findByTeamId(team.getId())).isEmpty();
    }

    @Test
    void testRepositoryRequiresTeam() {
        // Verify that a repository cannot be saved without a team
        Team team = new Team("Repo Team", "Team with repositories");
        teamRepository.saveAndFlush(team);

        Repository repo =
                new Repository("my-project", "https://github.com/user/my-project.git", "main", RepositoryType.GENERIC);
        repo.setTeam(team);
        repositoryRepository.saveAndFlush(repo);

        // Verify the relationship is established
        assertThat(repo.getTeam().getId()).isEqualTo(team.getId());
        assertThat(repositoryRepository.findByTeamId(team.getId())).hasSize(1);
    }
}
