package com.kratisai.controlplane.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Minimal immutable audit record of a single publish action from an execution.
 */
@Entity
@Table(name = "change_sets")
public class ChangeSet {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false, updatable = false)
    private SandboxExecution execution;

    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    @Column(name = "base_commit_sha", length = 64, nullable = false, updatable = false)
    private String baseCommitSha;

    @Column(name = "pushed_commit_sha", length = 64, nullable = false, updatable = false)
    private String pushedCommitSha;

    @Column(name = "branch_name", length = 255, nullable = false, updatable = false)
    private String branchName;

    @Column(name = "pr_number", updatable = false)
    private Long prNumber;

    @Column(name = "pr_url", length = 1024, updatable = false)
    private String prUrl;

    @Column(name = "base_branch", length = 255, updatable = false)
    private String baseBranch;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChangeSet() {}

    public ChangeSet(
            SandboxExecution execution,
            int seq,
            String baseCommitSha,
            String pushedCommitSha,
            String branchName,
            Long prNumber,
            String prUrl,
            String baseBranch,
            Instant createdAt) {
        this.execution = Objects.requireNonNull(execution, "execution is required");
        this.seq = seq;
        this.baseCommitSha = Objects.requireNonNull(baseCommitSha, "baseCommitSha is required");
        this.pushedCommitSha = Objects.requireNonNull(pushedCommitSha, "pushedCommitSha is required");
        this.branchName = Objects.requireNonNull(branchName, "branchName is required");
        this.prNumber = prNumber;
        this.prUrl = prUrl;
        this.baseBranch = baseBranch;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public SandboxExecution getExecution() {
        return execution;
    }

    public int getSeq() {
        return seq;
    }

    public String getBaseCommitSha() {
        return baseCommitSha;
    }

    public String getPushedCommitSha() {
        return pushedCommitSha;
    }

    public String getBranchName() {
        return branchName;
    }

    public Long getPrNumber() {
        return prNumber;
    }

    public String getPrUrl() {
        return prUrl;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
