package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hitl_rules")
public class HitlRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false, foreignKey = @ForeignKey(name = "fk_hitl_rules_team"))
    private Team team;

    @Column(name = "command_root", nullable = false, columnDefinition = "TEXT")
    private String commandRoot;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 50)
    private HitlRuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private HitlRuleAction action = HitlRuleAction.ALLOW;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", foreignKey = @ForeignKey(name = "fk_hitl_rules_user"))
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public HitlRule() {}

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

    public HitlRuleType getRuleType() {
        return ruleType;
    }

    public void setRuleType(HitlRuleType ruleType) {
        this.ruleType = ruleType;
    }

    public HitlRuleAction getAction() {
        return action;
    }

    public void setAction(HitlRuleAction action) {
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
        if (ruleType == HitlRuleType.EXACT) {
            return command.trim().equals(commandRoot.trim());
        } else if (ruleType == HitlRuleType.PREFIX_WILD) {
            String prefix = commandRoot.trim();
            if (prefix.endsWith("*")) {
                prefix = prefix.substring(0, prefix.length() - 1).trim();
            }
            String trimmed = command.trim();
            if (trimmed.equals(prefix)) {
                return true;
            }
            // Require a token boundary so a root like "npm run test" cannot match
            // "npm run tests-backdoor".
            return trimmed.startsWith(prefix)
                    && (prefix.isEmpty() || Character.isWhitespace(trimmed.charAt(prefix.length())));
        }
        return false;
    }
}
