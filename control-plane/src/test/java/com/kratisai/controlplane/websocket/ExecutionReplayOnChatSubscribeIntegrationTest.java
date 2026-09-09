package com.kratisai.controlplane.websocket;

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
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
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
 * Integration test verifying that clients subscribing to a chat after activities have been
 * broadcast (simulating a reconnect or control-plane restart) receive the persisted activity log
 * and pending permission replayed by the chat.subscribe handler.
 */
@SpringIntegrationTest
class ExecutionReplayOnChatSubscribeIntegrationTest {

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
    private SandboxExecutionActivityRepository activityRepository;

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
                new RegisterUserRequest("replayuser@example.com", "password123", "Replay User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest("replayuser@example.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        userAuthToken = objectMapper
                .readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        user = userRepository.findByEmail("replayuser@example.com").orElseThrow();
        team = teamMemberRepository.findByUserId(user.getId()).getFirst().getTeam();

        ModelProvider provider = new ModelProvider("test-provider", ProviderType.OPENAI, "dummy-api-key", null);
        provider.setTeam(team);
        provider.setModels(List.of(new ProviderModel("gpt-4o", ModelKind.CHAT)));
        modelProvider = modelProviderRepository.save(provider);
        liteLLMProvisioningService.provisionModel(modelProvider);

        chat = chatRepository.save(new ChatEntity(team, user, "Replay Test Session"));
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
    void lateSubscriberReceivesReplayedActivitiesAndPendingPermission() throws Exception {
        // 1. Create a workspace connector environment
        var createRequest = new CreateExecutionEnvironmentRequest("Replay Test Connector");
        var response = executionEnvironmentService.createConnector(user.getId(), team.getId(), createRequest);
        var envDto = response.environment();
        UUID envId = envDto.id();
        String connectorToken = envDto.authToken();

        // 2. Create a SPEC canvas and sandbox execution
        canvasService.createCanvas(
                chat.getId(),
                "test-plan-replay",
                "Test Plan",
                "# Test Plan",
                CanvasType.SPEC,
                createExecutionRepository("replay-repo"),
                null);
        var createExecRequest = new CreateSandboxExecutionRequest(
                null, envId, null, AgentHarness.OPENCODE, "test-plan-replay", modelProvider.getId(), "gpt-4o");
        var execDto = sandboxExecutionService.createExecution(user.getId(), chat.getId(), createExecRequest);
        UUID executionId = execDto.id();

        // 3. Connect sidecar and simulate an agent that streams an activity and then requests
        //    permission (this persists the activity row and registers the pending permission)
        StandardWebSocketClient client = new StandardWebSocketClient();
        SidecarWebSocketFixture sidecarFixture = new SidecarWebSocketFixture(connectorToken)
                .whenMethod("env.acp_prompt", (session, payload) -> {
                    JsonRpcInboundRequest promptRequest = objectMapper.readValue(payload, JsonRpcInboundRequest.class);
                    Object id = promptRequest.id() != null ? promptRequest.id() : 0;
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                            Map.of("jsonrpc", "2.0", "id", id, "result", Map.of("status", "completed")))));

                    JsonRpcInboundRequest activity = new JsonRpcInboundRequest(
                            EnvironmentRpcPayload.Activity.METHOD,
                            objectMapper.valueToTree(Map.of(
                                    "activityType",
                                    ActivityType.RESEARCH.getValue(),
                                    "description",
                                    "Reading main.go",
                                    "status",
                                    ActivityStatus.IN_PROGRESS.getValue(),
                                    "executionId",
                                    executionId.toString())),
                            null);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(activity)));

                    JsonRpcInboundRequest permRequest = new JsonRpcInboundRequest(
                            EnvironmentRpcPayload.HitlRequest.METHOD,
                            objectMapper.valueToTree(Map.of(
                                    "hitlId", "tool-call-100",
                                    "message", "Approve rm -rf /",
                                    "kind", "approval",
                                    "executionId", executionId.toString(),
                                    "command", "rm -rf /")),
                            100);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(permRequest)));
                });

        WebSocketSession sidecarSession = client.execute(sidecarFixture, "ws://localhost:" + port + "/ws/env")
                .get(5, TimeUnit.SECONDS);

        assertThat(sidecarFixture.awaitTrigger("env.launch_acp_agent", 5, TimeUnit.SECONDS))
                .as("Sidecar should receive env.launch_acp_agent dispatch")
                .isTrue();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(activityRepository.findByExecutionIdOrderBySequenceAsc(executionId))
                        .hasSize(2));

        List<SandboxExecutionActivity> activities = activityRepository.findByExecutionIdOrderBySequenceAsc(executionId);
        assertThat(activities.getFirst().getActivityType()).isEqualTo(ActivityType.RESEARCH);
        assertThat(activities.getFirst().getDescription()).isEqualTo("Reading main.go");
        assertThat(activities.getFirst().getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(activities.get(1).getActivityType()).isEqualTo(ActivityType.COMMAND);
        assertThat(activities.get(1).getStatus()).isEqualTo(ActivityStatus.PENDING);

        // 4. Connect a NEW client (simulating a reconnect) that sends chat.subscribe after auth
        ClientWebSocketFixtureWithChatSubscribe lateJoinerFixture =
                new ClientWebSocketFixtureWithChatSubscribe(userAuthToken, team.getId(), chat.getId());
        lateJoinerFixture.expectTrigger("execution_activity", 1);
        lateJoinerFixture.expectTrigger("execution_hitl_required", 1);

        WebSocketSession lateJoinerSession = client.execute(lateJoinerFixture, "ws://localhost:" + port + "/ws/client")
                .get(5, TimeUnit.SECONDS);

        // 5. Assert the late joiner receives replayed activity and pending permission
        boolean receivedActivity = lateJoinerFixture.awaitTrigger("execution_activity", 5, TimeUnit.SECONDS);
        assertThat(receivedActivity)
                .as("Late joiner should receive replayed execution_activity")
                .isTrue();
        assertThat(lateJoinerFixture.hasReceivedMessageContaining("execution_activity", executionId.toString()))
                .as("Replayed activity should reference the execution")
                .isTrue();

        boolean receivedPermission = lateJoinerFixture.awaitTrigger("execution_hitl_required", 5, TimeUnit.SECONDS);
        assertThat(receivedPermission)
                .as("Late joiner should receive replayed execution_hitl_required")
                .isTrue();
        assertThat(lateJoinerFixture.hasReceivedMessageContaining("execution_hitl_required", "rm -rf /"))
                .as("Replayed permission should contain the command")
                .isTrue();

        // 6. A second fresh client (restart-equivalent: no in-memory state) must receive the same
        //    replay from the database
        ClientWebSocketFixtureWithChatSubscribe restartClientFixture =
                new ClientWebSocketFixtureWithChatSubscribe(userAuthToken, team.getId(), chat.getId());
        restartClientFixture.expectTrigger("execution_activity", 1);
        restartClientFixture.expectTrigger("execution_hitl_required", 1);

        WebSocketSession restartClientSession = client.execute(
                        restartClientFixture, "ws://localhost:" + port + "/ws/client")
                .get(5, TimeUnit.SECONDS);

        assertThat(restartClientFixture.awaitTrigger("execution_activity", 5, TimeUnit.SECONDS))
                .as("Fresh client should receive the same replayed activity from the database")
                .isTrue();
        assertThat(restartClientFixture.awaitTrigger("execution_hitl_required", 5, TimeUnit.SECONDS))
                .as("Fresh client should receive the replayed pending permission")
                .isTrue();
        assertThat(restartClientFixture.hasReceivedMessageContaining("execution_activity", executionId.toString()))
                .as("Replayed activity should reference the execution")
                .isTrue();

        // Clean up
        sidecarSession.close();
        lateJoinerSession.close();
        restartClientSession.close();
    }

    /**
     * Custom fixture that sends chat.subscribe after authentication (in addition to the team
     * subscription). This simulates a client reconnecting and subscribing to a specific chat.
     */
    private static class ClientWebSocketFixtureWithChatSubscribe extends ClientWebSocketFixture {

        public ClientWebSocketFixtureWithChatSubscribe(String token, UUID teamId, UUID chatId) {
            super(token, teamId);
            whenContains("authenticated", (sess, payload) -> {
                JsonRpcInboundRequest chatSubRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.ChatSubscribe.METHOD,
                        objectMapper.valueToTree(Map.of("chatId", chatId.toString(), "teamId", teamId.toString())),
                        3);
                sess.sendMessage(new TextMessage(objectMapper.writeValueAsString(chatSubRequest)));
            });
        }
    }
}
