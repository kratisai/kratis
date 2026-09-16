package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.CanvasEvent;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionAcpInitializedResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionActivityResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionCompleteResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlRequiredResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlResolvedResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionOutputResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionStatusChangedResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.IngestionResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.TeamEntityChangedResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.UserEntityChangedResult;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.IngestionEvent;
import com.kratisai.controlplane.api.wsdto.IngestionStatusEvent;
import com.kratisai.controlplane.api.wsdto.OutputStream;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.CanvasDeletedEvent;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionAcpInitializedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionActivityEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlRequiredEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionOutputEvent;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.model.event.UserEntityChangedEvent;
import com.kratisai.controlplane.model.event.UserEntityType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClientRealtimeEventListenersTest {

    @Mock
    private WebSocketDispatch dispatch;

    private ClientRealtimeEventListeners listeners;

    @BeforeEach
    void setUp() {
        listeners = new ClientRealtimeEventListeners(dispatch);
    }

    @Test
    void onTeamEntityChangedEvent_broadcastsTeamResult() {
        UUID teamId = UUID.randomUUID();
        listeners.onTeamEntityChangedEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.CREDENTIALS));

        ArgumentCaptor<ClientPayload> captor = ArgumentCaptor.forClass(ClientPayload.class);
        verify(dispatch).broadcastNotificationToTeam(eq(teamId), captor.capture());
        TeamEntityChangedResult result = (TeamEntityChangedResult) captor.getValue();
        assertThat(result.teamId()).isEqualTo(teamId);
        assertThat(result.entity()).isEqualTo(TeamEntityType.CREDENTIALS);
    }

    @Test
    void onUserEntityChangedEvent_broadcastsUserResult() {
        UUID userId = UUID.randomUUID();
        listeners.onUserEntityChangedEvent(new UserEntityChangedEvent(userId, UserEntityType.TEAMS));

        ArgumentCaptor<ClientPayload> captor = ArgumentCaptor.forClass(ClientPayload.class);
        verify(dispatch).broadcastNotificationToUser(eq(userId), captor.capture());
        UserEntityChangedResult result = (UserEntityChangedResult) captor.getValue();
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.entity()).isEqualTo(UserEntityType.TEAMS);
    }

    @Test
    void onCanvasDeletedEvent_broadcastsCanvasResult() {
        UUID teamId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        String documentId = "doc-1";
        listeners.onCanvasDeletedEvent(new CanvasDeletedEvent(teamId, chatId, documentId));

        ArgumentCaptor<ClientPayload> captor = ArgumentCaptor.forClass(ClientPayload.class);
        verify(dispatch).broadcastNotificationToTeam(eq(teamId), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(ClientPayload.CanvasResult.class);
        ClientPayload.CanvasResult result = (ClientPayload.CanvasResult) captor.getValue();
        assertThat(result.event()).isInstanceOf(CanvasEvent.Delete.class);
        CanvasEvent.Delete delete = (CanvasEvent.Delete) result.event();
        assertThat(delete.documentId()).isEqualTo(documentId);
        assertThat(delete.chatId()).isEqualTo(chatId);
    }

    @Test
    void onIngestionStatusEvent_broadcastsIngestionResult() {
        UUID teamId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();

        Team team = new Team("Test Team", "Test");
        team.setId(teamId);
        Repository repository =
                new Repository("test-repo", "https://github.com/test/repo.git", "main", RepositoryType.GITHUB);
        repository.setId(repoId);
        repository.setTeam(team);
        IngestionBatch batch = new IngestionBatch(repository);
        batch.setId(batchId);
        batch.setStatus(IngestionStatus.SUCCESS);
        batch.setCommitHash("abc123");
        batch.setCompletedAt(Instant.now());

        listeners.onIngestionStatusEvent(new IngestionStatusEvent(batch));

        ArgumentCaptor<ClientPayload> captor = ArgumentCaptor.forClass(ClientPayload.class);
        verify(dispatch, times(2)).broadcastNotificationToTeam(eq(teamId), captor.capture());
        List<ClientPayload> values = captor.getAllValues();
        assertThat(values.getFirst()).isInstanceOf(IngestionResult.class);
        IngestionResult result = (IngestionResult) values.getFirst();
        assertThat(result.event()).isInstanceOf(IngestionEvent.StatusUpdate.class);
        assertThat(values.get(1)).isInstanceOf(TeamEntityChangedResult.class);
        TeamEntityChangedResult teamResult = (TeamEntityChangedResult) values.get(1);
        assertThat(teamResult.entity()).isEqualTo(TeamEntityType.REPOSITORIES);
    }

    @Test
    void onSandboxExecutionOutputEvent_broadcastsOutput() {
        UUID teamId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        listeners.onSandboxExecutionOutputEvent(
                new SandboxExecutionOutputEvent(teamId, executionId, "line\n", OutputStream.STDOUT));

        ArgumentCaptor<ClientPayload> captor = ArgumentCaptor.forClass(ClientPayload.class);
        verify(dispatch).broadcastNotificationToTeam(eq(teamId), captor.capture());
        ExecutionOutputResult result = (ExecutionOutputResult) captor.getValue();
        assertThat(result.executionId()).isEqualTo(executionId);
        assertThat(result.line()).isEqualTo("line\n");
    }

    @Test
    void onSandboxExecutionActivityEvent_broadcastsActivity() {
        UUID teamId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        listeners.onSandboxExecutionActivityEvent(new SandboxExecutionActivityEvent(
                teamId, executionId, ActivityType.COMMAND, "running", "tool-call-7", ActivityStatus.IN_PROGRESS, null));

        ArgumentCaptor<ClientPayload> captor = ArgumentCaptor.forClass(ClientPayload.class);
        verify(dispatch).broadcastNotificationToTeam(eq(teamId), captor.capture());
        ExecutionActivityResult result = (ExecutionActivityResult) captor.getValue();
        assertThat(result.executionId()).isEqualTo(executionId);
        assertThat(result.activityType()).isEqualTo(ActivityType.COMMAND);
        assertThat(result.actionId()).isEqualTo("tool-call-7");
        assertThat(result.status()).isEqualTo(ActivityStatus.IN_PROGRESS);
    }

    @Test
    void onSandboxExecutionHitlRequiredEvent_broadcastsApprovalRequired() {
        UUID teamId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        listeners.onSandboxExecutionHitlRequiredEvent(new SandboxExecutionHitlRequiredEvent(
                teamId,
                executionId,
                "tool-call-42",
                "Remove",
                HitlKind.APPROVAL,
                "rm -rf /",
                "Remove",
                "execute",
                null,
                null,
                null));

        ArgumentCaptor<ClientPayload> captor = ArgumentCaptor.forClass(ClientPayload.class);
        verify(dispatch).broadcastNotificationToTeam(eq(teamId), captor.capture());
        ExecutionHitlRequiredResult result = (ExecutionHitlRequiredResult) captor.getValue();
        assertThat(result.hitlId()).isEqualTo("tool-call-42");
        assertThat(result.command()).isEqualTo("rm -rf /");
        assertThat(result.kind()).isEqualTo(HitlKind.APPROVAL);
    }

    @Test
    void onSandboxExecutionHitlResolvedEvent_broadcastsApprovalResolved() {
        UUID teamId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        listeners.onSandboxExecutionHitlResolvedEvent(new SandboxExecutionHitlResolvedEvent(
                teamId,
                executionId,
                "tool-call-42",
                HitlKind.APPROVAL,
                HitlResponse.APPROVED,
                "allow-once",
                null,
                userId,
                "Alice"));

        ArgumentCaptor<ClientPayload> captor = ArgumentCaptor.forClass(ClientPayload.class);
        verify(dispatch).broadcastNotificationToTeam(eq(teamId), captor.capture());
        ExecutionHitlResolvedResult result = (ExecutionHitlResolvedResult) captor.getValue();
        assertThat(result.response()).isEqualTo(HitlResponse.APPROVED);
        assertThat(result.optionId()).isEqualTo("allow-once");
        assertThat(result.resolvedByDisplayName()).isEqualTo("Alice");
    }

    @Test
    void onSandboxExecutionHitlRequiredEvent_broadcastsQuestionRequired() {
        UUID teamId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        listeners.onSandboxExecutionHitlRequiredEvent(new SandboxExecutionHitlRequiredEvent(
                teamId,
                executionId,
                "el-1",
                "Choose a target",
                HitlKind.QUESTION,
                null,
                null,
                null,
                null,
                null,
                java.util.Map.of("type", "object")));

        ArgumentCaptor<ClientPayload> captor = ArgumentCaptor.forClass(ClientPayload.class);
        verify(dispatch).broadcastNotificationToTeam(eq(teamId), captor.capture());
        ExecutionHitlRequiredResult result = (ExecutionHitlRequiredResult) captor.getValue();
        assertThat(result.hitlId()).isEqualTo("el-1");
        assertThat(result.message()).isEqualTo("Choose a target");
        assertThat(result.kind()).isEqualTo(HitlKind.QUESTION);
        assertThat(result.form()).isEqualTo(java.util.Map.of("type", "object"));
    }

    @Test
    void onSandboxExecutionHitlResolvedEvent_broadcastsQuestionResolved() {
        UUID teamId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        listeners.onSandboxExecutionHitlResolvedEvent(new SandboxExecutionHitlResolvedEvent(
                teamId,
                executionId,
                "el-1",
                HitlKind.QUESTION,
                HitlResponse.ANSWERED,
                null,
                java.util.Map.of("target", "staging"),
                userId,
                "Alice"));

        ArgumentCaptor<ClientPayload> captor = ArgumentCaptor.forClass(ClientPayload.class);
        verify(dispatch).broadcastNotificationToTeam(eq(teamId), captor.capture());
        ExecutionHitlResolvedResult result = (ExecutionHitlResolvedResult) captor.getValue();
        assertThat(result.response()).isEqualTo(HitlResponse.ANSWERED);
        assertThat(result.content()).isEqualTo(java.util.Map.of("target", "staging"));
        assertThat(result.resolvedByDisplayName()).isEqualTo("Alice");
    }

    @Test
    void onSandboxExecutionCompleteEvent_broadcastsComplete() {
        UUID teamId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        listeners.onSandboxExecutionCompleteEvent(
                new SandboxExecutionCompleteEvent(teamId, executionId, 0, SandboxExecutionStatus.COMPLETED));

        ArgumentCaptor<ClientPayload> captor = ArgumentCaptor.forClass(ClientPayload.class);
        verify(dispatch).broadcastNotificationToTeam(eq(teamId), captor.capture());
        ExecutionCompleteResult result = (ExecutionCompleteResult) captor.getValue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.status()).isEqualTo(SandboxExecutionStatus.COMPLETED);
    }

    @Test
    void onSandboxExecutionAcpInitializedEvent_broadcastsInitialized() {
        UUID teamId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        listeners.onSandboxExecutionAcpInitializedEvent(
                new SandboxExecutionAcpInitializedEvent(teamId, executionId, "acp-sess-1", "goose", "1.46.0"));

        ArgumentCaptor<ClientPayload> captor = ArgumentCaptor.forClass(ClientPayload.class);
        verify(dispatch).broadcastNotificationToTeam(eq(teamId), captor.capture());
        ExecutionAcpInitializedResult result = (ExecutionAcpInitializedResult) captor.getValue();
        assertThat(result.executionId()).isEqualTo(executionId);
        assertThat(result.sessionId()).isEqualTo("acp-sess-1");
        assertThat(result.agentName()).isEqualTo("goose");
        assertThat(result.agentVersion()).isEqualTo("1.46.0");
    }

    @Test
    void onExecutionStatusChangedEvent_broadcastsStatusChanged() {
        UUID teamId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        listeners.onExecutionStatusChangedEvent(new ExecutionStatusChangedEvent(teamId, chatId, executionId));

        ArgumentCaptor<ClientPayload> captor = ArgumentCaptor.forClass(ClientPayload.class);
        verify(dispatch).broadcastNotificationToTeam(eq(teamId), captor.capture());
        ExecutionStatusChangedResult result = (ExecutionStatusChangedResult) captor.getValue();
        assertThat(result.teamId()).isEqualTo(teamId);
        assertThat(result.chatId()).isEqualTo(chatId);
        assertThat(result.executionId()).isEqualTo(executionId);
    }

    @Test
    void onTeamEntityChangedEvent_swallowsBroadcastFailures() {
        UUID teamId = UUID.randomUUID();
        doThrow(new RuntimeException("boom")).when(dispatch).broadcastNotificationToTeam(any(), any());

        listeners.onTeamEntityChangedEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.CHATS));

        verify(dispatch).broadcastNotificationToTeam(eq(teamId), any());
    }
}
