package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.CreateSandboxPermissionRuleRequest;
import com.kratisai.controlplane.model.SandboxPermissionAction;
import com.kratisai.controlplane.model.SandboxPermissionRule;
import com.kratisai.controlplane.model.SandboxPermissionRuleType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.SandboxPermissionRuleRepository;
import com.kratisai.controlplane.service.JwtService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringIntegrationTest
class SandboxPermissionControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private SandboxPermissionRuleRepository ruleRepository;

    @Autowired
    private JwtService jwtService;

    private String memberToken;
    private String nonMemberToken;
    private Team team;
    private User memberUser;
    private User nonMemberUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        databaseCleaner.cleanAll();

        TestDataFactory.TestContext memberCtx = testDataFactory.createUserAndTeam();
        team = memberCtx.team();
        memberUser = memberCtx.user();
        memberToken = jwtService.generateAccessToken(memberUser.getId(), memberUser.getEmail());

        TestDataFactory.TestContext nonMemberCtx = testDataFactory.createUserAndTeam();
        nonMemberUser = nonMemberCtx.user();
        nonMemberToken = jwtService.generateAccessToken(nonMemberUser.getId(), nonMemberUser.getEmail());
    }

    @Test
    void listRules_nonMember_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/permissions", team.getId())
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void listRules_member_returnsRulesOrderedByCreatedAtDesc() throws Exception {
        SandboxPermissionRule rule1 = new SandboxPermissionRule();
        rule1.setTeam(team);
        rule1.setCommandRoot("echo");
        rule1.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        rule1.setAction(SandboxPermissionAction.ALLOW);
        rule1.setCreatedBy(memberUser);
        rule1.setCreatedAt(Instant.now().minusSeconds(100));
        ruleRepository.save(rule1);

        SandboxPermissionRule rule2 = new SandboxPermissionRule();
        rule2.setTeam(team);
        rule2.setCommandRoot("rm -rf");
        rule2.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        rule2.setAction(SandboxPermissionAction.DENY);
        rule2.setCreatedBy(memberUser);
        rule2.setCreatedAt(Instant.now());
        ruleRepository.save(rule2);

        mockMvc.perform(get("/api/v1/teams/{teamId}/permissions", team.getId())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(rule2.getId().toString()))
                .andExpect(jsonPath("$[0].commandRoot").value("rm -rf"))
                .andExpect(jsonPath("$[0].action").value("DENY"))
                .andExpect(jsonPath("$[0].createdByName").value(memberUser.getDisplayName()))
                .andExpect(jsonPath("$[1].id").value(rule1.getId().toString()))
                .andExpect(jsonPath("$[1].commandRoot").value("echo"))
                .andExpect(jsonPath("$[1].action").value("ALLOW"));
    }

    @Test
    void createRule_nonMember_returns403() throws Exception {
        CreateSandboxPermissionRuleRequest request = new CreateSandboxPermissionRuleRequest(
                "npm test", SandboxPermissionRuleType.EXACT, SandboxPermissionAction.ALLOW);

        mockMvc.perform(post("/api/v1/teams/{teamId}/permissions", team.getId())
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createRule_member_success() throws Exception {
        CreateSandboxPermissionRuleRequest request = new CreateSandboxPermissionRuleRequest(
                "npm test", SandboxPermissionRuleType.EXACT, SandboxPermissionAction.ALLOW);

        mockMvc.perform(post("/api/v1/teams/{teamId}/permissions", team.getId())
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commandRoot").value("npm test"))
                .andExpect(jsonPath("$.ruleType").value("EXACT"))
                .andExpect(jsonPath("$.action").value("ALLOW"))
                .andExpect(
                        jsonPath("$.createdByUserId").value(memberUser.getId().toString()))
                .andExpect(jsonPath("$.createdByName").value(memberUser.getDisplayName()));

        List<SandboxPermissionRule> saved = ruleRepository.findByTeamId(team.getId());
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getCommandRoot()).isEqualTo("npm test");
        assertThat(saved.getFirst().getAction()).isEqualTo(SandboxPermissionAction.ALLOW);
        assertThat(saved.getFirst().getCreatedBy().getId()).isEqualTo(memberUser.getId());
    }

    @Test
    void createRule_duplicate_returns409() throws Exception {
        CreateSandboxPermissionRuleRequest request = new CreateSandboxPermissionRuleRequest(
                "git status", SandboxPermissionRuleType.EXACT, SandboxPermissionAction.ALLOW);

        mockMvc.perform(post("/api/v1/teams/{teamId}/permissions", team.getId())
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/teams/{teamId}/permissions", team.getId())
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteRule_nonMember_returns403() throws Exception {
        SandboxPermissionRule rule = new SandboxPermissionRule();
        rule.setTeam(team);
        rule.setCommandRoot("echo");
        rule.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        rule.setAction(SandboxPermissionAction.ALLOW);
        rule.setCreatedBy(memberUser);
        rule.setCreatedAt(Instant.now());
        rule = ruleRepository.save(rule);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/permissions/{ruleId}", team.getId(), rule.getId())
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteRule_member_success() throws Exception {
        SandboxPermissionRule rule = new SandboxPermissionRule();
        rule.setTeam(team);
        rule.setCommandRoot("echo");
        rule.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        rule.setAction(SandboxPermissionAction.ALLOW);
        rule.setCreatedBy(memberUser);
        rule.setCreatedAt(Instant.now());
        rule = ruleRepository.save(rule);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/permissions/{ruleId}", team.getId(), rule.getId())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNoContent());

        assertThat(ruleRepository.findById(rule.getId())).isEmpty();
    }

    @Test
    void deleteRule_notFound_returns404() throws Exception {
        UUID nonExistentRuleId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/teams/{teamId}/permissions/{ruleId}", team.getId(), nonExistentRuleId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRule_otherTeamRule_returns404() throws Exception {
        TestDataFactory.TestContext otherCtx = testDataFactory.createUserAndTeam();
        SandboxPermissionRule rule = new SandboxPermissionRule();
        rule.setTeam(otherCtx.team());
        rule.setCommandRoot("echo");
        rule.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        rule.setAction(SandboxPermissionAction.ALLOW);
        rule.setCreatedBy(otherCtx.user());
        rule.setCreatedAt(Instant.now());
        rule = ruleRepository.save(rule);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/permissions/{ruleId}", team.getId(), rule.getId())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }
}
