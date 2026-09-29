package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.MessageRole;
import com.kratisai.controlplane.model.ChatMemoryEntity;
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
import com.kratisai.controlplane.websocket.client.ClientChatSubscribeRpcHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Sinks;

@SpringIntegrationTest
class ChatSubscribeLateJoinerIntegrationTest {

    private AgentChatMemoryRepository chatMemoryRepository;
    private ClientSessionRegistry sessionRegistry;
    private TeamMemberRepository teamMemberRepository;
    private ChatFluxRegistry chatFluxRegistry;
    private ClientChatSubscribeRpcHandler handler;

    private UUID teamId;
    private UUID userId;
    private UUID chatId;

    @BeforeEach
    void setUp() {
        ChatService chatService = mock(ChatService.class);
        chatMemoryRepository = mock(AgentChatMemoryRepository.class);
        sessionRegistry = mock(ClientSessionRegistry.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        CanvasService canvasService = mock(CanvasService.class);
        chatFluxRegistry = new ChatFluxRegistry();
        SandboxExecutionRepository executionRepository = mock(SandboxExecutionRepository.class);
        PendingHitlRegistry pendingHitlRegistry = new PendingHitlRegistry(mock(EnvironmentSessionRegistry.class));
        ExecutionActivityPersistenceService activityPersistenceService =
                mock(ExecutionActivityPersistenceService.class);

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

        teamId = UUID.randomUUID();
        userId = UUID.randomUUID();
        chatId = UUID.randomUUID();

        when(canvasService.getCanvasesForChat(chatId)).thenReturn(List.of());
        when(executionRepository.findByChatIdOrderByStartedAtAsc(chatId)).thenReturn(List.of());
    }

    @Test
    void chatSubscribe_lateJoinerReceivesReplayAndLiveStream() {
        WebSocketSession session1 = mock(WebSocketSession.class);
        when(session1.getId()).thenReturn("sess-1");
        when(sessionRegistry.getUserId("sess-1")).thenReturn(Optional.of(userId.toString()));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);

        ChatMemoryEntity userMsgEntity =
                new ChatMemoryEntity(chatId, 0, MessageRole.USER, "What is Kratis?", null, null, null);
        userMsgEntity.setId(100L);
        when(chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(chatId)).thenReturn(List.of(userMsgEntity));

        Sinks.Many<ClientPayload.ChatStreamPayload> sink = Sinks.many().replay().all();
        chatFluxRegistry.register(chatId, sink.asFlux());

        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-assistant", "Chunk 1 "));

        ClientRpcPayload.ChatSubscribe params =
                new ClientRpcPayload.ChatSubscribe(chatId.toString(), teamId.toString());
        List<ClientPayload> payloads1 = new ArrayList<>();
        handler.handle(session1.getId(), 1, params).subscribe(payloads1::add);

        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-assistant", "Chunk 2 "));

        WebSocketSession session2 = mock(WebSocketSession.class);
        when(session2.getId()).thenReturn("sess-2");
        when(sessionRegistry.getUserId("sess-2")).thenReturn(Optional.of(userId.toString()));

        List<ClientPayload> payloads2 = new ArrayList<>();
        handler.handle(session2.getId(), 2, params).subscribe(payloads2::add);

        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-assistant", "Chunk 3"));
        sink.tryEmitNext(new ClientPayload.CompleteResult("msg-assistant"));
        sink.tryEmitComplete();

        assertThat(payloads1)
                .anyMatch(p -> p instanceof ClientPayload.MessageResult mr
                        && mr.content().contains("What is Kratis?"));
        assertThat(payloads1).anyMatch(p -> p instanceof ClientPayload.CompleteResult c && chatId.equals(c.chatId()));
        assertThat(payloads1)
                .anyMatch(p -> p instanceof ClientPayload.MessageChunkResult m
                        && m.content().contains("Chunk 1"));
        assertThat(payloads1)
                .anyMatch(p -> p instanceof ClientPayload.MessageChunkResult m
                        && m.content().contains("Chunk 2"));
        assertThat(payloads1)
                .anyMatch(p -> p instanceof ClientPayload.MessageChunkResult m
                        && m.content().contains("Chunk 3"));

        assertThat(payloads2)
                .anyMatch(p -> p instanceof ClientPayload.MessageResult mr
                        && mr.content().contains("What is Kratis?"));
        assertThat(payloads2).anyMatch(p -> p instanceof ClientPayload.CompleteResult c && chatId.equals(c.chatId()));
        assertThat(payloads2)
                .anyMatch(p -> p instanceof ClientPayload.MessageChunkResult m
                        && m.content().contains("Chunk 1"));
        assertThat(payloads2)
                .anyMatch(p -> p instanceof ClientPayload.MessageChunkResult m
                        && m.content().contains("Chunk 2"));
        assertThat(payloads2)
                .anyMatch(p -> p instanceof ClientPayload.MessageChunkResult m
                        && m.content().contains("Chunk 3"));
    }
}
