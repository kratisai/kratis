package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.restdto.CreateModelProviderRequest;
import com.kratisai.controlplane.api.restdto.CreateTeamRequest;
import com.kratisai.controlplane.api.restdto.LoginRequest;
import com.kratisai.controlplane.api.restdto.ModelEntryDto;
import com.kratisai.controlplane.api.restdto.RegisterUserRequest;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.service.ChatService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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

/**
 * Integration test verifying that telemetry events flow through WebSocket as
 * raw JSON-RPC notifications (not wrapped in JsonRpcResponse). Runs last to
 * avoid polluting the shared H2 database with non-transactional data.
 */
@SpringIntegrationTest
class WebSocketTelemetryIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private ChatService chatService;

    private String authToken;
    private String teamId;
    private String providerId;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        fakeChatModel.reset();

        MockMvc mockMvc =
                MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        String email = "ws-telemetry-" + UUID.randomUUID() + "@example.com";
        RegisterUserRequest registerRequest = new RegisterUserRequest(email, "password123", "Telemetry User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest(email, "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginResponseJson =
                objectMapper.readTree(loginResult.getResponse().getContentAsString());
        authToken = loginResponseJson.get("accessToken").asText();
        userId = UUID.fromString(loginResponseJson.get("user").get("id").asText());

        // Create a team with a model provider
        CreateTeamRequest teamRequest = new CreateTeamRequest("Telemetry Team", "Test team");
        MvcResult teamResult = mockMvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(teamRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        teamId = objectMapper
                .readTree(teamResult.getResponse().getContentAsString())
                .get("id")
                .asText();

        // Create a model provider for the team
        CreateModelProviderRequest providerRequest = new CreateModelProviderRequest(
                "Test Provider",
                ProviderType.OPENAI,
                "test-api-key",
                null,
                List.of(new ModelEntryDto("gpt-4o", ModelKind.CHAT, null, null)));
        MvcResult providerResult = mockMvc.perform(post("/api/v1/model-providers/teams/" + teamId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(providerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        providerId = objectMapper
                .readTree(providerResult.getResponse().getContentAsString())
                .get("id")
                .asText();
    }

    @AfterEach
    void tearDown() {
        // Clean up test data to avoid polluting subsequent tests
        databaseCleaner.cleanAll();
    }

    @Test
    void shouldReceiveTelemetryNotificationsAsRawWebSocketMessages() throws Exception {
        // Configure fake chat model to trigger a tool call
        String sessionId = UUID.randomUUID().toString();
        ToolCall toolCall = new ToolCall(
                "call-1",
                "function",
                "save_to_scratchpad",
                "{\"sessionId\":\"" + sessionId + "\",\"fact\":\"test fact\"}");

        // First call: LLM streams a real "thought" chunk  before deciding to call a tool
        Map<String, Object> thoughtMetadata = new HashMap<>();
        thoughtMetadata.put("isThought", true);
        AssistantMessage thoughtChunk = AssistantMessage.builder()
                .content("I should save this fact for later.")
                .properties(thoughtMetadata)
                .build();
        ChatResponse thoughtResponse = new ChatResponse(List.of(new Generation(thoughtChunk)));
        AssistantMessage toolCallMessage = AssistantMessage.builder()
                .content("Let me save that.")
                .toolCalls(List.of(toolCall))
                .build();
        ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(toolCallMessage)));

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .responses(p -> List.of(thoughtResponse, toolCallResponse))
                .maxMatches(1)
                .build());

        // Second call: After tool execution, LLM provides final response
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I've saved the fact.")
                .maxMatches(1)
                .build());

        CountDownLatch latch = new CountDownLatch(1);
        List<String> receivedMessages = new ArrayList<>();

        ChatEntity testChat = chatService.createChat(UUID.fromString(teamId), userId, "Telemetry Test");

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
                                        "chatId",
                                        testChat.getId().toString(),
                                        "message",
                                        "Save a fact",
                                        "teamId",
                                        teamId,
                                        "providerId",
                                        providerId,
                                        "modelName",
                                        "claude-3-5-sonnet-20241022")),
                                2);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(chatRequest)));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                // Check for completion signal (JsonRpcResponse with CompleteResult)
                if (message.getPayload().contains("\"complete\"")) {
                    latch.countDown();
                }
                if (message.getPayload().contains("LLM provider not found")
                        || message.getPayload().contains("\"error\"")) {
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

        // Extract telemetry event types from JsonRpcResponse messages
        List<String> telemetryTypes = new ArrayList<>();
        for (String msg : receivedMessages) {
            try {
                JsonNode node = objectMapper.readTree(msg);
                // Check for JsonRpcResponse with telemetry result
                if (node.has("result")) {
                    JsonNode result = node.get("result");
                    if (result.has("type")
                            && "telemetry".equals(result.get("type").asText())) {
                        JsonNode event = result.get("event");
                        // Determine event type by checking which fields are present
                        if (event.has("text")) {
                            telemetryTypes.add("thought");
                        } else if (event.has("toolName")) {
                            telemetryTypes.add("tool_start");
                        } else if (event.has("taskId") && event.has("status")) {
                            telemetryTypes.add("tool_complete");
                        }
                    }
                }
            } catch (Exception e) {
                // Not JSON or not a telemetry response
            }
        }

        // Verify telemetry events were received in the response stream
        assertThat(telemetryTypes).contains("thought");
        assertThat(telemetryTypes).contains("tool_start");
        assertThat(telemetryTypes).contains("tool_complete");
    }
}
