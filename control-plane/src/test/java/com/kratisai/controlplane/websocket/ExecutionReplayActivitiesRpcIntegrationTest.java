package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.restdto.CreateExecutionEnvironmentRequest;
import com.kratisai.controlplane.api.restdto.CreateSandboxExecutionRequest;
import com.kratisai.controlplane.api.restdto.LoginRequest;
import com.kratisai.controlplane.api.restdto.RegisterUserRequest;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.*;
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

@SpringIntegrationTest
class ExecutionReplayActivitiesRpcIntegrationTest {

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
    private SandboxExecutionRepository executionRepository;

    @Autowired
    private ExecutionActivityPersistenceService activityPersistenceService;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String userAuthToken;
    private User user;
    private Team team;
    private ChatEntity chat;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();

        MockMvc mockMvc =
                MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        RegisterUserRequest registerRequest =
                new RegisterUserRequest("replayrpc@example.com", "password123", "Replay Rpc User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest("replayrpc@example.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        userAuthToken = objectMapper
                .readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        user = userRepository.findByEmail("replayrpc@example.com").orElseThrow();
        team = teamMemberRepository.findByUserId(user.getId()).getFirst().getTeam();

        ModelProvider provider = new ModelProvider("test-provider", ProviderType.OPENAI, "dummy-api-key", null);
        provider.setTeam(team);
        provider.setModels(List.of(new ProviderModel("gpt-4o", ModelKind.CHAT)));
        modelProviderRepository.save(provider);
        liteLLMProvisioningService.provisionModel(provider);

        chat = chatRepository.save(new ChatEntity(team, user, "Replay Rpc Session"));

        // Store the provider on the test instance so helper methods can reference it
        this.modelProvider = provider;
    }

    private ModelProvider modelProvider;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    private Repository createExecutionRepository(String name) {
        Repository repository =
                new Repository(name, "https://example.com/" + name + ".git", "main", RepositoryType.GENERIC);
        repository.setTeam(team);
        return repositoryRepository.save(repository);
    }

    private UUID createCompletedExecutionWithActivities() {
        var createRequest = new CreateExecutionEnvironmentRequest("Replay Rpc Connector");
        var response = executionEnvironmentService.createConnector(user.getId(), team.getId(), createRequest);
        UUID envId = response.environment().id();

        canvasService.createCanvas(
                chat.getId(),
                "replay-rpc-plan",
                "Replay Rpc Plan",
                "# Replay Rpc Plan",
                CanvasType.SPEC,
                createExecutionRepository("replay-rpc-repo"),
                null);
        var createExecRequest = new CreateSandboxExecutionRequest(
                null, envId, null, AgentHarness.OPENCODE, "replay-rpc-plan", modelProvider.getId(), "gpt-4o");
        var execDto = sandboxExecutionService.createExecution(user.getId(), chat.getId(), createExecRequest);
        UUID executionId = execDto.id();

        activityPersistenceService.recordActivity(
                executionId, ActivityType.THINKING, "planning the task", "msg-1", ActivityStatus.COMPLETED, null);
        activityPersistenceService.recordActivity(
                executionId, ActivityType.RESEARCH, "Reading main.go", "act-1", ActivityStatus.COMPLETED, null);

        SandboxExecution execution = executionRepository.findById(executionId).orElseThrow();
        execution.setStatus(SandboxExecutionStatus.COMPLETED);
        execution.setExitCode(0);
        execution.setCompletedAt(java.time.Instant.now());
        executionRepository.save(execution);
        return executionId;
    }

    @Test
    void clientReceivesReplayedActivitiesAndCompletionMarkerForCompletedExecution() throws Exception {
        UUID executionId = createCompletedExecutionWithActivities();

        StandardWebSocketClient client = new StandardWebSocketClient();
        ClientWebSocketFixture fixture = new ClientWebSocketFixture(userAuthToken, team.getId())
                .whenContains("authenticated", (session, payload) -> {
                    JsonRpcInboundRequest replayRequest = new JsonRpcInboundRequest(
                            ClientRpcPayload.ExecutionReplayActivities.METHOD,
                            objectMapper.valueToTree(Map.of("executionId", executionId.toString())),
                            10);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(replayRequest)));
                });
        fixture.expectTrigger("execution_activity", 2);
        fixture.expectTrigger("execution_replay_complete", 1);

        WebSocketSession wsSession =
                client.execute(fixture, "ws://localhost:" + port + "/ws/client").get(5, TimeUnit.SECONDS);

        assertThat(fixture.awaitTrigger("execution_activity", 5, TimeUnit.SECONDS))
                .as("Client should receive 2 replayed execution_activity messages")
                .isTrue();
        assertThat(fixture.hasReceivedMessageContaining("execution_activity", executionId.toString()))
                .as("Replayed activities should reference the execution")
                .isTrue();
        assertThat(fixture.hasReceivedMessageContaining("execution_activity", "planning the task"))
                .as("First replayed activity should carry its persisted description")
                .isTrue();
        assertThat(fixture.hasReceivedMessageContaining("execution_activity", "Reading main.go"))
                .as("Second replayed activity should carry its persisted description")
                .isTrue();

        assertThat(fixture.awaitTrigger("execution_replay_complete", 5, TimeUnit.SECONDS))
                .as("Client should receive the execution_replay_complete marker")
                .isTrue();
        assertThat(fixture.hasReceivedMessageContaining("execution_replay_complete", executionId.toString(), "2"))
                .as("Completion marker should report the replayed activity count")
                .isTrue();

        wsSession.close();
    }
}
