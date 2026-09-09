package com.kratisai.controlplane;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

public class FakeChatModel implements ChatModel {

    public record Invocation(Prompt prompt, String modelName, ChatResponse response, Throwable thrownException) {}

    // FakeChatModel is reused within a context, but testing is single-threaded
    // within a context / shared DB.
    private final List<PromptMatcher> matchers = new CopyOnWriteArrayList<>();
    private final List<Invocation> invocations = new CopyOnWriteArrayList<>();
    private final ChatOptions defaultOptions = createDefaultOptionsMock();
    private volatile String lastModelName;

    public FakeChatModel() {}

    public void addMatcher(PromptMatcher matcher) {
        this.matchers.add(matcher);
    }

    public void addMatchers(List<PromptMatcher> matchers) {
        this.matchers.addAll(matchers);
    }

    public void addMatcherFirst(PromptMatcher matcher) {
        this.matchers.addFirst(matcher);
    }

    public void clearMatchers() {
        this.matchers.clear();
    }

    public void reset() {
        this.matchers.clear();
        this.invocations.clear();
        this.lastModelName = null;
    }

    public List<Invocation> getInvocations() {
        return List.copyOf(this.invocations);
    }

    public void setLastModelNameOverride(String modelNameOverride) {
        this.lastModelName = modelNameOverride;
    }

    @Override
    public @NonNull ChatResponse call(@NonNull Prompt prompt) {
        ChatResponse response = null;
        Throwable error = null;
        try {
            for (PromptMatcher matcher : matchers) {
                List<ChatResponse> responses = matcher.getResponses(prompt);
                if (responses != null) {
                    return aggregateResponses(responses);
                }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
        } catch (RuntimeException ex) {
            error = ex;
            throw ex;
        } finally {
            invocations.add(new Invocation(prompt, this.lastModelName, response, error));
        }
    }

    @Override
    public @NonNull Flux<ChatResponse> stream(@NonNull Prompt prompt) {
        List<ChatResponse> chunks;
        Throwable error;
        try {
            for (PromptMatcher matcher : matchers) {
                chunks = matcher.getResponses(prompt);
                if (chunks != null) {
                    ChatResponse fullResponse = aggregateResponses(chunks);
                    invocations.add(new Invocation(prompt, this.lastModelName, fullResponse, null));
                    return Flux.fromIterable(chunks);
                }
            }
            ChatResponse defaultResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
            invocations.add(new Invocation(prompt, this.lastModelName, defaultResponse, null));
            return Flux.just(defaultResponse);
        } catch (RuntimeException ex) {
            error = ex;
            invocations.add(new Invocation(prompt, this.lastModelName, null, error));
            throw ex;
        }
    }

    private ChatResponse aggregateResponses(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(""))));
        }
        if (responses.size() == 1) {
            return responses.getFirst();
        }
        StringBuilder sb = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = null;
        for (ChatResponse chunk : responses) {
            Generation result = chunk.getResult();
            if (result != null) {
                AssistantMessage msg = result.getOutput();
                if (msg.getText() != null) {
                    sb.append(msg.getText());
                }
                if (!msg.getToolCalls().isEmpty()) {
                    toolCalls = msg.getToolCalls();
                }
            }
        }
        if (toolCalls != null) {
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content(sb.toString())
                    .toolCalls(toolCalls)
                    .build())));
        } else {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(sb.toString()))));
        }
    }

    @Override
    public @NonNull ChatOptions getDefaultOptions() {
        return this.defaultOptions;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ChatOptions createDefaultOptionsMock() {
        var defaultOptions = mock(ChatOptions.class);
        ChatOptions.Builder mockBuilder = mock(ChatOptions.Builder.class, org.mockito.Answers.RETURNS_SELF);
        when(defaultOptions.mutate()).thenReturn(mockBuilder);
        when(mockBuilder.build()).thenReturn(defaultOptions);
        return defaultOptions;
    }
}
