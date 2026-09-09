package com.kratisai.controlplane.model;

import static java.util.Objects.requireNonNull;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "repositories",
        uniqueConstraints = {
            @UniqueConstraint(
                    columnNames = {"name", "team_id"},
                    name = "uq_repositories_name_team")
        })
public class Repository {

    @OneToMany(mappedBy = "repository", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IngestionBatch> ingestionBatches = new ArrayList<>();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(nullable = false, length = 255)
    private String branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "repository_type", nullable = false, length = 50)
    private RepositoryType repositoryType = RepositoryType.GENERIC;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credential_id", foreignKey = @ForeignKey(name = "fk_repositories_credential"))
    private RepoCredential credential;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "team_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_repositories_team"))
    private Team team;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (branch == null || branch.isEmpty()) {
            branch = "main";
        }
        if (repositoryType == null) {
            repositoryType = RepositoryType.GENERIC;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Repository() {}

    public Repository(String name, String url, String branch, RepositoryType repositoryType) {
        this.name = name;
        this.url = url;
        this.branch = branch;
        this.repositoryType = requireNonNull(repositoryType, "repositoryType must be provided explicitly");
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public RepositoryType getRepositoryType() {
        return repositoryType;
    }

    public void setRepositoryType(RepositoryType repositoryType) {
        this.repositoryType = requireNonNull(repositoryType, "repositoryType must be provided explicitly");
    }

    public RepoCredential getCredential() {
        return credential;
    }

    public void setCredential(RepoCredential credential) {
        this.credential = credential;
    }

    public Team getTeam() {
        return team;
    }

    public void setTeam(Team team) {
        this.team = team;
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

    public List<IngestionBatch> getIngestionBatches() {
        return ingestionBatches;
    }

    public void setIngestionBatches(List<IngestionBatch> ingestionBatches) {
        this.ingestionBatches = ingestionBatches;
    }
}
