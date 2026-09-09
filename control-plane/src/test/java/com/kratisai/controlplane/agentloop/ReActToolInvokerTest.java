package com.kratisai.controlplane.agentloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.agentloop.ReActLoop.ToolPhase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ReActToolInvokerTest {

    private static final ToolContext TOOL_CONTEXT = new ToolContext(Map.of());

    @Test
    void execute_stripsInnerThoughtAndEmitsLifecycleEvents() {
        ToolCallback tool = tool("test_tool", "result");
        List<Event> events = new ArrayList<>();
        ReActToolInvoker invoker = new ReActToolInvoker(
                new ToolCallback[] {tool},
                (taskId, toolName, phase, detail) -> events.add(new Event(taskId, toolName, phase, detail)));

        ToolResponse response = invoker.execute(
                new AssistantMessage.ToolCall(
                        "call_1",
                        "function",
                        "test_tool",
                        "{\"innerThought\":\"thinking about this\",\"actualArg\":\"value\"}"),
                TOOL_CONTEXT);

        assertThat(response.responseData()).isEqualTo("result");
        verify(tool).call(argThat(args -> args.contains("actualArg") && !args.contains("innerThought")), any());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).phase()).isEqualTo(ToolPhase.START);
        assertThat(events.get(0).detail()).isEqualTo("thinking about this");
        assertThat(events.get(1).phase()).isEqualTo(ToolPhase.COMPLETE);
        assertThat(events.get(1).detail()).isEqualTo("success");
        assertThat(events.get(0).taskId()).isEqualTo(events.get(1).taskId());
    }

    @Test
    void execute_unknownTool_emitsErrorAndWrapsJson() {
        List<Event> events = new ArrayList<>();
        ReActToolInvoker invoker = new ReActToolInvoker(
                new ToolCallback[0],
                (taskId, toolName, phase, detail) -> events.add(new Event(taskId, toolName, phase, detail)));

        ToolResponse response =
                invoker.execute(new AssistantMessage.ToolCall("id", "function", "missing", "{}"), TOOL_CONTEXT);

        assertThat(response.responseData()).contains("Tool not found: missing");
        assertThat(events).extracting(Event::phase).containsExactly(ToolPhase.START, ToolPhase.ERROR);
        assertThat(events.get(1).detail()).contains("Tool not found: missing");
    }

    @Test
    void execute_toolThrows_returnsErrorResponse() {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("failing");
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(any(), any())).thenThrow(new RuntimeException("boom"));

        ReActToolInvoker invoker = new ReActToolInvoker(new ToolCallback[] {tool}, null);
        ToolResponse response =
                invoker.execute(new AssistantMessage.ToolCall("id", "function", "failing", "{}"), TOOL_CONTEXT);

        assertThat(response.responseData()).contains("boom");
    }

    @Test
    void execute_generatesUniqueTaskIdsWhenProviderIdsCollide() {
        ToolCallback listRepositories = tool("list_repositories", "{\"repos\":[]}");
        ToolCallback listDimensions = tool("list_dimensions", "{\"dimensions\":[]}");
        List<Event> events = new ArrayList<>();
        ReActToolInvoker invoker = new ReActToolInvoker(
                new ToolCallback[] {listRepositories, listDimensions},
                (taskId, toolName, phase, detail) -> events.add(new Event(taskId, toolName, phase, detail)));

        String innerThoughtArgs = "{\"innerThought\":\"working\"}";
        invoker.execute(
                new AssistantMessage.ToolCall("dup-id", "function", "list_repositories", innerThoughtArgs),
                TOOL_CONTEXT);
        invoker.execute(
                new AssistantMessage.ToolCall("dup-id", "function", "list_dimensions", innerThoughtArgs), TOOL_CONTEXT);
        invoker.execute(
                new AssistantMessage.ToolCall("dup-id", "function", "save_scratchpad", innerThoughtArgs), TOOL_CONTEXT);

        List<Event> starts =
                events.stream().filter(e -> e.phase() == ToolPhase.START).toList();
        List<Event> errors =
                events.stream().filter(e -> e.phase() == ToolPhase.ERROR).toList();
        List<Event> completes =
                events.stream().filter(e -> e.phase() == ToolPhase.COMPLETE).toList();

        assertThat(starts).hasSize(3);
        assertThat(starts.stream().map(Event::taskId).distinct()).hasSize(3);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().detail()).contains("Tool not found: save_scratchpad");
        assertThat(completes).hasSize(2);
    }

    @Test
    void prepare_skipsParseWhenInnerThoughtAbsent() {
        ReActToolInvoker.PreparedCall prepared = ReActToolInvoker.prepare("{\"path\":\"a.java\"}", "read_file");

        assertThat(prepared.innerThought()).isEqualTo("read_file");
        assertThat(prepared.arguments()).isEqualTo("{\"path\":\"a.java\"}");
    }

    @Test
    void prepare_fallsBackToToolNameWhenInnerThoughtBlank() {
        ReActToolInvoker.PreparedCall prepared =
                ReActToolInvoker.prepare("{\"innerThought\":\"\",\"path\":\"a.java\"}", "read_file");

        assertThat(prepared.innerThought()).isEqualTo("read_file");
        assertThat(prepared.arguments()).contains("path");
        assertThat(prepared.arguments()).doesNotContain("innerThought");
    }

    @Test
    void prepare_returnsOriginalArgumentsWhenJsonInvalid() {
        ReActToolInvoker.PreparedCall prepared = ReActToolInvoker.prepare("{not-json innerThought", "read_file");

        assertThat(prepared.innerThought()).isEqualTo("read_file");
        assertThat(prepared.arguments()).isEqualTo("{not-json innerThought");
    }

    @Test
    void prepare_nullArguments_usesToolName() {
        ReActToolInvoker.PreparedCall prepared = ReActToolInvoker.prepare(null, "read_file");

        assertThat(prepared.innerThought()).isEqualTo("read_file");
        assertThat(prepared.arguments()).isNull();
    }

    private record Event(String taskId, String toolName, ToolPhase phase, String detail) {}

    private static ToolCallback tool(String name, String result) {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(any(), any())).thenReturn(result);
        return tool;
    }
}
