package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.planningagent.telemetry.TelemetryEvent;
import com.kratisai.controlplane.service.ScratchpadService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Sinks;

class PlanningAgentLoopTest {

    private FakeChatModel fakeChatModel;
    private ChatMemory chatMemory;
    private ChatClient chatClient;
    private PlanningContext context;
    private List<ClientPayload.ChatStreamPayload> emissions;
    private Sinks.Many<ClientPayload.ChatStreamPayload> sink;
    private UUID chatId;

    @BeforeEach
    void setUp() {
        fakeChatModel = new FakeChatModel();
        chatMemory = mock(ChatMemory.class);
        chatClient = ChatClient.builder(fakeChatModel).build();
        chatId = UUID.randomUUID();
        when(chatMemory.get(chatId.toString())).thenReturn(List.of());
        context = new PlanningContext(UUID.randomUUID(), chatId);
        sink = Sinks.many().replay().all();
        emissions = new ArrayList<>();
        sink.asFlux().subscribe(emissions::add);
    }

    @Test
    void memoryReplay_prependsPreviousMessagesToFirstPrompt() {
        when(chatMemory.get(chatId.toString()))
                .thenReturn(List.of(new UserMessage("previous question"), new AssistantMessage("previous answer")));
        AtomicReference<String> firstPrompt = new AtomicReference<>();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    firstPrompt.set(prompt.getContents());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("current reply"))));
                })
                .maxMatches(1)
                .build());

        loop(noTools()).run(chatClient, context, sink);

        assertThat(firstPrompt.get()).contains("previous answer");
        assertThat(emissions)
                .anyMatch(e ->
                        e instanceof ClientPayload.MessageChunkResult chunk && "current reply".equals(chunk.content()));
        verify(chatMemory).add(eq(chatId.toString()), argThat(isAssistantText("current reply")));
    }

    @Test
    void nullAssistantResponse_skipsMemoryAndEmitsNothing() {
        fakeChatModel.addMatcher(
                PromptMatcher.builder().responses(p -> List.of()).maxMatches(1).build());

        loop(noTools()).run(chatClient, context, sink);

        verify(chatMemory, never()).add(any(), (Message) any());
        verify(chatMemory, never()).add(any(), anyList());
        assertThat(emissions).isEmpty();
    }

    @Test
    void toolCalls_emitStartAndCompleteTelemetry_andPersistFinalAnswer() {
        ToolCallback tool = tool();
        AssistantMessage.ToolCall toolCall =
                new AssistantMessage.ToolCall("call_1", "function", "list_repositories", "{}");
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(
                        AssistantMessage.builder().toolCalls(List.of(toolCall)).build())
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response("final answer").maxMatches(1).build());

        loop(new ToolCallback[] {tool}).run(chatClient, context, sink);

        List<TelemetryEvent> events = telemetryEvents();
        assertThat(events).hasSize(2);
        assertThat(events.getFirst()).isInstanceOf(TelemetryEvent.ToolStart.class);
        TelemetryEvent.ToolStart start = (TelemetryEvent.ToolStart) events.getFirst();
        assertThat(start.toolName()).isEqualTo("list_repositories");
        assertThat(events.get(1)).isEqualTo(new TelemetryEvent.ToolComplete(start.taskId(), "success"));
        assertThat(emissions)
                .anyMatch(e ->
                        e instanceof ClientPayload.MessageChunkResult chunk && "final answer".equals(chunk.content()));
        verify(chatMemory).add(eq(chatId.toString()), argThat(isAssistantText("final answer")));
    }

    @Test
    void toolFailure_emitsToolErrorTelemetry_andRecovers() {
        ToolCallback tool = failingTool();
        AssistantMessage.ToolCall toolCall =
                new AssistantMessage.ToolCall("call_1", "function", "list_repositories", "{}");
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(
                        AssistantMessage.builder().toolCalls(List.of(toolCall)).build())
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response("recovered").maxMatches(1).build());

        loop(new ToolCallback[] {tool}).run(chatClient, context, sink);

        List<TelemetryEvent> events = telemetryEvents();
        assertThat(events).hasSize(2);
        assertThat(events.getFirst()).isInstanceOf(TelemetryEvent.ToolStart.class);
        assertThat(events.get(1)).isInstanceOf(TelemetryEvent.ToolError.class);
        assertThat(((TelemetryEvent.ToolError) events.get(1)).error()).isEqualTo("boom");
        verify(chatMemory).add(eq(chatId.toString()), argThat(isAssistantText("recovered")));
    }

    @Test
    void thoughtChunks_emitThoughtTelemetry() {
        AssistantMessage thought = AssistantMessage.builder()
                .content("thinking...")
                .properties(Map.of("isThought", true))
                .build();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .responses(p -> List.of(
                        new ChatResponse(List.of(new Generation(thought))),
                        new ChatResponse(List.of(new Generation(new AssistantMessage("final"))))))
                .maxMatches(1)
                .build());

        loop(noTools()).run(chatClient, context, sink);

        List<TelemetryEvent> thoughts = telemetryEvents().stream()
                .filter(TelemetryEvent.Thought.class::isInstance)
                .toList();
        assertThat(thoughts).hasSize(1);
        assertThat(((TelemetryEvent.Thought) thoughts.getFirst()).text()).isEqualTo("thinking...");
        verify(chatMemory).add(eq(chatId.toString()), argThat(isAssistantText("final")));
    }

    private List<TelemetryEvent> telemetryEvents() {
        return emissions.stream()
                .filter(ClientPayload.TelemetryResult.class::isInstance)
                .map(ClientPayload.TelemetryResult.class::cast)
                .map(ClientPayload.TelemetryResult::event)
                .toList();
    }

    private PlanningAgentLoop loop(ToolCallback[] tools) {
        return new PlanningAgentLoop(chatMemory, mock(ScratchpadService.class), tools, 25, Runnable::run);
    }

    private static org.mockito.ArgumentMatcher<Message> isAssistantText(String text) {
        return m -> m instanceof AssistantMessage assistant && text.equals(assistant.getText());
    }

    private static ToolCallback[] noTools() {
        return new ToolCallback[0];
    }

    private static ToolCallback tool() {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("list_repositories");
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(any(), any())).thenReturn("{\"repos\":[]}");
        return tool;
    }

    private static ToolCallback failingTool() {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("list_repositories");
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(any(), any())).thenThrow(new RuntimeException("boom"));
        return tool;
    }
}
