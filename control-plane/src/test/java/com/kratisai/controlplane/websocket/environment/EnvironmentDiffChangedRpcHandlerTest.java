package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.GitDiffStatus;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionDiffSnapshot;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.SandboxExecutionDiffChangedEvent;
import com.kratisai.controlplane.repository.ExecutionDiffSnapshotRepository;
import com.kratisai.controlplane.service.BlobStorageService;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class EnvironmentDiffChangedRpcHandlerTest {

    @Mock
    private EnvironmentExecutionGuard executionGuard;

    @Mock
    private ExecutionDiffSnapshotRepository diffSnapshotRepository;

    @Mock
    private BlobStorageService blobStorageService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EnvironmentDiffChangedRpcHandler handler;

    private final UUID teamId = UUID.randomUUID();
    private final UUID chatId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final String sessionId = "env-session-1";

    @BeforeEach
    void setUp() {
        handler = new EnvironmentDiffChangedRpcHandler(
                executionGuard, diffSnapshotRepository, blobStorageService, eventPublisher, objectMapper);

        Team team = new Team("TestTeam", "desc");
        team.setId(teamId);
        ChatEntity chat = new ChatEntity(team, null, "Chat");
        chat.setId(chatId);

        SandboxExecution execution = new SandboxExecution();
        execution.setId(executionId);
        execution.setChat(chat);

        when(executionGuard.requireExecutionInEnvironment(sessionId, executionId))
                .thenReturn(execution);
    }

    @Test
    void handle_createsNewSnapshotAndStoresBlob() {
        EnvironmentConnectorResult.GitDiffSummaryFile fileSummary =
                new EnvironmentConnectorResult.GitDiffSummaryFile("src/main.go", GitDiffStatus.MODIFIED, 5, 2, false);
        EnvironmentRpcPayload.DiffChanged payload = new EnvironmentRpcPayload.DiffChanged(
                executionId.toString(), "commit-a", "commit-b", 5, 2, List.of(fileSummary), "diff --git a/src/main.go");

        when(diffSnapshotRepository.findByExecutionId(executionId)).thenReturn(Optional.empty());

        EnvironmentResponsePayload ack =
                handler.handle(sessionId, null, payload).blockLast();

        assertThat(ack).isEqualTo(new EnvironmentResponsePayload.EnvironmentDiffChangedResult());

        byte[] patchBytes = payload.patch().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        verify(blobStorageService)
                .putObject(
                        eq("diffs/" + executionId + ".patch"),
                        any(InputStream.class),
                        eq((long) patchBytes.length),
                        eq("text/plain"));

        ArgumentCaptor<ExecutionDiffSnapshot> snapshotCaptor = ArgumentCaptor.forClass(ExecutionDiffSnapshot.class);
        verify(diffSnapshotRepository).save(snapshotCaptor.capture());

        ExecutionDiffSnapshot saved = snapshotCaptor.getValue();
        assertThat(saved.getExecutionId()).isEqualTo(executionId);
        assertThat(saved.getBaseCommit()).isEqualTo("commit-a");
        assertThat(saved.getHeadCommit()).isEqualTo("commit-b");
        assertThat(saved.getTotalAdditions()).isEqualTo(5);
        assertThat(saved.getTotalDeletions()).isEqualTo(2);
        assertThat(saved.getPatchStoragePath()).isEqualTo("diffs/" + executionId + ".patch");
        assertThat(saved.getSummaryJson()).contains("src/main.go");

        ArgumentCaptor<SandboxExecutionDiffChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(SandboxExecutionDiffChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().executionId()).isEqualTo(executionId);
        assertThat(eventCaptor.getValue().chatId()).isEqualTo(chatId);
        assertThat(eventCaptor.getValue().teamId()).isEqualTo(teamId);
    }

    @Test
    void handle_updatesExistingSnapshot() {
        ExecutionDiffSnapshot existing =
                new ExecutionDiffSnapshot(executionId, "old-a", "old-b", 1, 1, "[]", "diffs/" + executionId + ".patch");
        when(diffSnapshotRepository.findByExecutionId(executionId)).thenReturn(Optional.of(existing));

        EnvironmentRpcPayload.DiffChanged payload = new EnvironmentRpcPayload.DiffChanged(
                executionId.toString(), "new-a", "new-b", 10, 4, List.of(), "diff content");

        EnvironmentResponsePayload ack =
                handler.handle(sessionId, null, payload).blockLast();

        assertThat(ack).isEqualTo(new EnvironmentResponsePayload.EnvironmentDiffChangedResult());
        verify(diffSnapshotRepository).save(existing);
        assertThat(existing.getBaseCommit()).isEqualTo("new-a");
        assertThat(existing.getHeadCommit()).isEqualTo("new-b");
        assertThat(existing.getTotalAdditions()).isEqualTo(10);
        assertThat(existing.getTotalDeletions()).isEqualTo(4);
    }

    @Test
    void handle_propagatesBlobStorageFailure() {
        EnvironmentRpcPayload.DiffChanged payload = new EnvironmentRpcPayload.DiffChanged(
                executionId.toString(), "commit-a", "commit-b", 5, 2, List.of(), "diff --git a/src/main.go");

        org.mockito.Mockito.doThrow(
                        new java.io.UncheckedIOException(new java.nio.file.AccessDeniedException("/data/blobs")))
                .when(blobStorageService)
                .putObject(any(), any(InputStream.class), org.mockito.ArgumentMatchers.anyLong(), any());

        assertThatThrownBy(() -> handler.handle(sessionId, null, payload).blockLast())
                .isInstanceOf(java.io.UncheckedIOException.class);

        verify(diffSnapshotRepository, org.mockito.Mockito.never()).save(any(ExecutionDiffSnapshot.class));
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any(SandboxExecutionDiffChangedEvent.class));
    }
}
