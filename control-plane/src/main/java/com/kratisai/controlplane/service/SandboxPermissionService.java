package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.restdto.CreateSandboxPermissionRuleRequest;
import com.kratisai.controlplane.api.restdto.SandboxPermissionRuleDto;
import com.kratisai.controlplane.model.SandboxPermissionAction;
import com.kratisai.controlplane.model.SandboxPermissionRule;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.SandboxPermissionRuleRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SandboxPermissionService {

    private static final Logger logger = LoggerFactory.getLogger(SandboxPermissionService.class);

    public enum RuleDecision {
        DENY,
        ALLOW,
        NONE
    }

    private final SandboxPermissionRuleRepository ruleRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public SandboxPermissionService(
            SandboxPermissionRuleRepository ruleRepository,
            TeamMemberRepository teamMemberRepository,
            TeamRepository teamRepository,
            UserRepository userRepository,
            ApplicationEventPublisher eventPublisher) {
        this.ruleRepository = ruleRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<SandboxPermissionRuleDto> listRules(UUID userId, UUID teamId) {
        verifyTeamMembership(userId, teamId);
        return ruleRepository.findByTeamIdOrderByCreatedAtDesc(teamId).stream()
                .map(SandboxPermissionRuleDto::from)
                .toList();
    }

    @Transactional
    public SandboxPermissionRuleDto createRule(UUID userId, UUID teamId, CreateSandboxPermissionRuleRequest request) {
        verifyTeamMembership(userId, teamId);
        Team team = teamRepository
                .findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));

        if (ruleRepository.existsByTeamIdAndCommandRootAndRuleTypeAndAction(
                teamId, request.commandRoot(), request.ruleType(), request.action())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rule already exists");
        }

        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        SandboxPermissionRule rule = new SandboxPermissionRule();
        rule.setTeam(team);
        rule.setCommandRoot(request.commandRoot());
        rule.setRuleType(request.ruleType());
        rule.setAction(request.action());
        rule.setCreatedBy(user);
        rule.setCreatedAt(Instant.now());

        SandboxPermissionRule saved = ruleRepository.save(rule);
        logger.info(
                "Created permission rule {} (action={}, type={}, command='{}') for team {} by user {}",
                saved.getId(),
                saved.getAction(),
                saved.getRuleType(),
                saved.getCommandRoot(),
                teamId,
                userId);

        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.PERMISSIONS));
        return SandboxPermissionRuleDto.from(saved);
    }

    @Transactional
    public void deleteRule(UUID userId, UUID teamId, UUID ruleId) {
        verifyTeamMembership(userId, teamId);
        SandboxPermissionRule rule = ruleRepository
                .findByIdAndTeamId(ruleId, teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Permission rule not found"));

        ruleRepository.delete(rule);
        logger.info("Deleted permission rule {} for team {} by user {}", ruleId, teamId, userId);

        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.PERMISSIONS));
    }

    public static RuleDecision evaluate(List<SandboxPermissionRule> rules, String command) {
        if (rules == null || command == null) {
            return RuleDecision.NONE;
        }
        boolean allowMatched = false;
        for (SandboxPermissionRule rule : rules) {
            if (rule.matches(command)) {
                if (rule.getAction() == SandboxPermissionAction.DENY) {
                    return RuleDecision.DENY;
                }
                if (rule.getAction() == SandboxPermissionAction.ALLOW) {
                    allowMatched = true;
                }
            }
        }
        return allowMatched ? RuleDecision.ALLOW : RuleDecision.NONE;
    }

    private void verifyTeamMembership(UUID userId, UUID teamId) {
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }
}
