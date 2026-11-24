package com.kratisai.controlplane.testsupport.llm;

import com.kratisai.controlplane.testsupport.ContextCacheTracker;
import com.kratisai.controlplane.testsupport.TestTimingRegistry;
import com.kratisai.controlplane.testsupport.WikiPageContentFixtures;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

public class ResetChatModelTestExecutionListener implements TestExecutionListener {

    @Override
    public void beforeTestMethod(@NonNull TestContext testContext) {
        resetChatModel(testContext);
        boolean useReal = testContext.getTestMethod().isAnnotationPresent(UseRealLlmClient.class)
                || testContext.getTestClass().isAnnotationPresent(UseRealLlmClient.class);
        FakeChatModelConfig.setUseRealModel(useReal);

        boolean useRealEmbedding = testContext.getTestMethod().isAnnotationPresent(UseRealEmbeddingClient.class)
                || testContext.getTestClass().isAnnotationPresent(UseRealEmbeddingClient.class);
        FakeChatModelConfig.setUseRealEmbeddingModel(useRealEmbedding);
    }

    @Override
    public void afterTestMethod(@NonNull TestContext testContext) {
        resetChatModel(testContext);
        FakeChatModelConfig.clear();
    }

    private void resetChatModel(TestContext testContext) {
        long start = System.nanoTime();
        try {
            ApplicationContext context = ContextCacheTracker.resolvedContext(testContext);
            if (context == null) {
                return;
            }
            ChatModel chatModel = context.getBean(ChatModel.class);
            if (chatModel instanceof FakeChatModel fakeChatModel) {
                fakeChatModel.reset();
                reapplyDefaultMatchers(fakeChatModel);
            } else if (Mockito.mockingDetails(chatModel).isMock()
                    || Mockito.mockingDetails(chatModel).isSpy()) {
                Mockito.reset(chatModel);
                reapplyDefaultStubbing(chatModel);
            }
        } catch (Exception e) {
            // Ignore if ChatModel bean is not present
        } finally {
            TestTimingRegistry.instance().recordChatModelReset(System.nanoTime() - start);
        }
    }

    private void reapplyDefaultMatchers(FakeChatModel fakeChatModel) {
        fakeChatModel.addMatcher(FakeChatModelMatchers.wikiGenerationMatcher());
        fakeChatModel.addMatcher(FakeChatModelMatchers.wikiCompleteMatcher());

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(prompt -> {
                    String text = prompt.getContents().toLowerCase();
                    return !isIngestionStructuredPrompt(text) && text.contains("architecture pattern");
                })
                .response("""
                        [
                          {
                            "description": "Monolithic MVC Service Architecture pattern detected.",
                            "exemplarPaths": ["UserController.java", "UserService.java"]
                          }
                        ]
                        """)
                .build());

