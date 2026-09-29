package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class PostgresChatMemoryAdapterTest {

    @Autowired
    private PostgresChatMemoryAdapter adapter;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
    }

    @Test
    void shouldSerializeAndDeserializeUserMessage() {
        String conversationId = UUID.randomUUID().toString();
        UserMessage userMessage = new UserMessage("Hello, how are you?");

        adapter.saveAll(conversationId, List.of(userMessage));

        List<Message> messages = adapter.findByConversationId(conversationId);
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(messages.getFirst().getText()).isEqualTo("Hello, how are you?");
    }

    @Test
    void shouldSerializeAndDeserializeUserMessageWithMetadata() {
        String conversationId = UUID.randomUUID().toString();
        UserMessage userMessage = UserMessage.builder()
                .text("Hello with metadata")
                .metadata(Map.of("key1", "value1", "key2", 42))
                .build();

        adapter.saveAll(conversationId, List.of(userMessage));

        List<Message> messages = adapter.findByConversationId(conversationId);
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(messages.getFirst().getText()).isEqualTo("Hello with metadata");
        assertThat(messages.getFirst().getMetadata()).containsEntry("key1", "value1");
        assertThat(messages.getFirst().getMetadata()).containsEntry("key2", 42);
    }

    @Test
    void shouldSerializeAndDeserializeAssistantMessage() {
        String conversationId = UUID.randomUUID().toString();
        AssistantMessage assistantMessage = new AssistantMessage("I'm doing well, thank you!");

        adapter.saveAll(conversationId, List.of(assistantMessage));

        List<Message> messages = adapter.findByConversationId(conversationId);
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).isInstanceOf(AssistantMessage.class);
        assertThat(messages.getFirst().getText()).isEqualTo("I'm doing well, thank you!");
    }

    @Test
    void shouldSerializeAndDeserializeAssistantMessageWithToolCalls() {
        String conversationId = UUID.randomUUID().toString();
        List<ToolCall> toolCalls =
                List.of(new ToolCall("call_123", "function", "web_search", "{\"query\": \"What is Java?\"}"));
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content("Let me search for that...")
                .toolCalls(toolCalls)
                .build();

        adapter.saveAll(conversationId, List.of(assistantMessage));

        List<Message> messages = adapter.findByConversationId(conversationId);
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).isInstanceOf(AssistantMessage.class);
        assertThat(messages.getFirst().getText()).isEqualTo("Let me search for that...");
        AssistantMessage retrieved = (AssistantMessage) messages.getFirst();
        assertThat(retrieved.getToolCalls()).hasSize(1);
        assertThat(retrieved.getToolCalls().getFirst().id()).isEqualTo("call_123");
        assertThat(retrieved.getToolCalls().getFirst().name()).isEqualTo("web_search");
        assertThat(retrieved.getToolCalls().getFirst().arguments()).isEqualTo("{\"query\": \"What is Java?\"}");
    }

    @Test
    void shouldSerializeAndDeserializeSystemMessage() {
        String conversationId = UUID.randomUUID().toString();
        SystemMessage systemMessage = new SystemMessage("You are a helpful assistant.");

        adapter.saveAll(conversationId, List.of(systemMessage));

        List<Message> messages = adapter.findByConversationId(conversationId);
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(messages.getFirst().getText()).isEqualTo("You are a helpful assistant.");
    }

    @Test
    void shouldSerializeAndDeserializeToolResponseMessage() {
        String conversationId = UUID.randomUUID().toString();
        List<ToolResponse> responses = List.of(new ToolResponse(
                "call_123", "web_search", "Java is a high-level, class-based, object-oriented programming language."));
        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(responses)
                .metadata(Map.of("source", "web"))
                .build();

        adapter.saveAll(conversationId, List.of(toolResponseMessage));

        List<Message> messages = adapter.findByConversationId(conversationId);
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage retrieved = (ToolResponseMessage) messages.getFirst();
        assertThat(retrieved.getResponses()).hasSize(1);
        assertThat(retrieved.getResponses().getFirst().id()).isEqualTo("call_123");
        assertThat(retrieved.getResponses().getFirst().name()).isEqualTo("web_search");
        assertThat(retrieved.getResponses().getFirst().responseData())
                .isEqualTo("Java is a high-level, class-based, object-oriented programming language.");
        assertThat(retrieved.getMetadata()).containsEntry("source", "web");
    }

    @Test
    void shouldSerializeAndDeserializeMultipleMessages() {
        String conversationId = UUID.randomUUID().toString();
        List<Message> messages = List.of(
                new SystemMessage("You are a helpful assistant."),
                new UserMessage("What is Java?"),
                new AssistantMessage("Java is a high-level, class-based, object-oriented programming language."));

        adapter.saveAll(conversationId, messages);

        List<Message> retrieved = adapter.findByConversationId(conversationId);
        assertThat(retrieved).hasSize(3);
        assertThat(retrieved.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(retrieved.get(1)).isInstanceOf(UserMessage.class);
        assertThat(retrieved.get(2)).isInstanceOf(AssistantMessage.class);
    }

    @Test
    void shouldDeleteByConversationId() {
        String conversationId = UUID.randomUUID().toString();
        adapter.saveAll(conversationId, List.of(new UserMessage("Hello"), new AssistantMessage("Hi!")));

        adapter.deleteByConversationId(conversationId);

        List<Message> messages = adapter.findByConversationId(conversationId);
        assertThat(messages).isEmpty();
    }

    @Test
    void shouldFindConversationIds() {
        String conversationId1 = UUID.randomUUID().toString();
        String conversationId2 = UUID.randomUUID().toString();

        adapter.saveAll(conversationId1, List.of(new UserMessage("Hello 1")));
        adapter.saveAll(conversationId2, List.of(new UserMessage("Hello 2")));

        List<String> ids = adapter.findConversationIds();
        assertThat(ids).contains(conversationId1, conversationId2);
    }

    @Test
    void shouldPreserveMessageOrder() {
        String conversationId = UUID.randomUUID().toString();
        List<Message> messages = List.of(
                new SystemMessage("System message"),
                new UserMessage("First user message"),
                new AssistantMessage("First assistant message"),
                new UserMessage("Second user message"),
                new AssistantMessage("Second assistant message"));

        adapter.saveAll(conversationId, messages);

        List<Message> retrieved = adapter.findByConversationId(conversationId);
        assertThat(retrieved).hasSize(5);
        assertThat(retrieved.get(0).getText()).isEqualTo("System message");
        assertThat(retrieved.get(1).getText()).isEqualTo("First user message");
        assertThat(retrieved.get(2).getText()).isEqualTo("First assistant message");
        assertThat(retrieved.get(3).getText()).isEqualTo("Second user message");
        assertThat(retrieved.get(4).getText()).isEqualTo("Second assistant message");
    }
}
