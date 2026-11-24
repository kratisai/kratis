package com.kratisai.controlplane.testsupport.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.prompt.Prompt;

class FakeChatModelMatchersTest {

    @Test
    void failedWriteDoesNotLookLikeCompletion() {
        Prompt prompt = promptWithWriteResponse("{\"error\":\"Invalid Mermaid diagram syntax\"}");

        assertThat(FakeChatModelMatchers.wikiCompleteMatcher().matches(prompt)).isFalse();
        assertThat(FakeChatModelMatchers.wikiGenerationMatcher().matches(prompt))
                .isTrue();
    }

    @Test
    void successfulWriteLooksLikeCompletion() {
        Prompt prompt = promptWithWriteResponse("SUCCESS: Wiki page 'overview' created successfully.");

        assertThat(FakeChatModelMatchers.wikiCompleteMatcher().matches(prompt)).isTrue();
        assertThat(FakeChatModelMatchers.wikiGenerationMatcher().matches(prompt))
                .isFalse();
    }

    private static Prompt promptWithWriteResponse(String responseData) {
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponse("call_1", "write_wiki_page", responseData)))
                .build();
        return new Prompt(List.of(new SystemMessage(FakeChatModelMatchers.WIKI_SYSTEM_PROMPT_MARKER), response));
    }
}
