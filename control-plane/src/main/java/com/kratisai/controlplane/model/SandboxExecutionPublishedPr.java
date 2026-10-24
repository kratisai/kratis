package com.kratisai.controlplane.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.Objects;

/**
 * Historical record of a pull request published from a sandbox execution. The git remote is the
 * source of truth for whether the PR is still open; this row is the UI-rendering cache.
 */
@Embeddable
public class SandboxExecutionPublishedPr {

    @Column(name = "pr_number", nullable = false)
    private Long prNumber;

    @Column(name = "pr_url", nullable = false, length = 1024)
    private String prUrl;

    @Column(name = "head_branch", nullable = false, length = 255)
    private String headBranch;

    @Column(name = "base_branch", nullable = false, length = 255)
    private String baseBranch;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private PublishedPrStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SandboxExecutionPublishedPr() {}

    public SandboxExecutionPublishedPr(
            Long prNumber,
            String prUrl,
            String headBranch,
            String baseBranch,
            PublishedPrStatus status,
            Instant createdAt) {
        this.prNumber = Objects.requireNonNull(prNumber, "prNumber is required");
        this.prUrl = Objects.requireNonNull(prUrl, "prUrl is required");
        this.headBranch = Objects.requireNonNull(headBranch, "headBranch is required");
        this.baseBranch = Objects.requireNonNull(baseBranch, "baseBranch is required");
        this.status = Objects.requireNonNull(status, "status is required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
    }

    public Long getPrNumber() {
        return prNumber;
    }

    public String getPrUrl() {
        return prUrl;
    }

    public String getHeadBranch() {
        return headBranch;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public PublishedPrStatus getStatus() {
        return status;
    }

    public void setStatus(PublishedPrStatus status) {
        this.status = Objects.requireNonNull(status, "status is required");
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SandboxExecutionPublishedPr other)) {
            return false;
        }
        return Objects.equals(prNumber, other.prNumber)
                && Objects.equals(headBranch, other.headBranch)
                && Objects.equals(createdAt, other.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prNumber, headBranch, createdAt);
    }
}
