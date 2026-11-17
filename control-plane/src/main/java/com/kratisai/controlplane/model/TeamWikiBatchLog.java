package com.kratisai.controlplane.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "team_wiki_batch_logs")
public class TeamWikiBatchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "team_wiki_batch_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_team_wiki_batch_logs_batch"))
    private TeamWikiBatch teamWikiBatch;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(nullable = false, length = 20)
    private String level = "INFO";

    @Column(nullable = false, length = 50)
    private String step;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public TeamWikiBatchLog() {}

    public TeamWikiBatchLog(TeamWikiBatch teamWikiBatch, UUID teamId, String level, String step, String message) {
        this.teamWikiBatch = teamWikiBatch;
        this.teamId = teamId;
        this.level = level;
        this.step = step;
        this.message = message;
    }

    public UUID getId() {
        return id;
    }

    public TeamWikiBatch getTeamWikiBatch() {
        return teamWikiBatch;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public String getLevel() {
        return level;
    }

    public String getStep() {
        return step;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
