package com.kratisai.controlplane.websocket.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionActivityResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlRequiredResult;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.MessageRole;
import com.kratisai.controlplane.model.ChatMemoryEntity;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.AgentChatMemoryRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.CanvasService;
import com.kratisai.controlplane.service.ChatFluxRegistry;
import com.kratisai.controlplane.service.ChatService;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.ExecutionActivityPersistenceService;
import com.kratisai.controlplane.service.PendingHitlRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class ClientChatSubscribeRpcHandlerTest {

    private ChatFluxRegistry chatFluxRegistry;
    private ClientChatSubscribeRpcHandler handler;
    private SandboxExecutionRepository executionRepository;
    private PendingHitlRegistry pendingHitlRegistry;
    private ExecutionActivityPersistenceService activityPersistenceService;

    private ClientSessionRegistry sessionRegistry;
    private UUID teamId;
    private UUID userId;
    private UUID chatId;

    @BeforeEach
    void setUp() {
        ChatService chatService = mock(ChatService.class);
        AgentChatMemoryRepository chatMemoryRepository = mock(AgentChatMemoryRepository.class);
        sessionRegistry = mock(ClientSessionRegistry.class);
        TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
        CanvasService canvasService = mock(CanvasService.class);
        chatFluxRegistry = new ChatFluxRegistry();
        executionRepository = mock(SandboxExecutionRepository.class);
        pendingHitlRegistry = new PendingHitlRegistry(mock(EnvironmentSessionRegistry.class));
        activityPersistenceService = mock(ExecutionActivityPersistenceService.class);

        handler = new ClientChatSubscribeRpcHandler(
                chatService,
                chatMemoryRepository,
                sessionRegistry,
                teamMemberRepository,
                canvasService,
                chatFluxRegistry,
                executionRepository,
                pendingHitlRegistry,
                activityPersistenceService);

        userId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        chatId = UUID.randomUUID();

        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.of(userId.toString()));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        when(chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(chatId)).thenReturn(List.of());
        when(canvasService.getCanvasesForChat(chatId)).thenReturn(List.of());
        when(executionRepository.findByChatIdOrderByStartedAtAsc(chatId)).thenReturn(List.of());
    }

    private static SandboxExecution runningExecution(UUID id) {
        SandboxExecution execution = new SandboxExecution();
        execution.setId(id);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution.setStartedAt(Instant.now());
        return execution;
    }

    private static SandboxExecution completedExecution(UUID id) {
        SandboxExecution execution = new SandboxExecution();
        execution.setId(id);
        execution.setStatus(SandboxExecutionStatus.COMPLETED);
        execution.setStartedAt(Instant.now());
        return execution;
    }

    private static List<ClientPayload> collectComplete(Flux<ClientPayload> flux) {
        return Objects.requireNonNull(flux.collectList().block());
    }

    private static List<ClientPayload> collect(Flux<ClientPayload> flux) {
        List<ClientPayload> items = new ArrayList<>();
        flux.subscribe(items::add);
        return items;
    }

    @Test
    void buildHistoryPayload_marksPersistedErrorAsChatErrorResult() {
        ChatMemoryEntity entity = new ChatMemoryEntity();
        entity.setId(42L);
        entity.setChatId(chatId);
        entity.setMessageType(MessageRole.ASSISTANT);
        entity.setMessageText("Agent exceeded maximum iterations (3).");
        entity.setMetadata("{\"isError\":true,\"code\":-32008}");
        entity.setCreatedAt(Instant.now());

        ClientPayload payload = ClientChatSubscribeRpcHandler.buildHistoryPayload(entity, chatId);

        assertThat(payload).isInstanceOf(ClientPayload.ChatErrorResult.class);
        ClientPayload.ChatErrorResult error = (ClientPayload.ChatErrorResult) payload;
        assertThat(error.chatId()).isEqualTo(chatId);
        assertThat(error.code()).isEqualTo(-32008);
        assertThat(error.message()).isEqualTo("Agent exceeded maximum iterations (3).");
    }

    @Test
    void buildHistoryPayload_returnsMessageResultForRegularMessage() {
        ChatMemoryEntity entity = new ChatMemoryEntity();
        entity.setId(42L);
        entity.setChatId(chatId);
        entity.setMessageType(MessageRole.USER);
        entity.setMessageText("hello");
        entity.setCreatedAt(Instant.now());

        ClientPayload payload = ClientChatSubscribeRpcHandler.buildHistoryPayload(entity, chatId);

        assertThat(payload).isInstanceOf(ClientPayload.MessageResult.class);
    }

    @Test
    void handle_noLiveFlux_sendsCompleteResult() {
        ClientRpcPayload.ChatSubscribe params =
                new ClientRpcPayload.ChatSubscribe(chatId.toString(), teamId.toString());

        List<ClientPayload> sent = collectComplete(handler.handle("ws-1", 101, params));

        assertThat(sent).singleElement().isInstanceOf(ClientPayload.CompleteResult.class);
        ClientPayload.CompleteResult result = (ClientPayload.CompleteResult) sent.getFirst();
        assertThat(result.chatId()).isEqualTo(chatId);
        assertThat(result.messageCount()).isZero();
    }

    @Test
    void handle_withLiveFlux_pipesTokensToSession() {
        Sinks.Many<ClientPayload.ChatStreamPayload> sink = Sinks.many().replay().all();
        chatFluxRegistry.register(chatId, sink.asFlux());

        ClientRpcPayload.ChatSubscribe params =
                new ClientRpcPayload.ChatSubscribe(chatId.toString(), teamId.toString());

        List<ClientPayload> sent = collect(handler.handle("ws-1", 102, params));

        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-1", "Token 1"));
        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-1", "Token 2"));
        sink.tryEmitNext(new ClientPayload.CompleteResult("msg-1"));
        sink.tryEmitComplete();

        assertThat(sent.get(0)).isInstanceOf(ClientPayload.CompleteResult.class);
        assertThat(((ClientPayload.MessageChunkResult) sent.get(1)).content()).isEqualTo("Token 1");
        assertThat(((ClientPayload.MessageChunkResult) sent.get(2)).content()).isEqualTo("Token 2");
        assertThat(sent.get(3)).isInstanceOf(ClientPayload.CompleteResult.class);
    }

    @Test
    void handle_multipleSubscribersJoinMidStream_bothReceiveAllTokensFromBeginning() {
        Sinks.Many<ClientPayload.ChatStreamPayload> sink = Sinks.many().replay().all();
        chatFluxRegistry.register(chatId, sink.asFlux());

        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-1", "Token 0"));

        when(sessionRegistry.getUserId("ws-2")).thenReturn(Optional.of(userId.toString()));

        ClientRpcPayload.ChatSubscribe params =
                new ClientRpcPayload.ChatSubscribe(chatId.toString(), teamId.toString());
        List<ClientPayload> sent1 = collect(handler.handle("ws-1", 201, params));
        List<ClientPayload> sent2 = collect(handler.handle("ws-2", 202, params));

        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-1", "Token 1"));
        sink.tryEmitNext(new ClientPayload.CompleteResult("msg-1"));
        sink.tryEmitComplete();

        assertThat(sent1.get(0)).isInstanceOf(ClientPayload.CompleteResult.class);
        assertThat(((ClientPayload.MessageChunkResult) sent1.get(1)).content()).isEqualTo("Token 0");
        assertThat(sent2.get(0)).isInstanceOf(ClientPayload.CompleteResult.class);
        assertThat(((ClientPayload.MessageChunkResult) sent2.get(1)).content()).isEqualTo("Token 0");
    }

    private static SandboxExecutionActivity activity(
            UUID executionId,
            long sequence,
            String actionId,
            ActivityType type,
            ActivityStatus status,
            String description) {
        return new SandboxExecutionActivity(executionId, sequence, actionId, type, status, description, null);
    }

    @Test
    void handle_replaysPersistedActivitiesOfRunningExecutionInOrder() {
        UUID executionId = UUID.randomUUID();
        when(executionRepository.findByChatIdOrderByStartedAtAsc(chatId))
                .thenReturn(List.of(runningExecution(executionId)));
        when(activityPersistenceService.getActivities(executionId))
                .thenReturn(List.of(
                        activity(
                                executionId,
                                1,
                                "thought-1",
                                ActivityType.THINKING,
                                ActivityStatus.IN_PROGRESS,
                                "first"),
                        activity(executionId, 2, "tc-2", ActivityType.COMMAND, ActivityStatus.IN_PROGRESS, "second")));
        when(activityPersistenceService.detailOf(any(SandboxExecutionActivity.class)))
                .thenReturn(null);

        ClientRpcPayload.ChatSubscribe params =
                new ClientRpcPayload.ChatSubscribe(chatId.toString(), teamId.toString());
        List<ClientPayload> sent = collectComplete(handler.handle("ws-1", 301, params));

        assertThat(sent.getFirst()).isInstanceOf(ClientPayload.ExecutionActivityResult.class);
        ExecutionActivityResult first = (ExecutionActivityResult) sent.getFirst();
        assertThat(first.executionId()).isEqualTo(executionId);
        assertThat(first.activityType()).isEqualTo(ActivityType.THINKING);
        assertThat(first.description()).isEqualTo("first");
        assertThat(first.actionId()).isEqualTo("thought-1");
        ExecutionActivityResult second = (ExecutionActivityResult) sent.get(1);
        assertThat(second.description()).isEqualTo("second");
        assertThat(sent.get(2)).isInstanceOf(ClientPayload.CompleteResult.class);
    }

    @Test
    void handle_replaysPendingPermissionOfRunningExecution() {
        UUID executionId = UUID.randomUUID();
        when(executionRepository.findByChatIdOrderByStartedAtAsc(chatId))
                .thenReturn(List.of(runningExecution(executionId)));
        WebSocketSession envSession = mock(WebSocketSession.class);
        pendingHitlRegistry.register(
                executionId,
                new PendingHitlRegistry.PendingHitl(
                        HitlKind.APPROVAL,
                        envSession,
                        "req-1",
                        "tool-call-42",
                        "Approve rm -rf /",
                        "rm -rf /",
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.now(),
                        teamId));

        ClientRpcPayload.ChatSubscribe params =
                new ClientRpcPayload.ChatSubscribe(chatId.toString(), teamId.toString());
        List<ClientPayload> sent = collectComplete(handler.handle("ws-1", 302, params));

        ExecutionHitlRequiredResult permission = (ExecutionHitlRequiredResult) sent.getFirst();
        assertThat(permission.executionId()).isEqualTo(executionId);
        assertThat(permission.command()).isEqualTo("rm -rf /");
        assertThat(permission.hitlId()).isEqualTo("tool-call-42");
    }

    @Test
    void handle_doesNotReplayStateForCompletedExecution() {
        UUID executionId = UUID.randomUUID();
        when(executionRepository.findByChatIdOrderByStartedAtAsc(chatId))
                .thenReturn(List.of(completedExecution(executionId)));
        WebSocketSession envSession = mock(WebSocketSession.class);
        pendingHitlRegistry.register(
                executionId,
                new PendingHitlRegistry.PendingHitl(
                        HitlKind.APPROVAL,
                        envSession,
                        "req-1",
                        "tool-call-42",
                        "Approve rm -rf /",
                        "rm -rf /",
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.now(),
                        teamId));

        ClientRpcPayload.ChatSubscribe params =
                new ClientRpcPayload.ChatSubscribe(chatId.toString(), teamId.toString());
        List<ClientPayload> sent = collectComplete(handler.handle("ws-1", 303, params));

        assertThat(sent).singleElement().isInstanceOf(ClientPayload.CompleteResult.class);
        verify(activityPersistenceService, never()).getActivities(executionId);
    }
}
