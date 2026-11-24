package com.kratisai.controlplane.testsupport.llm;

import com.kratisai.controlplane.testsupport.WikiPageContentFixtures;
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

    static final String WIKI_SYSTEM_PROMPT_MARKER =
            "synthesize the human-readable high-level and architectural Markdown documentation for this codebase";

    public static List<PromptMatcher> ingestionPipelineMatchers() {
        // Match a full ingestion pipeline - shared by many tests.
        return List.of(
                assistedDiscoveryMatcher(),
                synopsisMatcher(),
                inferenceMatcher(),
                dimensionDiscoveryMatcher(),
                dimensionResearchMatcher(),
                patternResearchMatcher(),
                wikiGenerationMatcher(),
                wikiCompleteMatcher());
    }

    public static final String SYNOPSIS_SYSTEM_PROMPT_MARKER =
            "synthesize the synopsis of a repository for repo-intelligence tool";

    private static final String INFERENCE_PROMPT_MARKER = "Compare the supplied surface pairs";

    public static final String ASSISTED_DISCOVERY_PROMPT_MARKER =
            "Discover contracts between this codebase and external code or infrastructure";

    public static PromptMatcher assistedDiscoveryMatcher() {
        return PromptMatcher.builder()
                .contains(ASSISTED_DISCOVERY_PROMPT_MARKER)
                .response("{\"surfaces\":[]}")
                .build();
    }

    /** The synopsis JSON the shared pipeline matchers respond with; reused by phase-scoped tests. */
    public static String synopsisJson() {
        return """
                {
                  "repoRole": "SERVICE",
                  "knownAs": ["booking-service"],
                  "domain": "Core booking business capability for the team's booking workflows.",
                  "interfaces": "Provides the booking REST API; consumes the user management API.",
                  "deployment": "Builds a container and deploys to ECS as booking.teamvine.io.",
                  "citations": {"dimensionIds": [], "wikiSlug": "user-management-overview", "filePaths": [], "surfaceIds": [], "attributeIds": []}
                }
                """;
    }

    public static PromptMatcher synopsisMatcher() {
        return PromptMatcher.builder()
                .contains(SYNOPSIS_SYSTEM_PROMPT_MARKER)
                .response("""
                        {
                          "repoRole": "SERVICE",
                          "knownAs": ["booking-service"],
                          "domain": "Core booking business capability for the team's booking workflows.",
                          "interfaces": "Provides the booking REST API; consumes the user management API.",
                          "deployment": "Builds a container and deploys to ECS as booking.teamvine.io.",
                          "citations": {"dimensionIds": [], "wikiSlug": "user-management-overview", "filePaths": [], "surfaceIds": [], "attributeIds": []}
                        }
                        """)
                .build();
    }

    public static PromptMatcher inferenceMatcher() {
        return PromptMatcher.builder()
                .contains(INFERENCE_PROMPT_MARKER)
                .response("""
                        {"matches": []}
                        """)
                .build();
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
                "{\"pageSlug\":\"user-management-overview\",\"title\":\"User Management Overview\",\"content\":\"# User Management\\n\\n"
                        + WikiPageContentFixtures.WIKI_PAGE_BODY + "\"}");
        return PromptMatcher.builder()
                .condition(prompt -> hasWikiSystemPrompt(prompt) && !hasSuccessfulWrite(prompt))
                .response(
                        AssistantMessage.builder().toolCalls(List.of(toolCall)).build())
                .build();
    }

    public static PromptMatcher wikiCompleteMatcher() {
        return PromptMatcher.builder()
                .condition(prompt -> hasWikiSystemPrompt(prompt) && hasSuccessfulWrite(prompt))
                .response("Wiki generation complete.")
                .build();
    }

    private static boolean hasWikiSystemPrompt(Prompt prompt) {
        return prompt.getContents().contains(WIKI_SYSTEM_PROMPT_MARKER);
    }

    /** A failed write is still a tool response named write_wiki_page, so key on the success text. */
    private static boolean hasSuccessfulWrite(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .anyMatch(response -> "write_wiki_page".equals(response.name())
                        && response.responseData().contains("SUCCESS"));
    }
}
