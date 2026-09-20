package com.kratisai.controlplane.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.restdto.*;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.ApprovalOptionKind;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlRequiredResult;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.PermissionOption;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.CanvasService;
import com.kratisai.controlplane.service.ExecutionEnvironmentService;
import com.kratisai.controlplane.service.PendingHitlRegistry;
import com.kratisai.controlplane.service.SandboxExecutionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.WebSocketSession;

@SpringIntegrationTest
class SandboxHitlControllerTest {

    private static final List<PermissionOption> OPTIONS = List.of(
            new PermissionOption("allow-once", "Allow once", ApprovalOptionKind.ALLOW_ONCE),
            new PermissionOption("allow-always", "Always allow", ApprovalOptionKind.ALLOW_ALWAYS),
            new PermissionOption("reject-once", "Reject", ApprovalOptionKind.REJECT_ONCE));

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private HitlRuleRepository hitlRuleRepository;

    @Autowired
    private ExecutionEnvironmentService executionEnvironmentService;

    @Autowired
    private SandboxExecutionService sandboxExecutionService;

    @Autowired
    private CanvasService canvasService;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private SandboxExecutionActivityRepository activityRepository;

    @Autowired
    private PendingHitlRegistry pendingHitlRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;
    private String userAuthToken;
    private UUID executionId;
    private UUID teamId;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();
        pendingHitlRegistry.clearAll();

        mockMvc =
                MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        RegisterUserRequest registerRequest =
                new RegisterUserRequest("hitluser@example.com", "password123", "Hitl User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest("hitluser@example.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        userAuthToken = objectMapper
                .readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        user = userRepository.findByEmail("hitluser@example.com").orElseThrow();
        Team team = teamMemberRepository.findByUserId(user.getId()).getFirst().getTeam();
        teamId = team.getId();
        ChatEntity chat = chatRepository.save(new ChatEntity(team, user, "HITL Test Chat"));

        Repository repository =
                new Repository("hitl-repo", "https://example.com/hitl-repo.git", "main", RepositoryType.GENERIC);
        repository.setTeam(team);
        repository = repositoryRepository.save(repository);
        canvasService.createCanvas(
                chat.getId(), "test-plan", "Test Plan", "# Test Plan", CanvasType.SPEC, repository, null);

        var createRequest = new CreateExecutionEnvironmentRequest("Test Connector");
        var response = executionEnvironmentService.createConnector(user.getId(), team.getId(), createRequest);
        UUID envId = response.environment().id();

        ModelProvider mp = new ModelProvider("Test Provider", ProviderType.OPENAI, "sk-key", null);
        mp.setTeam(team);
        mp.setModels(List.of(new ProviderModel("gpt-4o", ModelKind.CHAT)));
        mp = modelProviderRepository.save(mp);

        var createExecRequest = new CreateSandboxExecutionRequest(
                null, envId, AgentHarness.OPENCODE, "test-plan", mp.getId(), "gpt-4o");
        var execDto = sandboxExecutionService.createExecution(user.getId(), chat.getId(), createExecRequest);
        executionId = execDto.id();
    }

    private void registerPendingApproval() {
        pendingHitlRegistry.register(
                executionId,
                new PendingHitlRegistry.PendingHitl(
                        new ExecutionHitlRequiredResult(
                                executionId,
                                "tool-call-42",
                                HitlKind.APPROVAL,
                                "Remove",
                                "rm -rf /",
                                null,
                                "Remove",
                                "execute",
                                OPTIONS,
                                null,
                                null),
                        Mockito.mock(WebSocketSession.class),
                        "req-1",
                        Instant.now(),
                        teamId));
    }

    private void registerPendingQuestion() {
        pendingHitlRegistry.register(
                executionId,
                new PendingHitlRegistry.PendingHitl(
                        new ExecutionHitlRequiredResult(
                                executionId,
                                "el-1",
                                HitlKind.QUESTION,
                                "Choose a deployment target",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                Map.of("type", "object")),
                        Mockito.mock(WebSocketSession.class),
                        "req-1",
                        Instant.now(),
                        teamId));
    }

    @Test
    void resolveApproval_allowOnce_returns204() throws Exception {
        registerPendingApproval();
        ResolveHitlRequest request =
                new ResolveHitlRequest(executionId, "tool-call-42", HitlResponse.APPROVED, "allow-once", null);

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
    }

