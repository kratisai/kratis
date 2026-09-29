package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "ingestion_batches")
public class IngestionBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "repository_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_ingestion_batches_repository"))
    private Repository repository;

    @Column(name = "commit_hash", length = 40)
    private String commitHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IngestionStatus status;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "total_tool_calls")
    private Long totalToolCalls;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CtxNode> nodes = new ArrayList<>();

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CtxEdge> edges = new ArrayList<>();

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<IngestionBatchLog> logs = new ArrayList<>();

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CtxWikiPage> wikiPages = new ArrayList<>();

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CtxEmbedding> embeddings = new ArrayList<>();

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<IngestionModelUsage> modelUsage = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        if (status == null) {
            status = IngestionStatus.QUEUED;
        }
    }

    public IngestionBatch() {}

    public IngestionBatch(Repository repository) {
        this.repository = repository;
        this.status = IngestionStatus.QUEUED;
        this.isActive = false;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Repository getRepository() {
        return repository;
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    public String getCommitHash() {
        return commitHash;
    }

    public void setCommitHash(String commitHash) {
        this.commitHash = commitHash;
    }

    public IngestionStatus getStatus() {
        return status;
    }

    public void setStatus(IngestionStatus status) {
        this.status = status;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getTotalToolCalls() {
        return totalToolCalls;
    }

    public void setTotalToolCalls(Long totalToolCalls) {
        this.totalToolCalls = totalToolCalls;
    }

    public List<CtxNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<CtxNode> nodes) {
        this.nodes = nodes;
    }

    public List<CtxEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<CtxEdge> edges) {
        this.edges = edges;
    }

    public List<IngestionBatchLog> getLogs() {
        return logs;
    }

    public void setLogs(List<IngestionBatchLog> logs) {
        this.logs = logs;
    }

    public List<CtxWikiPage> getWikiPages() {
        return wikiPages;
    }

    public void setWikiPages(List<CtxWikiPage> wikiPages) {
        this.wikiPages = wikiPages;
    }

    public List<CtxEmbedding> getEmbeddings() {
        return embeddings;
    }

    public void setEmbeddings(List<CtxEmbedding> embeddings) {
        this.embeddings = embeddings;
    }

    public List<IngestionModelUsage> getModelUsage() {
        return modelUsage;
    }

    public void setModelUsage(List<IngestionModelUsage> modelUsage) {
        this.modelUsage = modelUsage;
    }

    public Optional<IngestionModelUsage> modelUsageFor(ModelKind kind) {
        return modelUsage.stream().filter(usage -> usage.getModelKind() == kind).findFirst();
    }
}
