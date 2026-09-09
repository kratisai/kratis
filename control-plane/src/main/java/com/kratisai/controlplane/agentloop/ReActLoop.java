package com.kratisai.controlplane.agentloop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

public final class ReActLoop<T> {

    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);

    private static final int MAX_TRANSIENT_ATTEMPTS = 3;
    private static final long TRANSIENT_RETRY_BACKOFF_MILLIS = 250;

    public enum ToolPhase {
        START,
        COMPLETE,
        ERROR
    }

    @FunctionalInterface
    public interface ToolEventListener {
        void onToolEvent(String taskId, String toolName, ToolPhase phase, String detail);
    }

    private final ChatClient chatClient;
    private final ToolCallback[] tools;
    private final @Nullable BeanOutputConverter<T> converter;
    private final @Nullable Consumer<T> validator;

    // Builder-configured; volatile so a thread invoking run() always observes the configured value.
    private volatile int maxIterations = 25;
    private Executor executor = ForkJoinPool.commonPool();
    private @Nullable Consumer<String> onThought;
    private @Nullable Consumer<String> onAnswerChunk;
    private @Nullable ToolEventListener onToolEvent;
    private @Nullable IntConsumer onToolCallCount;

    private ReActLoop(
            ChatClient chatClient,
            ToolCallback[] tools,
            @Nullable BeanOutputConverter<T> converter,
            @Nullable Consumer<T> validator) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.converter = converter;
        this.validator = validator;
    }

    public static ReActLoop<String> text(ChatClient chatClient, ToolCallback[] tools) {
        return new ReActLoop<>(chatClient, tools, null, null);
    }

    public static <T> ReActLoop<T> structured(
            ChatClient chatClient, ToolCallback[] tools, Class<T> type, Consumer<T> validator) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(validator, "validator");
        return new ReActLoop<>(chatClient, tools, new BeanOutputConverter<>(type), validator);
    }

    public ReActLoop<T> maxIterations(int maxIterations) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1");
        }
        this.maxIterations = maxIterations;
        return this;
    }

    public ReActLoop<T> executor(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        return this;
    }

    public ReActLoop<T> onThought(Consumer<String> onThought) {
        this.onThought = onThought;
        return this;
    }

    public ReActLoop<T> onAnswerChunk(Consumer<String> onAnswerChunk) {
        this.onAnswerChunk = onAnswerChunk;
        return this;
    }

    public ReActLoop<T> onToolEvent(ToolEventListener onToolEvent) {
        this.onToolEvent = onToolEvent;
        return this;
    }

    public ReActLoop<T> onToolCallCount(IntConsumer onToolCallCount) {
        this.onToolCallCount = onToolCallCount;
        return this;
    }

    public String format() {
        return converter != null ? converter.getFormat() : "";
    }

    public @Nullable T run(List<Message> initialHistory, ToolContext toolContext) {
        Objects.requireNonNull(initialHistory, "initialHistory");
        Objects.requireNonNull(toolContext, "toolContext");

        List<Message> history = new ArrayList<>(initialHistory);
        String baseSystemText = baseSystemText(history);
        String feedback = "";
        String lastValidationError = null;
        ReActToolInvoker toolInvoker = new ReActToolInvoker(tools, onToolEvent);

        for (int turn = 0; turn < maxIterations; turn++) {
            boolean finalTurn = turn == maxIterations - 1;
            try {
                applyFeedback(
                        history, baseSystemText, feedback + turnBudgetNotice(maxIterations - turn - 1, finalTurn));
                feedback = "";

                log.trace(
                        "ReAct loop turn {}/{} starting{}",
                        turn + 1,
                        maxIterations,
                        finalTurn ? " (final turn; tools disabled)" : "");

                ToolCallback[] turnTools = finalTurn ? new ToolCallback[0] : tools;
                AssistantMessage assistantMessage = invokeLlm(history, toolContext, turnTools);
                if (assistantMessage == null) {
                    log.debug(
                            "ReAct loop turn {}/{} produced no assistant message; aborting.", turn + 1, maxIterations);
                    return null;
                }
                history.add(assistantMessage);

                if (!assistantMessage.hasToolCalls()) {
                    log.debug("ReAct loop turn {}/{} returned a final response.", turn + 1, maxIterations);
                    return parseFinal(assistantMessage);
                }

                List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
                log.debug(
                        "ReAct loop turn {}/{} requested {} tool call(s): {}",
                        turn + 1,
                        maxIterations,
                        toolCalls.size(),
                        toolCalls.stream().map(AssistantMessage.ToolCall::name).toList());
                if (onToolCallCount != null) {
                    onToolCallCount.accept(toolCalls.size());
                }
                history.add(toolInvoker.executeParallel(assistantMessage, toolContext, executor));
            } catch (StructuredOutputValidationException e) {
                lastValidationError = e.getMessage();
                log.debug(
                        "ReAct loop turn {}/{} failed structured output validation: {}",
                        turn + 1,
                        maxIterations,
                        e.getMessage());
                history.add(new UserMessage(e.getMessage()));
            } catch (RuntimeException e) {
                if (e instanceof ReActLoopFatalException fatal) {
                    throw fatal;
                }
                log.debug("ReAct loop turn failed; feeding error back to the model", e);
                feedback = extractGuidance(e);
            }
        }

        log.warn(
                "ReAct loop exhausted after {} iterations without a final response (lastValidationError={})",
                maxIterations,
                lastValidationError);
        throw new ReActLoopExhaustedException(maxIterations, lastValidationError);
    }

    private static String turnBudgetNotice(int remainingAfterThisTurn, boolean finalTurn) {
        if (finalTurn) {
            return """
                    **IMPORTANT** This is your final turn. Tool calls are disabled for this turn. \
                    You MUST return your final answer now, in the required format, based on the information \
                    you have already gathered. Do not request more turns or tools.""";
        }
        return "\nYou have " + remainingAfterThisTurn + " turn(s) remaining after this one. Use tools judiciously"
                + " and prefer parallel calls so you can synthesize a final answer before your turns run out.";
    }

    private @Nullable AssistantMessage invokeLlm(
            List<Message> history, ToolContext toolContext, ToolCallback[] turnTools) {
        AtomicBoolean emitted = new AtomicBoolean();
        int attempt = 0;
        while (true) {
            try {
                List<ChatResponse> chunks = chatClient
                        .prompt()
                        .messages(history)
                        .toolCallbacks(turnTools)
                        .toolContext(toolContext.getContext())
                        .options(DefaultToolCallingChatOptions.builder().internalToolExecutionEnabled(false))
                        .stream()
                        .chatResponse()
                        .doOnNext(chunk -> {
                            emitted.set(true);
                            handleChunk(chunk);
                        })
                        .collectList()
                        .block();
                return mergeChunks(chunks);
            } catch (RuntimeException e) {
                LlmErrorCategory category = LlmExceptionClassifier.classify(e);
                if (category.isFatal()) {
                    throw new ReActLoopFatalException(category, e);
                }
                if (!category.isTransient()) {
                    throw e;
                }
                if (emitted.get() || attempt >= MAX_TRANSIENT_ATTEMPTS - 1) {
                    throw new ReActLoopFatalException(category, e);
                }
                attempt++;
                log.warn(
                        "ReAct loop LLM call failed with {}; retrying (attempt {}/{})",
                        category,
                        attempt,
                        MAX_TRANSIENT_ATTEMPTS);
                sleepBeforeRetry(attempt);
            }
        }
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(TRANSIENT_RETRY_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off a transient LLM error", interrupted);
        }
    }

    void handleChunk(ChatResponse chunk) {
        Generation generation = chunk.getResult();
        if (generation == null) {
            return;
        }
        AssistantMessage output = generation.getOutput();
        String text = output.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        if (isThoughtChunk(output)) {
            if (onThought != null) {
                onThought.accept(text);
            }
        } else if (onAnswerChunk != null) {
            onAnswerChunk.accept(text);
        }
    }

    @SuppressWarnings("unchecked")
    private T parseFinal(AssistantMessage message) {
        if (converter == null) {
            return (T) message.getText();
        }
        try {
            String text = message.getText() != null ? message.getText() : "";
            T result = converter.convert(text);
            Objects.requireNonNull(validator).accept(result);
            return result;
        } catch (RuntimeException e) {
            throw new StructuredOutputValidationException(
                    "Your response did not match the required format or is incomplete. Error: Validation failed: "
                            + e.getMessage()
                            + "\nPlease return the result strictly in this format:\n"
                            + converter.getFormat());
        }
    }

    static @Nullable AssistantMessage mergeChunks(List<ChatResponse> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }

        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        Set<String> seenToolCallIds = new HashSet<>();
        Map<String, Object> mergedMetadata = new HashMap<>();

        for (ChatResponse chunk : chunks) {
            Generation gen = chunk.getResult();
            if (gen == null) {
                continue;
            }
            AssistantMessage msg = gen.getOutput();
            String t = msg.getText();
            if (t != null && !isThoughtChunk(msg)) {
                text.append(t);
            }
            for (AssistantMessage.ToolCall tc : msg.getToolCalls()) {
                // Google GenAI (Gemini) never assigns a per-call id - id() is always ""
                // Only dedupe when the provider actually supplied a non-blank id
                if (!tc.id().isBlank() && !seenToolCallIds.add(tc.id())) {
                    continue;
                }
                toolCalls.add(tc);
            }
            mergedMetadata.putAll(msg.getMetadata());
        }

        return AssistantMessage.builder()
                .content(text.toString())
                .toolCalls(toolCalls)
                .properties(mergedMetadata)
                .build();
    }

    static boolean isThoughtChunk(AssistantMessage output) {
        Map<String, Object> metadata = output.getMetadata();
        return isTruthy(metadata.get("isThought")) || isTruthy(metadata.get("thinking"));
    }

    static boolean isTruthy(@Nullable Object value) {
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static String baseSystemText(List<Message> history) {
        if (history.isEmpty()) {
            return "";
        }
        return history.getFirst() instanceof SystemMessage systemMessage ? systemMessage.getText() : "";
    }

    private static void applyFeedback(List<Message> history, String baseSystemText, String feedback) {
        if (feedback.isEmpty()) {
            return;
        }
        if (!history.isEmpty() && history.getFirst() instanceof SystemMessage) {
            history.set(0, new SystemMessage(baseSystemText + feedback));
        } else {
            history.addFirst(new SystemMessage(feedback));
        }
    }

    static String extractGuidance(Throwable error) {
        Throwable root = error;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < 10) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = root.getClass().getSimpleName();
        }
        return "\n**IMPORTANT** There was an error processing your last response. Please review instructions and tool definitions carefully. "
                + message;
    }
}
