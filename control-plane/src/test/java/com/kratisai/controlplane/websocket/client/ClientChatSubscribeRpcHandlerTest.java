package com.kratisai.controlplane.websocket.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.MessageRole;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ChatMemoryEntity;
import com.kratisai.controlplane.repository.AgentChatMemoryRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.CanvasService;
import com.kratisai.controlplane.service.ChatFluxRegistry;
import com.kratisai.controlplane.service.ChatService;
import com.kratisai.controlplane.service.ChatSubscriptionRegistry;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class ClientChatSubscribeRpcHandlerTest {

    private ChatFluxRegistry chatFluxRegistry;
    private AgentChatMemoryRepository chatMemoryRepository;
    private ChatSubscriptionRegistry chatSubscriptionRegistry;
    private ClientChatSubscribeRpcHandler handler;

    private ClientSessionRegistry sessionRegistry;
    private UUID teamId;
    private UUID userId;
    private UUID chatId;

    @BeforeEach
    void setUp() {
        ChatService chatService = mock(ChatService.class);
        chatMemoryRepository = mock(AgentChatMemoryRepository.class);
        sessionRegistry = mock(ClientSessionRegistry.class);
        TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
        CanvasService canvasService = mock(CanvasService.class);
        chatFluxRegistry = new ChatFluxRegistry();
        chatSubscriptionRegistry = new ChatSubscriptionRegistry();

        handler = new ClientChatSubscribeRpcHandler(
                chatService,
                chatMemoryRepository,
                sessionRegistry,
                teamMemberRepository,
                canvasService,
                chatFluxRegistry,
                chatSubscriptionRegistry);

        userId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        chatId = UUID.randomUUID();

        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.of(userId.toString()));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        when(chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(chatId)).thenReturn(List.of());
        when(canvasService.getCanvasesForChat(chatId)).thenReturn(List.of());
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
    void buildHistoryPayload_mapsInterstitialEntityToPlanningStatusResult() {
        ChatMemoryEntity entity = new ChatMemoryEntity();
        entity.setId(43L);
        entity.setChatId(chatId);
        entity.setMessageType(MessageRole.ASSISTANT);
        entity.setMessageText("");
        entity.setMetadata(
                "{\"interstitial\":true,\"status\":\"paused\",\"reason\":\"max_iterations\",\"directive\":\"resume directive\"}");
        entity.setCreatedAt(Instant.now());

        ClientPayload payload = ClientChatSubscribeRpcHandler.buildHistoryPayload(entity, chatId);

        assertThat(payload).isInstanceOf(ClientPayload.PlanningStatusResult.class);
        ClientPayload.PlanningStatusResult statusResult = (ClientPayload.PlanningStatusResult) payload;
        assertThat(statusResult.chatId()).isEqualTo(chatId);
        assertThat(statusResult.status()).isEqualTo("paused");
        assertThat(statusResult.reason()).isEqualTo("max_iterations");
        assertThat(statusResult.metadata()).containsEntry("directive", "resume directive");
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

        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-1", 1, "Token 1"));
        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-1", 1, "Token 2"));
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

        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-1", 1, "Token 0"));

        when(sessionRegistry.getUserId("ws-2")).thenReturn(Optional.of(userId.toString()));

        ClientRpcPayload.ChatSubscribe params =
                new ClientRpcPayload.ChatSubscribe(chatId.toString(), teamId.toString());
        List<ClientPayload> sent1 = collect(handler.handle("ws-1", 201, params));
        List<ClientPayload> sent2 = collect(handler.handle("ws-2", 202, params));

        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-1", 1, "Token 1"));
        sink.tryEmitNext(new ClientPayload.CompleteResult("msg-1"));
        sink.tryEmitComplete();

        assertThat(sent1.get(0)).isInstanceOf(ClientPayload.CompleteResult.class);
        assertThat(((ClientPayload.MessageChunkResult) sent1.get(1)).content()).isEqualTo("Token 0");
        assertThat(sent2.get(0)).isInstanceOf(ClientPayload.CompleteResult.class);
        assertThat(((ClientPayload.MessageChunkResult) sent2.get(1)).content()).isEqualTo("Token 0");
    }

    @Test
    void handle_duplicateSubscriptionFromSameSession_acknowledgesWithoutReplayingHistory() {
        ChatMemoryEntity entity = new ChatMemoryEntity();
        entity.setId(7L);
        entity.setChatId(chatId);
        entity.setMessageType(MessageRole.USER);
        entity.setMessageText("hello");
        entity.setCreatedAt(Instant.now());
        when(chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(chatId)).thenReturn(List.of(entity));

        ClientRpcPayload.ChatSubscribe params =
                new ClientRpcPayload.ChatSubscribe(chatId.toString(), teamId.toString());
        collectComplete(handler.handle("ws-1", 301, params));

        List<ClientPayload> duplicate = collectComplete(handler.handle("ws-1", 302, params));

        assertThat(duplicate).singleElement().isInstanceOf(ClientPayload.ChatSubscriptionResult.class);
        ClientPayload.ChatSubscriptionResult ack = (ClientPayload.ChatSubscriptionResult) duplicate.getFirst();
        assertThat(ack.status()).isEqualTo("subscribed");
        assertThat(ack.chatId()).isEqualTo(chatId);
        verify(chatMemoryRepository, times(1)).findByChatIdOrderByCreatedAtAsc(chatId);
    }

    @Test
    void handle_notAuthenticated_throwsNotAuthenticatedError() {
        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(
                        "ws-1", 1, new ClientRpcPayload.ChatSubscribe(chatId.toString(), teamId.toString())))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.NOT_AUTHENTICATED));
    }

    @Test
    void handle_invalidChatId_throwsInvalidParamsError() {
        assertThatThrownBy(() ->
                        handler.handle("ws-1", 1, new ClientRpcPayload.ChatSubscribe("not-a-uuid", teamId.toString())))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS));
    }

    @Test
    void handle_nonMemberTeam_throwsNotAuthorised() {
        TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(false);
        ClientChatSubscribeRpcHandler restricted = new ClientChatSubscribeRpcHandler(
                mock(ChatService.class),
                chatMemoryRepository,
                sessionRegistry,
                teamMemberRepository,
                mock(CanvasService.class),
                chatFluxRegistry,
                new ChatSubscriptionRegistry());

        assertThatThrownBy(() -> restricted.handle(
                        "ws-1", 1, new ClientRpcPayload.ChatSubscribe(chatId.toString(), teamId.toString())))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.NOT_AUTHORIZED));
    }
}
