package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(
        name = "team_members",
        uniqueConstraints = {
            @UniqueConstraint(
                    columnNames = {"user_id", "team_id"},
                    name = "uq_team_members_user_team")
        })
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_team_members_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "team_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_team_members_team"))
    private Team team;

    @Column(nullable = false, length = 50)
    private String role;

    public TeamMember() {}

    public TeamMember(User user, Team team, String role) {
        this.user = user;
        this.team = team;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Team getTeam() {
        return team;
    }

    public void setTeam(Team team) {
        this.team = team;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
