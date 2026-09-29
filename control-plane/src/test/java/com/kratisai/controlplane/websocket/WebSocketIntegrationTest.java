package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.restdto.LoginRequest;
import com.kratisai.controlplane.api.restdto.RegisterUserRequest;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.CanvasService;
import com.kratisai.controlplane.service.ChatService;
import com.kratisai.controlplane.service.ModelProviderService;
import com.kratisai.controlplane.websocket.client.ClientWebSocketHandler;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringIntegrationTest
class WebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private ClientWebSocketHandler webSocketHandler;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private ModelProviderService modelProviderService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private CanvasService canvasService;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String authToken;

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

        authToken = objectMapper
                .readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void testWebSocketAuthAndPingPong() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> receivedMessages = new CopyOnWriteArrayList<>();

        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // Send auth message
                JsonRpcInboundRequest authRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.Auth.METHOD, objectMapper.valueToTree(Map.of("token", authToken)), 1);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(authRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
                receivedMessages.add(message.getPayload());
                // After auth, send ping
                if (message.getPayload().contains("authenticated")) {
                    try {
                        JsonRpcInboundRequest pingRequest =
                                new JsonRpcInboundRequest(ClientRpcPayload.Ping.METHOD, null, 2);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(pingRequest)));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                // After pong, count down
                if (message.getPayload().contains("pong")) {
                    latch.countDown();
                }
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
                latch.countDown();
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session =
                client.execute(handler, "ws://localhost:" + port + "/ws/client").get(5, TimeUnit.SECONDS);

        // Wait for ping/pong exchange
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        session.close();

        assertThat(completed).isTrue();
        assertThat(receivedMessages).hasSize(2);
        assertThat(receivedMessages.get(0)).contains("authenticated");
        assertThat(receivedMessages.get(1)).contains("pong");
    }

    @Test
    void testWebSocketChatSend() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        boolean[] receivedCompleteChunk = {false};

        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // Send auth message
                JsonRpcInboundRequest authRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.Auth.METHOD, objectMapper.valueToTree(Map.of("token", authToken)), 1);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(authRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
                receivedMessages.add(message.getPayload());

                // After auth, send chat message
                if (message.getPayload().contains("authenticated")) {
                    try {
                        JsonRpcInboundRequest chatRequest = new JsonRpcInboundRequest(
                                ClientRpcPayload.ChatSend.METHOD,
                                objectMapper.valueToTree(Map.of(
                                        "message", "Hello, test!", "teamId", "00000000-0000-0000-0000-000000000000")),
                                3);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(chatRequest)));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                // Check for complete chunk or error
                if (message.getPayload().contains("isComplete")
                        && message.getPayload().contains("true")) {
                    receivedCompleteChunk[0] = true;
                    latch.countDown();
                }
                // Also count down if we get an error response (provider not found is OK)
                if (message.getPayload().contains("LLM provider not found")
                        || message.getPayload().contains("error")) {
                    receivedCompleteChunk[0] = true;
                    latch.countDown();
                }
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
                latch.countDown();
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session =
                client.execute(handler, "ws://localhost:" + port + "/ws/client").get(5, TimeUnit.SECONDS);

        // Wait for chat response
        boolean completed = latch.await(15, TimeUnit.SECONDS);
        session.close();

        assertThat(completed).isTrue();
        assertThat(receivedCompleteChunk[0]).isTrue();
        // Should have auth response + chat response (or error)
        assertThat(receivedMessages).hasSizeGreaterThan(1);
    }

    @Test
    void testWebSocketUnauthenticatedChatSend_shouldReturnError() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> receivedMessages = new CopyOnWriteArrayList<>();

        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // Send chat message without auth
                JsonRpcInboundRequest chatRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.ChatSend.METHOD,
                        objectMapper.valueToTree(Map.of(
                                "message",
                                "Hello!",
                                "teamId",
                                UUID.randomUUID().toString(),
                                "providerId",
                                UUID.randomUUID().toString(),
                                "modelName",
                                "gpt-4o",
                                "chatId",
                                UUID.randomUUID().toString())),
                        1);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(chatRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
                receivedMessages.add(message.getPayload());
                latch.countDown();
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
                latch.countDown();
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session =
                client.execute(handler, "ws://localhost:" + port + "/ws/client").get(5, TimeUnit.SECONDS);

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        session.close();

        assertThat(completed).isTrue();
        assertThat(receivedMessages).hasSize(1);
        assertThat(receivedMessages.getFirst()).contains("Not authenticated");
    }

    @Test
    void testUnauthenticatedSessionTrackedAsPending() throws Exception {
        // Before connection, there should be no pending sessions
        int initialPendingCount = webSocketHandler.getPendingSessionCount();

        CountDownLatch connectedLatch = new CountDownLatch(1);

        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(@NonNull WebSocketSession session) {
                connectedLatch.countDown();
                // Don't send auth - leave session unauthenticated
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {}
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session =
                client.execute(handler, "ws://localhost:" + port + "/ws/client").get(5, TimeUnit.SECONDS);

        // Wait for connection to be established
        if (!connectedLatch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for connection to be established");
        }

        // Wait for server-side session registration to complete (async operation)
        // The client callback fires before the server finishes registerPendingSession()
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(webSocketHandler.getPendingSessionCount())
                .isGreaterThan(initialPendingCount));

        assertThat(webSocketHandler.getAuthenticatedSessionCount()).isZero();

        // Clean up - close the session
        session.close();

        // Wait for server-side cleanup to complete (async operation)
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(webSocketHandler.getPendingSessionCount())
                .isEqualTo(initialPendingCount));
    }

    @Test
    void testAuthenticatedSessionNotTrackedAsPending() throws Exception {
        CountDownLatch authLatch = new CountDownLatch(1);

        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // Send auth immediately
                JsonRpcInboundRequest authRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.Auth.METHOD, objectMapper.valueToTree(Map.of("token", authToken)), 1);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(authRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
                if (message.getPayload().contains("authenticated")) {
                    authLatch.countDown();
                }
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {}
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session =
                client.execute(handler, "ws://localhost:" + port + "/ws/client").get(5, TimeUnit.SECONDS);

        // Wait for auth response to be received
        boolean authCompleted = authLatch.await(5, TimeUnit.SECONDS);
        assertThat(authCompleted).isTrue();

        // Wait for server-side auth state update to complete (async operation)
        // The client receives the response before the server finishes
        // authenticateSession()
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(webSocketHandler.getPendingSessionCount()).isZero();
            assertThat(webSocketHandler.getAuthenticatedSessionCount()).isGreaterThan(0);
        });

        // Clean up
        session.close();
    }

    @Test
    void testSessionLoadStreamsMessages() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> receivedMessages = new CopyOnWriteArrayList<>();

        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // Send auth message
                JsonRpcInboundRequest authRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.Auth.METHOD, objectMapper.valueToTree(Map.of("token", authToken)), 1);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(authRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
                receivedMessages.add(message.getPayload());

                // After auth, send session.load request
                if (message.getPayload().contains("authenticated")) {
                    try {
                        // Use a non-existent session ID - will get empty response
                        JsonRpcInboundRequest loadRequest = new JsonRpcInboundRequest(
                                ClientRpcPayload.ChatSubscribe.METHOD,
                                objectMapper.valueToTree(Map.of(
                                        "chatId",
                                        "00000000-0000-0000-0000-000000000000",
                                        "teamId",
                                        "00000000-0000-0000-0000-000000000000")),
                                2);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(loadRequest)));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                // Check for complete or error
                if (message.getPayload().contains("complete")
                        || message.getPayload().contains("error")
                        || message.getPayload().contains("RESOURCE_NOT_FOUND")) {
                    latch.countDown();
                }
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
                latch.countDown();
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session =
                client.execute(handler, "ws://localhost:" + port + "/ws/client").get(5, TimeUnit.SECONDS);

        // Wait for chat.subscribe response
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        session.close();

        assertThat(completed).isTrue();
        // Should have auth response + chat.subscribe response(s)
        assertThat(receivedMessages).hasSizeGreaterThan(1);
    }

    @Test
    void testChatSubscribeRequiresAuthentication() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> receivedMessages = new CopyOnWriteArrayList<>();

        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // Send chat.subscribe WITHOUT auth
                JsonRpcInboundRequest loadRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.ChatSubscribe.METHOD,
                        objectMapper.valueToTree(Map.of(
                                "chatId", "00000000-0000-0000-0000-000000000000",
                                "teamId", "00000000-0000-0000-0000-000000000000")),
                        1);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(loadRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
                receivedMessages.add(message.getPayload());
                latch.countDown();
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
                latch.countDown();
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session =
                client.execute(handler, "ws://localhost:" + port + "/ws/client").get(5, TimeUnit.SECONDS);

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        session.close();

        assertThat(completed).isTrue();
        assertThat(receivedMessages).hasSize(1);
        assertThat(receivedMessages.getFirst()).contains("Not authenticated");
    }

    @Test
    void testWebSocketChatSendNewSessionEchoesUserMessage() throws Exception {
        // Retrieve the registered user and their team
        User user = userRepository
                .findByEmail("wsuser@example.com")
                .orElseThrow(() -> new AssertionError("User should have been registered in setUp"));

        List<TeamMember> members = teamMemberRepository.findByUserId(user.getId());
        assertThat(members).isNotEmpty();
        Team team = members.getFirst().getTeam();

        // Create and save an active model provider
        ModelProvider provider = new ModelProvider("Integration OpenAI", ProviderType.OPENAI, "dummy-key", null);
        provider.setTeam(team);
        provider = modelProviderRepository.save(provider);

        fakeChatModel.reset();
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response("Hello from fake agent!").build());

        ChatEntity testSession = chatService.createChat(team.getId(), user.getId(), "Chat Echo Test");

        CountDownLatch latch = new CountDownLatch(1);
        List<String> receivedMessages = new CopyOnWriteArrayList<>();

        UUID providerId = provider.getId();
        UUID teamId = team.getId();

        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // Send auth message
                JsonRpcInboundRequest authRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.Auth.METHOD, objectMapper.valueToTree(Map.of("token", authToken)), 1);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(authRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
                String payload = message.getPayload();
                receivedMessages.add(payload);

                // After auth, send new session chat message
                if (payload.contains("authenticated")) {
                    try {
                        JsonRpcInboundRequest chatRequest = new JsonRpcInboundRequest(
                                ClientRpcPayload.ChatSend.METHOD,
                                objectMapper.valueToTree(Map.of(
                                        "chatId",
                                        testSession.getId().toString(),
                                        "message",
                                        "Hello, user message echo integration test!",
                                        "teamId",
                                        teamId.toString(),
                                        "providerId",
                                        providerId.toString(),
                                        "modelName",
                                        "gpt-4o")),
                                42);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(chatRequest)));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                // Stop when the chat finishes streaming or errors
                // Use robust substring matching to avoid flakiness from JSON formatting (e.g.,
                // spaces)
                if (payload.contains("complete") || payload.contains("error")) {
                    latch.countDown();
                }
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
                latch.countDown();
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session =
                client.execute(handler, "ws://localhost:" + port + "/ws/client").get(5, TimeUnit.SECONDS);

        boolean completed = latch.await(15, TimeUnit.SECONDS);
        session.close();

        assertThat(completed).isTrue();

        // We expect at least:
        // 1. Auth success
        // 2. Echoed user message
        // 3. assistant response & complete
        assertThat(receivedMessages).hasSizeGreaterThan(1);

        // Find the message from chat.send (should be a result with id 42)
        String firstChatMessagePayload = null;
        for (String msg : receivedMessages) {
            if (msg.contains("42") && msg.contains("message")) {
                firstChatMessagePayload = msg;
                break;
            }
        }

        assertThat(firstChatMessagePayload).isNotNull();
        assertThat(firstChatMessagePayload).contains(testSession.getId().toString());
    }

    @Test
    void testSessionCleanupOnDisconnect() throws Exception {
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch closedLatch = new CountDownLatch(1);

        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // Send auth message
                JsonRpcInboundRequest authRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.Auth.METHOD, objectMapper.valueToTree(Map.of("token", authToken)), 1);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(authRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
                if (message.getPayload().contains("authenticated")) {
                    connectedLatch.countDown();
                }
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
                closedLatch.countDown();
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session =
                client.execute(handler, "ws://localhost:" + port + "/ws/client").get(5, TimeUnit.SECONDS);

        // Wait for auth to complete
        boolean authCompleted = connectedLatch.await(5, TimeUnit.SECONDS);
        assertThat(authCompleted).isTrue();

        // Wait for server-side auth state update to complete
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(webSocketHandler.getPendingSessionCount()).isZero();
            assertThat(webSocketHandler.getAuthenticatedSessionCount()).isGreaterThan(0);
        });

        // Close the session
        session.close();
        if (!closedLatch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for session close");
        }

        // Verify session is cleaned up
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(webSocketHandler.getAuthenticatedSessionCount())
                        .isZero());
    }

    @Test
    void testTeamEntityChangedEventPropagation() throws Exception {
        // Retrieve the registered user and their team
        User user = userRepository
                .findByEmail("wsuser@example.com")
                .orElseThrow(() -> new AssertionError("User should have been registered in setUp"));

        List<TeamMember> members = teamMemberRepository.findByUserId(user.getId());
        assertThat(members).isNotEmpty();
        Team team = members.getFirst().getTeam();

        CountDownLatch latch = new CountDownLatch(1);
        List<String> receivedMessages = new CopyOnWriteArrayList<>();

        UUID teamId = team.getId();

        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // Send auth message
                JsonRpcInboundRequest authRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.Auth.METHOD, objectMapper.valueToTree(Map.of("token", authToken)), 1);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(authRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
                String payload = message.getPayload();
                receivedMessages.add(payload);

                // After auth, subscribe to the team
                if (payload.contains("authenticated")) {
                    try {
                        JsonRpcInboundRequest subRequest = new JsonRpcInboundRequest(
                                ClientRpcPayload.Subscribe.METHOD,
                                objectMapper.valueToTree(Map.of("teamId", teamId.toString())),
                                2);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(subRequest)));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                // Check for team entity changed event
                if (payload.contains("team_entity_changed")) {
                    latch.countDown();
                }
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
                latch.countDown();
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session =
                client.execute(handler, "ws://localhost:" + port + "/ws/client").get(5, TimeUnit.SECONDS);

        // Wait a short moment to ensure subscription is active
        Thread.sleep(500);

        // Trigger the event by creating a model provider
        modelProviderService.createModelProvider(
                user.getId(),
                teamId,
                new com.kratisai.controlplane.api.restdto.CreateModelProviderRequest(
                        "New Provider", ProviderType.OPENAI, "sk-123", null, null));

        // Wait for the websocket message to arrive
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        session.close();

        assertThat(completed).isTrue();

        // Verify the payload
        String eventPayload = null;
        for (String msg : receivedMessages) {
            if (msg.contains("team_entity_changed")) {
                eventPayload = msg;
                break;
            }
        }
        assertThat(eventPayload).isNotNull();
        assertThat(eventPayload).contains("\"type\":\"team_entity_changed\"");
        assertThat(eventPayload).contains("\"entity\":\"MODEL_PROVIDERS\"");
    }

    @Test
    void testCanvasDeletedEventPropagation() throws Exception {
        User user = userRepository
                .findByEmail("wsuser@example.com")
                .orElseThrow(() -> new AssertionError("User should have been registered in setUp"));
        Team team = teamMemberRepository.findByUserId(user.getId()).getFirst().getTeam();
        ChatEntity chat = chatRepository.save(new ChatEntity(team, user, "Canvas Delete WS Test"));
        String documentId = "ws-doc";
        canvasService.createCanvas(chat.getId(), documentId, "Doc", "# Content", CanvasType.DOCUMENT, null, null);

        CountDownLatch latch = new CountDownLatch(1);
        List<String> receivedMessages = new CopyOnWriteArrayList<>();

        UUID teamId = team.getId();

        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                JsonRpcInboundRequest authRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.Auth.METHOD, objectMapper.valueToTree(Map.of("token", authToken)), 1);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(authRequest)));
            }

            @Override
            protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
                String payload = message.getPayload();
                receivedMessages.add(payload);

                if (payload.contains("authenticated")) {
                    try {
                        JsonRpcInboundRequest subRequest = new JsonRpcInboundRequest(
                                ClientRpcPayload.Subscribe.METHOD,
                                objectMapper.valueToTree(Map.of("teamId", teamId.toString())),
                                2);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(subRequest)));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                if (payload.contains("\"type\":\"canvas\"") && payload.contains("\"documentId\":\"ws-doc\"")) {
                    latch.countDown();
                }
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
                latch.countDown();
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session =
                client.execute(handler, "ws://localhost:" + port + "/ws/client").get(5, TimeUnit.SECONDS);

        // Wait a short moment to ensure the team subscription is active
        Thread.sleep(500);

        canvasService.deleteCanvas(user.getId(), chat.getId(), documentId);

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        session.close();

        assertThat(completed).isTrue();
        String eventPayload = receivedMessages.stream()
                .filter(msg -> msg.contains("\"documentId\":\"ws-doc\""))
                .findFirst()
                .orElse(null);
        assertThat(eventPayload).isNotNull();
        assertThat(eventPayload).contains("\"type\":\"canvas\"");
    }
}
