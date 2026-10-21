package com.kratisai.controlplane.websocket.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.ChatSubscriptionRegistry;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.ExecutionActivityPersistenceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ClientExecutionSubscribeRpcHandlerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-20T17:32:12.672Z");

    private ClientSessionRegistry sessionRegistry;
    private SandboxExecutionRepository executionRepository;
    private TeamMemberRepository teamMemberRepository;
    private ExecutionActivityPersistenceService activityPersistenceService;
    private ChatSubscriptionRegistry chatSubscriptionRegistry;
    private ClientExecutionSubscribeRpcHandler handler;
    private UUID userId;
    private UUID teamId;
    private UUID executionId;

    @BeforeEach
    void setUp() {
        sessionRegistry = mock(ClientSessionRegistry.class);
        executionRepository = mock(SandboxExecutionRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        activityPersistenceService = mock(ExecutionActivityPersistenceService.class);
        chatSubscriptionRegistry = new ChatSubscriptionRegistry();
        handler = new ClientExecutionSubscribeRpcHandler(
                sessionRegistry,
                executionRepository,
                teamMemberRepository,
                activityPersistenceService,
                chatSubscriptionRegistry);

        userId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.of(userId.toString()));
    }

    @Test
    void handle_replaysActivitiesAndRegistersSubscription() {
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution()));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        SandboxExecutionActivity activity = new SandboxExecutionActivity(
                executionId, 1, "a1", ActivityType.COMMAND, ActivityStatus.COMPLETED, "ran ls", null);
        activity.setCreatedAt(CREATED_AT);
        when(activityPersistenceService.getActivities(executionId)).thenReturn(List.of(activity));
        when(activityPersistenceService.detailOf(any())).thenReturn(null);

        StepVerifier.create(handler.handle("ws-1", 1, new ClientRpcPayload.ExecutionSubscribe(executionId.toString())))
                .assertNext(payload -> {
                    assertThat(payload).isInstanceOf(ClientPayload.ExecutionActivityResult.class);
                    ClientPayload.ExecutionActivityResult result = (ClientPayload.ExecutionActivityResult) payload;
                    assertThat(result.executionId()).isEqualTo(executionId);
                    assertThat(result.description()).isEqualTo("ran ls");
                })
                .verifyComplete();

        assertThat(chatSubscriptionRegistry.isExecutionSubscribed("ws-1", executionId))
                .isTrue();
    }

    @Test
    void handle_duplicateSubscription_acknowledgesWithoutReplayingAgain() {
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution()));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        when(activityPersistenceService.getActivities(executionId)).thenReturn(List.of());

        handler.handle("ws-1", 1, new ClientRpcPayload.ExecutionSubscribe(executionId.toString()))
                .blockLast();

        StepVerifier.create(handler.handle("ws-1", 2, new ClientRpcPayload.ExecutionSubscribe(executionId.toString())))
                .assertNext(payload -> {
                    assertThat(payload).isInstanceOf(ClientPayload.ExecutionSubscriptionResult.class);
                    ClientPayload.ExecutionSubscriptionResult result =
                            (ClientPayload.ExecutionSubscriptionResult) payload;
                    assertThat(result.status()).isEqualTo("subscribed");
                    assertThat(result.executionId()).isEqualTo(executionId);
                })
                .verifyComplete();

        verify(activityPersistenceService, times(1)).getActivities(executionId);
        verify(executionRepository, times(1)).findById(executionId);
    }

    @Test
    void handle_notAuthenticated_throws() {
        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        handler.handle("ws-1", 1, new ClientRpcPayload.ExecutionSubscribe(executionId.toString())))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.NOT_AUTHENTICATED));
    }

    @Test
    void handle_invalidExecutionId_throws() {
        assertThatThrownBy(() -> handler.handle("ws-1", 1, new ClientRpcPayload.ExecutionSubscribe("nope")))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS));
    }

    @Test
    void handle_executionNotFound_throws() {
        when(executionRepository.findById(executionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        handler.handle("ws-1", 1, new ClientRpcPayload.ExecutionSubscribe(executionId.toString())))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.RESOURCE_NOT_FOUND));
    }

    @Test
    void handle_notTeamMember_throwsAndDoesNotRegister() {
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution()));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(false);

        assertThatThrownBy(() ->
                        handler.handle("ws-1", 1, new ClientRpcPayload.ExecutionSubscribe(executionId.toString())))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.NOT_AUTHORIZED));

        assertThat(chatSubscriptionRegistry.isExecutionSubscribed("ws-1", executionId))
                .isFalse();
        verify(activityPersistenceService, never()).getActivities(any());
    }

    @Test
    void handle_methodNameAndPayloadType_areCorrect() {
        assertThat(handler.getMethodName()).isEqualTo(ClientRpcPayload.ExecutionSubscribe.METHOD);
        assertThat(handler.getPayloadType()).isEqualTo(ClientRpcPayload.ExecutionSubscribe.class);
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
