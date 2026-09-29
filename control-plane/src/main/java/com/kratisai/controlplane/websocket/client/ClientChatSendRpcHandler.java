package com.kratisai.controlplane.websocket.client;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.planningagent.PlanningAgentService;
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
public class ClientChatSendRpcHandler
        implements ClientRpcHandler<ClientRpcPayload.ChatSend, ClientPayload.ChatStreamPayload> {

    private static final Logger logger = LoggerFactory.getLogger(ClientChatSendRpcHandler.class);

    private final PlanningAgentService planningAgentService;
    private final ChatService chatService;
    private final ClientSessionRegistry sessionRegistry;
    private final TeamMemberRepository teamMemberRepository;
    private final ChatFluxRegistry chatFluxRegistry;

    public ClientChatSendRpcHandler(
            PlanningAgentService planningAgentService,
            ChatService chatService,
            ClientSessionRegistry sessionRegistry,
            TeamMemberRepository teamMemberRepository,
            ChatFluxRegistry chatFluxRegistry) {
        this.planningAgentService = planningAgentService;
        this.chatService = chatService;
        this.sessionRegistry = sessionRegistry;
        this.teamMemberRepository = teamMemberRepository;
        this.chatFluxRegistry = chatFluxRegistry;
    }

    @Override
    public String getMethodName() {
        return ClientRpcPayload.ChatSend.METHOD;
    }

    @Override
    public Class<ClientRpcPayload.ChatSend> getPayloadType() {
        return ClientRpcPayload.ChatSend.class;
    }

    @Override
    public Flux<ClientPayload.ChatStreamPayload> handle(
            String sessionId, Object requestId, ClientRpcPayload.ChatSend params) {
        String userId = sessionRegistry
                .getUserId(sessionId)
                .orElseThrow(() -> new RpcErrorException(JsonRpcError.NotAuthenticated()));

        String message = params.message();
        String teamIdStr = params.teamId();
        String providerIdStr = params.providerId();
        String modelName = params.modelName();
        String chatIdStr = params.chatId();

        if (message == null || message.isEmpty()) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("Message is required"));
        }
        if (teamIdStr == null || teamIdStr.isEmpty()) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("teamId is required"));
        }
        if (providerIdStr == null || providerIdStr.isEmpty()) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("providerId is required"));
        }
        if (modelName == null || modelName.isEmpty()) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("modelName is required"));
        }
        if (chatIdStr == null || chatIdStr.isEmpty()) {
            throw new RpcErrorException(JsonRpcError.InvalidParams("chatId is required"));
        }

        UUID teamId = UUID.fromString(teamIdStr);
        UUID providerId = UUID.fromString(providerIdStr);
        UUID userIdUuid = UUID.fromString(userId);

        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userIdUuid)) {
            throw new RpcErrorException(JsonRpcError.NotAuthorised("Not a member of this team"));
        }

        UUID chatId = UUID.fromString(chatIdStr);
        chatService.verifyChat(chatId, teamId);

        try {
            Flux<ClientPayload.ChatStreamPayload> resultFlux =
                    planningAgentService.streamMessage(teamId, providerId, modelName, chatId, message);
            chatFluxRegistry.register(chatId, resultFlux);

            return resultFlux.onErrorResume(error -> {
                logger.error("Error streaming response", error);
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
