package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sandbox_permission_rules")
public class SandboxPermissionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false, foreignKey = @ForeignKey(name = "fk_sandbox_permission_rules_team"))
    private Team team;

    @Column(name = "command_root", nullable = false, columnDefinition = "TEXT")
    private String commandRoot;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 50)
    private SandboxPermissionRuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private SandboxPermissionAction action = SandboxPermissionAction.ALLOW;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", foreignKey = @ForeignKey(name = "fk_sandbox_permission_rules_user"))
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public SandboxPermissionRule() {}

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

    public String getCommandRoot() {
        return commandRoot;
    }

    public void setCommandRoot(String commandRoot) {
        this.commandRoot = commandRoot;
    }

    public SandboxPermissionRuleType getRuleType() {
        return ruleType;
    }

    public void setRuleType(SandboxPermissionRuleType ruleType) {
        this.ruleType = ruleType;
    }

    public SandboxPermissionAction getAction() {
        return action;
    }

    public void setAction(SandboxPermissionAction action) {
        this.action = action;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public boolean matches(String command) {
        if (command == null) {
            return false;
        }
        if (ruleType == SandboxPermissionRuleType.EXACT) {
            return command.trim().equals(commandRoot.trim());
        } else if (ruleType == SandboxPermissionRuleType.PREFIX_WILD) {
            String prefix = commandRoot.trim();
            if (prefix.endsWith("*")) {
                prefix = prefix.substring(0, prefix.length() - 1).trim();
            }
            return command.trim().startsWith(prefix);
        }
        return false;
    }
}
