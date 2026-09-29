package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "canvas",
        indexes = {@Index(name = "idx_canvas_chat_id", columnList = "chat_id")},
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_canvas_chat_document",
                    columnNames = {"chat_id", "document_id"})
        })
public class CanvasEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private UUID chatId;

    @Column(name = "document_id", nullable = false, length = 255)
    private String documentId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "canvas_type", nullable = false, length = 50)
    private CanvasType canvasType = CanvasType.DOCUMENT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = true, foreignKey = @ForeignKey(name = "fk_canvas_repository"))
    private Repository repository;

    @Column(name = "new_repo_name", length = 255)
    private String newRepoName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public CanvasEntity() {}

    public CanvasEntity(UUID chatId, String documentId, String title, String content) {
        this.chatId = chatId;
        this.documentId = documentId;
        this.title = title;
        this.content = content;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
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

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public CanvasType getCanvasType() {
        return canvasType;
    }

    public void setCanvasType(CanvasType canvasType) {
        this.canvasType = canvasType;
    }

    public Repository getRepository() {
        return repository;
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    public String getNewRepoName() {
        return newRepoName;
    }

    public void setNewRepoName(String newRepoName) {
        this.newRepoName = newRepoName;
    }

    public String getRepoLabel() {
        if (repository != null) {
            return repository.getName();
        }
        if (newRepoName != null) {
            return "New: " + newRepoName;
        }
        return null;
    }

    public boolean isNewRepo() {
        return newRepoName != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
