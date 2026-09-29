package com.kratisai.controlplane.agentloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

class ReActLoopTest {

    private static final ToolContext TOOL_CONTEXT = new ToolContext(Map.of());

    @Test
    void freeTextResponse_returnsText() {
        FakeChatModel model = model(assistant("all done"));

        String result = ReActLoop.text(client(model), noTools())
                .executor(Runnable::run)
                .run(List.of(new UserMessage("go")), TOOL_CONTEXT);

        assertThat(result).isEqualTo("all done");
    }

    @Test
    void toolCallThenFinalResponse_executesToolAndReturnsText() {
        ToolCallback echo = tool("{\"ok\":true}");
        AssistantMessage.ToolCall toolCall = toolCall("call_1", "echo", "{}");
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(PromptMatcher.builder()
                .response(assistantWithTools(toolCall))
                .maxMatches(1)
                .build());
        model.addMatcher(PromptMatcher.builder().response("done").maxMatches(1).build());

        AtomicInteger toolCalls = new AtomicInteger();
        String result = ReActLoop.text(client(model), new ToolCallback[] {echo})
                .executor(Runnable::run)
                .onToolCallCount(toolCalls::addAndGet)
                .run(List.of(new UserMessage("go")), TOOL_CONTEXT);

        assertThat(result).isEqualTo("done");
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    @Test
    void circuitBreaker_throwsWhenBudgetExhausted() {
        AssistantMessage.ToolCall toolCall = toolCall("call_1", "echo", "{}");
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(
                PromptMatcher.builder().response(assistantWithTools(toolCall)).build());

        assertThatThrownBy(() -> ReActLoop.text(client(model), new ToolCallback[] {tool("{}")})
                        .executor(Runnable::run)
                        .maxIterations(3)
                        .run(List.of(new UserMessage("go")), TOOL_CONTEXT))
                .isInstanceOf(ReActLoopExhaustedException.class)
                .hasMessageContaining("3");
    }

    @Test
    void parallelToolExecution_executesAllToolCalls() {
        List<String> executed = new ArrayList<>();
        ToolCallback a = capturingTool("a", executed);
        ToolCallback b = capturingTool("b", executed);
        ToolCallback c = capturingTool("c", executed);
        List<AssistantMessage.ToolCall> toolCalls =
                List.of(toolCall("1", "a", "{}"), toolCall("2", "b", "{}"), toolCall("3", "c", "{}"));

        FakeChatModel model = new FakeChatModel();
        model.addMatcher(PromptMatcher.builder()
                .response(assistantWithTools(toolCalls.toArray(AssistantMessage.ToolCall[]::new)))
                .maxMatches(1)
                .build());
        model.addMatcher(PromptMatcher.builder().response("done").maxMatches(1).build());

        ReActLoop.text(client(model), new ToolCallback[] {a, b, c})
                .executor(Runnable::run)
                .run(List.of(new UserMessage("go")), TOOL_CONTEXT);

        assertThat(executed).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void toolError_isWrappedAsJsonToolResponse() throws Exception {
        AssistantMessage.ToolCall failing = toolCall("call_1", "missing", "{}");
        AtomicReference<String> captured = new AtomicReference<>();
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(PromptMatcher.builder()
                .response(assistantWithTools(failing))
                .maxMatches(1)
                .build());
        model.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    prompt.getInstructions().stream()
                            .filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast)
                            .findFirst()
                            .ifPresent(trm ->
                                    captured.set(trm.getResponses().getFirst().responseData()));
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("recovered"))));
                })
                .maxMatches(1)
                .build());

        String result = ReActLoop.text(client(model), noTools())
                .executor(Runnable::run)
                .run(List.of(new UserMessage("go")), TOOL_CONTEXT);

        assertThat(result).isEqualTo("recovered");
        assertThat(new ObjectMapper().readTree(captured.get()).get("error").asText())
                .contains("Tool not found: missing");
    }

    @Test
    void errorFeedback_prependsGuidanceAndRetries() {
        AtomicReference<String> secondPrompt = new AtomicReference<>();
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(PromptMatcher.builder()
                .throwsException(new IllegalStateException("boom"))
                .maxMatches(1)
                .build());
        model.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    secondPrompt.set(prompt.getContents());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("recovered"))));
                })
                .maxMatches(1)
                .build());

        String result = ReActLoop.text(client(model), noTools())
                .executor(Runnable::run)
                .run(List.of(new SystemMessage("base"), new UserMessage("go")), TOOL_CONTEXT);

        assertThat(result).isEqualTo("recovered");
        assertThat(secondPrompt.get()).contains("IMPORTANT").contains("boom");
    }

    @Test
    void errorFeedback_usesRootCauseMessage() {
        AtomicReference<String> secondPrompt = new AtomicReference<>();
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(PromptMatcher.builder()
                .throwsException(new RuntimeException("wrapper", new IllegalStateException("root-cause")))
                .maxMatches(1)
                .build());
        model.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    secondPrompt.set(prompt.getContents());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
                })
                .maxMatches(1)
                .build());

        ReActLoop.text(client(model), noTools())
                .executor(Runnable::run)
                .run(List.of(new UserMessage("go")), TOOL_CONTEXT);

        assertThat(secondPrompt.get()).contains("root-cause");
    }

    @Test
    void fatalProviderError_terminatesImmediately() {
        PromptMatcher fatalMatcher = PromptMatcher.builder()
                .throwsException(new NonTransientAiException("401 - invalid api key"))
                .build();
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(fatalMatcher);

        assertThatThrownBy(() -> ReActLoop.text(client(model), noTools())
                        .executor(Runnable::run)
                        .maxIterations(5)
                        .run(List.of(new UserMessage("go")), TOOL_CONTEXT))
                .isInstanceOf(ReActLoopFatalException.class)
                .satisfies(e -> assertThat(((ReActLoopFatalException) e).category())
                        .isEqualTo(LlmErrorCategory.AUTHENTICATION));

        assertThat(fatalMatcher.getMatchCount()).isEqualTo(1);
    }

    @Test
    void contextOverflow_isFatalAndNotAppendedToPrompt() {
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(PromptMatcher.builder()
                .throwsException(
                        new RuntimeException(
                                "This model's maximum context length is 128000 tokens. However, your messages resulted in 150000 tokens."))
                .build());

        assertThatThrownBy(() -> ReActLoop.text(client(model), noTools())
                        .executor(Runnable::run)
                        .run(List.of(new UserMessage("go")), TOOL_CONTEXT))
                .isInstanceOf(ReActLoopFatalException.class)
                .satisfies(e -> assertThat(((ReActLoopFatalException) e).category())
                        .isEqualTo(LlmErrorCategory.CONTEXT_LENGTH_EXCEEDED));
    }

    @Test
    void transientError_recoversWithoutPromptFeedback() {
        PromptMatcher flakyMatcher = PromptMatcher.builder()
                .throwsException(new TransientAiException("503 - upstream overloaded"))
                .maxMatches(2)
                .build();
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(flakyMatcher);
        AtomicReference<String> recoveryPrompt = new AtomicReference<>();
        model.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    recoveryPrompt.set(prompt.getContents());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("recovered"))));
                })
                .maxMatches(1)
                .build());

        String result = ReActLoop.text(client(model), noTools())
                .executor(Runnable::run)
                .run(List.of(new UserMessage("go")), TOOL_CONTEXT);

        assertThat(result).isEqualTo("recovered");
        assertThat(flakyMatcher.getMatchCount()).isEqualTo(2);
        assertThat(recoveryPrompt.get()).doesNotContain("IMPORTANT");
    }

    @Test
    void transientError_persists_abortsAfterBoundedAttempts() {
        PromptMatcher flakyMatcher = PromptMatcher.builder()
                .throwsException(new TransientAiException("503 - upstream overloaded"))
                .build();
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(flakyMatcher);

        assertThatThrownBy(() -> ReActLoop.text(client(model), noTools())
                        .executor(Runnable::run)
                        .run(List.of(new UserMessage("go")), TOOL_CONTEXT))
                .isInstanceOf(ReActLoopFatalException.class)
                .satisfies(e ->
                        assertThat(((ReActLoopFatalException) e).category()).isEqualTo(LlmErrorCategory.SERVER_ERROR));

        assertThat(flakyMatcher.getMatchCount()).isEqualTo(3);
    }

    @Test
    void transientError_afterPartialStream_abortsWithoutRetry() {
        ChatModel model = mock(ChatModel.class);
        ChatOptions defaults = defaultOptionsMock();
        when(model.getDefaultOptions()).thenReturn(defaults);
        AtomicInteger calls = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return Flux.concat(
                    Flux.just(chatResponse(new AssistantMessage("partial"))),
                    Flux.error(new TransientAiException("503 - upstream failed mid-stream")));
        });
        List<String> answers = new ArrayList<>();

        assertThatThrownBy(() -> ReActLoop.text(client(model), noTools())
                        .executor(Runnable::run)
                        .onAnswerChunk(answers::add)
                        .run(List.of(new UserMessage("go")), TOOL_CONTEXT))
                .isInstanceOf(ReActLoopFatalException.class)
                .satisfies(e ->
                        assertThat(((ReActLoopFatalException) e).category()).isEqualTo(LlmErrorCategory.SERVER_ERROR));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(answers).containsExactly("partial");
    }

    @Test
    void structuredOutput_validationFailure_retriesThenSucceeds() {
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(
                PromptMatcher.builder().response("not json").maxMatches(1).build());
        model.addMatcher(
                PromptMatcher.builder().response("{\"value\":42}").maxMatches(1).build());

        TestRecord result = ReActLoop.structured(client(model), noTools(), TestRecord.class, value -> {
                    if (value.value() == null) {
                        throw new IllegalArgumentException("missing value");
                    }
                })
                .executor(Runnable::run)
                .maxIterations(5)
                .run(List.of(new UserMessage("go")), TOOL_CONTEXT);

        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualTo(42);
    }

    @Test
    void structuredOutput_exhaustion_throwsWithLastValidationError() {
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(PromptMatcher.builder().response("still not json").build());

        assertThatThrownBy(() -> ReActLoop.structured(client(model), noTools(), TestRecord.class, value -> {})
                        .executor(Runnable::run)
                        .maxIterations(3)
                        .run(List.of(new UserMessage("go")), TOOL_CONTEXT))
                .isInstanceOf(ReActLoopExhaustedException.class)
                .satisfies(e -> assertThat(((ReActLoopExhaustedException) e).lastValidationError())
                        .isNotNull());
    }

    @Test
    void emptyChunkList_returnsNull() {
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(PromptMatcher.builder().responses(p -> List.of()).build());

        String result = ReActLoop.text(client(model), noTools())
                .executor(Runnable::run)
                .run(List.of(new UserMessage("go")), TOOL_CONTEXT);

        assertThat(result).isNull();
    }

    @Test
    void thoughtChunks_routeToOnThought_andAreExcludedFromAnswer() {
        List<String> thoughts = new ArrayList<>();
        List<String> answers = new ArrayList<>();
        FakeChatModel model = new FakeChatModel();
        Map<String, Object> isThought = new HashMap<>();
        isThought.put("isThought", true);
        Map<String, Object> thinking = new HashMap<>();
        thinking.put("thinking", true);
        model.addMatcher(PromptMatcher.builder()
                .responses(p -> List.of(
                        chatResponse(AssistantMessage.builder()
                                .content("Weighing the trade-offs...")
                                .properties(isThought)
                                .build()),
                        chatResponse(AssistantMessage.builder()
                                .content("Considering the options...")
                                .properties(thinking)
                                .build()),
                        chatResponse(assistant("The answer is 42."))))
                .build());

        String result = ReActLoop.text(client(model), noTools())
                .executor(Runnable::run)
                .onThought(thoughts::add)
                .onAnswerChunk(answers::add)
                .run(List.of(new UserMessage("go")), TOOL_CONTEXT);

        assertThat(thoughts).containsExactly("Weighing the trade-offs...", "Considering the options...");
        assertThat(answers).containsExactly("The answer is 42.");
        assertThat(result).isEqualTo("The answer is 42.");
    }

    @Test
    void format_exposesJsonSchemaForStructuredLoop() {
        FakeChatModel model = new FakeChatModel();
        ReActLoop<TestRecord> loop = ReActLoop.structured(client(model), noTools(), TestRecord.class, value -> {});

        assertThat(loop.format()).isNotBlank();
        assertThat(ReActLoop.text(client(model), noTools()).format()).isEmpty();
    }

    @Test
    void maxIterations_rejectsNonPositive() {
        FakeChatModel model = new FakeChatModel();
        assertThatThrownBy(() -> ReActLoop.text(client(model), noTools()).maxIterations(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxIterations");
    }

    @Test
    void defaultMaxIterations_isTwentyFive() {
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(PromptMatcher.builder()
                .response(assistantWithTools(toolCall("id", "echo", "{}")))
                .build());

        assertThatThrownBy(() -> ReActLoop.text(client(model), new ToolCallback[] {tool("{}")})
                        .executor(Runnable::run)
                        .run(List.of(new UserMessage("go")), TOOL_CONTEXT))
                .isInstanceOf(ReActLoopExhaustedException.class)
                .hasMessageContaining("25");
    }

    @Test
    void mergeChunksShouldPreserveMetadata() {
        byte[] thoughtSignature1 = new byte[] {1, 2, 3, 4};
        byte[] thoughtSignature2 = new byte[] {5, 6, 7, 8};
        Map<String, Object> metadata1 = new HashMap<>();
        metadata1.put("thoughtSignatures", List.of(thoughtSignature1, thoughtSignature2));

        AssistantMessage chunk1 = AssistantMessage.builder()
                .content("Thinking...")
                .toolCalls(List.of(toolCall("call_1", "test_tool", "{\"arg1\": \"value1\"}")))
                .properties(metadata1)
                .build();
        AssistantMessage chunk2 =
                AssistantMessage.builder().content(" more thinking").build();

        AssistantMessage result = ReActLoop.mergeChunks(List.of(chatResponse(chunk1), chatResponse(chunk2)));

        assertThat(result).isNotNull();
        assertThat(result.getText()).isEqualTo("Thinking... more thinking");
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(result.getMetadata()).containsKey("thoughtSignatures");
        @SuppressWarnings("unchecked")
        List<byte[]> preservedSignatures = (List<byte[]>) result.getMetadata().get("thoughtSignatures");
        assertThat(preservedSignatures).hasSize(2);
        assertThat(preservedSignatures.getFirst()).isEqualTo(thoughtSignature1);
    }

    @Test
    void mergeChunksShouldHandleNullAndEmpty() {
        assertThat(ReActLoop.mergeChunks(null)).isNull();
        assertThat(ReActLoop.mergeChunks(List.of())).isNull();
    }

    @Test
    void mergeChunksShouldMergeMultipleToolCalls() {
        AssistantMessage chunk1 = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall("call_1", "tool_one", "{\"a\": 1}")))
                .build();
        AssistantMessage chunk2 = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall("call_2", "tool_two", "{\"b\": 2}")))
                .build();

        AssistantMessage result = ReActLoop.mergeChunks(List.of(chatResponse(chunk1), chatResponse(chunk2)));

        assertThat(result).isNotNull();
        assertThat(result.getToolCalls()).hasSize(2);
        assertThat(result.getToolCalls().get(0).id()).isEqualTo("call_1");
        assertThat(result.getToolCalls().get(1).id()).isEqualTo("call_2");
    }

    @Test
    void mergeChunksShouldPreserveAllParallelToolCallsWithBlankIds() {
        // Google GenAI (Gemini) never assigns a per-call id: every AssistantMessage.ToolCall it
        // returns has id() == "". When the model requests several tools in parallel, all of them
        // arrive as separate parts within the *same* chunk/message. Deduping by id would collapse
        // them down to just the first one (see the regression this guards against).
        AssistantMessage chunk = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        toolCall("", "read_file", "{\"relativePath\": \"a.java\"}"),
                        toolCall("", "read_file", "{\"relativePath\": \"b.java\"}"),
                        toolCall("", "read_file", "{\"relativePath\": \"c.java\"}")))
                .build();

        AssistantMessage result = ReActLoop.mergeChunks(List.of(chatResponse(chunk)));

        assertThat(result).isNotNull();
        assertThat(result.getToolCalls()).hasSize(3);
        assertThat(result.getToolCalls())
                .extracting(AssistantMessage.ToolCall::arguments)
                .containsExactly(
                        "{\"relativePath\": \"a.java\"}",
                        "{\"relativePath\": \"b.java\"}",
                        "{\"relativePath\": \"c.java\"}");
    }

    @Test
    void extractGuidance_fallsBackToClassNameWhenMessageBlank() {
        assertThat(ReActLoop.extractGuidance(new RuntimeException()))
                .contains("RuntimeException")
                .contains("IMPORTANT");
    }

    @Test
    void handleChunk_ignoresEmptyText() {
        List<String> answers = new ArrayList<>();
        FakeChatModel model = new FakeChatModel();
        ReActLoop<String> loop = ReActLoop.text(client(model), noTools()).onAnswerChunk(answers::add);

        loop.handleChunk(chatResponse(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall("call_1", "some_tool", "{}")))
                .build()));
        loop.handleChunk(new ChatResponse(List.of()));

        assertThat(answers).isEmpty();
    }

    private record TestRecord(Integer value) {}

    private static FakeChatModel model(AssistantMessage message) {
        FakeChatModel fake = new FakeChatModel();
        fake.addMatcher(PromptMatcher.builder().response(message).build());
        return fake;
    }

    private static ChatClient client(FakeChatModel model) {
        return ChatClient.builder(model).build();
    }

    private static ChatClient client(ChatModel model) {
        return ChatClient.builder(model).build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ChatOptions defaultOptionsMock() {
        ChatOptions options = mock(ChatOptions.class);
        ChatOptions.Builder builder = mock(ChatOptions.Builder.class, Answers.RETURNS_SELF);
        when(options.mutate()).thenReturn(builder);
        when(builder.build()).thenReturn(options);
        return options;
    }

    private static ToolCallback[] noTools() {
        return new ToolCallback[0];
    }

    private static AssistantMessage assistant(String text) {
        return new AssistantMessage(text);
    }

    private static AssistantMessage assistantWithTools(AssistantMessage.ToolCall... toolCalls) {
        return AssistantMessage.builder().toolCalls(List.of(toolCalls)).build();
    }

    private static AssistantMessage.ToolCall toolCall(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private static ChatResponse chatResponse(AssistantMessage message) {
        return new ChatResponse(
                List.of(new Generation(message, ChatGenerationMetadata.builder().build())));
    }

    private static ToolCallback tool(String result) {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("echo");
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(any(), any())).thenReturn(result);
        return tool;
    }

    private static ToolCallback capturingTool(String name, List<String> executed) {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(any(), any())).thenAnswer(invocation -> {
            synchronized (executed) {
                executed.add(name);
            }
            return "{}";
        });
        return tool;
    }
}
