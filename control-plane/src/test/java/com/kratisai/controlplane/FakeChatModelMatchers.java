package com.kratisai.controlplane;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Reusable matchers for testing using the fake chat model.
 * <p/
 * Add / Modify matchers here where they are shared by at least 2 tests.
 */
public class FakeChatModelMatchers {

    private static final String WIKI_SYSTEM_PROMPT_MARKER =
            "synthesize the human-readable high-level and architectural Markdown documentation for this codebase";

    public static List<PromptMatcher> ingestionPipelineMatchers() {
        // Match a full ingestion pipeline - shared by many tests.
        return List.of(
                dimensionDiscoveryMatcher(),
                dimensionResearchMatcher(),
                patternResearchMatcher(),
                wikiGenerationMatcher(),
                wikiCompleteMatcher());
    }

    public static PromptMatcher dimensionDiscoveryMatcher() {
        return PromptMatcher.builder()
                .contains("deduce the primary architectural dimensions")
                .response("""
                        {
                          "domains": [{"name": "User Management", "globPatterns": ["*"]}],
                          "archetypes": [
                              {"name": "Controller", "globPatterns": ["*"]},
                              {"name": "Service", "globPatterns": ["*"]}
                          ],
                          "crossCutting": [{"name": "Utils", "globPatterns": ["*"]}]
                        }
                        """)
                .build();
    }

    public static PromptMatcher dimensionResearchMatcher() {
        return PromptMatcher.builder()
                .contains("generate a rich synopsis for a specific")
                .response("""
                        {
                          "synopsis": "This represents a core business capability, encapsulating key services and data models."
                        }
                        """)
                .build();
    }

    public static PromptMatcher patternResearchMatcher() {
        return PromptMatcher.builder()
                .contains("deduce the system-wide architecture patterns")
                .response("""
                        [
                          {
                            "name": "Monolithic MVC",
                            "description": "Monolithic MVC Service Architecture pattern detected.",
                            "exemplarPaths": ["UserController.java", "UserService.java"]
                          }
                        ]
                        """)
                .build();
    }

    public static PromptMatcher wikiGenerationMatcher() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_1",
                "function",
                "write_wiki_page",
                "{\"pageSlug\":\"user-management-overview\",\"title\":\"User Management Overview\",\"content\":\"# User Management\"}");
        AssistantMessage assistantMessage =
                AssistantMessage.builder().toolCalls(List.of(toolCall)).build();
        return PromptMatcher.builder()
                // Write one page per batch, not one page per test, so concurrent ingestions
                // each produce a wiki rather than an empty one. Once a tool response is present
                // the batch has already written its page and falls through to wikiCompleteMatcher.
                .condition(prompt -> hasWikiSystemPrompt(prompt) && !hasToolResponse(prompt))
                .response(assistantMessage)
                .build();
    }

    public static PromptMatcher wikiCompleteMatcher() {
        return PromptMatcher.builder()
                .contains(WIKI_SYSTEM_PROMPT_MARKER)
                .response("Wiki generation complete.")
                .build();
    }

    private static boolean hasWikiSystemPrompt(Prompt prompt) {
        String contents = prompt.getContents();
        return contents.contains(WIKI_SYSTEM_PROMPT_MARKER);
    }

    private static boolean hasToolResponse(Prompt prompt) {
        return prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
    }
}