        // 3. Architect/Dimension/File tree matcher
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(prompt -> {
                    String text = prompt.getContents().toLowerCase();
                    return !isIngestionStructuredPrompt(text)
                            && (text.contains("architect") || text.contains("dimension") || text.contains("file tree"));
                })
                .response("""
                        {
                          "domains": [{"name": "User Management", "globPatterns": ["*User*.java"]}],
                          "archetypes": [
                              {"name": "Controller", "globPatterns": ["*Controller.java"]},
                              {"name": "Service", "globPatterns": ["*Service.java"]}
                          ],
                          "crossCutting": [{"name": "Utils", "globPatterns": ["*util*.java"]}]
                        }
                        """)
                .build());

        // 4. Pull request / commit summary matcher
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(prompt -> {
                    String text = prompt.getContents().toLowerCase();
                    return !isIngestionStructuredPrompt(text)
                            && (text.contains("pull request") || text.contains("commit summary"));
                })
                .response("""
                        {
                          "title": "feat(auth): implement token verification",
                          "body": "Added verification logic and tests."
                        }
                        """)
                .build());
    }

    private static final String DISCOVERY_MARKER_LOWER =
            FakeChatModelMatchers.ASSISTED_DISCOVERY_PROMPT_MARKER.toLowerCase(Locale.ROOT);
    private static final String SYNOPSIS_MARKER_LOWER =
            FakeChatModelMatchers.SYNOPSIS_SYSTEM_PROMPT_MARKER.toLowerCase(Locale.ROOT);

    private static boolean isIngestionStructuredPrompt(String text) {
        return text.contains(DISCOVERY_MARKER_LOWER)
                || text.contains("compare the supplied surface pairs")
                || text.contains(SYNOPSIS_MARKER_LOWER);
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

    private void reapplyDefaultStubbing(ChatModel chatModel) {
        Mockito.doAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    String text = prompt.getContents().toLowerCase();

                    if (text.contains(DISCOVERY_MARKER_LOWER)) {
                        return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"surfaces\":[]}"))));
                    } else if (text.contains("compare the supplied surface pairs")) {
                        return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"matches\":[]}"))));
                    } else if (text.contains(SYNOPSIS_MARKER_LOWER)) {
                        return new ChatResponse(
                                List.of(new Generation(new AssistantMessage(FakeChatModelMatchers.synopsisJson()))));
                    } else if (text.contains("architectural patterns")
                            || text.contains("wiki documentation")
                            || text.contains("markdown documentation")
                            || text.contains("you are kratis")) {
                        AssistantMessage message;
                        if (!hasSuccessfulWrite(prompt)) {
                            AssistantMessage.ToolCall pageCall = new AssistantMessage.ToolCall(
                                    "call_1",
                                    "function",
                                    "write_wiki_page",
                                    "{\"pageSlug\":\"user-management-overview\",\"title\":\"User Management Overview\",\"content\":\"# User Management\\n\\n"
                                            + WikiPageContentFixtures.WIKI_PAGE_BODY + "\"}");
                            message = AssistantMessage.builder()
                                    .toolCalls(List.of(pageCall))
                                    .build();
                        } else {
                            message = new AssistantMessage("Wiki generation complete.");
                        }
                        return new ChatResponse(List.of(new Generation(message)));
                    } else if (text.contains("architecture pattern")) {
                        String response = """
                        [
                          {
                            "description": "Monolithic MVC Service Architecture pattern detected.",
                            "exemplarPaths": ["UserController.java", "UserService.java"]
                          }
                        ]
                        """;
                        AssistantMessage message = new AssistantMessage(response);
                        Generation generation = new Generation(message);
                        return new ChatResponse(List.of(generation));
                    } else if (text.contains("architect") || text.contains("dimension") || text.contains("file tree")) {
                        String response = """
                        {
                          "domains": [{"name": "User Management", "globPatterns": ["*User*.java"]}],
                          "archetypes": [
                              {"name": "Controller", "globPatterns": ["*Controller.java"]},
                              {"name": "Service", "globPatterns": ["*Service.java"]}
                          ],
                          "crossCutting": [{"name": "Utils", "globPatterns": ["*util*.java"]}]
                        }
                        """;
                        AssistantMessage message = new AssistantMessage(response);
                        Generation generation = new Generation(message);
                        return new ChatResponse(List.of(generation));
                    } else if (text.contains("pull request") || text.contains("commit summary")) {
                        String response = """
                        {
                          "title": "feat(auth): implement token verification",
                          "body": "Added verification logic and tests."
                        }
                        """;
                        AssistantMessage message = new AssistantMessage(response);
                        Generation generation = new Generation(message);
                        return new ChatResponse(List.of(generation));
                    }
                    AssistantMessage message = new AssistantMessage("{}");
                    Generation generation = new Generation(message);
                    return new ChatResponse(List.of(generation));
                })
                .when(chatModel)
                .call(org.mockito.ArgumentMatchers.any(Prompt.class));

        Mockito.doReturn(DefaultToolCallingChatOptions.builder().build())
                .when(chatModel)
                .getDefaultOptions();
    }
}
