package com.kratisai.controlplane.websocket.client;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.AgentChatMemoryRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.CanvasService;
import com.kratisai.controlplane.service.ChatFluxRegistry;
import com.kratisai.controlplane.service.ChatService;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.ExecutionActivityPersistenceService;
import com.kratisai.controlplane.service.PendingHitlRegistry;
import java.util.List;
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
    private final SandboxExecutionRepository executionRepository;
    private final PendingHitlRegistry pendingHitlRegistry;
    private final ExecutionActivityPersistenceService activityPersistenceService;

    public ClientChatSubscribeRpcHandler(
            ChatService chatService,
            AgentChatMemoryRepository chatMemoryRepository,
            ClientSessionRegistry sessionRegistry,
            TeamMemberRepository teamMemberRepository,
            CanvasService canvasService,
            ChatFluxRegistry chatFluxRegistry,
            SandboxExecutionRepository executionRepository,
            PendingHitlRegistry pendingHitlRegistry,
            ExecutionActivityPersistenceService activityPersistenceService) {
        this.chatService = chatService;
        this.chatMemoryRepository = chatMemoryRepository;
        this.sessionRegistry = sessionRegistry;
        this.teamMemberRepository = teamMemberRepository;
        this.canvasService = canvasService;
        this.chatFluxRegistry = chatFluxRegistry;
        this.executionRepository = executionRepository;
        this.pendingHitlRegistry = pendingHitlRegistry;
        this.activityPersistenceService = activityPersistenceService;
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
            String chatIdStr = params.chatId();
            String teamIdStr = params.teamId();
            UUID chatId = UUID.fromString(chatIdStr);
            UUID userIdUuid = UUID.fromString(userId);

            if (teamIdStr != null && !teamIdStr.isBlank()) {
                UUID teamId = UUID.fromString(teamIdStr);
                if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userIdUuid)) {
                    throw new RpcErrorException(JsonRpcError.NotAuthorised("Not a member of this team"));
                }
                chatService.verifyChat(chatId, teamId);
            }

            List<ChatMemoryEntity> entities = chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(chatId);

            Flux<ClientPayload> history =
                    Flux.fromIterable(entities).map(entity -> buildHistoryPayload(entity, chatId));

            List<CanvasEntity> canvases = canvasService.getCanvasesForChat(chatId);
            Flux<ClientPayload> canvasFlux = Flux.fromIterable(canvases)
                    .map(canvas -> new ClientPayload.CanvasResult(CanvasEvent.Create.of(canvas)));

            Flux<ClientPayload> replay = replayRunningExecutionState(chatId);

            Flux<ClientPayload> complete = Flux.just(new ClientPayload.CompleteResult(chatId, entities.size()));

            Flux<ClientPayload> liveFlux = Flux.defer(() -> {
                Flux<ClientPayload.ChatStreamPayload> live = chatFluxRegistry.get(chatId);
                return live != null ? live.cast(ClientPayload.class) : Flux.empty();
            });

            return Flux.concat(history, canvasFlux, replay, complete, liveFlux);
        } catch (IllegalArgumentException e) {
            throw new RpcErrorException(JsonRpcError.InvalidParams(e.getMessage()));
        } catch (ResponseStatusException e) {
            int code = e.getStatusCode().is4xxClientError()
                    ? JsonRpcErrorCodes.RESOURCE_NOT_FOUND
                    : JsonRpcErrorCodes.INTERNAL_ERROR;
            throw new RpcErrorException(new JsonRpcError(code, e.getReason(), e.getMessage()));
        }
    }

    /**
     * Replays the live state of every running execution of the chat: persisted activities followed
     * by the pending permission (if any). Reconnect relies on this to rebuild the activity log and
     * re-surface outstanding HITL requests after the team broadcast of the original events was
     * missed. Activities are read from the database, so replay is identical for late joiners and
     * after a control-plane restart.
     */
    private Flux<ClientPayload> replayRunningExecutionState(UUID chatId) {
        List<SandboxExecution> runningExecutions = executionRepository.findByChatIdOrderByStartedAtAsc(chatId).stream()
                .filter(execution -> execution.getStatus() == SandboxExecutionStatus.RUNNING
                        || execution.getStatus() == SandboxExecutionStatus.IDLE)
                .toList();

        return Flux.fromIterable(runningExecutions).flatMap(execution -> {
            UUID executionId = execution.getId();
            Flux<ClientPayload> activities = Flux.fromIterable(activityPersistenceService.getActivities(executionId))
                    .map(activity -> new ClientPayload.ExecutionActivityResult(
                            executionId,
                            activity.getActivityType(),
                            activity.getDescription(),
                            activity.getActionId(),
                            activity.getStatus(),
                            activityPersistenceService.detailOf(activity)));

            Flux<ClientPayload> pendingHitl = Flux.defer(() -> {
                PendingHitlRegistry.PendingHitl pending =
                        pendingHitlRegistry.getPending().get(executionId);
                if (pending != null) {
                    return Flux.just(new ClientPayload.ExecutionHitlRequiredResult(
                            executionId,
                            pending.hitlId(),
                            pending.kind(),
                            pending.message(),
                            pending.command(),
                            pending.title(),
                            pending.toolKind(),
                            pending.options(),
                            pending.diff(),
                            pending.form()));
                }
                return Flux.empty();
            });

            return Flux.concat(activities, pendingHitl);
        });
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
        Integer errorCode = errorCode(entity);
        if (errorCode != null) {
            return new ClientPayload.ChatErrorResult(
                    chatId, entity.getId().toString(), errorCode, entity.getMessageText());
        }
        return buildMessagePayload(entity, chatId);
    }

    private static Integer errorCode(ChatMemoryEntity entity) {
        String metadata = entity.getMetadata();
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(metadata);
            if (node.path("isError").asBoolean(false)) {
                return node.path("code").asInt(JsonRpcErrorCodes.INTERNAL_ERROR);
            }
        } catch (JsonProcessingException ignored) {
            // Malformed metadata is treated as a regular message.
        }
        return null;
    }
}
