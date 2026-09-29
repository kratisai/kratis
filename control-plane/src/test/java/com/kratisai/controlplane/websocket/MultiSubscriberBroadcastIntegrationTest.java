package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.planningagent.telemetry.TelemetryEvent;
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
class MultiSubscriberBroadcastIntegrationTest {

    private ClientSessionRegistry sessionRegistry;
    private ChatFluxRegistry chatFluxRegistry;
    private ClientChatSubscribeRpcHandler handler;

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
        when(chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(chatId)).thenReturn(List.of());
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        when(executionRepository.findByChatIdOrderByStartedAtAsc(chatId)).thenReturn(List.of());
    }

    @Test
    void multiSubscriber_receivesAllStreamPayloadTypesIdentically() {
        WebSocketSession session1 = mock(WebSocketSession.class);
        when(session1.getId()).thenReturn("sub-1");
        when(sessionRegistry.getUserId("sub-1")).thenReturn(Optional.of(userId.toString()));

        WebSocketSession session2 = mock(WebSocketSession.class);
        when(session2.getId()).thenReturn("sub-2");
        when(sessionRegistry.getUserId("sub-2")).thenReturn(Optional.of(userId.toString()));

        Sinks.Many<ClientPayload.ChatStreamPayload> sink = Sinks.many().replay().all();
        chatFluxRegistry.register(chatId, sink.asFlux());

        ClientRpcPayload.ChatSubscribe params =
                new ClientRpcPayload.ChatSubscribe(chatId.toString(), teamId.toString());
        List<ClientPayload> payloads1 = new ArrayList<>();
        List<ClientPayload> payloads2 = new ArrayList<>();
        handler.handle(session1.getId(), 101, params).subscribe(payloads1::add);
        handler.handle(session2.getId(), 102, params).subscribe(payloads2::add);

        sink.tryEmitNext(
                new ClientPayload.TelemetryResult(chatId, new TelemetryEvent.Thought("Planning execution...")));
        sink.tryEmitNext(new ClientPayload.TelemetryResult(
                chatId, new TelemetryEvent.ToolStart("call-1", "execute_command", "Running bash tool")));
        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-123", "Task completed."));
        sink.tryEmitNext(new ClientPayload.CompleteResult("msg-123"));
        sink.tryEmitComplete();

        assertThat(payloads1)
                .anyMatch(p -> p instanceof ClientPayload.TelemetryResult t
                        && ((TelemetryEvent.Thought) t.event()).text().contains("Planning execution..."));
        assertThat(payloads1)
                .anyMatch(p ->
                        p instanceof ClientPayload.TelemetryResult t && t.event() instanceof TelemetryEvent.ToolStart);
        assertThat(payloads1)
                .anyMatch(p -> p instanceof ClientPayload.MessageChunkResult m
                        && m.content().equals("Task completed."));
        assertThat(payloads1).anyMatch(p -> p instanceof ClientPayload.CompleteResult);

        assertThat(payloads2)
                .anyMatch(p -> p instanceof ClientPayload.TelemetryResult t
                        && ((TelemetryEvent.Thought) t.event()).text().contains("Planning execution..."));
        assertThat(payloads2)
                .anyMatch(p ->
                        p instanceof ClientPayload.TelemetryResult t && t.event() instanceof TelemetryEvent.ToolStart);
        assertThat(payloads2)
                .anyMatch(p -> p instanceof ClientPayload.MessageChunkResult m
                        && m.content().equals("Task completed."));
        assertThat(payloads2).anyMatch(p -> p instanceof ClientPayload.CompleteResult);
    }
}
