package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "teams")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "tavily_api_key", columnDefinition = "TEXT")
    private String tavilyApiKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingestion_provider_id", foreignKey = @ForeignKey(name = "fk_teams_ingestion_provider"))
    private ModelProvider ingestionProvider;

    @Column(name = "ingestion_model", length = 255)
    private String ingestionModel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "embedding_provider_id", foreignKey = @ForeignKey(name = "fk_teams_embedding_provider"))
    private ModelProvider embeddingProvider;

    @Column(name = "embedding_model", length = 255)
    private String embeddingModel;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "team", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TeamMember> members = new ArrayList<>();

    @OneToMany(mappedBy = "team", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Repository> repositories = new ArrayList<>();

    @OneToMany(mappedBy = "team", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ModelProvider> modelProviders = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Team() {}

    public Team(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public Team(String name, String description, boolean isDefault) {
        this.name = name;
        this.description = description;
        this.isDefault = isDefault;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTavilyApiKey() {
        return tavilyApiKey;
    }

    public void setTavilyApiKey(String tavilyApiKey) {
        this.tavilyApiKey = tavilyApiKey;
    }

    public ModelProvider getIngestionProvider() {
        return ingestionProvider;
    }

    public void setIngestionProvider(ModelProvider ingestionProvider) {
        this.ingestionProvider = ingestionProvider;
    }

    public String getIngestionModel() {
        return ingestionModel;
    }

    public void setIngestionModel(String ingestionModel) {
        this.ingestionModel = ingestionModel;
    }

    public ModelProvider getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(ModelProvider embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
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

    public List<TeamMember> getMembers() {
        return members;
    }

    public void setMembers(List<TeamMember> members) {
        this.members = members;
    }

    public List<Repository> getRepositories() {
        return repositories;
    }

    public void setRepositories(List<Repository> repositories) {
        this.repositories = repositories;
    }

    public void addMember(TeamMember member) {
        members.add(member);
        member.setTeam(this);
    }

    public void removeMember(TeamMember member) {
        members.remove(member);
        member.setTeam(null);
    }

    public void addRepository(Repository repository) {
        repositories.add(repository);
        repository.setTeam(this);
    }

    public void removeRepository(Repository repository) {
        repositories.remove(repository);
        repository.setTeam(null);
    }

    public List<ModelProvider> getModelProviders() {
        return modelProviders;
    }

    public void setModelProviders(List<ModelProvider> modelProviders) {
        this.modelProviders = modelProviders;
    }

    public void addModelProvider(ModelProvider provider) {
        modelProviders.add(provider);
        provider.setTeam(this);
    }

    public void removeModelProvider(ModelProvider provider) {
        modelProviders.remove(provider);
        provider.setTeam(null);
    }
}
