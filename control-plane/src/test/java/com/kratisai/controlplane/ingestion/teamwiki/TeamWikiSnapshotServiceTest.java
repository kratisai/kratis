package com.kratisai.controlplane.ingestion.teamwiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiSnapshotService.SnapshotDiff;
import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiSnapshotService.SnapshotState;
import com.kratisai.controlplane.model.Ceiling;
import com.kratisai.controlplane.model.CtxRepoRelation;
import com.kratisai.controlplane.model.CtxRepoSynopsis;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Nature;
import com.kratisai.controlplane.model.RepoRole;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot.RelationEntry;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot.RepoEntry;
import com.kratisai.controlplane.repository.CtxRepoRelationRepository;
import com.kratisai.controlplane.repository.CtxRepoSynopsisRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamWikiSnapshotServiceTest {

    @Mock
    private IngestionBatchRepository ingestionBatchRepository;

    @Mock
    private CtxRepoSynopsisRepository synopsisRepository;

    @Mock
    private CtxRepoRelationRepository relationRepository;

    private TeamWikiSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new TeamWikiSnapshotService(ingestionBatchRepository, synopsisRepository, relationRepository);
    }

    @Test
    void captureCurrentState_populatesSortedReposAndRelations() {
        UUID teamId = UUID.randomUUID();
        Team team = new Team();
        team.setId(teamId);
        UUID repoAId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID repoBId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        Repository repoA = new Repository();
        repoA.setId(repoAId);
        repoA.setTeam(team);
        repoA.setName("service-a");
        IngestionBatch batchA = new IngestionBatch(repoA);
        batchA.setId(UUID.randomUUID());
        batchA.setCommitHash("aaa");
        batchA.setCompletedAt(Instant.parse("2026-11-16T10:00:00Z"));

        Repository repoB = new Repository();
        repoB.setId(repoBId);
        repoB.setTeam(team);
        repoB.setName("service-b");
        IngestionBatch batchB = new IngestionBatch(repoB);
        batchB.setId(UUID.randomUUID());
        batchB.setCommitHash("bbb");
        batchB.setCompletedAt(Instant.parse("2026-11-16T10:05:00Z"));

        CtxRepoSynopsis synA = new CtxRepoSynopsis(batchA, RepoRole.SERVICE, List.of(), "Service A", "{}");

        CtxRepoRelation rel =
                new CtxRepoRelation(teamId, batchA, batchB, Nature.CALLS, Direction.A_TO_B, Ceiling.CONFIRMED);

        when(ingestionBatchRepository.findActiveByTeamId(teamId)).thenReturn(List.of(batchB, batchA));
        when(synopsisRepository.findByBatchIdIn(List.of(batchB.getId(), batchA.getId())))
                .thenReturn(List.of(synA));
        when(relationRepository.findCurrentByTeamId(teamId)).thenReturn(List.of(rel));

        SnapshotState state = service.captureCurrentState(teamId);

        assertThat(state.snapshot().repos()).hasSize(2);
        assertThat(state.snapshot().repos().getFirst().repoName()).isEqualTo("service-a");
        assertThat(state.snapshot().repos().getFirst().repoRole()).isEqualTo(RepoRole.SERVICE);
        assertThat(state.snapshot().relations()).hasSize(1);
        assertThat(state.snapshot().relations().getFirst().nature()).isEqualTo(Nature.CALLS);
    }

    @Test
    void diff_detectsStructuralVsNonStructuralChanges() {
        UUID repoAId = UUID.randomUUID();
        UUID repoBId = UUID.randomUUID();
        UUID batchA1 = UUID.randomUUID();
        UUID batchA2 = UUID.randomUUID();
        UUID batchB1 = UUID.randomUUID();

        RepoEntry prevA = new RepoEntry(repoAId, "repo-a", batchA1, "c1", RepoRole.SERVICE);
        RepoEntry prevB = new RepoEntry(repoBId, "repo-b", batchB1, "c2", RepoRole.LIBRARY);
        RelationEntry rel = new RelationEntry(
                repoAId, "repo-a", repoBId, "repo-b", Nature.BUILDS_AGAINST, Direction.B_TO_A, Ceiling.CONFIRMED);

        TeamWikiSourceSnapshot previous = new TeamWikiSourceSnapshot(List.of(prevA, prevB), List.of(rel));

        // 1. Internal re-ingestion of repo-a on a new commit, same role and relations -> NOT structural
        RepoEntry currAInternal = new RepoEntry(repoAId, "repo-a", batchA2, "c3", RepoRole.SERVICE);
        SnapshotState nonStructuralState = new SnapshotState(
                new TeamWikiSourceSnapshot(List.of(currAInternal, prevB), List.of(rel)), Map.of(), Map.of());

        SnapshotDiff nonStructuralDiff = service.diff(previous, nonStructuralState);
        assertThat(nonStructuralDiff.hasStructuralChange()).isFalse();
        assertThat(nonStructuralDiff.reingestedRepos()).containsExactly(currAInternal);

        // 2. Role change -> structural
        RepoEntry currARoleChanged = new RepoEntry(repoAId, "repo-a", batchA2, "c3", RepoRole.INFRA);
        SnapshotState roleState = new SnapshotState(
                new TeamWikiSourceSnapshot(List.of(currARoleChanged, prevB), List.of(rel)),
                Map.of(repoAId, Instant.parse("2026-11-16T11:00:00Z")),
                Map.of());
        SnapshotDiff roleDiff = service.diff(previous, roleState);
        assertThat(roleDiff.hasStructuralChange()).isTrue();
        assertThat(roleDiff.roleChanges()).hasSize(1);
        assertThat(roleDiff.earliestStructuralChangeAt()).isEqualTo(Instant.parse("2026-11-16T11:00:00Z"));

        // 3. Deleted repo -> structural
        SnapshotState deletedState =
                new SnapshotState(new TeamWikiSourceSnapshot(List.of(prevA), List.of()), Map.of(), Map.of());
        SnapshotDiff deletedDiff = service.diff(previous, deletedState);
        assertThat(deletedDiff.hasStructuralChange()).isTrue();
        assertThat(deletedDiff.removedRepos()).containsExactly(prevB);
        assertThat(deletedDiff.removedRelations()).containsExactly(rel);
    }
}
