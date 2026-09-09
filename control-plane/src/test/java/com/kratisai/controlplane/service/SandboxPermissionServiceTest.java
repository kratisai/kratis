package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.restdto.CreateSandboxPermissionRuleRequest;
import com.kratisai.controlplane.api.restdto.SandboxPermissionRuleDto;
import com.kratisai.controlplane.model.SandboxPermissionAction;
import com.kratisai.controlplane.model.SandboxPermissionRule;
import com.kratisai.controlplane.model.SandboxPermissionRuleType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.SandboxPermissionRuleRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.SandboxPermissionService.RuleDecision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class SandboxPermissionServiceTest {

    @Mock
    private SandboxPermissionRuleRepository ruleRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SandboxPermissionService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID teamId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SandboxPermissionService(
                ruleRepository, teamMemberRepository, teamRepository, userRepository, eventPublisher);
    }

    @Test
    void evaluate_denyMatches_returnsDeny() {
        SandboxPermissionRule denyRule = new SandboxPermissionRule();
        denyRule.setCommandRoot("rm -rf");
        denyRule.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        denyRule.setAction(SandboxPermissionAction.DENY);

        RuleDecision decision = SandboxPermissionService.evaluate(List.of(denyRule), "rm -rf /");
        assertThat(decision).isEqualTo(RuleDecision.DENY);
    }

    @Test
    void evaluate_denyAndAllowBothMatch_denyTakesImmediatePrecedence() {
        SandboxPermissionRule allowRule = new SandboxPermissionRule();
        allowRule.setCommandRoot("git");
        allowRule.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        allowRule.setAction(SandboxPermissionAction.ALLOW);

        SandboxPermissionRule denyRule = new SandboxPermissionRule();
        denyRule.setCommandRoot("git push --force");
        denyRule.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        denyRule.setAction(SandboxPermissionAction.DENY);

        // Deny rule first
        assertThat(SandboxPermissionService.evaluate(List.of(denyRule, allowRule), "git push --force origin main"))
                .isEqualTo(RuleDecision.DENY);

        // Allow rule first - Deny must still take precedence!
        assertThat(SandboxPermissionService.evaluate(List.of(allowRule, denyRule), "git push --force origin main"))
                .isEqualTo(RuleDecision.DENY);
    }

    @Test
    void evaluate_allowMatchesAndNoDeny_returnsAllow() {
        SandboxPermissionRule allowRule = new SandboxPermissionRule();
        allowRule.setCommandRoot("git status");
        allowRule.setRuleType(SandboxPermissionRuleType.EXACT);
        allowRule.setAction(SandboxPermissionAction.ALLOW);

        RuleDecision decision = SandboxPermissionService.evaluate(List.of(allowRule), "git status");
        assertThat(decision).isEqualTo(RuleDecision.ALLOW);
    }

    @Test
    void evaluate_noRuleMatches_returnsNone() {
        SandboxPermissionRule allowRule = new SandboxPermissionRule();
        allowRule.setCommandRoot("git status");
        allowRule.setRuleType(SandboxPermissionRuleType.EXACT);
        allowRule.setAction(SandboxPermissionAction.ALLOW);

        RuleDecision decision = SandboxPermissionService.evaluate(List.of(allowRule), "git push");
        assertThat(decision).isEqualTo(RuleDecision.NONE);
    }

    @Test
    void evaluate_emptyOrNullRules_returnsNone() {
        assertThat(SandboxPermissionService.evaluate(List.of(), "echo hello")).isEqualTo(RuleDecision.NONE);
        assertThat(SandboxPermissionService.evaluate(null, "echo hello")).isEqualTo(RuleDecision.NONE);
        assertThat(SandboxPermissionService.evaluate(List.of(), null)).isEqualTo(RuleDecision.NONE);
    }

    @Test
    void listRules_nonMember_throwsForbidden() {
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(false);

        assertThatThrownBy(() -> service.listRules(userId, teamId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void listRules_member_returnsList() {
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);

        Team team = new Team();
        team.setId(teamId);

        User user = new User();
        user.setId(userId);
        user.setDisplayName("Alice");

        SandboxPermissionRule rule = new SandboxPermissionRule();
        rule.setId(UUID.randomUUID());
        rule.setTeam(team);
        rule.setCommandRoot("npm test");
        rule.setRuleType(SandboxPermissionRuleType.EXACT);
        rule.setAction(SandboxPermissionAction.ALLOW);
        rule.setCreatedBy(user);
        rule.setCreatedAt(Instant.now());

        when(ruleRepository.findByTeamIdOrderByCreatedAtDesc(teamId)).thenReturn(List.of(rule));

        List<SandboxPermissionRuleDto> result = service.listRules(userId, teamId);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().commandRoot()).isEqualTo("npm test");
        assertThat(result.getFirst().createdByName()).isEqualTo("Alice");
    }

    @Test
    void createRule_duplicate_throwsConflict() {
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(new Team()));
        when(ruleRepository.existsByTeamIdAndCommandRootAndRuleTypeAndAction(
                        teamId, "ls", SandboxPermissionRuleType.EXACT, SandboxPermissionAction.ALLOW))
                .thenReturn(true);

        CreateSandboxPermissionRuleRequest req = new CreateSandboxPermissionRuleRequest(
                "ls", SandboxPermissionRuleType.EXACT, SandboxPermissionAction.ALLOW);

        assertThatThrownBy(() -> service.createRule(userId, teamId, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(ruleRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void createRule_success_savesAndPublishesEvent() {
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        Team team = new Team();
        team.setId(teamId);
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));

        when(ruleRepository.existsByTeamIdAndCommandRootAndRuleTypeAndAction(
                        teamId, "cargo build", SandboxPermissionRuleType.PREFIX_WILD, SandboxPermissionAction.ALLOW))
                .thenReturn(false);

        User user = new User();
        user.setId(userId);
        user.setDisplayName("Bob");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        when(ruleRepository.save(any(SandboxPermissionRule.class))).thenAnswer(inv -> {
            SandboxPermissionRule r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        CreateSandboxPermissionRuleRequest req = new CreateSandboxPermissionRuleRequest(
                "cargo build", SandboxPermissionRuleType.PREFIX_WILD, SandboxPermissionAction.ALLOW);
        SandboxPermissionRuleDto result = service.createRule(userId, teamId, req);

        assertThat(result.commandRoot()).isEqualTo("cargo build");
        assertThat(result.createdByName()).isEqualTo("Bob");

        ArgumentCaptor<TeamEntityChangedEvent> eventCaptor = ArgumentCaptor.forClass(TeamEntityChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().teamId()).isEqualTo(teamId);
        assertThat(eventCaptor.getValue().type()).isEqualTo(TeamEntityType.PERMISSIONS);
    }

    @Test
    void deleteRule_notFound_throwsNotFound() {
        UUID ruleId = UUID.randomUUID();
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        when(ruleRepository.findByIdAndTeamId(ruleId, teamId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteRule(userId, teamId, ruleId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(ruleRepository, never()).delete(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void deleteRule_success_deletesAndPublishesEvent() {
        UUID ruleId = UUID.randomUUID();
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);

        SandboxPermissionRule rule = new SandboxPermissionRule();
        rule.setId(ruleId);
        when(ruleRepository.findByIdAndTeamId(ruleId, teamId)).thenReturn(Optional.of(rule));

        service.deleteRule(userId, teamId, ruleId);

        verify(ruleRepository).delete(rule);
        ArgumentCaptor<TeamEntityChangedEvent> eventCaptor = ArgumentCaptor.forClass(TeamEntityChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().teamId()).isEqualTo(teamId);
        assertThat(eventCaptor.getValue().type()).isEqualTo(TeamEntityType.PERMISSIONS);
    }
}
