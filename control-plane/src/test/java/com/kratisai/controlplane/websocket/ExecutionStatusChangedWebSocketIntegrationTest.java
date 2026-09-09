package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.*;
import com.kratisai.controlplane.api.wsdto.CheckoutStatus;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.service.CanvasService;
import com.kratisai.controlplane.service.ExecutionEnvironmentService;
import com.kratisai.controlplane.service.SandboxExecutionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
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
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Integration test verifying that execution state changes flow through WebSocket as
 * {@code execution_status_changed} notifications: usage refreshes and RUNNING → COMPLETED
 * transitions each invalidate the chat's execution list.
 */
@SpringIntegrationTest
class ExecutionStatusChangedWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private ExecutionEnvironmentService executionEnvironmentService;

    @Autowired
    private SandboxExecutionService sandboxExecutionService;

    @Autowired
    private CanvasService canvasService;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User user;
    private Team team;
    private ModelProvider modelProvider;
    private ChatEntity chat;
    private String authToken;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext context = testDataFactory.createUserAndTeam();
        user = context.user();
        team = context.team();

        ModelProvider provider = new ModelProvider("test-provider", ProviderType.OPENAI, "dummy-api-key", null);
        provider.setTeam(team);
        provider.setModels(List.of(new ProviderModel("gpt-4o", ModelKind.CHAT)));
        modelProvider = modelProviderRepository.save(provider);
        testDataFactory.provisionModel(modelProvider);

        chat = chatRepository.save(new ChatEntity(team, user, "Status Changed WebSocket Test Session"));

        MockMvc mockMvc =
                MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        LoginRequest loginRequest = new LoginRequest(user.getEmail(), TestDataFactory.DUMMY_PASSWORD);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        authToken = objectMapper
                .readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();
    }

    private Repository createExecutionRepository(String name) {
        return testDataFactory.createRepository(team, name, "https://example.com/" + name + ".git", "main", null);
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    private record ConnectionFixture(UUID executionId, String connectorToken) {}

    private ConnectionFixture createConnectorAndExecution() {
        var createRequest = new CreateExecutionEnvironmentRequest("Status Changed Test Connector");
        var response = executionEnvironmentService.createConnector(user.getId(), team.getId(), createRequest);
        UUID envId = response.environment().id();
        String connectorToken = response.environment().authToken();

        canvasService.createCanvas(
                chat.getId(),
                "test-plan-status",
                "Test Plan",
                "# Test Plan",
                CanvasType.SPEC,
                createExecutionRepository("status-repo"),
                null);
        var createExecRequest = new CreateSandboxExecutionRequest(
                null, envId, null, AgentHarness.OPENCODE, "test-plan-status", modelProvider.getId(), "gpt-4o");
        UUID executionId = sandboxExecutionService
                .createExecution(user.getId(), chat.getId(), createExecRequest)
                .id();
        return new ConnectionFixture(executionId, connectorToken);
    }

    private record UiConnection(List<String> messages, WebSocketSession session) {}

    private UiConnection connectUiClient(CountDownLatch statusLatch, CountDownLatch subscribedLatch) throws Exception {
        List<String> uiMessages = new ArrayList<>();

        WebSocketHandler uiHandler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
                JsonRpcInboundRequest authRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.Auth.METHOD, objectMapper.valueToTree(Map.of("token", authToken)), 1);
                wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(authRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession wsSession, TextMessage message) {
                String payload = message.getPayload();
                uiMessages.add(payload);

                if (payload.contains("authenticated")) {
                    try {
                        JsonRpcInboundRequest subscribeRequest = new JsonRpcInboundRequest(
                                ClientRpcPayload.Subscribe.METHOD,
                                objectMapper.valueToTree(
                                        Map.of("teamId", team.getId().toString())),
                                2);
                        wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(subscribeRequest)));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                if (payload.toLowerCase().contains("subscribed")) {
                    subscribedLatch.countDown();
                }

                if (payload.contains("execution_status_changed")) {
                    statusLatch.countDown();
                }
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession uiSession = client.execute(uiHandler, "ws://localhost:" + port + "/ws/client")
                .get(5, TimeUnit.SECONDS);

        boolean subscribed = subscribedLatch.await(5, TimeUnit.SECONDS);
        assertThat(subscribed).isTrue();

        return new UiConnection(uiMessages, uiSession);
    }

    private WebSocketSession connectSidecar(String connectorToken, boolean sendComplete) throws Exception {
        CountDownLatch commandLatch = new CountDownLatch(1);

        WebSocketHandler sidecarHandler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
                JsonRpcInboundRequest registerRequest = new JsonRpcInboundRequest(
                        EnvironmentRpcPayload.Register.METHOD,
                        objectMapper.valueToTree(Map.of("token", connectorToken)),
                        1);
                wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(registerRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession wsSession, TextMessage message) {
                String payload = message.getPayload();
                try {
                    String execId = extractExecutionId(payload);
                    if (payload.contains("env.checkout")) {
                        wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(new JsonRpcInboundRequest(
                                EnvironmentRpcPayload.CheckoutComplete.METHOD,
                                objectMapper.valueToTree(Map.of(
                                        "status",
                                        CheckoutStatus.SUCCESS.getValue(),
                                        "commitHash",
                                        "abcdef",
                                        "executionId",
                                        execId)),
                                null))));
                    } else if (payload.contains("env.exec")) {
                        JsonRpcInboundRequest request = objectMapper.readValue(payload, JsonRpcInboundRequest.class);
                        Object id = request.id() != null ? request.id() : 0;
                        wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0", "id", id, "result", Map.of("status", "completed", "exitCode", 0)))));
                    } else if (payload.contains("env.launch_acp_agent")) {
                        commandLatch.countDown();
                        wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(new JsonRpcInboundRequest(
                                EnvironmentRpcPayload.Output.METHOD,
                                objectMapper.valueToTree(
                                        Map.of("line", "test output line", "stream", "stdout", "executionId", execId)),
                                null))));
                        if (sendComplete) {
                            wsSession.sendMessage(
                                    new TextMessage(objectMapper.writeValueAsString(new JsonRpcInboundRequest(
                                            EnvironmentRpcPayload.Complete.METHOD,
                                            objectMapper.valueToTree(Map.of("exitCode", 0, "executionId", execId)),
                                            null))));
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            private String extractExecutionId(String json) {
                try {
                    JsonNode node = objectMapper.readTree(json);
                    if (node.has("params") && node.get("params").has("executionId")) {
                        return node.get("params").get("executionId").asText();
                    }
                } catch (Exception ignored) {
                }
                return "123e4567-e89b-12d3-a456-426614174000";
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession sidecarSession = client.execute(sidecarHandler, "ws://localhost:" + port + "/ws/env")
                .get(5, TimeUnit.SECONDS);

        boolean commandDispatched = commandLatch.await(5, TimeUnit.SECONDS);
        assertThat(commandDispatched).isTrue();
        return sidecarSession;
    }

    private JsonNode statusChangedMessage(List<String> uiMessages) throws Exception {
        String statusMessage = uiMessages.stream()
                .filter(msg -> msg.contains("execution_status_changed"))
                .findFirst()
                .orElseThrow();
        return objectMapper.readTree(statusMessage).path("result");
    }

    @Test
    void shouldBroadcastExecutionStatusChangedOnUsageRefresh() throws Exception {
        ConnectionFixture fixture = createConnectorAndExecution();

        CountDownLatch statusLatch = new CountDownLatch(1);
        CountDownLatch subscribedLatch = new CountDownLatch(1);
        UiConnection ui = connectUiClient(statusLatch, subscribedLatch);

        // Connect sidecar and stream an output event; the debounced usage refresh (test-wide 1s
        // interval from src/test/resources/application.properties) then publishes
        // execution_status_changed to the subscribed UI client.
        WebSocketSession sidecarSession = connectSidecar(fixture.connectorToken(), false);

        boolean statusReceived = statusLatch.await(10, TimeUnit.SECONDS);
        assertThat(statusReceived)
                .as("execution_status_changed broadcast after agent output event")
                .isTrue();

        JsonNode result = statusChangedMessage(ui.messages());
        assertThat(result.get("type").asText()).isEqualTo("execution_status_changed");
        assertThat(result.get("chatId").asText()).isEqualTo(chat.getId().toString());
        assertThat(result.get("executionId").asText())
                .isEqualTo(fixture.executionId().toString());

        sidecarSession.close();
        ui.session().close();
    }

    @Test
    void shouldBroadcastExecutionStatusChangedOnCompletion() throws Exception {
        ConnectionFixture fixture = createConnectorAndExecution();

        CountDownLatch statusLatch = new CountDownLatch(1);
        CountDownLatch subscribedLatch = new CountDownLatch(1);
        UiConnection ui = connectUiClient(statusLatch, subscribedLatch);

        WebSocketSession sidecarSession = connectSidecar(fixture.connectorToken(), true);

        boolean statusReceived = statusLatch.await(10, TimeUnit.SECONDS);
        assertThat(statusReceived)
                .as("execution_status_changed broadcast after env.complete")
                .isTrue();

        JsonNode result = statusChangedMessage(ui.messages());
        assertThat(result.get("type").asText()).isEqualTo("execution_status_changed");
        assertThat(result.get("chatId").asText()).isEqualTo(chat.getId().toString());
        assertThat(result.get("executionId").asText())
                .isEqualTo(fixture.executionId().toString());

        sidecarSession.close();
        ui.session().close();
    }
}
