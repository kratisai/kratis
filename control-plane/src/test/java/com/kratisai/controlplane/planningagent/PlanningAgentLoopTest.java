package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.agentloop.AgentActivityListener;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.planningagent.telemetry.TelemetryEvent;
import com.kratisai.controlplane.service.ScratchpadService;
import com.kratisai.controlplane.testsupport.PlanningTestStubs;
import com.kratisai.controlplane.testsupport.llm.FakeChatModel;
import com.kratisai.controlplane.testsupport.llm.PromptMatcher;
import com.kratisai.controlplane.validation.MermaidDiagramValidator;
import com.kratisai.controlplane.validation.mermaid.MermaidDiagramRepairer;
import com.kratisai.controlplane.validation.mermaid.MermaidNormalizer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class PlanningAgentLoopTest {

    private FakeChatModel fakeChatModel;
    private ChatMemory chatMemory;
    private ChatClient chatClient;
    private PlanningContext context;
    private List<ClientPayload.ChatStreamPayload> emissions;
    private Sinks.Many<ClientPayload.ChatStreamPayload> sink;
    private UUID chatId;
    private AgentActivityListener activityListener;
    private List<UUID> activityChats;

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
        activityChats = new ArrayList<>();
        activityListener = activityChats::add;
    }

    @Test
    void emitsActivityForEveryTelemetryChunkAndRetractionExactlyOnce() {
        ToolCallback tool = tool();
        AssistantMessage.ToolCall toolCall =
                new AssistantMessage.ToolCall("call_1", "function", "list_repositories", "{}");
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .responses(p -> List.of(
                        new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                                .toolCalls(List.of(toolCall))
                                .build()))),
                        new ChatResponse(List.of(new Generation(new AssistantMessage("partial"))))))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response("answer").maxMatches(1).build());
        PlanningAgentLoop loop = loop(new ToolCallback[] {tool}, PlanningTestStubs.emptyScratchpad(), activityListener);

        loop.run(chatClient, context, sink);

        assertThat(activityChats).isNotEmpty().containsOnly(chatId);
        assertThat(activityChats).hasSize((int) emissions.stream()
                .filter(payload -> payload instanceof ClientPayload.MessageChunkResult
                        || payload instanceof ClientPayload.MessageRetractResult
                        || payload instanceof ClientPayload.TelemetryResult)
                .count());
    }

    @Test
    void absentActivityListenerDoesNotInterruptLoop() {
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response("answer").maxMatches(1).build());
        loop(noTools(), PlanningTestStubs.emptyScratchpad(), null).run(chatClient, context, sink);
        assertThat(emissions).anyMatch(ClientPayload.MessageChunkResult.class::isInstance);
    }

    @Test
    void activityListenerFailureDoesNotAbortStream() {
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response("answer").maxMatches(1).build());
        AgentActivityListener failingListener = id -> {
            throw new IllegalStateException("scheduler unavailable");
        };

        loop(noTools(), PlanningTestStubs.emptyScratchpad(), failingListener).run(chatClient, context, sink);

        assertThat(emissions)
                .anyMatch(payload ->
                        payload instanceof ClientPayload.MessageChunkResult chunk && "answer".equals(chunk.content()));
        verify(chatMemory).add(eq(chatId.toString()), argThat(isAssistantText("answer")));
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

    @Test
    void run_withContinuationDirective_appendsItAsUserMessage() {
        AtomicReference<String> prompt = new AtomicReference<>();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(p -> {
                    prompt.set(p.getContents());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("continued"))));
                })
                .maxMatches(1)
                .build());

        loop(noTools())
                .run(
                        chatClient,
                        PlanningContext.continuation(context.teamId(), chatId, "build on existing findings"),
                        sink);

        assertThat(prompt.get()).contains("build on existing findings");
        assertThat(emissions)
                .anyMatch(e ->
                        e instanceof ClientPayload.MessageChunkResult chunk && "continued".equals(chunk.content()));
        verify(chatMemory).add(eq(chatId.toString()), argThat(isAssistantText("continued")));
    }

    @Test
    void transientFailure_emitsRetractBeforeRetry() {
        PlanningAgentLoop loop = new PlanningAgentLoop(
                chatMemory,
                PlanningTestStubs.emptyScratchpad(),
                noTools(),
                25,
                Runnable::run,
                Runnable::run,
                new MermaidNormalizer(),
                new MermaidDiagramValidator(),
                new MermaidDiagramRepairer(new MermaidNormalizer()),
                activityListener);

        loop.run(ChatClient.builder(new FlakyStreamingChatModel()).build(), context, sink);

        assertThat(emissions)
                .filteredOn(ClientPayload.MessageRetractResult.class::isInstance)
                .containsExactly(new ClientPayload.MessageRetractResult(chatId, context.messageId(), 1));
        assertThat(emissions)
                .filteredOn(ClientPayload.MessageChunkResult.class::isInstance)
                .extracting(e -> {
                    ClientPayload.MessageChunkResult chunk = (ClientPayload.MessageChunkResult) e;
                    return chunk.attempt() + ":" + chunk.content();
                })
                .containsExactly("1:partial", "2:recovered");
        assertThat(activityChats).containsOnly(chatId);
    }

    @Test
    void continuation_requiresNonNullDirective() {
        assertThatThrownBy(() -> nullDirectiveContext(context)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void streamedDiagramWithMissingMermaidFence_normalizesAndEmitsMessageResult() {
        String streamed = """
                Here is the flow:
                ```flowchart LR
                A["Client"] --> B["API"]
                ```""";
        ChatClient client =
                ChatClient.builder(new MermaidRepairChatModel(streamed, null)).build();
        loop(noTools()).run(client, context, sink);
        String expected = """
                Here is the flow:
                ```mermaid
                flowchart LR
                A["Client"] --> B["API"]
                ```""";
        assertThat(emissions)
                .filteredOn(ClientPayload.MessageRetractResult.class::isInstance)
                .isEmpty();
        assertThat(emissions)
                .filteredOn(ClientPayload.MessageResult.class::isInstance)
                .map(ClientPayload.MessageResult.class::cast)
                .singleElement()
                .satisfies(msg -> {
                    assertThat(msg.messageId()).isEqualTo(context.messageId());
                    assertThat(msg.content()).isEqualTo(expected);
                });
        verify(chatMemory).add(eq(chatId.toString()), argThat(isAssistantText(expected)));
    }

    @Test
    void streamedInvalidMermaidDiagram_repairsOutOfBandWithoutRetractingTurn() {
        String streamed = """
                Architecture:
                ```mermaid
                flowchart LR
                A -> B
                ```""";
        String repaired = """
                ```mermaid
                flowchart LR
                A["quoted"] --> B["API"]
                ```""";
        ChatClient client = ChatClient.builder(new MermaidRepairChatModel(streamed, repaired))
                .build();
        loop(noTools()).run(client, context, sink);
        String expected = """
                Architecture:
                ```mermaid
                flowchart LR
                A["quoted"] --> B["API"]
                ```""";
        assertThat(emissions)
                .filteredOn(ClientPayload.MessageRetractResult.class::isInstance)
                .isEmpty();
        assertThat(emissions)
                .filteredOn(ClientPayload.MessageResult.class::isInstance)
                .map(ClientPayload.MessageResult.class::cast)
                .singleElement()
                .satisfies(msg -> assertThat(msg.content()).isEqualTo(expected));
        verify(chatMemory).add(eq(chatId.toString()), argThat(isAssistantText(expected)));
    }

    @Test
    void scratchpadFeedback_appendsFactsToTheSystemMessageExactlyOnce() {
        ScratchpadService scratchpad = mock(ScratchpadService.class);
        when(scratchpad.getFactsAsString(chatId)).thenReturn("\n- write the API page");
        AtomicReference<String> firstPrompt = new AtomicReference<>();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    firstPrompt.set(prompt.getContents());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
                })
                .maxMatches(1)
                .build());
        loop(noTools(), scratchpad).run(chatClient, context, sink);
        assertThat(countOccurrences(firstPrompt.get(), "<scratchpad>")).isEqualTo(1);
        assertThat(firstPrompt.get()).contains("- write the API page");
    }

    @Test
    void scratchpadFeedback_refreshesSavedFactsOnLaterTurns() {
        ScratchpadService scratchpad = mock(ScratchpadService.class);
        ToolCallback saveTool = tool();
        when(scratchpad.getFactsAsString(chatId)).thenReturn("").thenReturn("\n- step one done");
        List<String> prompts = new ArrayList<>();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    prompts.add(prompt.getContents());
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                            .toolCalls(List.of(
                                    new AssistantMessage.ToolCall("call_1", "function", "list_repositories", "{}")))
                            .build())));
                })
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    prompts.add(prompt.getContents());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
                })
                .maxMatches(1)
                .build());
        loop(new ToolCallback[] {saveTool}, scratchpad).run(chatClient, context, sink);
        assertThat(prompts).hasSize(2);
        assertThat(prompts.getFirst()).doesNotContain("<scratchpad>");
        assertThat(prompts.getLast()).contains("<scratchpad>").contains("- step one done");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    @SuppressFBWarnings({"NP_NULL_PARAM_DEREF_ALL_TARGETS_DANGEROUS", "NP_NULL_PARAM_DEREF_NONVIRTUAL"})
    private static void nullDirectiveContext(PlanningContext context) {
        PlanningContext.continuation(context.teamId(), context.chatId(), null);
    }

    private List<TelemetryEvent> telemetryEvents() {
        return emissions.stream()
                .filter(ClientPayload.TelemetryResult.class::isInstance)
                .map(ClientPayload.TelemetryResult.class::cast)
                .map(ClientPayload.TelemetryResult::event)
                .toList();
    }

    private PlanningAgentLoop loop(ToolCallback[] tools) {
        return loop(tools, PlanningTestStubs.emptyScratchpad(), activityListener);
    }

    private PlanningAgentLoop loop(ToolCallback[] tools, ScratchpadService scratchpadService) {
        return loop(tools, scratchpadService, activityListener);
    }

    private PlanningAgentLoop loop(
            ToolCallback[] tools, ScratchpadService scratchpadService, AgentActivityListener listener) {
        return new PlanningAgentLoop(
                chatMemory,
                scratchpadService,
                tools,
                25,
                Runnable::run,
                Runnable::run,
                new MermaidNormalizer(),
                new MermaidDiagramValidator(),
                new MermaidDiagramRepairer(new MermaidNormalizer()),
                listener);
    }

    private static org.mockito.ArgumentMatcher<Message> isAssistantText(String text) {
        return m -> m instanceof AssistantMessage assistant && text.equals(assistant.getText());
    }

    private static ToolCallback[] noTools() {
        return new ToolCallback[0];
    }

    private static final class FlakyStreamingChatModel implements ChatModel {
        private final ChatModel delegate = new FakeChatModel();
        private int calls;

        @Override
        public @NonNull ChatResponse call(@NonNull Prompt prompt) {
            throw new UnsupportedOperationException("call is not used by the ReAct loop");
        }

        @Override
        public @NonNull Flux<ChatResponse> stream(@NonNull Prompt prompt) {
            if (++calls == 1) {
                ChatResponse partial = new ChatResponse(List.of(new Generation(new AssistantMessage("partial"))));
                return Flux.concat(Flux.just(partial), Flux.error(new TransientAiException("503")));
            }
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("recovered")))));
        }

        @Override
        public @NonNull ChatOptions getDefaultOptions() {
            return delegate.getDefaultOptions();
        }
    }

    private static final class MermaidRepairChatModel implements ChatModel {
        private final ChatModel delegate = new FakeChatModel();
        private final String streamedText;
        private final String repairedText;

        private MermaidRepairChatModel(String streamedText, String repairedText) {
            this.streamedText = streamedText;
            this.repairedText = repairedText;
        }

        @Override
        public @NonNull ChatResponse call(@NonNull Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(repairedText))));
        }

        @Override
        public @NonNull Flux<ChatResponse> stream(@NonNull Prompt prompt) {
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(streamedText)))));
        }

        @Override
        public @NonNull ChatOptions getDefaultOptions() {
            return delegate.getDefaultOptions();
        }
    }

    private static ToolCallback tool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("list_repositories")
                        .description("list repositories")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String input) {
                return "[]";
            }
        };
    }

    private static ToolCallback failingTool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("list_repositories")
                        .description("list repositories")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String input) {
                throw new IllegalStateException("boom");
            }
        };
    }
}
