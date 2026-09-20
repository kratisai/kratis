package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.restdto.*;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.*;
import java.util.List;
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
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/**
 * Comprehensive E2E integration test verifying the full multi-client HITL
 * (Human-in-the-Loop) sync flow. Validates that when a sidecar requests
 * permission for a command, ALL connected UI clients receive the
 * permission_required broadcast, and after REST approval, ALL clients
 * receive the permission_resolved broadcast with correct resolution metadata.
 * <p>
 * Test scenario:
 * <ol>
 * <li>Setup: Create environment, execution, connect TWO UI clients to the same
 * team
 * <li>Connect sidecar and simulate agent requesting permission for a
 * destructive command
 * <li>Assert BOTH UI clients receive execution_hitl_required
 * <li>Client 1 calls POST /api/v1/hitl/resolve
 * <li>Assert BOTH UI clients receive execution_hitl_resolved
 * <li>Assert sidecar receives the JSON-RPC approval response
 * <li>Verify the pending HITL request is cleared from PendingHitlRegistry
 * <li>Verify activity log permission events are consistent across both clients
 * </ol>
 */
@SpringIntegrationTest
class ActivityLogSyncIntegrationTest {

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

    @Autowired
    private PendingHitlRegistry pendingHitlRegistry;

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

        // Register and login to get a valid JWT token
        RegisterUserRequest registerRequest =
                new RegisterUserRequest("syncuser@example.com", "password123", "Sync User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest("syncuser@example.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        userAuthToken = objectMapper
                .readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        user = userRepository.findByEmail("syncuser@example.com").orElseThrow();
        team = teamMemberRepository.findByUserId(user.getId()).getFirst().getTeam();

        // Create a ModelProvider for the team
        ModelProvider provider = new ModelProvider("test-provider", ProviderType.OPENAI, "dummy-api-key", null);
        provider.setTeam(team);
        provider.setModels(List.of(new ProviderModel("gpt-4o", ModelKind.CHAT)));
        modelProvider = modelProviderRepository.save(provider);
        liteLLMProvisioningService.provisionModel(modelProvider);

        chat = chatRepository.save(new ChatEntity(team, user, "Activity Log Sync Test Session"));
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
    void testMultiClientActivityLogSync() throws Exception {
        // 1. Create a workspace connector environment
        var createRequest = new CreateExecutionEnvironmentRequest("Multi-Client Sync Connector");
        var response = executionEnvironmentService.createConnector(user.getId(), team.getId(), createRequest);
        var envDto = response.environment();
        UUID envId = envDto.id();
        String connectorToken = envDto.authToken();

        // 2. Create a SPEC canvas bound to a repository and sandbox execution with a
        // command requiring HITL approval
        canvasService.createCanvas(
                chat.getId(),
                "test-plan-sync",
                "Test Plan",
                "# Test Plan",
                CanvasType.SPEC,
                createExecutionRepository("sync-repo"),
                null);
        var createExecRequest = new CreateSandboxExecutionRequest(
                null, envId, AgentHarness.OPENCODE, "test-plan-sync", modelProvider.getId(), "gpt-4o");
        var execDto = sandboxExecutionService.createExecution(user.getId(), chat.getId(), createExecRequest);
        UUID executionId = execDto.id();

        // 3. Connect TWO UI clients subscribed to the same team
        StandardWebSocketClient client = new StandardWebSocketClient();

        // Client 1 - simulates User A's browser tab
        ClientWebSocketFixture client1Fixture = new ClientWebSocketFixture(userAuthToken, team.getId())
                .expectTrigger("execution_hitl_required", 1)
                .expectTrigger("execution_hitl_resolved", 1);

        WebSocketSession client1Session = client.execute(client1Fixture, "ws://localhost:" + port + "/ws/client")
                .get(5, TimeUnit.SECONDS);

        // Client 2 - simulates User B's browser tab (same team subscription)
        ClientWebSocketFixture client2Fixture = new ClientWebSocketFixture(userAuthToken, team.getId())
                .expectTrigger("execution_hitl_required", 1)
                .expectTrigger("execution_hitl_resolved", 1);

        WebSocketSession client2Session = client.execute(client2Fixture, "ws://localhost:" + port + "/ws/client")
                .get(5, TimeUnit.SECONDS);

        // Allow subscription registry to process both subscribers
        Thread.sleep(500);

        // 4. Connect Sidecar and simulate agent requesting permission
        SidecarWebSocketFixture sidecarFixture = new SidecarWebSocketFixture(connectorToken)
                .withAcpCommand("rm -rf /")
                .expectTrigger("env.launch_acp_agent", 1)
                .expectTrigger("\"optionId\":\"allow\"", 1);

        WebSocketSession sidecarSession = client.execute(sidecarFixture, "ws://localhost:" + port + "/ws/env")
                .get(5, TimeUnit.SECONDS);

        // 5. Verify sidecar received command dispatch
        boolean dispatched = sidecarFixture.awaitTrigger("env.launch_acp_agent", 5, TimeUnit.SECONDS);
        assertThat(dispatched)
                .as("Sidecar should receive env.launch_acp_agent dispatch")
                .isTrue();

        // 6. Verify HITL command has NOT received any immediate response (deferred
        // until user approval) With the new permission flow, non-auto-approved
        // commands get no immediate response
        assertThat(sidecarFixture.hasReceivedMessageContaining("\"id\":100", "\"optionId\""))
                .as("HITL command should not receive an immediate response")
                .isFalse();

        // 7. Assert BOTH UI clients received execution_hitl_required broadcast
        boolean client1Required = client1Fixture.awaitTrigger("execution_hitl_required", 5, TimeUnit.SECONDS);
        assertThat(client1Required)
                .as("Client 1 should receive execution_hitl_required")
                .isTrue();
        assertThat(client1Fixture.hasReceivedMessageContaining("execution_hitl_required", "rm -rf /"))
                .as("Client 1 permission_required should contain the command")
                .isTrue();

        boolean client2Required = client2Fixture.awaitTrigger("execution_hitl_required", 5, TimeUnit.SECONDS);
        assertThat(client2Required)
                .as("Client 2 should receive execution_hitl_required")
                .isTrue();
        assertThat(client2Fixture.hasReceivedMessageContaining("execution_hitl_required", "rm -rf /"))
                .as("Client 2 permission_required should contain the command")
                .isTrue();

        // 8. Verify pending permission is registered before approval
        assertThat(pendingHitlRegistry.getPending()).containsKey(executionId);

        // 9. Client 1 approves the permission via REST API
        MockMvc mockMvc =
                MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        ResolveHitlRequest approveRequest =
                new ResolveHitlRequest(executionId, "tool-call-100", HitlResponse.APPROVED, "allow", null);
        mockMvc.perform(post("/api/v1/hitl/resolve")
                        .header("Authorization", "Bearer " + userAuthToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(approveRequest)))
                .andExpect(status().isNoContent());

        // 10. Assert sidecar receives the interactive approval response (selectedOptionId
        // for request 100)
        boolean sidecarApproved = sidecarFixture.awaitTrigger("\"optionId\":\"allow\"", 5, TimeUnit.SECONDS);
        assertThat(sidecarApproved)
                .as("Sidecar should receive selectedOptionId after REST approval")
                .isTrue();
        assertThat(sidecarFixture.hasReceivedMessageContaining("100", "\"optionId\":\"allow\""))
                .as("Sidecar approval should be for request ID 100")
                .isTrue();

        // 11. Assert BOTH UI clients received execution_hitl_resolved broadcast
        boolean client1Resolved = client1Fixture.awaitTrigger("execution_hitl_resolved", 5, TimeUnit.SECONDS);
        assertThat(client1Resolved)
                .as("Client 1 should receive execution_hitl_resolved")
                .isTrue();
        assertThat(client1Fixture.hasReceivedMessageContaining("execution_hitl_resolved", "\"response\":\"approved\""))
                .as("Client 1 resolved event should indicate approval")
                .isTrue();
        assertThat(client1Fixture.hasReceivedMessageContaining("resolvedByDisplayName", "Sync User"))
                .as("Client 1 resolved event should contain the resolving user's display name")
                .isTrue();

        boolean client2Resolved = client2Fixture.awaitTrigger("execution_hitl_resolved", 5, TimeUnit.SECONDS);
        assertThat(client2Resolved)
                .as("Client 2 should receive execution_hitl_resolved")
                .isTrue();
        assertThat(client2Fixture.hasReceivedMessageContaining("execution_hitl_resolved", "\"response\":\"approved\""))
                .as("Client 2 resolved event should indicate approval")
                .isTrue();
        assertThat(client2Fixture.hasReceivedMessageContaining("resolvedByDisplayName", "Sync User"))
                .as("Client 2 resolved event should contain the resolving user's display name")
                .isTrue();

        // 12. Verify pending permission is cleared from registry after approval
        assertThat(pendingHitlRegistry.getPending())
                .as("Pending permission should be removed from registry after approval")
                .doesNotContainKey(executionId);

        // 13. Verify activity log consistency: both clients received the same
        // permission events Both clients should have received:
        // permission_required and permission_resolved
        assertThat(client1Fixture.hasReceivedMessageContaining("execution_hitl_required"))
                .isTrue();
        assertThat(client1Fixture.hasReceivedMessageContaining("execution_hitl_resolved"))
                .isTrue();

        assertThat(client2Fixture.hasReceivedMessageContaining("execution_hitl_required"))
                .isTrue();
        assertThat(client2Fixture.hasReceivedMessageContaining("execution_hitl_resolved"))
                .isTrue();

        // 14. Verify both clients received the same executionId in permission events
        assertThat(client1Fixture.hasReceivedMessageContaining(executionId.toString()))
                .as("Client 1 should have received events with the correct executionId")
                .isTrue();
        assertThat(client2Fixture.hasReceivedMessageContaining(executionId.toString()))
                .as("Client 2 should have received events with the correct executionId")
                .isTrue();

        // Clean up sessions
        sidecarSession.close();
        client1Session.close();
        client2Session.close();
    }

    @Test
    void testMultiClientPermissionRequiredBroadcastContainsExecutionId() throws Exception {
        // This test verifies that the execution_hitl_required broadcast contains
        // the correct executionId, enabling UI clients to correlate the permission
        // request with the correct execution's activity log.

        // 1. Create a workspace connector environment
        var createRequest = new CreateExecutionEnvironmentRequest("Execution ID Test Connector");
        var response = executionEnvironmentService.createConnector(user.getId(), team.getId(), createRequest);
        var envDto = response.environment();
        UUID envId = envDto.id();
        String connectorToken = envDto.authToken();

        // 2. Create a SPEC canvas bound to a repository and sandbox execution
        canvasService.createCanvas(
                chat.getId(),
                "test-plan-broadcast",
                "Test Plan",
                "# Test Plan",
                CanvasType.SPEC,
                createExecutionRepository("broadcast-repo"),
                null);
        var createExecRequest = new CreateSandboxExecutionRequest(
                null, envId, AgentHarness.OPENCODE, "test-plan-broadcast", modelProvider.getId(), "gpt-4o");
        var execDto = sandboxExecutionService.createExecution(user.getId(), chat.getId(), createExecRequest);
        UUID executionId = execDto.id();

        // 3. Connect two UI clients
        StandardWebSocketClient client = new StandardWebSocketClient();

        ClientWebSocketFixture client1Fixture =
                new ClientWebSocketFixture(userAuthToken, team.getId()).expectTrigger("execution_hitl_required", 1);

        WebSocketSession client1Session = client.execute(client1Fixture, "ws://localhost:" + port + "/ws/client")
                .get(5, TimeUnit.SECONDS);

        ClientWebSocketFixture client2Fixture =
                new ClientWebSocketFixture(userAuthToken, team.getId()).expectTrigger("execution_hitl_required", 1);

        WebSocketSession client2Session = client.execute(client2Fixture, "ws://localhost:" + port + "/ws/client")
                .get(5, TimeUnit.SECONDS);

        Thread.sleep(500);

        // 4. Connect sidecar
        SidecarWebSocketFixture sidecarFixture =
                new SidecarWebSocketFixture(connectorToken).withAcpCommand("dangerous-command");

        WebSocketSession sidecarSession = client.execute(sidecarFixture, "ws://localhost:" + port + "/ws/env")
                .get(5, TimeUnit.SECONDS);

        // 5. Both clients should receive permission_required with the correct
        // executionId
        boolean client1Received = client1Fixture.awaitTrigger("execution_hitl_required", 5, TimeUnit.SECONDS);
        assertThat(client1Received).isTrue();
        assertThat(client1Fixture.hasReceivedMessageContaining("execution_hitl_required", executionId.toString()))
                .as("Client 1 should receive permission_required with correct executionId")
                .isTrue();

        boolean client2Received = client2Fixture.awaitTrigger("execution_hitl_required", 5, TimeUnit.SECONDS);
        assertThat(client2Received).isTrue();
        assertThat(client2Fixture.hasReceivedMessageContaining("execution_hitl_required", executionId.toString()))
                .as("Client 2 should receive permission_required with correct executionId")
                .isTrue();

        // Clean up
        sidecarSession.close();
        client1Session.close();
        client2Session.close();
    }
}
