package com.kratisai.controlplane.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;

@Embeddable
public class LlmUsage {

    @Column(name = "virtual_key", columnDefinition = "TEXT")
    private String virtualKey;

    @Column(name = "total_spend")
    private Double totalSpend = 0.0;

    @Column(name = "total_tokens")
    private Long totalTokens = 0L;

    @Column(name = "prompt_tokens")
    private Long promptTokens = 0L;

    @Column(name = "completion_tokens")
    private Long completionTokens = 0L;

    @Column(name = "usage_last_updated_at")
    private Instant usageLastUpdatedAt;

    public LlmUsage() {}

    public static LlmUsage withKey(String virtualKey) {
        LlmUsage usage = new LlmUsage();
        usage.virtualKey = virtualKey;
        return usage;
    }

    public void apply(LlmUsageSnapshot snapshot) {
        this.totalSpend = snapshot.spend() != null ? snapshot.spend() : 0.0;
        this.totalTokens = snapshot.totalTokens() != null ? snapshot.totalTokens() : 0L;
        this.promptTokens = snapshot.promptTokens() != null ? snapshot.promptTokens() : 0L;
        this.completionTokens = snapshot.completionTokens() != null ? snapshot.completionTokens() : 0L;
        this.usageLastUpdatedAt = Instant.now();
    }

    public String getVirtualKey() {
        return virtualKey;
    }

    public void setVirtualKey(String virtualKey) {
        this.virtualKey = virtualKey;
    }

    public Double getTotalSpend() {
        return totalSpend;
    }

    public void setTotalSpend(Double totalSpend) {
        this.totalSpend = totalSpend;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Instant getUsageLastUpdatedAt() {
        return usageLastUpdatedAt;
    }

    public void setUsageLastUpdatedAt(Instant usageLastUpdatedAt) {
        this.usageLastUpdatedAt = usageLastUpdatedAt;
    }
}
