package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.restdto.CreateHitlRuleRequest;
import com.kratisai.controlplane.api.restdto.HitlRuleDto;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.ToolKind;
import com.kratisai.controlplane.model.HitlRule;
import com.kratisai.controlplane.model.HitlRuleAction;
import com.kratisai.controlplane.model.HitlRuleType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.HitlRuleRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.command.ShellCommandSplitter;
import com.kratisai.controlplane.service.command.ShellCommandSplitter.ParseResult;
import com.kratisai.controlplane.service.command.ShellCommandSplitter.ParsedSegment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HitlRuleService {

    private static final Logger logger = LoggerFactory.getLogger(HitlRuleService.class);

    private final HitlRuleRepository ruleRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public HitlRuleService(
            HitlRuleRepository ruleRepository,
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
    public List<HitlRuleDto> listRules(UUID userId, UUID teamId) {
        verifyTeamMembership(userId, teamId);
        return ruleRepository.findByTeamIdOrderByCreatedAtDesc(teamId).stream()
                .map(HitlRuleDto::from)
                .toList();
    }

    @Transactional
    public HitlRuleDto createRule(UUID userId, UUID teamId, CreateHitlRuleRequest request) {
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

        HitlRule rule = new HitlRule();
        rule.setTeam(team);
        rule.setCommandRoot(request.commandRoot());
        rule.setRuleType(request.ruleType());
        rule.setAction(request.action());
        rule.setCreatedBy(user);
        rule.setCreatedAt(Instant.now());

        HitlRule saved = ruleRepository.save(rule);
        logger.info(
                "Created HITL rule {} (action={}, type={}, command='{}') for team {} by user {}",
                saved.getId(),
                saved.getAction(),
                saved.getRuleType(),
                saved.getCommandRoot(),
                teamId,
                userId);

        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.PERMISSIONS));
        return HitlRuleDto.from(saved);
    }

    @Transactional
    public void deleteRule(UUID userId, UUID teamId, UUID ruleId) {
        verifyTeamMembership(userId, teamId);
        HitlRule rule = ruleRepository
                .findByIdAndTeamId(ruleId, teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "HITL rule not found"));

        ruleRepository.delete(rule);
        logger.info("Deleted HITL rule {} for team {} by user {}", ruleId, teamId, userId);

        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.PERMISSIONS));
    }

    public static Optional<HitlResponse> autoResolve(List<HitlRule> rules, String command) {
        return autoResolve(rules, command, null);
    }

    /**
     * Initial auto-resolution from team rules; empty leaves the request pending for a human.
     * Non-command kinds carry synthesized command text, so only TOOL_KIND rules resolve them.
     * DENY always wins.
     */
    public static Optional<HitlResponse> autoResolve(List<HitlRule> rules, String command, String toolKind) {
        if (rules == null) {
            return Optional.empty();
        }
        String effectiveKind = ToolKind.effectiveWireValue(toolKind);
        boolean toolKindAllowed = false;
        for (HitlRule rule : rules) {
            if (rule.getRuleType() != HitlRuleType.TOOL_KIND
                    || !rule.getCommandRoot().trim().equalsIgnoreCase(effectiveKind)) {
                continue;
            }
            if (rule.getAction() == HitlRuleAction.DENY) {
                return Optional.of(HitlResponse.DECLINED);
            }
            if (rule.getAction() == HitlRuleAction.ALLOW) {
                toolKindAllowed = true;
            }
        }
        if (!ToolKind.isCommandLike(toolKind)) {
            return toolKindAllowed ? Optional.of(HitlResponse.APPROVED) : Optional.empty();
        }
        ParseResult parse = ShellCommandSplitter.parse(command);
        for (HitlRule rule : rules) {
            if (rule.getRuleType() == HitlRuleType.TOOL_KIND || rule.getAction() != HitlRuleAction.DENY) {
                continue;
            }
            if (rule.matches(parse.command())) {
                return Optional.of(HitlResponse.DECLINED);
            }
            for (ParsedSegment segment : parse.segments()) {
                if (rule.matches(segment.text())) {
                    return Optional.of(HitlResponse.DECLINED);
                }
            }
        }
        if (toolKindAllowed) {
            return Optional.of(HitlResponse.APPROVED);
        }
        if (!parse.fullyParsed() || parse.segments().isEmpty()) {
            return Optional.empty();
        }
        for (ParsedSegment segment : parse.segments()) {
            if (!segment.autoAllowable() || !anyAllowMatches(rules, segment.text())) {
                return Optional.empty();
            }
        }
        return Optional.of(HitlResponse.APPROVED);
    }

    public static boolean anyAllowMatches(List<HitlRule> rules, String text) {
        for (HitlRule rule : rules) {
            if (rule.getRuleType() != HitlRuleType.TOOL_KIND
                    && rule.getAction() == HitlRuleAction.ALLOW
                    && rule.matches(text)) {
                return true;
            }
        }
        return false;
    }

    private void verifyTeamMembership(UUID userId, UUID teamId) {
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }
}
