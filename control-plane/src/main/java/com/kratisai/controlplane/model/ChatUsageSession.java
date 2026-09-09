package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "chat_usage_sessions",
        indexes = {@Index(name = "idx_chat_usage_sessions_chat_id", columnList = "chat_id")})
public class ChatUsageSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false, foreignKey = @ForeignKey(name = "fk_chat_usage_session_chat"))
    private ChatEntity chat;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "model")
    private String model;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "virtualKey", column = @Column(name = "virtual_key", columnDefinition = "TEXT")),
        @AttributeOverride(name = "totalSpend", column = @Column(name = "total_spend")),
        @AttributeOverride(name = "totalTokens", column = @Column(name = "total_tokens")),
        @AttributeOverride(name = "promptTokens", column = @Column(name = "prompt_tokens")),
        @AttributeOverride(name = "completionTokens", column = @Column(name = "completion_tokens")),
        @AttributeOverride(name = "usageLastUpdatedAt", column = @Column(name = "usage_last_updated_at"))
    })
    private LlmUsage usage = new LlmUsage();

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }

    public ChatUsageSession() {}

    public ChatUsageSession(ChatEntity chat, String virtualKey) {
        this.chat = chat;
        this.startedAt = Instant.now();
        this.usage.setVirtualKey(virtualKey);
    }

    public ChatUsageSession(ChatEntity chat, String virtualKey, String model) {
        this.chat = chat;
        this.startedAt = Instant.now();
        this.usage.setVirtualKey(virtualKey);
        this.model = model;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public ChatEntity getChat() {
        return chat;
    }

    public void setChat(ChatEntity chat) {
        this.chat = chat;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public LlmUsage getUsage() {
        return usage;
    }

    public void setUsage(LlmUsage usage) {
        this.usage = usage;
    }

    public boolean isEnded() {
        return endedAt != null;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<String> getModels() {
        return model != null ? List.of(model) : List.of();
    }

    public void setModels(List<String> models) {
        this.model = models != null && !models.isEmpty() ? models.getFirst() : null;
    }
}
