package com.kratisai.controlplane.planningagent;

import com.kratisai.controlplane.agentloop.LlmErrorCategory;
import com.kratisai.controlplane.agentloop.ReActLoopExhaustedException;
import com.kratisai.controlplane.agentloop.ReActLoopFatalException;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.ChatService;
import com.kratisai.controlplane.service.ChatUsageSessionService;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.ScratchpadService;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.augment.AugmentedToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class PlanningAgentService {

    private static final Logger logger = LoggerFactory.getLogger(PlanningAgentService.class);

    private final ModelProviderRepository modelProviderRepository;
    private final ChatModelFactory chatModelFactory;
    private final ChatService chatService;
    private final ChatUsageSessionService chatUsageSessionService;
    private final LiteLLMProvisioningService liteLLMProvisioningService;
    private final PlanningAgentLoop orchestrationLoop;
    private final ChatTitleService chatTitleService;
    private final TaskExecutor agentTaskExecutor;
    private final Set<UUID> activeAgentLoops = ConcurrentHashMap.newKeySet();

    public PlanningAgentService(
            ModelProviderRepository modelProviderRepository,
            ChatModelFactory chatModelFactory,
            ChatService chatService,
            ChatUsageSessionService chatUsageSessionService,
            LiteLLMProvisioningService liteLLMProvisioningService,
            ChatTitleService chatTitleService,
            ChatMemory chatMemory,
            CanvasTool canvasTool,
            RepositoryTool repositoryTool,
            DimensionTool dimensionTool,
            WikiTool wikiTool,
            ScratchpadTool scratchpadTool,
            WebSearchTool webSearchTool,
            ScratchpadService scratchpadService,
            @Value("${kratis.agent.max-iterations:25}") int maxIterations,
            @Qualifier("agentTaskExecutor") TaskExecutor agentTaskExecutor) {

        this.modelProviderRepository = modelProviderRepository;
        this.chatModelFactory = chatModelFactory;
        this.chatService = chatService;
        this.chatUsageSessionService = chatUsageSessionService;
        this.liteLLMProvisioningService = liteLLMProvisioningService;
        this.chatTitleService = chatTitleService;
        this.agentTaskExecutor = agentTaskExecutor;

        ToolCallback[] toolCallbacks =
                getWrappedTools(canvasTool, repositoryTool, dimensionTool, wikiTool, scratchpadTool, webSearchTool);
        this.orchestrationLoop =
                new PlanningAgentLoop(chatMemory, scratchpadService, toolCallbacks, maxIterations, agentTaskExecutor);
    }

    /** Add AgentThinking to our tool-callbacks * */
    public static ToolCallback @NonNull [] getWrappedTools(Object... toolBeans) {
        ToolCallbackProvider delegate = ToolCallbackProvider.from(ToolCallbacks.from(toolBeans));
        ToolCallbackProvider wrappedProvider = AugmentedToolCallbackProvider.<AgentThinking>builder()
                .argumentType(AgentThinking.class)
                .delegate(delegate)
                .argumentConsumer(i -> {}) // intentional no-op
                .removeExtraArgumentsAfterProcessing(false)
                .build();
        return wrappedProvider.getToolCallbacks();
    }

    /**
     * Stream a message to the LLM and return a reactive Flux. Uses the full ReAct
     * loop with parallel tool execution.
     */
    public Flux<ClientPayload.ChatStreamPayload> streamMessage(
            UUID teamId, UUID providerId, String modelName, UUID sessionId, String userQuery) {
        ModelProvider modelProvider = resolveProvider(teamId, providerId);

        if (!modelProvider.isActive()) {
            return Flux.error(() -> new LlmProviderInactiveException(modelProvider.getId()));
        }

        // Synchronously save and commit user prompt in DB before starting ReAct loop or returning Flux.
        // This runs eagerly on the calling (servlet/virtual) thread, not a reactive scheduler.
        //noinspection BlockingMethodInNonBlockingContext
        chatService.saveUserMessage(sessionId, userQuery);

        logger.info(
                "Streaming chat message for session {} from team {} using provider {} model {}",
                sessionId,
                teamId,
                modelProvider.getId(),
                modelName);
        Sinks.Many<ClientPayload.ChatStreamPayload> sink = Sinks.many().replay().all();
        PlanningContext context = new PlanningContext(teamId, sessionId);

        String litellmModelName = liteLLMProvisioningService.buildLiteLLMModelName(modelProvider, modelName);
        String virtualKey = chatUsageSessionService
                .getOrCreateActiveSession(sessionId, modelName, litellmModelName)
                .getUsage()
                .getVirtualKey();
        ChatModel chatModel =
                chatModelFactory.createChatModelViaLiteLLM(modelProvider, litellmModelName, virtualKey, true);
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        agentTaskExecutor.execute(
                () -> execute(chatClient, context, sink, modelProvider, modelName, virtualKey, sessionId));
        return sink.asFlux();
    }

    /**
     * Generate the initial title for a new chat from its first user prompt. Runs synchronously
     * before the chat is persisted so the topic history shows a summary rather than the raw prompt.
     */
    public String generateInitialTitle(UUID teamId, UUID providerId, String modelName, String userQuery) {
        ModelProvider modelProvider = resolveProvider(teamId, providerId);
        return chatTitleService.generateInitialTitle(modelProvider, modelName, userQuery);
    }

    private ModelProvider resolveProvider(UUID teamId, UUID modelProviderId) {
        if (modelProviderId == null) {
            throw new LlmProviderNotFoundException(teamId, "providerId is required and must be specified");
        }
        return modelProviderRepository
                .findByTeamIdAndId(teamId, modelProviderId)
                .orElseThrow(() -> new LlmProviderNotFoundException(modelProviderId));
    }

    public static class LlmProviderNotFoundException extends RuntimeException {
        public LlmProviderNotFoundException(UUID teamOrProviderId) {
            super("No active LLM provider found for team: " + teamOrProviderId);
        }

        public LlmProviderNotFoundException(UUID teamOrProviderId, String reason) {
            super("No active LLM provider found for team: " + teamOrProviderId + " - " + reason);
        }
    }

    public static class LlmProviderInactiveException extends RuntimeException {
        public LlmProviderInactiveException(UUID providerId) {
            super("LLM provider is inactive: " + providerId);
        }
    }

    private void execute(
            ChatClient chatClient,
            PlanningContext context,
            Sinks.Many<ClientPayload.ChatStreamPayload> sink,
            ModelProvider provider,
            String modelName,
            String virtualKey,
            UUID sessionId) {
        activeAgentLoops.add(context.chatId());
        try {
            orchestrationLoop.run(chatClient, context, sink);
            sink.tryEmitNext(new ClientPayload.CompleteResult(context.messageId()));
            sink.tryEmitComplete();
        } catch (Exception e) {
            logger.error("Planning agent failed for chat {}", context.chatId(), e);
            ClientPayload.ChatErrorResult error = chatErrorResult(context, e);
            try {
                chatService.saveAssistantErrorMessage(context.chatId(), error.message(), error.code());
            } catch (Exception persistError) {
                logger.warn("Failed to persist chat error for chat {}", context.chatId(), persistError);
            }
            sink.tryEmitNext(error);
            sink.tryEmitComplete();
        } finally {
            activeAgentLoops.remove(context.chatId());
            chatUsageSessionService.syncUsage(sessionId, virtualKey);
        }
        try {
            chatTitleService.maybeUpdateTitle(provider, modelName, context.teamId(), context.chatId());
        } catch (Exception e) {
            logger.warn("Failed to auto-generate title for chat {}", context.chatId(), e);
        }
    }

    private static ClientPayload.ChatErrorResult chatErrorResult(PlanningContext context, Exception e) {
        if (e instanceof ReActLoopFatalException fatal) {
            return new ClientPayload.ChatErrorResult(
                    context.chatId(), context.messageId(), chatErrorCode(fatal.category()), fatal.getMessage());
        }
        if (e instanceof ReActLoopExhaustedException) {
            return new ClientPayload.ChatErrorResult(
                    context.chatId(), context.messageId(), JsonRpcErrorCodes.MAX_ITERATIONS_EXCEEDED, e.getMessage());
        }
        return new ClientPayload.ChatErrorResult(
                context.chatId(),
                context.messageId(),
                JsonRpcErrorCodes.INTERNAL_ERROR,
                "An unexpected error occurred while processing your request.");
    }

    private static int chatErrorCode(LlmErrorCategory category) {
        return switch (category) {
            case AUTHENTICATION, PERMISSION_DENIED -> JsonRpcErrorCodes.LLM_AUTHENTICATION_FAILED;
            case QUOTA_EXCEEDED, RATE_LIMIT -> JsonRpcErrorCodes.RATE_LIMIT_EXCEEDED;
            case CONTEXT_LENGTH_EXCEEDED -> JsonRpcErrorCodes.LLM_CONTEXT_LENGTH_EXCEEDED;
            case MODEL_NOT_FOUND -> JsonRpcErrorCodes.LLM_PROVIDER_NOT_FOUND;
            case BAD_REQUEST, SERVER_ERROR, NETWORK_ERROR, UNKNOWN -> JsonRpcErrorCodes.INTERNAL_ERROR;
        };
    }
}
