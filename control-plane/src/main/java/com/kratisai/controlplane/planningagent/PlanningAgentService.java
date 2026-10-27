package com.kratisai.controlplane.planningagent;

import com.kratisai.controlplane.agentloop.ChatResponseAssembler;
import com.kratisai.controlplane.agentloop.LlmErrorCategory;
import com.kratisai.controlplane.agentloop.ReActLoopExhaustedException;
import com.kratisai.controlplane.agentloop.ReActLoopFatalException;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.config.PlanningProperties;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.service.ChatFluxRegistry;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.ChatService;
import com.kratisai.controlplane.service.ChatUsageSessionService;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.ScratchpadService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.augment.AugmentedToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class PlanningAgentService {

    private static final Logger logger = LoggerFactory.getLogger(PlanningAgentService.class);

    public static final String DEFAULT_CONTINUATION_DIRECTIVE =
            "Resume planning by utilizing existing findings and completing remaining tasks without repeating completed research.";

    private static final int DIRECTIVE_TRANSCRIPT_MAX_MESSAGES = 40;
    private static final int DIRECTIVE_TRANSCRIPT_MAX_MESSAGE_CHARS = 4000;
    private static final int DIRECTIVE_TRANSCRIPT_MAX_CHARS = 24000;

    private final ModelProviderRepository modelProviderRepository;
    private final ChatModelFactory chatModelFactory;
    private final ChatService chatService;
    private final ChatUsageSessionService chatUsageSessionService;
    private final LiteLLMProvisioningService liteLLMProvisioningService;
    private final PlanningAgentLoop orchestrationLoop;
    private final ChatTitleService chatTitleService;
    private final ChatFluxRegistry chatFluxRegistry;
    private final AsyncTaskExecutor agentTaskExecutor;

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
            PlanningProperties planningProperties,
            ChatFluxRegistry chatFluxRegistry,
            @Qualifier("agentTaskExecutor") AsyncTaskExecutor agentTaskExecutor) {

        this.modelProviderRepository = modelProviderRepository;
        this.chatModelFactory = chatModelFactory;
        this.chatService = chatService;
        this.chatUsageSessionService = chatUsageSessionService;
        this.liteLLMProvisioningService = liteLLMProvisioningService;
        this.chatTitleService = chatTitleService;
        this.chatFluxRegistry = chatFluxRegistry;
        this.agentTaskExecutor = agentTaskExecutor;

        ToolCallback[] toolCallbacks =
                getWrappedTools(canvasTool, repositoryTool, dimensionTool, wikiTool, scratchpadTool, webSearchTool);
        this.orchestrationLoop = new PlanningAgentLoop(
                chatMemory, scratchpadService, toolCallbacks, planningProperties.getMaxIterations(), agentTaskExecutor);
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
        return startTurn(teamId, providerId, modelName, new PlanningContext(teamId, sessionId), userQuery);
    }

    /** Continue a paused planning execution; the directive is injected into history, not persisted. */
    public Flux<ClientPayload.ChatStreamPayload> continuePlanning(
            UUID teamId, UUID providerId, String modelName, UUID sessionId, String directive) {
        logger.debug(
                "Continuing planning execution for team {}, session {}, directive {}", teamId, sessionId, directive);
        return startTurn(
                teamId, providerId, modelName, PlanningContext.continuation(teamId, sessionId, directive), null);
    }

    private Flux<ClientPayload.ChatStreamPayload> startTurn(
            UUID teamId,
            UUID providerId,
            String modelName,
            PlanningContext context,
            @Nullable String userQueryToPersist) {
        ModelProvider modelProvider = resolveProvider(teamId, providerId);
        if (!modelProvider.isActive()) {
            return Flux.error(() -> new LlmProviderInactiveException(modelProvider.getId()));
        }
        UUID chatId = context.chatId();

        if (userQueryToPersist != null) {
            // Synchronously save and commit user prompt in DB before starting ReAct loop or returning Flux.
            // This runs eagerly on the calling (servlet/virtual) thread, not a reactive scheduler.
            //noinspection BlockingMethodInNonBlockingContext
            chatService.saveUserMessage(chatId, userQueryToPersist);
        }

        logger.info(
                "Starting {} turn for session {} from team {} using provider {} model {}",
                context.continuationDirective() == null ? "user" : "continuation",
                chatId,
                teamId,
                modelProvider.getId(),
                modelName);
        Sinks.Many<ClientPayload.ChatStreamPayload> sink = Sinks.many().replay().all();
        Flux<ClientPayload.ChatStreamPayload> stream = sink.asFlux();

        String litellmModelName = liteLLMProvisioningService.buildLiteLLMModelName(modelProvider, modelName);
        String virtualKey = chatUsageSessionService
                .getOrCreateActiveSession(chatId, modelName, litellmModelName)
                .getUsage()
                .getVirtualKey();
        ChatModel chatModel =
                chatModelFactory.createChatModelViaLiteLLM(modelProvider, litellmModelName, virtualKey, true);
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        // The turn owns the registry entry. Subscriber disconnects must not cancel the run or
        // remove the entry, so reconnecting clients can replay the stream while it is active.
        chatFluxRegistry.register(chatId, stream);
        try {
            agentTaskExecutor.execute(
                    () -> execute(chatClient, context, sink, stream, modelProvider, modelName, virtualKey));
        } catch (RuntimeException e) {
            chatFluxRegistry.remove(chatId, stream);
            throw e;
        }
        return stream;
    }

    public String initialPlaceholderTitle(String userQuery) {
        return chatTitleService.initialPlaceholderTitle(userQuery);
    }

    public String evaluateContinuationDirective(ChatClient chatClient, List<Message> history) {
        String directivePrompt = """
                You are summarizing the progress of an autonomous planning agent that reached its iteration limit.
                Analyze the transcript for completed research, key findings, tools that were already queried, and remaining tasks.
                Write a concise continuation directive for the next agent turn.
                The directive must state the key findings and completed steps so the next turn does not repeat them.
                It must name the remaining work and instruct the agent to synthesize the final plan or finish that work.
                Do not copy raw tool output. Be direct and concise.
                """;
        try {
            String transcript = formatContinuationTranscript(history);
            if (!transcript.isBlank()) {
                ChatResponse response = chatClient
                        .prompt()
                        .system(directivePrompt)
                        .user("Transcript of the interrupted turn:\n\n" + transcript
                                + "\n\nWrite the continuation directive now.")
                        .call()
                        .chatResponse();
                String directive = ChatResponseAssembler.answerText(response);
                if (!directive.isBlank()) {
                    return directive.trim();
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to evaluate continuation directive using LLM", e);
        }
        return DEFAULT_CONTINUATION_DIRECTIVE;
    }

    /**
     * Render the aborted turn as plain text. Raw assistant tool calls cannot be replayed without
     * their tool responses, and the provider rejects that message sequence.
     */
    static String formatContinuationTranscript(@Nullable List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        int lastUserIndex = lastUserMessageIndex(history);
        String request = lastUserIndex >= 0
                ? textOrEmpty(history.get(lastUserIndex).getText()).strip()
                : "";
        List<String> entries = history.subList(lastUserIndex + 1, history.size()).stream()
                .filter(PlanningAgentService::hasDigestContent)
                .map(PlanningAgentService::formatDigestMessage)
                .map(entry -> truncate(entry.strip(), DIRECTIVE_TRANSCRIPT_MAX_MESSAGE_CHARS))
                .filter(entry -> !entry.isEmpty())
                .toList();

        StringBuilder transcript = new StringBuilder();
        if (!request.isBlank()) {
            transcript.append("TURN REQUEST:\n").append(truncate(request, DIRECTIVE_TRANSCRIPT_MAX_MESSAGE_CHARS));
        }
        appendRecentEntries(transcript, entries);
        return transcript.toString();
    }

    private static void appendRecentEntries(StringBuilder transcript, List<String> entries) {
        int start = Math.max(0, entries.size() - DIRECTIVE_TRANSCRIPT_MAX_MESSAGES);
        StringBuilder body = new StringBuilder();
        for (int i = entries.size() - 1; i >= start; i--) {
            String entry = entries.get(i);
            int separator = body.isEmpty() ? 0 : 2;
            if (transcript.length() + body.length() + entry.length() + separator > DIRECTIVE_TRANSCRIPT_MAX_CHARS) {
                break;
            }
            if (!body.isEmpty()) {
                body.insert(0, "\n\n");
            }
            body.insert(0, entry);
        }
        if (!body.isEmpty()) {
            if (!transcript.isEmpty()) {
                transcript.append("\n\n");
            }
            transcript.append(body);
        }
    }

    private static int lastUserMessageIndex(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof UserMessage user
                    && !textOrEmpty(user.getText()).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasDigestContent(Message message) {
        return switch (message.getMessageType()) {
            case USER -> !textOrEmpty(message.getText()).isBlank();
            case ASSISTANT -> {
                AssistantMessage assistant = (AssistantMessage) message;
                yield !textOrEmpty(assistant.getText()).isBlank()
                        || !assistant.getToolCalls().isEmpty();
            }
            case TOOL -> !((ToolResponseMessage) message).getResponses().isEmpty();
            case SYSTEM -> false;
        };
    }

    private static String formatDigestMessage(Message message) {
        return switch (message.getMessageType()) {
            case USER -> "USER:\n" + textOrEmpty(message.getText()).strip();
            case ASSISTANT -> formatAssistantMessage((AssistantMessage) message);
            case TOOL -> formatToolResponseMessage((ToolResponseMessage) message);
            case SYSTEM -> "";
        };
    }

    private static String formatAssistantMessage(AssistantMessage message) {
        StringBuilder entry = new StringBuilder("ASSISTANT:");
        String text = textOrEmpty(message.getText()).strip();
        if (!text.isEmpty()) {
            entry.append('\n').append(text);
        }
        for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
            entry.append("\nTOOL CALL: ").append(toolCall.name());
            String arguments = textOrEmpty(toolCall.arguments()).strip();
            if (!arguments.isEmpty()) {
                entry.append(' ').append(arguments);
            }
        }
        return entry.toString();
    }

    private static String formatToolResponseMessage(ToolResponseMessage message) {
        StringBuilder entry = new StringBuilder("TOOL RESULTS:");
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            entry.append("\n- ")
                    .append(response.name().isBlank() ? "tool" : response.name())
                    .append(": ")
                    .append(textOrEmpty(response.responseData()).strip());
        }
        return entry.toString();
    }

    private static String textOrEmpty(@Nullable String text) {
        return text != null ? text : "";
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n[truncated]";
    }

    public void evaluateInitialTitleAsync(
            UUID teamId, UUID providerId, String modelName, UUID chatId, String userQuery) {
        ModelProvider modelProvider = resolveProvider(teamId, providerId);
        agentTaskExecutor.execute(
                () -> chatTitleService.evaluateInitialTitle(modelProvider, modelName, teamId, chatId, userQuery));
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
            Flux<ClientPayload.ChatStreamPayload> stream,
            ModelProvider provider,
            String modelName,
            String virtualKey) {
        try {
            orchestrationLoop.run(chatClient, context, sink);
            if (Thread.currentThread().isInterrupted()) {
                logger.info("Planning agent cancelled by thread interruption for chat {}", context.chatId());
                sink.tryEmitComplete();
                return;
            }
            sink.tryEmitNext(new ClientPayload.CompleteResult(context.messageId()));
            sink.tryEmitComplete();
        } catch (ReActLoopExhaustedException exhausted) {
            logger.info("Planning agent reached maximum iterations for chat {}", context.chatId());
            String directive = evaluateContinuationDirective(chatClient, exhausted.history());
            Map<String, Object> metadata = Map.of(
                    "interstitial", true, "status", "paused", "reason", "max_iterations", "directive", directive);
            try {
                chatService.saveInterstitialMessage(context.chatId(), "paused", "max_iterations", directive);
            } catch (Exception persistError) {
                logger.warn("Failed to persist interstitial status for chat {}", context.chatId(), persistError);
            }
            ClientPayload.PlanningStatusResult statusResult =
                    new ClientPayload.PlanningStatusResult(context.chatId(), "paused", "max_iterations", metadata);
            sink.tryEmitNext(statusResult);
            sink.tryEmitComplete();
        } catch (Exception e) {
            if (isCancellation(e)) {
                logger.info("Planning agent cancelled for chat {}", context.chatId());
                sink.tryEmitComplete();
            } else {
                logger.error("Planning agent failed for chat {}", context.chatId(), e);
                ClientPayload.ChatErrorResult error = chatErrorResult(context, e);
                try {
                    chatService.saveAssistantErrorMessage(context.chatId(), error.message(), error.code());
                } catch (Exception persistError) {
                    logger.warn("Failed to persist chat error for chat {}", context.chatId(), persistError);
                }
                sink.tryEmitNext(error);
                sink.tryEmitComplete();
            }
        } finally {
            chatFluxRegistry.remove(context.chatId(), stream);
            chatUsageSessionService.syncUsage(context.chatId(), virtualKey);
        }
        if (!Thread.currentThread().isInterrupted()) {
            try {
                chatTitleService.maybeUpdateTitle(provider, modelName, context.teamId(), context.chatId());
            } catch (Exception e) {
                logger.warn("Failed to auto-generate title for chat {}", context.chatId(), e);
            }
        }
    }

    private static boolean isCancellation(Exception e) {
        if (Thread.currentThread().isInterrupted()
                || e instanceof InterruptedException
                || e instanceof CancellationException) {
            return true;
        }
        Throwable curr = e;
        while (curr != null) {
            if (curr instanceof InterruptedException || curr instanceof CancellationException) {
                return true;
            }
            if (curr.getMessage() != null && curr.getMessage().contains("cancelled by thread interruption")) {
                return true;
            }
            curr = curr.getCause();
        }
        return false;
    }

    private static ClientPayload.ChatErrorResult chatErrorResult(PlanningContext context, Exception e) {
        if (e instanceof ReActLoopFatalException fatal) {
            return new ClientPayload.ChatErrorResult(
                    context.chatId(), context.messageId(), chatErrorCode(fatal.category()), fatal.getMessage());
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
