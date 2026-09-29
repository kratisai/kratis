package com.kratisai.controlplane.websocket.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ActivityDetail;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.PlanEntry;
import com.kratisai.controlplane.api.wsdto.PlanEntryPriority;
import com.kratisai.controlplane.api.wsdto.PlanEntryStatus;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.ExecutionActivityPersistenceService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ClientExecutionReplayActivitiesRpcHandlerTest {

    private ClientSessionRegistry sessionRegistry;
    private SandboxExecutionRepository executionRepository;
    private TeamMemberRepository teamMemberRepository;
    private ExecutionActivityPersistenceService activityPersistenceService;
    private ClientExecutionReplayActivitiesRpcHandler handler;
    private UUID userId;
    private UUID teamId;
    private UUID executionId;

    @BeforeEach
    void setUp() {
        sessionRegistry = mock(ClientSessionRegistry.class);
        executionRepository = mock(SandboxExecutionRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        activityPersistenceService = mock(ExecutionActivityPersistenceService.class);
        handler = new ClientExecutionReplayActivitiesRpcHandler(
                sessionRegistry, executionRepository, teamMemberRepository, activityPersistenceService);

        userId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.of(userId.toString()));
    }

    @Test
    void handle_replaysActivitiesThenComplete() {
        SandboxExecution execution = execution();
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        SandboxExecutionActivity activity = new SandboxExecutionActivity(
                executionId, 1, "a1", ActivityType.COMMAND, ActivityStatus.COMPLETED, "ran ls", null);
        when(activityPersistenceService.getActivities(executionId)).thenReturn(List.of(activity));
        when(activityPersistenceService.detailOf(any())).thenReturn(null);

        StepVerifier.create(handler.handle(
                        "ws-1", 1, new ClientRpcPayload.ExecutionReplayActivities(executionId.toString())))
                .assertNext(payload -> {
                    assertThat(payload).isInstanceOf(ClientPayload.ExecutionActivityResult.class);
                    ClientPayload.ExecutionActivityResult result = (ClientPayload.ExecutionActivityResult) payload;
                    assertThat(result.executionId()).isEqualTo(executionId);
                    assertThat(result.description()).isEqualTo("ran ls");
                })
                .assertNext(payload -> {
                    assertThat(payload).isInstanceOf(ClientPayload.ExecutionReplayCompleteResult.class);
                    ClientPayload.ExecutionReplayCompleteResult complete =
                            (ClientPayload.ExecutionReplayCompleteResult) payload;
                    assertThat(complete.executionId()).isEqualTo(executionId);
                    assertThat(complete.activityCount()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void handle_notAuthenticated_throws() {
        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(
                        "ws-1", 1, new ClientRpcPayload.ExecutionReplayActivities(executionId.toString())))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.NOT_AUTHENTICATED));
    }

    @Test
    void handle_invalidExecutionId_throws() {
        assertThatThrownBy(() -> handler.handle("ws-1", 1, new ClientRpcPayload.ExecutionReplayActivities("nope")))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS));
    }

    @Test
    void handle_executionNotFound_throws() {
        when(executionRepository.findById(executionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(
                        "ws-1", 1, new ClientRpcPayload.ExecutionReplayActivities(executionId.toString())))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.RESOURCE_NOT_FOUND));
    }

    @Test
    void handle_notTeamMember_throws() {
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution()));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(false);

        assertThatThrownBy(() -> handler.handle(
                        "ws-1", 1, new ClientRpcPayload.ExecutionReplayActivities(executionId.toString())))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.NOT_AUTHORIZED));
    }

    @Test
    void handle_replaysLatestPlanActivity() {
        SandboxExecution execution = execution();
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        SandboxExecutionActivity plan = new SandboxExecutionActivity(
                executionId, 1, "plan", ActivityType.PLAN, ActivityStatus.IN_PROGRESS, "Agent plan updated", null);
        when(activityPersistenceService.getActivities(executionId)).thenReturn(List.of(plan));
        when(activityPersistenceService.detailOf(any()))
                .thenReturn(new ActivityDetail(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(new PlanEntry("Setup repo", PlanEntryPriority.HIGH, PlanEntryStatus.IN_PROGRESS)),
                        null,
                        null));

        StepVerifier.create(handler.handle(
                        "ws-1", 1, new ClientRpcPayload.ExecutionReplayActivities(executionId.toString())))
                .assertNext(payload -> {
                    assertThat(payload).isInstanceOf(ClientPayload.ExecutionActivityResult.class);
                    ClientPayload.ExecutionActivityResult result = (ClientPayload.ExecutionActivityResult) payload;
                    assertThat(result.activityType()).isEqualTo(ActivityType.PLAN);
                    assertThat(result.detail().plan()).singleElement().satisfies(entry -> {
                        assertThat(entry.content()).isEqualTo("Setup repo");
                        assertThat(entry.priority()).isEqualTo(PlanEntryPriority.HIGH);
                        assertThat(entry.status()).isEqualTo(PlanEntryStatus.IN_PROGRESS);
                    });
                })
                .assertNext(payload -> {
                    assertThat(payload).isInstanceOf(ClientPayload.ExecutionReplayCompleteResult.class);
                    assertThat(((ClientPayload.ExecutionReplayCompleteResult) payload).activityCount())
                            .isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void handle_methodNameAndPayloadType_areCorrect() {
        assertThat(handler.getMethodName()).isEqualTo(ClientRpcPayload.ExecutionReplayActivities.METHOD);
        assertThat(handler.getPayloadType()).isEqualTo(ClientRpcPayload.ExecutionReplayActivities.class);
    }

    private SandboxExecution execution() {
        Team team = new Team();
        team.setId(teamId);
        ChatEntity chat = new ChatEntity();
        chat.setTeam(team);
        SandboxExecution execution = new SandboxExecution();
        execution.setId(executionId);
        execution.setChat(chat);
        return execution;
    }
}
