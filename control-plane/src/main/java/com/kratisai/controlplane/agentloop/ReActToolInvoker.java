package com.kratisai.controlplane.agentloop;

import com.kratisai.controlplane.agentloop.ReActLoop.ToolEventListener;
import com.kratisai.controlplane.agentloop.ReActLoop.ToolPhase;
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

    ToolResponseMessage executeParallel(AssistantMessage assistantMessage, ToolContext toolContext, Executor executor) {
        List<CompletableFuture<ToolResponse>> futures = assistantMessage.getToolCalls().stream()
                .map(toolCall -> CompletableFuture.supplyAsync(() -> execute(toolCall, toolContext), executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<ToolResponse> responses =
                futures.stream().map(CompletableFuture::join).toList();
        return ToolResponseMessage.builder().responses(responses).build();
    }

    ToolResponse execute(AssistantMessage.ToolCall toolCall, ToolContext toolContext) {
        String taskId = UUID.randomUUID().toString();
        PreparedCall prepared = prepare(toolCall.arguments(), toolCall.name());
        emit(taskId, toolCall.name(), ToolPhase.START, prepared.innerThought());
        try {
            ToolCallback tool = lookup(toolCall.name());
            String result = tool.call(prepared.arguments() != null ? prepared.arguments() : "{}", toolContext);
            emit(taskId, toolCall.name(), ToolPhase.COMPLETE, "success");
            return new ToolResponse(toolCall.id(), toolCall.name(), result);
        } catch (Exception e) {
            String message =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            emit(taskId, toolCall.name(), ToolPhase.ERROR, message);
            return new ToolResponse(toolCall.id(), toolCall.name(), JsonParser.toJson(Map.of("error", message)));
        }
    }

    private ToolCallback lookup(String name) {
        return Arrays.stream(tools)
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + name));
    }

    private void emit(String taskId, String toolName, ToolPhase phase, String detail) {
        if (onToolEvent != null) {
            onToolEvent.onToolEvent(taskId, toolName, phase, detail);
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
