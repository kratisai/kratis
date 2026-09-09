package com.kratisai.controlplane;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

public final class PromptMatcher {
    private final Predicate<Prompt> condition;
    private final Function<Prompt, List<ChatResponse>> responseSupplier;
    private final int maxMatches;
    private int currentMatches; // default 0

    private PromptMatcher(Builder builder) {
        this.condition = builder.condition;
        this.responseSupplier = builder.responseSupplier;
        this.maxMatches = builder.maxMatches;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean matches(Prompt prompt) {
        if (isExhausted()) {
            return false;
        }
        return condition.test(prompt);
    }

    public List<ChatResponse> getResponses(Prompt prompt) {
        if (matches(prompt)) {
            currentMatches++;
            return responseSupplier.apply(prompt);
        }
        return null;
    }

    public boolean isExhausted() {
        return currentMatches >= maxMatches;
    }

    public void reset() {
        currentMatches = 0;
    }

    public int getMatchCount() {
        return currentMatches;
    }

    public static class Builder {
        private Predicate<Prompt> condition = p -> true;
        private Function<Prompt, List<ChatResponse>> responseSupplier =
                p -> List.of(new ChatResponse(List.of(new Generation(new AssistantMessage("{}")))));
        private int maxMatches = Integer.MAX_VALUE;
        private Usage usage;

        public Builder condition(Predicate<Prompt> condition) {
            this.condition = condition;
            return this;
        }

        public Builder contains(String text) {
            this.condition = p -> {
                String contents = p.getContents();
                if (contents != null && contents.toLowerCase().contains(text.toLowerCase())) {
                    return true;
                }
                for (var msg : p.getInstructions()) {
                    if (msg instanceof ToolResponseMessage trm) {
                        for (ToolResponseMessage.ToolResponse res : trm.getResponses()) {
                            if (res.responseData() != null
                                    && res.responseData().toLowerCase().contains(text.toLowerCase())) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            };
            return this;
        }

        public Builder regex(String regex) {
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            this.condition = p -> {
                String contents = p.getContents();
                return pattern.matcher(contents).find();
            };
            return this;
        }

        public Builder hasToolResponse() {
            this.condition = p -> p.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
            return this;
        }

        public Builder response(String text) {
            this.responseSupplier = p -> List.of(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
            return this;
        }

        public Builder response(String text, List<AssistantMessage.ToolCall> toolCalls) {
            this.responseSupplier = p -> List.of(new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content(text)
                    .toolCalls(toolCalls)
                    .build()))));
            return this;
        }

        public Builder response(AssistantMessage message) {
            this.responseSupplier = p -> List.of(new ChatResponse(List.of(new Generation(message))));
            return this;
        }

        public Builder response(Function<Prompt, ChatResponse> supplier) {
            this.responseSupplier = p -> List.of(supplier.apply(p));
            return this;
        }

        public Builder responses(Function<Prompt, List<ChatResponse>> supplier) {
            this.responseSupplier = supplier;
            return this;
        }

        public Builder throwsException(RuntimeException ex) {
            this.responseSupplier = p -> {
                throw ex;
            };
            return this;
        }

        public Builder responseChunks(List<String> textChunks) {
            this.responseSupplier = p -> textChunks.stream()
                    .map(text -> new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
                    .toList();
            return this;
        }

        public Builder maxMatches(int maxMatches) {
            this.maxMatches = maxMatches;
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public PromptMatcher build() {
            if (this.usage != null) {
                ChatResponseMetadata metadata =
                        ChatResponseMetadata.builder().usage(this.usage).build();
                Function<Prompt, List<ChatResponse>> baseSupplier = this.responseSupplier;
                this.responseSupplier = p -> baseSupplier.apply(p).stream()
                        .map(r -> new ChatResponse(r.getResults(), metadata))
                        .toList();
            }
            return new PromptMatcher(this);
        }
    }
}
