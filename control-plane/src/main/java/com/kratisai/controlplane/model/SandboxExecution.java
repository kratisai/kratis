package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "harness", length = 50)
    private AgentHarness harness;

    @Column(name = "task_prompt", columnDefinition = "TEXT")
    private String taskPrompt;

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

    @Column(name = "published_pr_number")
    private Long publishedPrNumber;

    @Column(name = "published_pr_url", length = 1024)
    private String publishedPrUrl;

    @PrePersist
    protected void onCreate() {
        if (status == null) {
            status = SandboxExecutionStatus.RUNNING;
        }
        if (startedAt == null) {
            startedAt = Instant.now();
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
        this.status = status;
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

    public Long getTotalTokens() {
        return usage.getTotalTokens();
    }

    public void setTotalTokens(Long totalTokens) {
        usage.setTotalTokens(totalTokens);
    }

    public Long getPromptTokens() {
        return usage.getPromptTokens();
    }

    public void setPromptTokens(Long promptTokens) {
        usage.setPromptTokens(promptTokens);
    }

    public Long getCompletionTokens() {
        return usage.getCompletionTokens();
    }

    public void setCompletionTokens(Long completionTokens) {
        usage.setCompletionTokens(completionTokens);
    }

    public Double getTotalSpend() {
        return usage.getTotalSpend();
    }

    public void setTotalSpend(Double totalSpend) {
        usage.setTotalSpend(totalSpend);
    }

    public Instant getUsageLastUpdatedAt() {
        return usage.getUsageLastUpdatedAt();
    }

    public void setUsageLastUpdatedAt(Instant usageLastUpdatedAt) {
        usage.setUsageLastUpdatedAt(usageLastUpdatedAt);
    }

    public String getVirtualKey() {
        return usage.getVirtualKey();
    }

    public void setVirtualKey(String virtualKey) {
        usage.setVirtualKey(virtualKey);
    }

    public LlmUsage getUsage() {
        return usage;
    }

    public void setUsage(LlmUsage usage) {
        this.usage = usage;
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

    public Long getPublishedPrNumber() {
        return publishedPrNumber;
    }

    public void setPublishedPrNumber(Long publishedPrNumber) {
        this.publishedPrNumber = publishedPrNumber;
    }

    public String getPublishedPrUrl() {
        return publishedPrUrl;
    }

    public void setPublishedPrUrl(String publishedPrUrl) {
        this.publishedPrUrl = publishedPrUrl;
    }
}
