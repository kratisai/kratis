package com.kratisai.controlplane.agentloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ChatResponseAssemblerTest {

    @Test
    void nullResponse_returnsEmptyText() {
        assertThat(ChatResponseAssembler.answerText(null)).isEmpty();
    }

    @Test
    void concatenatesEveryPartInOrder() {
        ChatResponse response = responseOf(
                generation("{\"synopsis\": \"The Native Interop"), generation(" & SDK Wrappers archetype bridges\"}"));

        assertThat(ChatResponseAssembler.answerText(response))
                .isEqualTo("{\"synopsis\": \"The Native Interop & SDK Wrappers archetype bridges\"}");
    }

    @Test
    void skipsThoughtParts() {
        Map<String, Object> isThought = new HashMap<>();
        isThought.put("isThought", true);

        ChatResponse response = responseOf(
                generation(AssistantMessage.builder()
                        .content("Weighing the trade-offs...")
                        .properties(isThought)
                        .build()),
                generation("{\"synopsis\": \"Answer only\"}"));

        assertThat(ChatResponseAssembler.answerText(response)).isEqualTo("{\"synopsis\": \"Answer only\"}");
    }

    @Test
    void skipsThinkingMetadataParts() {
        Map<String, Object> thinking = new HashMap<>();
        thinking.put("thinking", "true");

        ChatResponse response = responseOf(
                generation(AssistantMessage.builder()
                        .content("Considering the options...")
                        .properties(thinking)
                        .build()),
                generation("answer"));

        assertThat(ChatResponseAssembler.answerText(response)).isEqualTo("answer");
    }

    private static ChatResponse responseOf(Generation... generations) {
        return new ChatResponse(List.of(generations));
    }

    private static Generation generation(String text) {
        return new Generation(new AssistantMessage(text));
    }

    private static Generation generation(AssistantMessage message) {
        return new Generation(message);
    }
}
