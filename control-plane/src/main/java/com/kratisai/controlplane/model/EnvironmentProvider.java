package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "environment_providers")
public class EnvironmentProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "team_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_env_providers_team"))
    private Team team;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "docker_image", length = 255)
    private String dockerImage;

    public EnvironmentProvider() {}

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDockerImage() {
        return dockerImage;
    }

    public void setDockerImage(String dockerImage) {
        this.dockerImage = dockerImage;
    }

    public ExecutionProviderType getType() {
        return ExecutionProviderType.DOCKER;
    }
}
