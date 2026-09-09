package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.restdto.*;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.CanvasService;
import com.kratisai.controlplane.service.ExecutionEnvironmentService;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.SandboxExecutionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/**
 * E2E integration test verifying the full activity stream with HITL approval flow. Validates that
 * activity events, permission events, and completion events are correctly interleaved and
 * broadcast to UI clients in the expected order.
 *
 * <p>Test scenario:
 *
 * <ol>
 *   <li>Setup: Create environment, execution, connect UI client + sidecar
 *   <li>Sidecar sends env.activity events (thinking, tool_execution)
 *   <li>Assert UI client receives execution_activity broadcasts
 *   <li>Sidecar sends env.hitl_request (command requiring HITL)
 *   <li>Assert UI client receives execution_hitl_required
 *   <li>REST approve the permission
 *   <li>Assert UI client receives execution_hitl_resolved
 *   <li>Sidecar sends more env.activity events
 *   <li>Assert UI client receives continued activity stream
 *   <li>Sidecar sends env.complete
 *   <li>Assert UI client receives execution_complete
 * </ol>
 */
@SpringIntegrationTest
class ActivityStreamIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private LiteLLMProvisioningService liteLLMProvisioningService;

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String userAuthToken;
    private User user;
    private Team team;
    private ModelProvider modelProvider;
    private ChatEntity chat;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();

        MockMvc mockMvc =
                MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        RegisterUserRequest registerRequest =
                new RegisterUserRequest("streamuser@example.com", "password123", "Stream User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest("streamuser@example.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        userAuthToken = objectMapper
                .readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        user = userRepository.findByEmail("streamuser@example.com").orElseThrow();
        team = teamMemberRepository.findByUserId(user.getId()).getFirst().getTeam();

        // Create a ModelProvider for the team
        ModelProvider provider = new ModelProvider("test-provider", ProviderType.OPENAI, "dummy-api-key", null);
        provider.setTeam(team);
        provider.setModels(List.of(new ProviderModel("gpt-4o", ModelKind.CHAT)));
        modelProvider = modelProviderRepository.save(provider);
        liteLLMProvisioningService.provisionModel(modelProvider);

        chat = chatRepository.save(new ChatEntity(team, user, "Activity Stream Test Session"));
    }

    private Repository createExecutionRepository(String name) {
        Repository repository =
                new Repository(name, "https://example.com/" + name + ".git", "main", RepositoryType.GENERIC);
        repository.setTeam(team);
        return repositoryRepository.save(repository);
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void testActivityStreamWithHitlApproval() throws Exception {
        // 1. Create a workspace connector environment
        var createRequest = new CreateExecutionEnvironmentRequest("Activity Stream Connector");
        var response = executionEnvironmentService.createConnector(user.getId(), team.getId(), createRequest);
        var envDto = response.environment();
        UUID envId = envDto.id();
        String connectorToken = envDto.authToken();

        // 2. Create a SPEC canvas bound to a repository and sandbox execution with a
        // command requiring HITL approval
        canvasService.createCanvas(
                chat.getId(),
                "test-plan-stream",
                "Test Plan",
                "# Test Plan",
                CanvasType.SPEC,
                createExecutionRepository("stream-repo"),
                null);
        var createExecRequest = new CreateSandboxExecutionRequest(
                null, envId, null, AgentHarness.OPENCODE, "test-plan-stream", modelProvider.getId(), "gpt-4o");
        var execDto = sandboxExecutionService.createExecution(user.getId(), chat.getId(), createExecRequest);
        UUID executionId = execDto.id();

        // 3. Connect UI client subscribed to team events
        //    Expect: 2 activity events before permission, 1 permission_required,
        //    1 permission_resolved, 1 execution_complete
        //    (3rd activity event verified via awaitMessageContaining after permission flow)
        ClientWebSocketFixture clientFixture = new ClientWebSocketFixture(userAuthToken, team.getId())
                .expectTrigger("execution_activity", 2)
                .expectTrigger("execution_hitl_required", 1)
                .expectTrigger("execution_hitl_resolved", 1)
                .expectTrigger("execution_complete", 1);

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession clientSession = client.execute(clientFixture, "ws://localhost:" + port + "/ws/client")
                .get(5, TimeUnit.SECONDS);

        // Wait for team subscription acknowledgement before driving the sidecar flow
        assertThat(clientFixture.awaitSubscription(5, TimeUnit.SECONDS))
                .as("UI client should complete team subscription")
                .isTrue();

        // 4. Connect Sidecar — override default rules to prevent auto-complete and auto-output
        SidecarWebSocketFixture sidecarFixture = new SidecarWebSocketFixture(connectorToken)
                // Override default env.acp_prompt → output+complete auto-response
                .whenMethod("env.acp_prompt", (session, payload) -> {
                    // No-op: we manually control the sidecar flow
                })
                .expectTrigger("env.launch_acp_agent", 1)
                .expectTrigger("\"optionId\":\"allow\"", 1);

        WebSocketSession sidecarSession = client.execute(sidecarFixture, "ws://localhost:" + port + "/ws/env")
                .get(5, TimeUnit.SECONDS);

        // 5. Verify sidecar received command dispatch
        boolean dispatched = sidecarFixture.awaitTrigger("env.launch_acp_agent", 5, TimeUnit.SECONDS);
        assertThat(dispatched)
                .as("Sidecar should receive env.launch_acp_agent dispatch")
                .isTrue();

        // 6. Sidecar sends first env.activity event (thinking)
        JsonRpcInboundRequest activity1 = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Activity.METHOD,
                objectMapper.valueToTree(Map.of(
                        "activityType",
                        "THINKING",
                        "description",
                        "Analyzing codebase",
                        "status",
                        "in_progress",
                        "executionId",
                        executionId.toString())),
                null);
        sidecarSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(activity1)));

        // 7. Sidecar sends second env.activity event (tool_execution)
        JsonRpcInboundRequest activity2 = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Activity.METHOD,
                objectMapper.valueToTree(Map.of(
                        "activityType",
                        "RESEARCH",
                        "description",
                        "Reading files",
                        "status",
                        "in_progress",
                        "executionId",
                        executionId.toString())),
                null);
        sidecarSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(activity2)));

        // 8. Assert UI client receives execution_activity broadcasts
        boolean activitiesReceived = clientFixture.awaitTrigger("execution_activity", 5, TimeUnit.SECONDS);
        assertThat(activitiesReceived)
                .as("UI client should receive at least 2 execution_activity events")
                .isTrue();
        assertThat(clientFixture.hasReceivedMessageContaining("execution_activity", "THINKING", "Analyzing codebase"))
                .as("UI client should receive thinking activity with description")
                .isTrue();
        assertThat(clientFixture.hasReceivedMessageContaining("execution_activity", "RESEARCH", "Reading files"))
                .as("UI client should receive tool_execution activity with description")
                .isTrue();
        assertThat(clientFixture.hasReceivedMessageContaining("execution_activity", executionId.toString()))
                .as("Activity events should contain the correct executionId")
                .isTrue();

        // 9. Sidecar sends env.hitl_request for a destructive command
        JsonRpcInboundRequest permRequest = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.HitlRequest.METHOD,
                objectMapper.valueToTree(Map.of(
                        "hitlId", "tool-call-100",
                        "message", "Approve rm -rf /tmp/build",
                        "kind", "approval",
                        "executionId", executionId.toString(),
                        "command", "rm -rf /tmp/build",
                        "options", SidecarWebSocketFixture.defaultPermissionOptions())),
                100);
        sidecarSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(permRequest)));

        // 10. Assert sidecar receives NO immediate response (handler returns null for HITL)
        Thread.sleep(500); // Brief wait to ensure no response arrives
        assertThat(sidecarFixture.hasReceivedMessageContaining("\"optionId\""))
                .as("Sidecar should NOT receive an immediate response for HITL command")
                .isFalse();

        // 11. Assert UI client receives execution_hitl_required
        boolean permRequired = clientFixture.awaitTrigger("execution_hitl_required", 5, TimeUnit.SECONDS);
        assertThat(permRequired)
                .as("UI client should receive execution_hitl_required")
                .isTrue();
        assertThat(clientFixture.hasReceivedMessageContaining("execution_hitl_required", "rm -rf /tmp/build"))
                .as("Permission required event should contain the command")
                .isTrue();
        assertThat(clientFixture.hasReceivedMessageContaining("execution_hitl_required", executionId.toString()))
                .as("Permission required event should contain the correct executionId")
                .isTrue();

        // 12. REST approve the permission
        MockMvc mockMvc =
                MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        ResolveHitlRequest approveRequest =
                new ResolveHitlRequest(executionId, "tool-call-100", HitlResponse.APPROVED, "allow", null);
        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(approveRequest)))
                .andExpect(status().isNoContent());

        // 13. Assert sidecar receives interactive approval (selectedOptionId for request 100)
        boolean sidecarApproved = sidecarFixture.awaitTrigger("\"optionId\":\"allow\"", 5, TimeUnit.SECONDS);
        assertThat(sidecarApproved)
                .as("Sidecar should receive selectedOptionId after REST approval")
                .isTrue();

        // 14. Assert UI client receives execution_hitl_resolved
        boolean permResolved = clientFixture.awaitTrigger("execution_hitl_resolved", 5, TimeUnit.SECONDS);
        assertThat(permResolved)
                .as("UI client should receive execution_hitl_resolved")
                .isTrue();
        assertThat(clientFixture.hasReceivedMessageContaining("execution_hitl_resolved", "\"response\":\"approved\""))
                .as("Resolved event should indicate approval")
                .isTrue();
        assertThat(clientFixture.hasReceivedMessageContaining("resolvedByDisplayName", "Stream User"))
                .as("Resolved event should contain the resolving user's display name")
                .isTrue();

        // 15. Sidecar sends another activity event after approval
        JsonRpcInboundRequest activity3 = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Activity.METHOD,
                objectMapper.valueToTree(Map.of(
                        "activityType",
                        "RESEARCH",
                        "description",
                        "Executing approved command",
                        "status",
                        "completed",
                        "executionId",
                        executionId.toString())),
                null);
        sidecarSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(activity3)));

        // 16. Assert UI client receives the continued activity stream (3rd activity event)
        boolean activityAfterApproval = clientFixture.awaitMessageContaining(
                5, TimeUnit.SECONDS, "execution_activity", "RESEARCH", "Executing approved command");
        assertThat(activityAfterApproval)
                .as("UI client should receive activity event after permission approval")
                .isTrue();

        // 17. Sidecar sends env.complete
        JsonRpcInboundRequest completeRequest = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Complete.METHOD,
                objectMapper.valueToTree(Map.of("exitCode", 0, "executionId", executionId.toString())),
                null);
        sidecarSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(completeRequest)));

        // 18. Assert UI client receives execution_complete
        boolean completeReceived = clientFixture.awaitTrigger("execution_complete", 5, TimeUnit.SECONDS);
        assertThat(completeReceived)
                .as("UI client should receive execution_complete")
                .isTrue();
        assertThat(clientFixture.hasReceivedMessageContaining("execution_complete", "COMPLETED"))
                .as("Execution complete event should contain COMPLETED status")
                .isTrue();
        assertThat(clientFixture.hasReceivedMessageContaining("execution_complete", executionId.toString()))
                .as("Execution complete event should contain the correct executionId")
                .isTrue();

        // Clean up sessions
        sidecarSession.close();
        clientSession.close();
    }
}
