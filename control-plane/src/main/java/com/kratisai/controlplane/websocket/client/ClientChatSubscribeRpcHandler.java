package com.kratisai.controlplane.websocket.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.CanvasEvent;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.ChatMemoryEntity;
import com.kratisai.controlplane.repository.AgentChatMemoryRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.CanvasService;
import com.kratisai.controlplane.service.ChatFluxRegistry;
import com.kratisai.controlplane.service.ChatService;
import com.kratisai.controlplane.service.ChatSubscriptionRegistry;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

@Component
public class ClientChatSubscribeRpcHandler implements ClientRpcHandler<ClientRpcPayload.ChatSubscribe, ClientPayload> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatService chatService;
    private final AgentChatMemoryRepository chatMemoryRepository;
    private final ClientSessionRegistry sessionRegistry;
    private final TeamMemberRepository teamMemberRepository;
    private final CanvasService canvasService;
    private final ChatFluxRegistry chatFluxRegistry;
    private final ChatSubscriptionRegistry chatSubscriptionRegistry;

    public ClientChatSubscribeRpcHandler(
            ChatService chatService,
            AgentChatMemoryRepository chatMemoryRepository,
            ClientSessionRegistry sessionRegistry,
            TeamMemberRepository teamMemberRepository,
            CanvasService canvasService,
            ChatFluxRegistry chatFluxRegistry,
            ChatSubscriptionRegistry chatSubscriptionRegistry) {
        this.chatService = chatService;
        this.chatMemoryRepository = chatMemoryRepository;
        this.sessionRegistry = sessionRegistry;
        this.teamMemberRepository = teamMemberRepository;
        this.canvasService = canvasService;
        this.chatFluxRegistry = chatFluxRegistry;
        this.chatSubscriptionRegistry = chatSubscriptionRegistry;
    }

    @Override
    public String getMethodName() {
        return ClientRpcPayload.ChatSubscribe.METHOD;
    }

    @Override
    public Class<ClientRpcPayload.ChatSubscribe> getPayloadType() {
        return ClientRpcPayload.ChatSubscribe.class;
    }

    @Override
    public Flux<ClientPayload> handle(String sessionId, Object requestId, ClientRpcPayload.ChatSubscribe params) {
        String userId = sessionRegistry
                .getUserId(sessionId)
                .orElseThrow(() -> new RpcErrorException(JsonRpcError.NotAuthenticated()));

        try {
            UUID chatId = UUID.fromString(params.chatId());
            String teamIdStr = params.teamId();
            UUID userIdUuid = UUID.fromString(userId);

            if (chatSubscriptionRegistry.isChatSubscribed(sessionId, chatId)) {
                return Flux.just(new ClientPayload.ChatSubscriptionResult("subscribed", chatId));
            }

            if (teamIdStr != null && !teamIdStr.isBlank()) {
                UUID teamId = UUID.fromString(teamIdStr);
                if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userIdUuid)) {
                    throw new RpcErrorException(JsonRpcError.NotAuthorised("Not a member of this team"));
                }
                chatService.verifyChat(chatId, teamId);
            }

            if (!chatSubscriptionRegistry.subscribeChat(sessionId, chatId)) {
                return Flux.just(new ClientPayload.ChatSubscriptionResult("subscribed", chatId));
            }

            List<ChatMemoryEntity> entities = chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(chatId);

            Flux<ClientPayload> history =
                    Flux.fromIterable(entities).map(entity -> buildHistoryPayload(entity, chatId));

            List<CanvasEntity> canvases = canvasService.getCanvasesForChat(chatId);
            Flux<ClientPayload> canvasFlux = Flux.fromIterable(canvases)
                    .map(canvas -> new ClientPayload.CanvasResult(CanvasEvent.Create.of(canvas)));

            Flux<ClientPayload> complete = Flux.just(new ClientPayload.CompleteResult(chatId, entities.size()));

            Flux<ClientPayload> liveFlux = Flux.defer(() -> {
                Flux<ClientPayload.ChatStreamPayload> live = chatFluxRegistry.get(chatId);
                return live != null ? live.cast(ClientPayload.class) : Flux.empty();
            });

            return Flux.concat(history, canvasFlux, complete, liveFlux);
        } catch (IllegalArgumentException e) {
            throw new RpcErrorException(JsonRpcError.InvalidParams(e.getMessage()));
        } catch (ResponseStatusException e) {
            int code = e.getStatusCode().is4xxClientError()
                    ? JsonRpcErrorCodes.RESOURCE_NOT_FOUND
                    : JsonRpcErrorCodes.INTERNAL_ERROR;
            throw new RpcErrorException(new JsonRpcError(code, e.getReason(), e.getMessage()));
        }
    }

    public static ClientPayload buildMessagePayload(ChatMemoryEntity entity, UUID chatId) {
        return ClientPayload.MessageResult.replay(
                chatId,
                entity.getId().toString(),
                entity.getMessageType(),
                entity.getMessageText(),
                entity.getCreatedAt().toString());
    }

    public static ClientPayload buildHistoryPayload(ChatMemoryEntity entity, UUID chatId) {
        String metadata = entity.getMetadata();
        if (metadata != null && !metadata.isBlank()) {
            try {
                JsonNode node = MAPPER.readTree(metadata);
                if (node.path("interstitial").asBoolean(false)) {
                    String status = node.path("status").asText("paused");
                    String reason = node.has("reason") ? node.path("reason").asText() : null;
                    Map<String, Object> metaMap =
                            MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {});
                    return new ClientPayload.PlanningStatusResult(chatId, status, reason, metaMap);
                }
                if (node.path("isError").asBoolean(false)) {
                    int code = node.path("code").asInt(JsonRpcErrorCodes.INTERNAL_ERROR);
                    return new ClientPayload.ChatErrorResult(
                            chatId, entity.getId().toString(), code, entity.getMessageText());
                }
            } catch (JsonProcessingException ignored) {
                // Malformed metadata is treated as a regular message.
            }
        }
        return buildMessagePayload(entity, chatId);
    }
}
