package com.kratisai.controlplane.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.config.PullRequestStatusProperties;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.git.provider.PullRequestSnapshot;
import com.kratisai.controlplane.git.provider.RepoProvider;
import com.kratisai.controlplane.model.PublishedPrStatus;
import com.kratisai.controlplane.model.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrStatusPollerTest {

    private final PrStatusRefresher refresher = mock(PrStatusRefresher.class);
    private final RepoProvider provider = mock(RepoProvider.class);
    private final Repository repository = mock(Repository.class);
    private final GitAuthMaterial auth = GitAuthMaterial.ofToken("token");
    private final UUID changeSetId = UUID.randomUUID();

    private PrStatusPoller poller;

    @BeforeEach
    void setUp() {
        poller = new PrStatusPoller(refresher, new PullRequestStatusProperties());
        when(refresher.findCandidateIds(any(Instant.class))).thenReturn(List.of(changeSetId));
        when(provider.supportsPullRequests()).thenReturn(true);
    }

    @Test
    void skipsWritesWhenTheOpenStatusIsUnchanged() {
        when(refresher.load(changeSetId)).thenReturn(Optional.of(target(PublishedPrStatus.OPEN)));
        when(provider.getPullRequest(repository, auth, 42L))
                .thenReturn(snapshot(PullRequestSnapshot.PullRequestState.OPEN));

        poller.pollOpenPullRequests();

        verify(refresher, never()).apply(any(), any(), any());
    }

    @Test
    void appliesAMergeTransition() {
        when(refresher.load(changeSetId)).thenReturn(Optional.of(target(PublishedPrStatus.OPEN)));
        when(provider.getPullRequest(repository, auth, 42L))
                .thenReturn(snapshot(PullRequestSnapshot.PullRequestState.MERGED));

        poller.pollOpenPullRequests();

        verify(refresher).apply(changeSetId, PublishedPrStatus.MERGED, null);
    }

    @Test
    void passesObservedMergeTimestampToRefresher() {
        Instant mergedAt = Instant.parse("2026-05-01T10:00:00Z");
        when(refresher.load(changeSetId)).thenReturn(Optional.of(target(PublishedPrStatus.OPEN)));
        when(provider.getPullRequest(repository, auth, 42L))
                .thenReturn(new PullRequestSnapshot(
                        42,
                        PullRequestSnapshot.PullRequestState.MERGED,
                        "https://example.test/pr/42",
                        "feature",
                        "main",
                        "sha",
                        mergedAt));

        poller.pollOpenPullRequests();

        verify(refresher).apply(changeSetId, PublishedPrStatus.MERGED, mergedAt);
    }

    @Test
    void pollsLegacyRowsWithoutAStoredStatus() {
        when(refresher.load(changeSetId)).thenReturn(Optional.of(target(null)));
        when(provider.getPullRequest(repository, auth, 42L))
                .thenReturn(snapshot(PullRequestSnapshot.PullRequestState.OPEN));

        poller.pollOpenPullRequests();

        verify(refresher).apply(changeSetId, PublishedPrStatus.OPEN, null);
    }

    @Test
    void continuesWithRemainingChangeSetsAfterOneFails() {
        UUID otherId = UUID.randomUUID();
        when(refresher.findCandidateIds(any(Instant.class))).thenReturn(List.of(changeSetId, otherId));
        when(refresher.load(changeSetId)).thenThrow(new IllegalStateException("provider unavailable"));
        when(refresher.load(otherId))
                .thenReturn(Optional.of(
                        new PrStatusRefresher.PollTarget(43L, PublishedPrStatus.OPEN, repository, provider, auth)));
        when(provider.getPullRequest(repository, auth, 43L))
                .thenReturn(snapshot(PullRequestSnapshot.PullRequestState.CLOSED));

        poller.pollOpenPullRequests();

        verify(refresher).apply(otherId, PublishedPrStatus.CLOSED, null);
    }

    @Test
    void skipsProvidersWithoutPullRequestSupport() {
        when(refresher.load(changeSetId)).thenReturn(Optional.of(target(PublishedPrStatus.OPEN)));
        when(provider.supportsPullRequests()).thenReturn(false);

        poller.pollOpenPullRequests();

        verify(provider, never()).getPullRequest(any(), any(), eq(42L));
        verify(refresher, never()).apply(any(), any(), any());
    }

    private PrStatusRefresher.PollTarget target(PublishedPrStatus status) {
        return new PrStatusRefresher.PollTarget(42L, status, repository, provider, auth);
    }

    private PullRequestSnapshot snapshot(PullRequestSnapshot.PullRequestState state) {
        return new PullRequestSnapshot(42, state, "https://example.test/pr/42", "feature", "main", "sha", null);
    }
}
