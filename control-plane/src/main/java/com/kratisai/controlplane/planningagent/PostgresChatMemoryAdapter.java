package com.kratisai.controlplane.planningagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.MessageRole;
import com.kratisai.controlplane.model.ChatMemoryEntity;
import com.kratisai.controlplane.repository.AgentChatMemoryRepository;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed implementation of Spring AI's ChatMemoryRepository interface. */
@Component
public class PostgresChatMemoryAdapter implements ChatMemoryRepository {

    private final AgentChatMemoryRepository jpaRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostgresChatMemoryAdapter(AgentChatMemoryRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public @NonNull List<String> findConversationIds() {
        return jpaRepository.findAll().stream()
                .map(ChatMemoryEntity::getChatId)
                .map(UUID::toString)
                .distinct()
                .toList();
    }

    @Override
    public @NonNull List<Message> findByConversationId(@NonNull String conversationId) {
        UUID chatId = UUID.fromString(conversationId);
        return jpaRepository.findByChatIdOrderByCreatedAtAsc(chatId).stream()
                .map(this::deserializeMessage)
                .toList();
    }

    @Override
    @Transactional
    public void saveAll(@NonNull String conversationId, List<Message> messages) {
        UUID chatId = UUID.fromString(conversationId);
        // Delete existing messages for this conversation
        jpaRepository.deleteByChatId(chatId);

        // Save new messages
        int index = 0;
        for (Message message : messages) {
            ChatMemoryEntity entity = serializeMessage(message, chatId, index++);
            jpaRepository.save(entity);
        }
    }

    @Override
    @Transactional
    public void deleteByConversationId(@NonNull String conversationId) {
        UUID chatId = UUID.fromString(conversationId);
        jpaRepository.deleteByChatId(chatId);
    }

    private ChatMemoryEntity serializeMessage(Message message, UUID sessionId, int messageIndex) {
        String messageText = message.getText();
        String toolCallsJson = null;
        String toolCallResponsesJson = null;
        String metadataJson = null;

        // Serialize metadata if present
        message.getMetadata();
        if (!message.getMetadata().isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(message.getMetadata());
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize message metadata", e);
            }
        }

        // Handle AssistantMessage tool calls
        if (message instanceof AssistantMessage assistantMessage) {
            List<ToolCall> toolCalls = assistantMessage.getToolCalls();
            if (!toolCalls.isEmpty()) {
                try {
                    toolCallsJson = objectMapper.writeValueAsString(toolCalls);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to serialize tool calls", e);
                }
            }
        }

        // Handle ToolResponseMessage responses
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            List<ToolResponse> responses = toolResponseMessage.getResponses();
            if (!responses.isEmpty()) {
                try {
                    toolCallResponsesJson = objectMapper.writeValueAsString(responses);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to serialize tool call responses", e);
                }
            }
        }

        return new ChatMemoryEntity(
                sessionId,
                messageIndex,
                MessageRole.fromString(message.getMessageType().name()),
                messageText != null ? messageText : "",
                toolCallsJson,
                toolCallResponsesJson,
                metadataJson);
    }

    private Message deserializeMessage(ChatMemoryEntity entity) {
        try {
            MessageRole messageType = entity.getMessageType();
            String messageText = entity.getMessageText();
            String metadataJson = entity.getMetadata();

            // Deserialize metadata
            var metadata = deserializeMetadata(metadataJson);

            return switch (messageType) {
                case USER ->
                    UserMessage.builder().text(messageText).metadata(metadata).build();
                case ASSISTANT -> deserializeAssistantMessage(entity, messageText, metadata);
                case SYSTEM ->
                    SystemMessage.builder().text(messageText).metadata(metadata).build();
                case TOOL -> deserializeToolResponseMessage(entity, metadata);
                default -> throw new IllegalArgumentException("Unknown message type: " + messageType);
            };
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize message", e);
        }
    }

    private java.util.Map<String, Object> deserializeMetadata(String metadataJson) throws JsonProcessingException {
        if (metadataJson == null || metadataJson.isBlank()) {
            return java.util.Map.of();
        }
        return objectMapper.readValue(metadataJson, new TypeReference<>() {});
    }

    private AssistantMessage deserializeAssistantMessage(
            ChatMemoryEntity entity, String messageText, java.util.Map<String, Object> metadata)
            throws JsonProcessingException {
        String toolCallsJson = entity.getToolCalls();
        List<ToolCall> toolCalls = List.of();

        if (toolCallsJson != null && !toolCallsJson.isBlank()) {
            toolCalls = objectMapper.readValue(toolCallsJson, new TypeReference<>() {});
        }

        return AssistantMessage.builder()
                .content(messageText)
                .properties(metadata)
                .toolCalls(toolCalls)
                .build();
    }

    private ToolResponseMessage deserializeToolResponseMessage(
            ChatMemoryEntity entity, java.util.Map<String, Object> metadata) throws JsonProcessingException {
        String toolCallResponsesJson = entity.getToolCallResponses();
        List<ToolResponse> responses = List.of();

        if (toolCallResponsesJson != null && !toolCallResponsesJson.isBlank()) {
            responses = objectMapper.readValue(toolCallResponsesJson, new TypeReference<>() {});
        }

        return ToolResponseMessage.builder()
                .responses(responses)
                .metadata(metadata)
                .build();
    }
}
