package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

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
        String root = commandRoot == null ? "" : commandRoot.trim();
        String trimmed = command.trim();
        if (ruleType == HitlRuleType.EXACT) {
            return trimmed.equals(root);
        }
        if (ruleType != HitlRuleType.PREFIX_WILD) {
            return false;
        }
        // An explicit '*' is a wildcard, not a literal prefix. The splitter derives such roots for
        // env assignments (VAR=*), generalized redirections (> ~/*), and timeouts (timeout *).
        if (root.contains("*")) {
            return matchesWildcard(root, trimmed);
        }
        if (trimmed.equals(root)) {
            return true;
        }
        if (!trimmed.startsWith(root)) {
            return false;
        }
        // A rule for a plain command (e.g. "cat") must not match a heredoc invocation ("cat <<")
        if (!root.contains("<<") && trimmed.substring(root.length()).trim().startsWith("<<")) {
            return false;
        }
        // Require a token boundary so a root like "npm run test" cannot match
        // "npm run tests-backdoor". Boundaries include whitespace, equals, colon, or comma.
        char next = trimmed.charAt(root.length());
        return Character.isWhitespace(next) || next == '=' || next == ':' || next == ',';
    }

    /** Wildcard roots match the whole text, with each {@code *} standing for any run of characters. */
    private static boolean matchesWildcard(String root, String command) {
        StringBuilder regex = new StringBuilder();
        int start = 0;
        for (int i = 0; i < root.length(); i++) {
            if (root.charAt(i) == '*') {
                regex.append(Pattern.quote(root.substring(start, i))).append(".*");
                start = i + 1;
            }
        }
        regex.append(Pattern.quote(root.substring(start)));
        return command.matches(regex.toString());
    }
}
