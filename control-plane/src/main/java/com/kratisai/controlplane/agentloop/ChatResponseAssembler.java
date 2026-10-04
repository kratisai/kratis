package com.kratisai.controlplane.agentloop;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Spring AI's Google GenAI model maps every response part to its own {@link Generation}, so
 * {@link ChatResponse#getResult()} silently drops all but the first part of a multi-part answer.
 */
public final class ChatResponseAssembler {

    private ChatResponseAssembler() {}

    public static String answerText(@Nullable ChatResponse response) {
        StringBuilder text = new StringBuilder();
        if (response != null) {
            for (Generation generation : response.getResults()) {
                AssistantMessage output = generation.getOutput();
                String partText = output.getText();
                if (partText != null && !isThought(output)) {
                    text.append(partText);
                }
            }
        }
        return text.toString();
    }

    public static boolean isThought(AssistantMessage output) {
        Map<String, Object> metadata = output.getMetadata();
        return isTruthy(metadata.get("isThought")) || isTruthy(metadata.get("thinking"));
    }

    private static boolean isTruthy(@Nullable Object value) {
        return value != null && Boolean.parseBoolean(value.toString());
    }
}
