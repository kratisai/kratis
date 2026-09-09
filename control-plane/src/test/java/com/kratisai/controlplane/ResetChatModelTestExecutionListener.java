package com.kratisai.controlplane;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
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
        try {
            ChatModel chatModel = testContext.getApplicationContext().getBean(ChatModel.class);
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
        }
    }

    private void reapplyDefaultMatchers(FakeChatModel fakeChatModel) {
        // 1. Wiki/Kratis matcher
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(prompt -> {
                    String text = prompt.getContents().toLowerCase();
                    return text.contains("architectural patterns")
                            || text.contains("wiki documentation")
                            || text.contains("markdown documentation")
                            || text.contains("you are kratis");
                })
                .responses(prompt -> {
                    boolean hasToolResponse =
                            prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
                    if (hasToolResponse) {
                        return List.of(new ChatResponse(
                                List.of(new Generation(new AssistantMessage("Wiki generation complete.")))));
                    } else {
                        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                                "call_1",
                                "function",
                                "write_wiki_page",
                                "{\"pageSlug\":\"user-management-overview\",\"title\":\"User Management Overview\",\"content\":\"# User Management\"}");
                        AssistantMessage message = AssistantMessage.builder()
                                .toolCalls(List.of(toolCall))
                                .build();
                        return List.of(new ChatResponse(List.of(new Generation(message))));
                    }
                })
                .build());

        // 2. Architecture pattern matcher
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("architecture pattern")
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
                    return text.contains("architect") || text.contains("dimension") || text.contains("file tree");
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
                    return text.contains("pull request") || text.contains("commit summary");
                })
                .response("""
                        {
                          "title": "feat(auth): implement token verification",
                          "body": "Added verification logic and tests."
                        }
                        """)
                .build());
    }

    private void reapplyDefaultStubbing(ChatModel chatModel) {
        Mockito.doAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    String text = prompt.getContents().toLowerCase();
                    boolean hasToolResponse =
                            prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);

                    if (text.contains("architectural patterns")
                            || text.contains("wiki documentation")
                            || text.contains("markdown documentation")
                            || text.contains("you are kratis")) {
                        if (hasToolResponse) {
                            AssistantMessage message = new AssistantMessage("Wiki generation complete.");
                            Generation generation = new Generation(message);
                            return new ChatResponse(List.of(generation));
                        } else {
                            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                                    "call_1",
                                    "function",
                                    "write_wiki_page",
                                    "{\"pageSlug\":\"user-management-overview\",\"title\":\"User Management Overview\",\"content\":\"# User Management\"}");
                            AssistantMessage message = AssistantMessage.builder()
                                    .toolCalls(List.of(toolCall))
                                    .build();
                            Generation generation = new Generation(message);
                            return new ChatResponse(List.of(generation));
                        }
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
