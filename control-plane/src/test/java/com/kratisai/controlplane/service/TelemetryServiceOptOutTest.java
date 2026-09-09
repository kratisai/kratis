package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.client.PostHogCaptureRequest;
import com.kratisai.controlplane.client.PostHogClient;
import com.kratisai.controlplane.config.TelemetryProperties;
import com.kratisai.controlplane.repository.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TelemetryServiceOptOutTest {

    private InstallationService installationService;
    private TeamRepository teamRepository;
    private RepositoryRepository repositoryRepository;
    private ChatRepository chatRepository;
    private ChatUsageSessionRepository chatUsageSessionRepository;
    private IngestionBatchRepository ingestionBatchRepository;
    private SandboxExecutionRepository sandboxExecutionRepository;
    private PostHogClient postHogClient;

    @BeforeEach
    void setUp() {
        installationService = mock(InstallationService.class);
        teamRepository = mock(TeamRepository.class);
        repositoryRepository = mock(RepositoryRepository.class);
        chatRepository = mock(ChatRepository.class);
        chatUsageSessionRepository = mock(ChatUsageSessionRepository.class);
        ingestionBatchRepository = mock(IngestionBatchRepository.class);
        sandboxExecutionRepository = mock(SandboxExecutionRepository.class);
        postHogClient = mock(PostHogClient.class);
    }

    private TelemetryService service(TelemetryProperties properties) {
        return new TelemetryService(
                properties,
                installationService,
                teamRepository,
                repositoryRepository,
                chatRepository,
                chatUsageSessionRepository,
                ingestionBatchRepository,
                sandboxExecutionRepository,
                postHogClient);
    }

    @Test
    void kratisTelemetryDisabledSkipsEverything() {
        TelemetryProperties props = new TelemetryProperties();
        props.setDisabled("1");

        TelemetryService service = service(props);
        assertThatCode(service::reportUsage).doesNotThrowAnyException();

        verifyNoInteractions(
                installationService,
                teamRepository,
                repositoryRepository,
                chatRepository,
                chatUsageSessionRepository,
                ingestionBatchRepository,
                sandboxExecutionRepository,
                postHogClient);
    }

    @Test
    void doNotTrackSkipsEverything() {
        TelemetryProperties props = new TelemetryProperties();
        props.setDoNotTrack("1");

        TelemetryService service = service(props);
        assertThatCode(service::reportUsage).doesNotThrowAnyException();

        verifyNoInteractions(
                installationService,
                teamRepository,
                repositoryRepository,
                chatRepository,
                chatUsageSessionRepository,
                ingestionBatchRepository,
                sandboxExecutionRepository,
                postHogClient);
    }

    @Test
    void bothFlagsSkipsEverything() {
        TelemetryProperties props = new TelemetryProperties();
        props.setDisabled("1");
        props.setDoNotTrack("1");

        TelemetryService service = service(props);
        assertThatCode(service::reportUsage).doesNotThrowAnyException();

        verifyNoInteractions(
                installationService,
                teamRepository,
                repositoryRepository,
                chatRepository,
                chatUsageSessionRepository,
                ingestionBatchRepository,
                sandboxExecutionRepository,
                postHogClient);
    }

    @Test
    void enabledClientFailureIsSwallowed() {
        when(installationService.getOrCreateInstallationId()).thenReturn(UUID.randomUUID());
        doThrow(new IllegalStateException("boom")).when(postHogClient).capture(any(PostHogCaptureRequest.class));

        TelemetryService service = service(new TelemetryProperties());
        assertThatCode(service::reportUsage).doesNotThrowAnyException();

        verify(postHogClient).capture(any(PostHogCaptureRequest.class));
    }

    @Test
    void enabledCapturesStats() {
        UUID installId = UUID.randomUUID();
        when(installationService.getOrCreateInstallationId()).thenReturn(installId);
        when(teamRepository.count()).thenReturn(2L);
        when(repositoryRepository.count()).thenReturn(5L);
        when(chatRepository.count()).thenReturn(11L);
        when(chatUsageSessionRepository.sumTotalSpend()).thenReturn(4.56789);
        when(ingestionBatchRepository.count()).thenReturn(7L);
        when(ingestionBatchRepository.sumTotalSpend()).thenReturn(0.0001);
        when(sandboxExecutionRepository.count()).thenReturn(42L);
        when(sandboxExecutionRepository.sumTotalSpend()).thenReturn(12.34567);

        TelemetryProperties props = new TelemetryProperties();
        props.setVersion("1.2.3");
        props.setBuildTag("git-abc");
        TelemetryService service = service(props);

        service.reportUsage();

        ArgumentCaptor<PostHogCaptureRequest> captor = ArgumentCaptor.forClass(PostHogCaptureRequest.class);
        verify(postHogClient).capture(captor.capture());
        PostHogCaptureRequest req = captor.getValue();
        assertThat(req.event()).isEqualTo(TelemetryService.EVENT_NAME);
        assertThat(req.distinctId()).isEqualTo(installId.toString());
        assertThat(req.properties()).containsEntry("version", "1.2.3");
        assertThat(req.properties()).containsEntry("build_tag", "git-abc");
        assertThat(req.properties()).containsEntry("team_count", 2L);
        assertThat(req.properties()).containsEntry("repo_count", 5L);
        assertThat(req.properties()).containsEntry("planning_topic_count", 11L);
        assertThat(req.properties()).containsEntry("planning_cost", 4.5679);
        assertThat(req.properties()).containsEntry("ingestion_job_count", 7L);
        assertThat(req.properties()).containsEntry("ingestion_cost", 0.0001);
        assertThat(req.properties()).containsEntry("execution_count", 42L);
        assertThat(req.properties()).containsEntry("execution_cost", 12.3457);
    }
}
