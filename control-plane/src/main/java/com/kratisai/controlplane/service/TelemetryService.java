package com.kratisai.controlplane.service;

import com.kratisai.controlplane.client.PostHogCaptureRequest;
import com.kratisai.controlplane.client.PostHogClient;
import com.kratisai.controlplane.config.TelemetryProperties;
import com.kratisai.controlplane.repository.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Daily anonymous usage heartbeat. Reports cumulative lifetime counts and LLM
 * spend to PostHog unless {@link TelemetryProperties#isEnabled()} is false
 * (KRATIS_TELEMETRY_DISABLED or DO_NOT_TRACK). Failures are logged and never
 * propagated, so telemetry can never take the control plane down.
 */
@Service
public class TelemetryService {

    private static final Logger logger = LoggerFactory.getLogger(TelemetryService.class);

    static final String EVENT_NAME = "kratis_daily_usage";

    private final TelemetryProperties properties;
    private final InstallationService installationService;
    private final TeamRepository teamRepository;
    private final RepositoryRepository repositoryRepository;
    private final ChatRepository chatRepository;
    private final ChatUsageSessionRepository chatUsageSessionRepository;
    private final IngestionBatchRepository ingestionBatchRepository;
    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final PostHogClient postHogClient;

    public TelemetryService(
            TelemetryProperties properties,
            InstallationService installationService,
            TeamRepository teamRepository,
            RepositoryRepository repositoryRepository,
            ChatRepository chatRepository,
            ChatUsageSessionRepository chatUsageSessionRepository,
            IngestionBatchRepository ingestionBatchRepository,
            SandboxExecutionRepository sandboxExecutionRepository,
            PostHogClient postHogClient) {
        this.properties = properties;
        this.installationService = installationService;
        this.teamRepository = teamRepository;
        this.repositoryRepository = repositoryRepository;
        this.chatRepository = chatRepository;
        this.chatUsageSessionRepository = chatUsageSessionRepository;
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.postHogClient = postHogClient;
    }

    @Scheduled(
            initialDelayString = "${kratis.telemetry.initial-delay-ms:60000}",
            fixedDelayString = "${kratis.telemetry.fixed-delay-ms:86400000}")
    public void reportUsage() {
        if (!properties.isEnabled()) {
            logger.debug("Usage telemetry disabled; skipping report");
            return;
        }
        try {
            UUID installId = installationService.getOrCreateInstallationId();
            postHogClient.capture(new PostHogCaptureRequest(EVENT_NAME, installId.toString(), collectStats()));
        } catch (RuntimeException e) {
            logger.warn("Failed to report usage telemetry: {}", e.getMessage());
        }
    }

    private Map<String, Object> collectStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("version", properties.getVersion());
        stats.put("build_tag", properties.getBuildTag());
        stats.put("team_count", teamRepository.count());
        stats.put("repo_count", repositoryRepository.count());
        stats.put("planning_topic_count", chatRepository.count());
        stats.put("planning_cost", round4(chatUsageSessionRepository.sumTotalSpend()));
        stats.put("ingestion_job_count", ingestionBatchRepository.count());
        stats.put("ingestion_cost", round4(ingestionBatchRepository.sumTotalSpend()));
        stats.put("execution_count", sandboxExecutionRepository.count());
        stats.put("execution_cost", round4(sandboxExecutionRepository.sumTotalSpend()));
        return stats;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
