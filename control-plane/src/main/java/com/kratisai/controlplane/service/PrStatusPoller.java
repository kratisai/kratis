package com.kratisai.controlplane.service;

import com.kratisai.controlplane.config.PullRequestStatusProperties;
import com.kratisai.controlplane.git.provider.PullRequestSnapshot;
import com.kratisai.controlplane.model.PublishedPrStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls open pull requests on a fixed cadence. The provider call runs outside any transaction and
 * each status write commits on its own, so a failure only skips the affected change set.
 */
@Component
public class PrStatusPoller {

    private static final Logger logger = LoggerFactory.getLogger(PrStatusPoller.class);

    private final PrStatusRefresher refresher;
    private final PullRequestStatusProperties properties;

    public PrStatusPoller(PrStatusRefresher refresher, PullRequestStatusProperties properties) {
        this.refresher = Objects.requireNonNull(refresher);
        this.properties = Objects.requireNonNull(properties);
    }

    @Scheduled(fixedDelayString = "${kratis.pull-request-status.poll-interval:60000}")
    public void pollOpenPullRequests() {
        if (!properties.isEnabled()) {
            return;
        }
        for (UUID changeSetId : refresher.findCandidateIds(Instant.now().minus(properties.getMaxExecutionAge()))) {
            try {
                refresh(changeSetId);
            } catch (Exception exception) {
                logger.warn("Failed to poll PR status for change set {}: {}", changeSetId, exception.getMessage());
            }
        }
    }

    private void refresh(UUID changeSetId) {
        PrStatusRefresher.PollTarget target = refresher.load(changeSetId).orElse(null);
        if (target == null || !target.provider().supportsPullRequests()) {
            return;
        }
        PullRequestSnapshot snapshot =
                target.provider().getPullRequest(target.repository(), target.auth(), target.prNumber());
        PublishedPrStatus observedStatus = status(snapshot.state());
        if (observedStatus != target.currentStatus()) {
            refresher.apply(changeSetId, observedStatus);
        }
    }

    private PublishedPrStatus status(PullRequestSnapshot.PullRequestState state) {
        return switch (state) {
            case OPEN -> PublishedPrStatus.OPEN;
            case MERGED -> PublishedPrStatus.MERGED;
            case CLOSED -> PublishedPrStatus.CLOSED;
        };
    }
}