    @Test
    void resolveApproval_updatesPendingActivityRowWithSelectedOption() throws Exception {
        activityRepository.save(new SandboxExecutionActivity(
                executionId, 1, "tool-call-42", ActivityType.COMMAND, ActivityStatus.PENDING, "rm -rf /", null));
        registerPendingApproval();

        ResolveHitlRequest request =
                new ResolveHitlRequest(executionId, "tool-call-42", HitlResponse.APPROVED, "allow-once", null);
        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        SandboxExecutionActivity row = activityRepository
                .findFirstByExecutionIdAndActionIdOrderBySequenceDesc(executionId, "tool-call-42")
                .orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ActivityStatus.IN_PROGRESS);
    }

    @Test
    void resolveApproval_rejectOption_marksActivityFailed() throws Exception {
        activityRepository.save(new SandboxExecutionActivity(
                executionId, 1, "tool-call-42", ActivityType.COMMAND, ActivityStatus.PENDING, "rm -rf /", null));
        registerPendingApproval();

        ResolveHitlRequest request =
                new ResolveHitlRequest(executionId, "tool-call-42", HitlResponse.DECLINED, "reject-once", null);
        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        SandboxExecutionActivity row = activityRepository
                .findFirstByExecutionIdAndActionIdOrderBySequenceDesc(executionId, "tool-call-42")
                .orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ActivityStatus.FAILED);
    }

    @Test
    void resolveApproval_withRememberedRules_createsPrefixWildRulesAndReturns204() throws Exception {
        registerPendingApproval();
        ResolveHitlRequest request = new ResolveHitlRequest(
                executionId,
                "tool-call-42",
                HitlResponse.APPROVED,
                "allow-once",
                null,
                List.of(new CreateHitlRuleRequest("rm -rf", null, HitlRuleAction.ALLOW)));

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        long ruleCount = hitlRuleRepository.count();
        assertThat(ruleCount).isEqualTo(1);
        HitlRule saved = hitlRuleRepository.findAll().getFirst();
        assertThat(saved.getAction()).isEqualTo(HitlRuleAction.ALLOW);
        assertThat(saved.getRuleType()).isEqualTo(HitlRuleType.PREFIX_WILD);
        assertThat(saved.getCommandRoot()).isEqualTo("rm -rf");
        assertThat(saved.getCreatedBy()).isNotNull();
        assertThat(saved.getCreatedBy().getId()).isEqualTo(user.getId());

        registerPendingApproval();
        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        assertThat(hitlRuleRepository.count()).isEqualTo(1);
    }

    @Test
    void resolveApproval_declinedWithDenyRule_persistsDenyRule() throws Exception {
        registerPendingApproval();
        ResolveHitlRequest request = new ResolveHitlRequest(
                executionId,
                "tool-call-42",
                HitlResponse.DECLINED,
                "reject-once",
                null,
                List.of(new CreateHitlRuleRequest("rm -rf", null, HitlRuleAction.DENY)));

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        List<HitlRule> rules = hitlRuleRepository.findByTeamId(teamId);
        assertThat(rules).hasSize(1);
        assertThat(rules.getFirst().getAction()).isEqualTo(HitlRuleAction.DENY);
        assertThat(rules.getFirst().getRuleType()).isEqualTo(HitlRuleType.PREFIX_WILD);
        assertThat(rules.getFirst().getCommandRoot()).isEqualTo("rm -rf");
    }

    @Test
    void resolveApproval_rememberedRule_normalizesWhitespaceAndRejectsCancelled() throws Exception {
        registerPendingApproval();
        ResolveHitlRequest request = new ResolveHitlRequest(
                executionId,
                "tool-call-42",
                HitlResponse.APPROVED,
                "allow-once",
                null,
                List.of(new CreateHitlRuleRequest("  npm   run\ttest  ", null, null)));

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        List<HitlRule> rules = hitlRuleRepository.findByTeamId(teamId);
        assertThat(rules).hasSize(1);
        assertThat(rules.getFirst().getCommandRoot()).isEqualTo("npm run test");
        assertThat(rules.getFirst().getAction()).isEqualTo(HitlRuleAction.ALLOW);

        registerPendingApproval();
        ResolveHitlRequest cancelled = new ResolveHitlRequest(
                executionId,
                "tool-call-42",
                HitlResponse.CANCELLED,
                null,
                null,
                List.of(new CreateHitlRuleRequest("git push", null, HitlRuleAction.ALLOW)));
        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(cancelled)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resolveApproval_unknownOptionId_returns400() throws Exception {
        registerPendingApproval();
        ResolveHitlRequest request =
                new ResolveHitlRequest(executionId, "tool-call-42", HitlResponse.APPROVED, "not-offered", null);

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resolveApproval_noPendingRequest_returns404() throws Exception {
        ResolveHitlRequest request =
                new ResolveHitlRequest(executionId, "tool-call-42", HitlResponse.APPROVED, "allow-once", null);

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolveApproval_notFound_returns404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        ResolveHitlRequest request =
                new ResolveHitlRequest(nonExistentId, "tool-call-42", HitlResponse.APPROVED, "allow-once", null);

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolveApproval_forbidden_nonTeamMember_returns403() throws Exception {
        RegisterUserRequest registerRequest2 =
                new RegisterUserRequest("hitl-outsider@example.com", "password123", "Outsider");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest2)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest2 = new LoginRequest("hitl-outsider@example.com", "password123");
        MvcResult loginResult2 = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest2)))
                .andExpect(status().isOk())
                .andReturn();

        String outsiderToken = objectMapper
                .readTree(loginResult2.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        registerPendingApproval();
        ResolveHitlRequest request =
                new ResolveHitlRequest(executionId, "tool-call-42", HitlResponse.APPROVED, "allow-once", null);

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void resolveApproval_unauthenticated_returns403() throws Exception {
        ResolveHitlRequest request =
                new ResolveHitlRequest(executionId, "tool-call-42", HitlResponse.APPROVED, "allow-once", null);

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void resolveApproval_cancelled_marksActivityFailed() throws Exception {
        activityRepository.save(new SandboxExecutionActivity(
                executionId, 1, "tool-call-42", ActivityType.COMMAND, ActivityStatus.PENDING, "rm -rf /", null));
        registerPendingApproval();

        ResolveHitlRequest request =
                new ResolveHitlRequest(executionId, "tool-call-42", HitlResponse.CANCELLED, null, null);
        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        SandboxExecutionActivity row = activityRepository
                .findFirstByExecutionIdAndActionIdOrderBySequenceDesc(executionId, "tool-call-42")
                .orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ActivityStatus.FAILED);
    }

    @Test
    void resolveApproval_cancelled_notFound_returns404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        ResolveHitlRequest request =
                new ResolveHitlRequest(nonExistentId, "tool-call-42", HitlResponse.CANCELLED, null, null);

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolveApproval_cancelled_forbidden_nonTeamMember_returns403() throws Exception {
        RegisterUserRequest registerRequest2 =
                new RegisterUserRequest("hitl-outsider2@example.com", "password123", "Outsider 2");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest2)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest2 = new LoginRequest("hitl-outsider2@example.com", "password123");
        MvcResult loginResult2 = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest2)))
                .andExpect(status().isOk())
                .andReturn();

        String outsiderToken = objectMapper
                .readTree(loginResult2.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        registerPendingApproval();
        ResolveHitlRequest request =
                new ResolveHitlRequest(executionId, "tool-call-42", HitlResponse.CANCELLED, null, null);

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void resolveQuestion_answered_returns204AndRemovesPending() throws Exception {
        registerPendingQuestion();
        ResolveHitlRequest request =
                new ResolveHitlRequest(executionId, "el-1", HitlResponse.ANSWERED, null, Map.of("target", "staging"));

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        assertThat(pendingHitlRegistry.getPending()).isEmpty();
    }

    @Test
    void resolveQuestion_declined_returns204() throws Exception {
        registerPendingQuestion();
        ResolveHitlRequest request = new ResolveHitlRequest(executionId, "el-1", HitlResponse.DECLINED, null, null);

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
    }

    @Test
    void resolveQuestion_noPending_returns404() throws Exception {
        ResolveHitlRequest request =
                new ResolveHitlRequest(executionId, "el-missing", HitlResponse.ANSWERED, null, null);

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolveQuestion_executionNotFound_returns404() throws Exception {
        ResolveHitlRequest request =
                new ResolveHitlRequest(UUID.randomUUID(), "el-1", HitlResponse.ANSWERED, null, null);

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolveQuestion_nonTeamMember_returns403() throws Exception {
        RegisterUserRequest registerRequest2 =
                new RegisterUserRequest("hitl-q-outsider@example.com", "password123", "Q Outsider");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest2)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest2 = new LoginRequest("hitl-q-outsider@example.com", "password123");
        MvcResult loginResult2 = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest2)))
                .andExpect(status().isOk())
                .andReturn();

        String outsiderToken = objectMapper
                .readTree(loginResult2.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        registerPendingQuestion();
        ResolveHitlRequest request = new ResolveHitlRequest(executionId, "el-1", HitlResponse.ANSWERED, null, null);

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void resolveQuestion_unauthenticated_returns403() throws Exception {
        ResolveHitlRequest request = new ResolveHitlRequest(executionId, "el-1", HitlResponse.ANSWERED, null, null);

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void resolveQuestion_invalidRequest_returns400() throws Exception {
        String invalidBody = "{\"hitlId\":\"el-1\"}";

        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }
}
