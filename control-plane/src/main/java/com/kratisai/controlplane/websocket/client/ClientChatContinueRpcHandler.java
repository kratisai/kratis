package com.kratisai.controlplane.websocket.client;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.planningagent.PlanningAgentService;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.ChatFluxRegistry;
import com.kratisai.controlplane.service.ChatService;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class ClientChatContinueRpcHandler
        implements ClientRpcHandler<ClientRpcPayload.ChatContinue, ClientPayload.ChatStreamPayload> {

    private static final Logger logger = LoggerFactory.getLogger(ClientChatContinueRpcHandler.class);

    private final PlanningAgentService planningAgentService;
    private final ChatService chatService;
    private final ClientSessionRegistry sessionRegistry;
    private final TeamMemberRepository teamMemberRepository;
    private final ChatFluxRegistry chatFluxRegistry;
    private final ChatRepository chatRepository;
    private final ModelProviderRepository modelProviderRepository;
    private final ChatUsageSessionRepository chatUsageSessionRepository;

    public ClientChatContinueRpcHandler(
            PlanningAgentService planningAgentService,
            ChatService chatService,
            ClientSessionRegistry sessionRegistry,
            TeamMemberRepository teamMemberRepository,
            ChatFluxRegistry chatFluxRegistry,
            ChatRepository chatRepository,
            ModelProviderRepository modelProviderRepository,
            ChatUsageSessionRepository chatUsageSessionRepository) {
        this.planningAgentService = planningAgentService;
        this.chatService = chatService;
        this.sessionRegistry = sessionRegistry;
        this.teamMemberRepository = teamMemberRepository;
        this.chatFluxRegistry = chatFluxRegistry;
        this.chatRepository = chatRepository;
        this.modelProviderRepository = modelProviderRepository;
        this.chatUsageSessionRepository = chatUsageSessionRepository;
    }

    @Override
    public String getMethodName() {
        return ClientRpcPayload.ChatContinue.METHOD;
    }

    @Override
    public Class<ClientRpcPayload.ChatContinue> getPayloadType() {
        return ClientRpcPayload.ChatContinue.class;
    }

    @Override
    public Flux<ClientPayload.ChatStreamPayload> handle(
            String sessionId, Object requestId, ClientRpcPayload.ChatContinue params) {
        String userId = sessionRegistry
                .getUserId(sessionId)
                .orElseThrow(() -> new RpcErrorException(JsonRpcError.NotAuthenticated()));

        String chatIdStr = params.chatId();
        if (chatIdStr == null || chatIdStr.isBlank()) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("chatId is required"));
        }

        UUID chatId;
        try {
            chatId = UUID.fromString(chatIdStr);
        } catch (IllegalArgumentException e) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("Invalid chatId: " + chatIdStr));
        }

        UUID userIdUuid = UUID.fromString(userId);

        ChatEntity chat = chatRepository
                .findById(chatId)
                .orElseThrow(() -> new RpcErrorException(JsonRpcError.InvalidParams("Chat not found: " + chatId)));

        UUID teamId;
        if (params.teamId() != null && !params.teamId().isBlank()) {
            try {
                teamId = UUID.fromString(params.teamId());
            } catch (IllegalArgumentException e) {
                throw new RpcErrorException(JsonRpcError.InvalidParams("Invalid teamId: " + params.teamId()));
            }
        } else {
            teamId = chat.getTeam().getId();
        }

        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userIdUuid)) {
            throw new RpcErrorException(JsonRpcError.NotAuthorised("Not a member of this team"));
        }

        chatService.verifyChat(chatId, teamId);

        UUID providerId;
        if (params.providerId() != null && !params.providerId().isBlank()) {
            try {
                providerId = UUID.fromString(params.providerId());
            } catch (IllegalArgumentException e) {
                throw new RpcErrorException(JsonRpcError.InvalidParams("Invalid providerId: " + params.providerId()));
            }
        } else {
            providerId = modelProviderRepository
                    .findFirstByTeamIdAndIsActiveTrue(teamId)
                    .map(ModelProvider::getId)
                    .orElseThrow(() -> new RpcErrorException(new JsonRpcError(
                            JsonRpcErrorCodes.LLM_PROVIDER_NOT_FOUND,
                            "LLM provider not found",
                            "No active LLM provider found for team: " + teamId)));
        }

        String modelName = params.modelName();
        if (modelName == null || modelName.isBlank()) {
            modelName = chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chatId).stream()
                    .map(ChatUsageSession::getModel)
                    .filter(m -> m != null && !m.isBlank())
                    .findFirst()
                    .orElse("claude-sonnet-4");
        }

        String directive = chatService
                .readAndResolveInterstitialDirective(chatId)
                .orElse(PlanningAgentService.DEFAULT_CONTINUATION_DIRECTIVE);

        try {
            Flux<ClientPayload.ChatStreamPayload> resultFlux =
                    planningAgentService.continuePlanning(teamId, providerId, modelName, chatId, directive);
            chatFluxRegistry.register(chatId, resultFlux);

            return resultFlux.onErrorResume(error -> {
                logger.error("Error streaming continued response", error);
                return Flux.error(new RpcErrorException(errorWithChatId(error.getMessage(), chatId)));
            });
        } catch (PlanningAgentService.LlmProviderNotFoundException e) {
            logger.warn("LLM provider not found: {}", e.getMessage());
            throw new RpcErrorException(new JsonRpcError(
                    JsonRpcErrorCodes.LLM_PROVIDER_NOT_FOUND, "LLM provider not found", e.getMessage()));
        } catch (PlanningAgentService.LlmProviderInactiveException e) {
            logger.warn("LLM provider inactive: {}", e.getMessage());
            throw new RpcErrorException(
                    new JsonRpcError(JsonRpcErrorCodes.LLM_PROVIDER_INACTIVE, "LLM provider inactive", e.getMessage()));
        }
    }

    private JsonRpcError errorWithChatId(Object data, UUID chatId) {
        Map<String, Object> errorData = new HashMap<>();
        if (chatId != null) {
            errorData.put("chatId", chatId.toString());
        }
        if (data != null) {
            errorData.put("detail", data.toString());
        }
        return new JsonRpcError(JsonRpcErrorCodes.INTERNAL_ERROR, "Internal error", errorData);
    }
}
