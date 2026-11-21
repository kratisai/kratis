package com.kratisai.controlplane.ingestion.teamwiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiSchedulerService.DeferralReason;
import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiSchedulerService.TeamWikiScheduleStatus;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.model.TeamWikiBuildMode;
import com.kratisai.controlplane.model.TeamWikiTriggerKind;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

@SpringIntegrationTest
class TeamWikiSchedulerServiceTest {

    @Autowired
    private TeamWikiSchedulerService schedulerService;

    @Autowired
    private TeamWikiSnapshotService snapshotService;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private TeamWikiBatchRepository teamWikiBatchRepository;

    @Autowired
    private TeamWikiWorker teamWikiWorker;

    private Team team;
    private Repository repo1;
    private IngestionBatch batch1;

    @BeforeEach
    void setUp() {
        doNothing().when(teamWikiWorker).runBuild(any());
        databaseCleaner.cleanAll();
        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext("Sched Team", "repo-one");
        team = ctx.team();
        repo1 = ctx.repository();
        batch1 = ctx.batch();
        batch1.setStatus(IngestionStatus.SUCCESS);
        batch1.setCompletedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        batch1.setActive(true);
        ingestionBatchRepository.saveAndFlush(batch1);
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    private Repository createRepo(String name) {
        return testDataFactory.createRepository(team, name, TestDataFactory.getOrCreateDummyRepo(), "main", null);
    }

    private void createBatch(Repository repo, IngestionStatus status, boolean active, Instant completedAt) {
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStatus(status);
        batch.setActive(active);
        batch.setCompletedAt(completedAt);
        batch.setCommitHash(UUID.randomUUID().toString());
        ingestionBatchRepository.saveAndFlush(batch);
    }

    @Test
    void evaluateTeam_requiresAtLeastTwoIngestedRepos_andDeactivatesIfBelowThreshold() {
        assertThat(schedulerService.evaluateTeam(team.getId())).isNull();

        TeamWikiBatch staleActive = new TeamWikiBatch(
                team,
                TeamWikiBuildMode.FULL,
                TeamWikiTriggerKind.AUTOMATIC,
                snapshotService.captureCurrentState(team.getId()).snapshot());
        staleActive.setStatus(IngestionStatus.SUCCESS);
        staleActive.setActive(true);
        teamWikiBatchRepository.saveAndFlush(staleActive);

        assertThat(schedulerService.evaluateTeam(team.getId())).isNull();
        assertThat(teamWikiBatchRepository.findByTeamIdAndIsActiveTrue(team.getId()))
                .isEmpty();
    }

    @Test
    void evaluateTeam_schedulesFullWhenTwoReposIngested_andDefersWhileInFlightWithin90m() {
        Repository repo2 = createRepo("repo-two");
        createBatch(repo2, IngestionStatus.SUCCESS, true, Instant.now().minus(5, ChronoUnit.MINUTES));

        // Add a 3rd repo currently PROCESSING -> should defer because dirtySince is 10m ago (< 90m)
        Repository repo3 = createRepo("repo-three");
        createBatch(repo3, IngestionStatus.PROCESSING, false, null);

        assertThat(schedulerService.evaluateTeam(team.getId())).isNull();
        TeamWikiScheduleStatus deferredStatus = schedulerService.computeStatus(team.getId());
        assertThat(deferredStatus.dirty()).isTrue();
        assertThat(deferredStatus.deferralReason()).isEqualTo(DeferralReason.IN_FLIGHT_REPOS);

        // Age batch1 past 90 minutes -> max deferral exceeded -> triggers despite in-flight repo3
        batch1.setCompletedAt(Instant.now().minus(95, ChronoUnit.MINUTES));
        ingestionBatchRepository.saveAndFlush(batch1);

        UUID queuedBatchId = schedulerService.evaluateTeam(team.getId());
        assertThat(queuedBatchId).isNotNull();
        TeamWikiBatch queued = teamWikiBatchRepository.findById(queuedBatchId).orElseThrow();
        assertThat(queued.getBuildMode()).isEqualTo(TeamWikiBuildMode.FULL);
        assertThat(queued.getTriggerKind()).isEqualTo(TeamWikiTriggerKind.AUTOMATIC);
        verify(teamWikiWorker).runBuild(queuedBatchId);
    }

    @Test
    void evaluateTeam_respectsRolling24hQuota_andManualBypassesQuota() {
        Repository repo2 = createRepo("repo-two");
        createBatch(repo2, IngestionStatus.SUCCESS, true, Instant.now().minus(5, ChronoUnit.MINUTES));

        for (int i = 0; i < 4; i++) {
            TeamWikiBatch past = new TeamWikiBatch(
                    team,
                    TeamWikiBuildMode.INCREMENTAL,
                    TeamWikiTriggerKind.AUTOMATIC,
                    snapshotService.captureCurrentState(team.getId()).snapshot());
            past.setStatus(IngestionStatus.SUCCESS);
            past.setStartedAt(Instant.now().minus(4 - i, ChronoUnit.HOURS));
            past.setCompletedAt(Instant.now().minus(4 - i, ChronoUnit.HOURS));
            teamWikiBatchRepository.saveAndFlush(past);
        }

        assertThat(schedulerService.evaluateTeam(team.getId())).isNull();
        assertThat(schedulerService.computeStatus(team.getId()).deferralReason())
                .isEqualTo(DeferralReason.QUOTA_REACHED);

        TeamWikiBatch manual = schedulerService.triggerManualBuild(team.getId(), TeamWikiBuildMode.FULL);
        assertThat(manual.getTriggerKind()).isEqualTo(TeamWikiTriggerKind.MANUAL);
        assertThat(manual.getBuildMode()).isEqualTo(TeamWikiBuildMode.FULL);
        verify(teamWikiWorker).runBuild(manual.getId());
    }

    @Test
    void evaluateTeam_ignoresNonStructuralReingestion_andSchedulesIncrementalOnStructuralChange() {
        Repository repo2 = createRepo("repo-two");
        createBatch(repo2, IngestionStatus.SUCCESS, true, Instant.now().minus(5, ChronoUnit.MINUTES));

        TeamWikiBatch active = new TeamWikiBatch(
                team,
                TeamWikiBuildMode.FULL,
                TeamWikiTriggerKind.AUTOMATIC,
                snapshotService.captureCurrentState(team.getId()).snapshot());
        active.setStatus(IngestionStatus.SUCCESS);
        active.setActive(true);
        active.setCompletedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        teamWikiBatchRepository.saveAndFlush(active);

        // Non-structural re-ingestion of repo1 on new commit
        batch1.setActive(false);
        ingestionBatchRepository.saveAndFlush(batch1);
        createBatch(repo1, IngestionStatus.SUCCESS, true, Instant.now());

        assertThat(schedulerService.evaluateTeam(team.getId())).isNull();
        assertThat(schedulerService.computeStatus(team.getId()).nonStructuralChangedRepoCount())
                .isEqualTo(1);

        // Structural change: ingest a 3rd repo
        Repository repo3 = createRepo("repo-three");
        createBatch(repo3, IngestionStatus.SUCCESS, true, Instant.now());

        UUID incrementalId = schedulerService.evaluateTeam(team.getId());
        assertThat(incrementalId).isNotNull();
        assertThat(teamWikiBatchRepository.findById(incrementalId).orElseThrow().getBuildMode())
                .isEqualTo(TeamWikiBuildMode.INCREMENTAL);
    }

    @Test
    void triggerManualBuild_rejectsWhenFewerThanTwoRepos() {
        assertThatThrownBy(() -> schedulerService.triggerManualBuild(team.getId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("At least 2 ingested repositories");
    }

    @Test
    void evaluateAllTeams_respectsFailureBackoffUntilNewerRepoEvent() {
        Repository repo2 = createRepo("repo-two");
        createBatch(repo2, IngestionStatus.SUCCESS, true, Instant.now().minus(5, ChronoUnit.MINUTES));

        TeamWikiBatch failed = new TeamWikiBatch(
                team,
                TeamWikiBuildMode.FULL,
                TeamWikiTriggerKind.AUTOMATIC,
                snapshotService.captureCurrentState(team.getId()).snapshot());
        failed.setStatus(IngestionStatus.FAILED);
        failed.setCompletedAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        teamWikiBatchRepository.saveAndFlush(failed);

        schedulerService.evaluateAllTeams();
        assertThat(schedulerService.computeStatus(team.getId()).deferralReason())
                .isEqualTo(DeferralReason.FAILURE_BACKOFF);
    }
}
