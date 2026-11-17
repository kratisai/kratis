package com.kratisai.controlplane.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "team_wiki_batches")
public class TeamWikiBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false, foreignKey = @ForeignKey(name = "fk_team_wiki_batches_team"))
    private Team team;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IngestionStatus status;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Enumerated(EnumType.STRING)
    @Column(name = "build_mode", nullable = false, length = 20)
    private TeamWikiBuildMode buildMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, length = 20)
    private TeamWikiTriggerKind triggerKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_snapshot", columnDefinition = "jsonb")
    private TeamWikiSourceSnapshot sourceSnapshot;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "total_tool_calls")
    private Long totalToolCalls;

    @OneToMany(mappedBy = "teamWikiBatch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<TeamWikiBatchLog> logs = new ArrayList<>();

    @OneToMany(mappedBy = "teamWikiBatch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CtxWikiPage> wikiPages = new ArrayList<>();

    @OneToMany(mappedBy = "teamWikiBatch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CtxEmbedding> embeddings = new ArrayList<>();

    @OneToMany(mappedBy = "teamWikiBatch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<TeamWikiModelUsage> modelUsage = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        if (status == null) {
            status = IngestionStatus.QUEUED;
        }
    }

    public TeamWikiBatch() {}

    public TeamWikiBatch(
            Team team,
            TeamWikiBuildMode buildMode,
            TeamWikiTriggerKind triggerKind,
            TeamWikiSourceSnapshot sourceSnapshot) {
        this.team = Objects.requireNonNull(team, "team");
        this.buildMode = Objects.requireNonNull(buildMode, "buildMode");
        this.triggerKind = Objects.requireNonNull(triggerKind, "triggerKind");
        this.sourceSnapshot = sourceSnapshot;
        this.status = IngestionStatus.QUEUED;
        this.isActive = false;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Team getTeam() {
        return team;
    }

    public void setTeam(Team team) {
        this.team = team;
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

    public TeamWikiBuildMode getBuildMode() {
        return buildMode;
    }

    public void setBuildMode(TeamWikiBuildMode buildMode) {
        this.buildMode = buildMode;
    }

    public TeamWikiTriggerKind getTriggerKind() {
        return triggerKind;
    }

    public void setTriggerKind(TeamWikiTriggerKind triggerKind) {
        this.triggerKind = triggerKind;
    }

    public TeamWikiSourceSnapshot getSourceSnapshot() {
        return sourceSnapshot;
    }

    public void setSourceSnapshot(TeamWikiSourceSnapshot sourceSnapshot) {
        this.sourceSnapshot = sourceSnapshot;
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

    public List<TeamWikiBatchLog> getLogs() {
        return logs;
    }

    public void setLogs(List<TeamWikiBatchLog> logs) {
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

    public List<TeamWikiModelUsage> getModelUsage() {
        return modelUsage;
    }

    public void setModelUsage(List<TeamWikiModelUsage> modelUsage) {
        this.modelUsage = modelUsage;
    }

    public Optional<TeamWikiModelUsage> modelUsageFor(ModelKind kind) {
        return modelUsage.stream().filter(usage -> usage.getModelKind() == kind).findFirst();
    }
}
