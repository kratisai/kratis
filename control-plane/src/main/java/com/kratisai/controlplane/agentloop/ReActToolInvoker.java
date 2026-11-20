package com.kratisai.controlplane.agentloop;

import com.kratisai.controlplane.agentloop.ReActLoop.ToolEventListener;
import com.kratisai.controlplane.agentloop.ReActLoop.ToolPhase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.util.json.JsonParser;
import tools.jackson.core.type.TypeReference;

/** Parallel tool execution with innerThought stripping, lifecycle events, and error wrapping. */
final class ReActToolInvoker {

    private final ToolCallback[] tools;
    private final @Nullable ToolEventListener onToolEvent;

    ReActToolInvoker(ToolCallback[] tools, @Nullable ToolEventListener onToolEvent) {
        this.tools = tools;
        this.onToolEvent = onToolEvent;
    }

    ToolResponseMessage executeParallel(
            AssistantMessage assistantMessage, ToolContext toolContext, Executor executor, int attempt) {
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
        List<String> taskIds = new ArrayList<>(toolCalls.size());
        List<CompletableFuture<ToolResponse>> futures = new ArrayList<>(toolCalls.size());
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String taskId = UUID.randomUUID().toString();
            taskIds.add(taskId);
            try {
                futures.add(
                        CompletableFuture.supplyAsync(() -> execute(toolCall, toolContext, attempt, taskId), executor));
            } catch (RuntimeException e) {
                // Unbounded-queue executors only reject while shutting down, and every
                // submission in the batch would reject too. Pause the turn so no later
                // request replays this assistant message without its tool responses
                // (providers reject a request ending on a model turn).
                throw new ReActLoopPausedException(
                        "Agent task pool rejected tool call " + toolCall.name() + " because it is shutting down", e);
            }
        }
        List<ToolResponse> responses = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            responses.add(await(futures.get(i), taskIds.get(i), toolCalls.get(i), attempt));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private ToolResponse await(
            CompletableFuture<ToolResponse> future, String taskId, AssistantMessage.ToolCall toolCall, int attempt) {
        try {
            return future.join();
        } catch (RuntimeException e) {
            // A tool that threw an Error (not Exception) bypasses execute()'s wrapping but must
            // still yield a matching response so the assistant turn stays answerable.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage() != null
                    ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            emit(attempt, taskId, toolCall.name(), ToolPhase.ERROR, message);
            return new ToolResponse(toolCall.id(), toolCall.name(), JsonParser.toJson(Map.of("error", message)));
        }
    }

    ToolResponse execute(AssistantMessage.ToolCall toolCall, ToolContext toolContext, int attempt, String taskId) {
        PreparedCall prepared = prepare(toolCall.arguments(), toolCall.name());
        emit(attempt, taskId, toolCall.name(), ToolPhase.START, prepared.innerThought());
        try {
            ToolCallback tool = lookup(toolCall.name());
            String result = tool.call(prepared.arguments() != null ? prepared.arguments() : "{}", toolContext);
            emit(attempt, taskId, toolCall.name(), ToolPhase.COMPLETE, "success");
            return new ToolResponse(toolCall.id(), toolCall.name(), result);
        } catch (Exception e) {
            String message =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            emit(attempt, taskId, toolCall.name(), ToolPhase.ERROR, message);
            return new ToolResponse(toolCall.id(), toolCall.name(), JsonParser.toJson(Map.of("error", message)));
        }
    }

    private ToolCallback lookup(String name) {
        return Arrays.stream(tools)
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + name));
    }

    private void emit(int attempt, String taskId, String toolName, ToolPhase phase, String detail) {
        if (onToolEvent != null) {
            onToolEvent.onToolEvent(attempt, taskId, toolName, phase, detail);
        }
    }

    static PreparedCall prepare(@Nullable String arguments, String toolName) {
        if (arguments == null || !arguments.contains("innerThought")) {
            return new PreparedCall(toolName, arguments);
        }
        try {
            Map<String, Object> args = JsonParser.fromJson(arguments, new TypeReference<>() {});
            Object thought = args.remove("innerThought");
            String innerThought = thought instanceof String s && !s.isBlank() ? s : toolName;
            return new PreparedCall(innerThought, JsonParser.toJson(args));
        } catch (RuntimeException e) {
            return new PreparedCall(toolName, arguments);
        }
    }

    record PreparedCall(String innerThought, @Nullable String arguments) {}
}
