package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "sandbox_executions")
public class SandboxExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "environment_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sandbox_executions_environment"))
    private ExecutionEnvironment environment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false, foreignKey = @ForeignKey(name = "fk_sandbox_executions_chat"))
    private ChatEntity chat;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private SandboxExecutionStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Active time from RUNNING segments that have already closed, in milliseconds. The open segment
     * is tracked by {@link #activeSince} and is deliberately NOT included, so the live total is
     * {@code previousActiveMs + (now - activeSince)}. Named "previous" relative to the open segment.
     */
    @Column(name = "previous_active_ms", nullable = false)
    private long previousActiveMs = 0L;

    /** Start of the currently-open RUNNING segment, or {@code null} when the execution is not running. */
    @Column(name = "active_since")
    private Instant activeSince;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "repository_id",
            nullable = true,
            foreignKey = @ForeignKey(name = "fk_sandbox_executions_repository"))
    private Repository repository;

    @Column(name = "new_repo_name", length = 255)
    private String newRepoName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "new_repo_credential_id",
            nullable = true,
            foreignKey = @ForeignKey(name = "fk_sandbox_executions_new_repo_credential"))
    private RepoCredential newRepoCredential;

    @Convert(converter = AgentHarnessConverter.class)
    @Column(name = "harness", length = 50)
    private AgentHarness harness;

    @Column(name = "task_prompt", columnDefinition = "TEXT")
    private String taskPrompt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "canvas_id", nullable = true, foreignKey = @ForeignKey(name = "fk_sandbox_executions_canvas"))
    private CanvasEntity canvas;

    @OneToMany(mappedBy = "execution", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startedAt ASC")
    private List<SandboxExecutionUsage> usages = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "model_provider_id",
            nullable = true,
            foreignKey = @ForeignKey(name = "fk_sandbox_executions_model_provider"))
    private ModelProvider modelProvider;

    @Column(name = "model_name", nullable = true)
    private String modelName;

    @Column(name = "published_branch", length = 255)
    private String publishedBranch;

    @Column(name = "target_branch", length = 255)
    private String targetBranch;

    /**
     * Every pull request published from this execution. The latest row mirrors the legacy
     * {@code published*} fields above for older clients and queries.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sandbox_execution_published_prs", joinColumns = @JoinColumn(name = "execution_id"))
    @OrderBy("createdAt ASC")
    private List<SandboxExecutionPublishedPr> publishedPrs = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (status == null) {
            status = SandboxExecutionStatus.RUNNING;
        }
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        if (status == SandboxExecutionStatus.RUNNING && activeSince == null) {
            activeSince = startedAt;
        }
    }

    public SandboxExecution() {}

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public ExecutionEnvironment getEnvironment() {
        return environment;
    }

    public void setEnvironment(ExecutionEnvironment environment) {
        this.environment = environment;
    }

    public ChatEntity getChat() {
        return chat;
    }

    public void setChat(ChatEntity chat) {
        this.chat = chat;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public SandboxExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(SandboxExecutionStatus status) {
        boolean wasRunning = this.status == SandboxExecutionStatus.RUNNING;
        boolean willRun = status == SandboxExecutionStatus.RUNNING;
        Instant now = Instant.now();
        if (willRun && !wasRunning) {
            activeSince = now;
        } else if (!willRun && wasRunning) {
            endActive(now);
            if (environment != null) {
                environment.setStateChangedAt(now);
            }
        }
        this.status = status;
    }

    private void endActive(Instant now) {
        if (activeSince != null) {
            previousActiveMs += Duration.between(activeSince, now).toMillis();
            activeSince = null;
        }
    }

    /** Total active seconds: previous closed segments plus the open RUNNING segment when active. */
    public long getTotalActiveSeconds() {
        long millis = previousActiveMs;
        if (activeSince != null) {
            millis += Duration.between(activeSince, Instant.now()).toMillis();
        }
        return millis / 1000;
    }

    /** Active seconds from previous closed segments only (excludes the open RUNNING segment). */
    public long getPreviousActiveSeconds() {
        return previousActiveMs / 1000;
    }

    public Instant getActiveSince() {
        return activeSince;
    }

    public void setActiveSince(Instant activeSince) {
        this.activeSince = activeSince;
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

    public RepoCredential getNewRepoCredential() {
        return newRepoCredential;
    }

    public void setNewRepoCredential(RepoCredential newRepoCredential) {
        this.newRepoCredential = newRepoCredential;
    }

    public AgentHarness getHarness() {
        return harness;
    }

    public void setHarness(AgentHarness harness) {
        this.harness = harness;
    }

    public String getTaskPrompt() {
        return taskPrompt;
    }

    public void setTaskPrompt(String taskPrompt) {
        this.taskPrompt = taskPrompt;
    }

    public CanvasEntity getCanvas() {
        return canvas;
    }

    public void setCanvas(CanvasEntity canvas) {
        this.canvas = canvas;
    }

    public List<SandboxExecutionUsage> getUsages() {
        return usages;
    }

    public SandboxExecutionUsage getActiveUsage() {
        for (int i = usages.size() - 1; i >= 0; i--) {
            if (usages.get(i).isActive()) {
                return usages.get(i);
            }
        }
        return null;
    }

    public SandboxExecutionUsage startUsage(String virtualKey, String modelName) {
        SandboxExecutionUsage usage = new SandboxExecutionUsage(this, virtualKey, modelName);
        usages.add(usage);
        return usage;
    }

    public String getVirtualKey() {
        SandboxExecutionUsage active = getActiveUsage();
        if (active != null) {
            return active.getVirtualKey();
        }
        return usages.isEmpty() ? null : usages.get(usages.size() - 1).getVirtualKey();
    }

    public Long getTotalTokens() {
        return usages.stream().mapToLong(this::tokenCount).sum();
    }

    public Long getPromptTokens() {
        return usages.stream().mapToLong(u -> tokenCount(u.getPromptTokens())).sum();
    }

    public Long getCompletionTokens() {
        return usages.stream()
                .mapToLong(u -> tokenCount(u.getCompletionTokens()))
                .sum();
    }

    public Double getTotalSpend() {
        return usages.stream()
                .mapToDouble(u -> u.getTotalSpend() != null ? u.getTotalSpend() : 0.0)
                .sum();
    }

    public Instant getUsageLastUpdatedAt() {
        return usages.stream()
                .map(SandboxExecutionUsage::getUsageLastUpdatedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }

    private long tokenCount(SandboxExecutionUsage usage) {
        return tokenCount(usage.getTotalTokens());
    }

    private static long tokenCount(Long value) {
        return value != null ? value : 0L;
    }

    public ModelProvider getModelProvider() {
        return modelProvider;
    }

    public void setModelProvider(ModelProvider modelProvider) {
        this.modelProvider = modelProvider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getPublishedBranch() {
        return publishedBranch;
    }

    public void setPublishedBranch(String publishedBranch) {
        this.publishedBranch = publishedBranch;
    }

    public String getTargetBranch() {
        return targetBranch;
    }

    public void setTargetBranch(String targetBranch) {
        this.targetBranch = targetBranch;
    }

    public List<SandboxExecutionPublishedPr> getPublishedPrs() {
        return publishedPrs;
    }

    public SandboxExecutionPublishedPr getLatestPublishedPr() {
        return publishedPrs.isEmpty() ? null : publishedPrs.get(publishedPrs.size() - 1);
    }

    public void addPublishedPr(SandboxExecutionPublishedPr publishedPr) {
        publishedPrs.add(publishedPr);
    }
}
