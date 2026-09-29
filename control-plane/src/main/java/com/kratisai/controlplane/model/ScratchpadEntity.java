package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "scratchpad",
        indexes = {@Index(name = "idx_scratchpad_chat_id", columnList = "chat_id")})
public class ScratchpadEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private UUID chatId;

    @Column(name = "fact", nullable = false, columnDefinition = "TEXT")
    private String fact;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ScratchpadEntity() {}

    public ScratchpadEntity(UUID sessionId, String fact) {
        this.chatId = sessionId;
        this.fact = fact;
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

    public void setChatId(UUID chatId) {
        this.chatId = chatId;
    }

    public String getFact() {
        return fact;
    }

    public void setFact(String fact) {
        this.fact = fact;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
