package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_environments")
public class ExecutionEnvironment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "team_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_execution_environments_team"))
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id",
            nullable = true,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_execution_environments_user"))
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ExecutionEnvironmentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EnvironmentStatus status;

    @Column(name = "container_id", length = 255)
    private String containerId;

    @Column(name = "auth_token", length = 255)
    private String authToken;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id", foreignKey = @jakarta.persistence.ForeignKey(name = "fk_exec_envs_provider"))
    private EnvironmentProvider provider;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    @PrePersist
    protected void onCreate() {
        if (status == null) {
            status = EnvironmentStatus.DISCONNECTED;
        }
        if (type == null) {
            type = ExecutionEnvironmentType.SANDBOX;
        }
    }

    public ExecutionEnvironment() {}

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

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ExecutionEnvironmentType getType() {
        return type;
    }

    public void setType(ExecutionEnvironmentType type) {
        this.type = type;
    }

    public EnvironmentStatus getStatus() {
        return status;
    }

    public void setStatus(EnvironmentStatus status) {
        this.status = status;
    }

    public String getContainerId() {
        return containerId;
    }

    public void setContainerId(String containerId) {
        this.containerId = containerId;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public EnvironmentProvider getProvider() {
        return provider;
    }

    public void setProvider(EnvironmentProvider provider) {
        this.provider = provider;
    }

    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(Instant lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }
}
