package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.restdto.CreateExecutionEnvironmentRequest;
import com.kratisai.controlplane.api.restdto.CreateExecutionEnvironmentResponse;
import com.kratisai.controlplane.api.restdto.LoginRequest;
import com.kratisai.controlplane.api.restdto.RegisterUserRequest;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.ExecutionEnvironmentService;
import java.io.IOException;
import java.time.Duration;
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

@SpringIntegrationTest
class ExecutionEnvironmentWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private ExecutionEnvironmentService executionEnvironmentService;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String userAuthToken;
    private User user;
    private Team team;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();

        MockMvc mockMvc =
                MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        // Register and login to get a valid JWT token
        RegisterUserRequest registerRequest = new RegisterUserRequest("wsuser@example.com", "password123", "WS User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest("wsuser@example.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        userAuthToken = objectMapper
                .readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        user = userRepository.findByEmail("wsuser@example.com").orElseThrow();
        team = teamMemberRepository.findByUserId(user.getId()).getFirst().getTeam();
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void testEnvironmentConnectionLifecycle() throws Exception {
        // Create an execution environment of type CONNECTOR
        var createRequest = new CreateExecutionEnvironmentRequest("My Workspace Connector");
        CreateExecutionEnvironmentResponse response =
                executionEnvironmentService.createConnector(user.getId(), team.getId(), createRequest);
        var envDto = response.environment();
        UUID envId = envDto.id();
        String connectorToken = envDto.authToken();

        assertThat(connectorToken).isNotNull();
        assertThat(envDto.type()).isEqualTo(ExecutionEnvironmentType.CONNECTOR);
        assertThat(envDto.status()).isEqualTo(EnvironmentStatus.DISCONNECTED);

        CountDownLatch registerLatch = new CountDownLatch(1);
        CountDownLatch heartbeatLatch = new CountDownLatch(1);
        List<String> receivedMessages = new ArrayList<>();

        WebSocketHandler sidecarHandler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // Send register message with connector token
                JsonRpcInboundRequest registerRequest = new JsonRpcInboundRequest(
                        EnvironmentRpcPayload.Register.METHOD,
                        objectMapper.valueToTree(Map.of("token", connectorToken, "containerId", "container-123")),
                        1);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(registerRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
                String payload = message.getPayload();
                receivedMessages.add(payload);

                if (payload.contains("registered")) {
                    registerLatch.countDown();
                    // Send heartbeat message
                    try {
                        JsonRpcInboundRequest heartbeatRequest =
                                new JsonRpcInboundRequest(EnvironmentRpcPayload.Heartbeat.METHOD, null, 2);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(heartbeatRequest)));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else if (payload.contains("ok")) {
                    heartbeatLatch.countDown();
                }
            }
        };

        // Connect sidecar
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession sidecarSession = client.execute(sidecarHandler, "ws://localhost:" + port + "/ws/env")
                .get(5, TimeUnit.SECONDS);

        // Verify sidecar successfully registered
        boolean registered = registerLatch.await(5, TimeUnit.SECONDS);
        assertThat(registered).isTrue();

        // Verify database state updated to CONNECTED
        ExecutionEnvironment envAfterConnect =
                executionEnvironmentRepository.findById(envId).orElseThrow();
        assertThat(envAfterConnect.getStatus()).isEqualTo(EnvironmentStatus.CONNECTED);
        assertThat(envAfterConnect.getContainerId()).isEqualTo("container-123");

        // Verify heartbeat succeeded
        boolean heartbeatOk = heartbeatLatch.await(5, TimeUnit.SECONDS);
        assertThat(heartbeatOk).isTrue();

        // Verify last heartbeat was set/updated
        ExecutionEnvironment envAfterHeartbeat =
                executionEnvironmentRepository.findById(envId).orElseThrow();
        assertThat(envAfterHeartbeat.getLastHeartbeat()).isNotNull();

        // Close the sidecar session
        sidecarSession.close();

        // Wait for server to process close event and transition status back to
        // DISCONNECTED
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ExecutionEnvironment envAfterClose =
                    executionEnvironmentRepository.findById(envId).orElseThrow();
            assertThat(envAfterClose.getStatus()).isEqualTo(EnvironmentStatus.DISCONNECTED);
        });

        // Verify received messages contain expected responses
        assertThat(receivedMessages).anyMatch(msg -> msg.contains("registered"));
        assertThat(receivedMessages).anyMatch(msg -> msg.contains("ok"));
    }

    @Test
    void testTeamEntityChangedEventOnEnvironmentStatusChange() throws Exception {
        // Create environment of type CONNECTOR
        var createRequest = new CreateExecutionEnvironmentRequest("My Subscribed Connector");
        CreateExecutionEnvironmentResponse response =
                executionEnvironmentService.createConnector(user.getId(), team.getId(), createRequest);
        var envDto = response.environment();
        String connectorToken = envDto.authToken();

        CountDownLatch eventLatch = new CountDownLatch(1);
        List<String> userReceivedMessages = new ArrayList<>();

        // Create a user client handler that subscribes to team events
        WebSocketHandler userClientHandler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // Auth user session
                JsonRpcInboundRequest authRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.Auth.METHOD, objectMapper.valueToTree(Map.of("token", userAuthToken)), 1);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(authRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
                String payload = message.getPayload();
                userReceivedMessages.add(payload);

                if (payload.contains("authenticated")) {
                    // Subscribe to team events
                    try {
                        JsonRpcInboundRequest subRequest = new JsonRpcInboundRequest(
                                ClientRpcPayload.Subscribe.METHOD,
                                objectMapper.valueToTree(
                                        Map.of("teamId", team.getId().toString())),
                                2);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(subRequest)));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else if (payload.contains("team_entity_changed") && payload.contains("ENVIRONMENTS")) {
                    eventLatch.countDown();
                }
            }
        };

        // Connect user UI client
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession userSession = client.execute(userClientHandler, "ws://localhost:" + port + "/ws/client")
                .get(5, TimeUnit.SECONDS);

        // Sleep briefly to ensure subscription is registered
        Thread.sleep(500);

        // Create sidecar client and connect to trigger registration (CONNECTED status
        // transition)
        CountDownLatch sidecarLatch = new CountDownLatch(1);
        WebSocketHandler sidecarHandler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                JsonRpcInboundRequest registerRequest = new JsonRpcInboundRequest(
                        EnvironmentRpcPayload.Register.METHOD,
                        objectMapper.valueToTree(Map.of("token", connectorToken)),
                        1);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(registerRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
                if (message.getPayload().contains("registered")) {
                    sidecarLatch.countDown();
                }
            }
        };

        WebSocketSession sidecarSession = client.execute(sidecarHandler, "ws://localhost:" + port + "/ws/env")
                .get(5, TimeUnit.SECONDS);

        boolean sidecarRegistered = sidecarLatch.await(5, TimeUnit.SECONDS);
        assertThat(sidecarRegistered).isTrue();

        // Verify the user received the team_entity_changed notification for
        // ENVIRONMENTS
        boolean receivedEvent = eventLatch.await(5, TimeUnit.SECONDS);
        assertThat(receivedEvent).isTrue();

        // Verify user received messages contain expected responses
        assertThat(userReceivedMessages).anyMatch(msg -> msg.contains("authenticated"));
        assertThat(userReceivedMessages)
                .anyMatch(msg -> msg.contains("team_entity_changed") && msg.contains("ENVIRONMENTS"));

        // Clean up
        sidecarSession.close();
        userSession.close();
    }
}
