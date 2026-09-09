package com.kratisai.controlplane;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.MessageAggregator;

/**
 * Reusable matchers for testing using the fake chat model.
 * <p/
 * Add / Modify matchers here where they are shared by at least 2 tests.
 */
public class FakeChatModelMatchers {

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
                .usage(new MessageAggregator.DefaultUsage(200, 100, 300))
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
                .usage(new MessageAggregator.DefaultUsage(100, 50, 150))
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
                .usage(new MessageAggregator.DefaultUsage(150, 75, 225))
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
                .contains(
                        "synthesize the human-readable high-level and architectural Markdown documentation for this codebase")
                .usage(new MessageAggregator.DefaultUsage(250, 125, 375))
                .response(assistantMessage)
                // If a single-test runs the ingestion pipeline multiple times, we won't create a wiki-page for every
                // run, which might cause a problem later but is acceptable for now.
                .maxMatches(1)
                .build();
    }

    public static PromptMatcher wikiCompleteMatcher() {
        return PromptMatcher.builder()
                .contains(
                        "synthesize the human-readable high-level and architectural Markdown documentation for this codebase")
                .usage(new MessageAggregator.DefaultUsage(250, 125, 375))
                .response("Wiki generation complete.")
                .build();
    }
}
