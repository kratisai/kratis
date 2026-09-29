package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BatchResolutionServiceTest {

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private IngestionBatchRepository batchRepository;

    private BatchResolutionService batchResolutionService;

    private UUID teamId;
    private Repository testRepo;
    private IngestionBatch activeBatch;

    @BeforeEach
    void setUp() {
        batchResolutionService = new BatchResolutionService(repositoryRepository, batchRepository);

        teamId = UUID.randomUUID();
        Team team = new Team();
        team.setId(teamId);

        testRepo = new Repository();
        testRepo.setId(UUID.randomUUID());
        testRepo.setName("test-repo");
        testRepo.setTeam(team);

        activeBatch = new IngestionBatch();
        activeBatch.setId(UUID.randomUUID());
        activeBatch.setStatus(IngestionStatus.SUCCESS);
        activeBatch.setActive(true);
    }

    @Test
    void resolveActiveBatch_withValidRepo_returnsBatch() {
        when(repositoryRepository.findByTeamIdAndName(teamId, "test-repo")).thenReturn(Optional.of(testRepo));
        when(batchRepository.findByRepositoryIdAndIsActiveTrue(testRepo.getId()))
                .thenReturn(Optional.of(activeBatch));

        IngestionBatch result = batchResolutionService.resolveActiveBatch(teamId, "test-repo");

        assertThat(result).isEqualTo(activeBatch);
    }

    @Test
    void resolveActiveBatch_withNullRepoName_throwsException() {
        assertThatThrownBy(() -> batchResolutionService.resolveActiveBatch(teamId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Repository name must not be blank");
    }

    @Test
    void resolveActiveBatch_withBlankRepoName_throwsException() {
        assertThatThrownBy(() -> batchResolutionService.resolveActiveBatch(teamId, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Repository name must not be blank");
    }

    @Test
    void resolveActiveBatch_withNonexistentRepo_throwsException() {
        when(repositoryRepository.findByTeamIdAndName(teamId, "nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> batchResolutionService.resolveActiveBatch(teamId, "nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found for team");
    }

    @Test
    void resolveActiveBatch_withNoActiveBatch_throwsException() {
        when(repositoryRepository.findByTeamIdAndName(teamId, "test-repo")).thenReturn(Optional.of(testRepo));
        when(batchRepository.findByRepositoryIdAndIsActiveTrue(testRepo.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> batchResolutionService.resolveActiveBatch(teamId, "test-repo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No active ingestion batch");
    }

    @Test
    void resolveRepository_withValidRepo_returnsRepository() {
        when(repositoryRepository.findByTeamIdAndName(teamId, "test-repo")).thenReturn(Optional.of(testRepo));

        Repository result = batchResolutionService.resolveRepository(teamId, "test-repo");

        assertThat(result).isEqualTo(testRepo);
    }

    @Test
    void resolveRepository_withNullRepoName_throwsException() {
        assertThatThrownBy(() -> batchResolutionService.resolveRepository(teamId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Repository name must not be blank");
    }

    @Test
    void resolveRepository_withBlankRepoName_throwsException() {
        assertThatThrownBy(() -> batchResolutionService.resolveRepository(teamId, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Repository name must not be blank");
    }

    @Test
    void resolveRepository_withNonexistentRepo_throwsException() {
        when(repositoryRepository.findByTeamIdAndName(teamId, "nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> batchResolutionService.resolveRepository(teamId, "nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found for team");
    }
}
