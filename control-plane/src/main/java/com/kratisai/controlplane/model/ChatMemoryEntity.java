package com.kratisai.controlplane.model;

import com.kratisai.controlplane.api.wsdto.MessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "chat_memory",
        indexes = {@Index(name = "idx_chat_memory_chat_id", columnList = "chat_id, message_index")})
public class ChatMemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private UUID chatId;

    @Column(name = "message_index", nullable = false)
    private Integer messageIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 50)
    private MessageRole messageType;

    @Column(name = "message_text", nullable = false, columnDefinition = "TEXT")
    private String messageText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "jsonb")
    private String toolCalls;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_call_responses", columnDefinition = "jsonb")
    private String toolCallResponses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ChatMemoryEntity() {}

    public ChatMemoryEntity(
            UUID sessionId,
            Integer messageIndex,
            MessageRole messageType,
            String messageText,
            String toolCalls,
            String toolCallResponses,
            String metadata) {
        this.chatId = sessionId;
        this.messageIndex = messageIndex;
        this.messageType = messageType;
        this.messageText = messageText;
        this.toolCalls = toolCalls;
        this.toolCallResponses = toolCallResponses;
        this.metadata = metadata;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getChatId() {
        return chatId;
    }

    public void setChatId(UUID sessionId) {
        this.chatId = sessionId;
    }

    public Integer getMessageIndex() {
        return messageIndex;
    }

    public void setMessageIndex(Integer messageIndex) {
        this.messageIndex = messageIndex;
    }

    public MessageRole getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageRole messageType) {
        this.messageType = messageType;
    }

    public String getMessageText() {
        return messageText;
    }

    public void setMessageText(String messageText) {
        this.messageText = messageText;
    }

    public String getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(String toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallResponses() {
        return toolCallResponses;
    }

    public void setToolCallResponses(String toolCallResponses) {
        this.toolCallResponses = toolCallResponses;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
