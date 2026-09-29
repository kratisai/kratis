package com.kratisai.controlplane.api.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.SteerCommentDto;
import com.kratisai.controlplane.api.restdto.SteerExecutionRequest;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentRpcClient;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.JwtService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@SpringIntegrationTest
class SandboxExecutionDiffControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private EnvironmentSessionRegistry sessionRegistry;

    @Autowired
    private EnvironmentRpcClient environmentRpcClient;

    private String authToken;
    private ChatEntity chat;
    private ExecutionEnvironment environment;
    private SandboxExecution execution;
    private WebSocketSession mockSession;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        authToken =
                jwtService.generateAccessToken(ctx.user().getId(), ctx.user().getEmail());
        chat = chatRepository.save(new ChatEntity(ctx.team(), ctx.user(), "Diff Test Chat"));

        environment = new ExecutionEnvironment();
        environment.setTeam(chat.getTeam());
        environment.setName("Diff Test Env");
        environment.setType(ExecutionEnvironmentType.SANDBOX);
        environment.setStatus(EnvironmentStatus.CONNECTED);
        environment = executionEnvironmentRepository.save(environment);

        execution = new SandboxExecution();
        execution.setChat(chat);
        execution.setEnvironment(environment);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution = sandboxExecutionRepository.save(execution);

        mockSession = Mockito.mock(WebSocketSession.class);
        Mockito.when(mockSession.getId()).thenReturn("mock-diff-session-" + UUID.randomUUID());
        Mockito.when(mockSession.isOpen()).thenReturn(true);

        Mockito.doAnswer(inv -> {
                    TextMessage msg = inv.getArgument(0);
                    JsonNode envelope = objectMapper.readTree(msg.getPayload());
                    if (envelope.has("method")) {
                        String method = envelope.get("method").asText();
                        ObjectNode response = objectMapper.createObjectNode();
                        response.put("jsonrpc", "2.0");
                        response.set("id", envelope.get("id"));

                        switch (method) {
                            case "env.git_diff_summary" -> {
                                ObjectNode result = response.putObject("result");
                                result.put("baseCommit", "1111111");
                                result.put("headCommit", "2222222");
                                result.put("totalAdditions", 15);
                                result.put("totalDeletions", 3);
                                ArrayNode files = result.putArray("files");
                                ObjectNode f = files.addObject();
                                f.put("path", "src/Test.java");
                                f.put("status", "MODIFIED");
                                f.put("additions", 15);
                                f.put("deletions", 3);
                                f.put("isCollapsedByDefault", false);
                                environmentRpcClient.completeResponse(response);
                            }
                            case "env.git_file_diff" -> {
                                ObjectNode result = response.putObject("result");
                                result.put(
                                        "path",
                                        envelope.path("params").path("path").asText("src/Test.java"));
                                result.put("patch", "@@ -1,3 +1,5 @@\n+line1\n+line2");
                                result.put("additions", 2);
                                result.put("deletions", 0);
                                result.put("totalLines", 45);
                                environmentRpcClient.completeResponse(response);
                            }
                            case "env.read_file_slice" -> {
                                ObjectNode result = response.putObject("result");
                                result.put(
                                        "path",
                                        envelope.path("params").path("path").asText("src/Test.java"));
                                result.put(
                                        "startLine",
                                        envelope.path("params")
                                                .path("startLine")
                                                .asInt(1));
                                ArrayNode lines = result.putArray("lines");
                                lines.add("line 1 content");
                                lines.add("line 2 content");
                                environmentRpcClient.completeResponse(response);
                            }
                            case "env.acp_prompt" -> {
                                ObjectNode result = response.putObject("result");
                                result.put("status", "completed");
                                environmentRpcClient.completeResponse(response);
                            }
                            default -> {
                                // Ignore unrecognized methods in mock
                            }
                        }
                    }
                    return null;
                })
                .when(mockSession)
                .sendMessage(Mockito.any(TextMessage.class));

        sessionRegistry.registerEnvironmentSession(mockSession, environment.getId());

        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void getDiffSummary_returnsSummary() throws Exception {
        mockMvc.perform(get(
                                "/api/v1/chats/{chatId}/executions/{executionId}/diff/summary",
                                chat.getId(),
                                execution.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCommit").value("1111111"))
                .andExpect(jsonPath("$.headCommit").value("2222222"))
                .andExpect(jsonPath("$.totalAdditions").value(15))
                .andExpect(jsonPath("$.totalDeletions").value(3))
                .andExpect(jsonPath("$.files[0].path").value("src/Test.java"))
                .andExpect(jsonPath("$.files[0].status").value("MODIFIED"));
    }

    @Test
    void getFileDiff_returnsFilePatch() throws Exception {
        mockMvc.perform(get(
                                "/api/v1/chats/{chatId}/executions/{executionId}/diff/file",
                                chat.getId(),
                                execution.getId())
                        .param("path", "src/Test.java")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("src/Test.java"))
                .andExpect(jsonPath("$.additions").value(2))
                .andExpect(jsonPath("$.totalLines").value(45))
                .andExpect(jsonPath("$.patch").isNotEmpty());
    }

    @Test
    void getReadFileSlice_returnsLines() throws Exception {
        mockMvc.perform(get(
                                "/api/v1/chats/{chatId}/executions/{executionId}/diff/context",
                                chat.getId(),
                                execution.getId())
                        .param("path", "src/Test.java")
                        .param("startLine", "1")
                        .param("endLine", "2")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("src/Test.java"))
                .andExpect(jsonPath("$.startLine").value(1))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0]").value("line 1 content"));
    }

    @Test
    void steerExecution_dispatchesSteeringPrompt() throws Exception {
        SteerExecutionRequest request = new SteerExecutionRequest(
                "Please fix the null check in parseToken",
                List.of(new SteerCommentDto("src/Test.java", 42, "token.trim()", "Check for null before trimming")));

        mockMvc.perform(post("/api/v1/chats/{chatId}/executions/{executionId}/steer", chat.getId(), execution.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());
    }

    @Test
    void steerExecution_whenExecutionCompleted_returnsBadRequest() throws Exception {
        execution.setStatus(SandboxExecutionStatus.COMPLETED);
        sandboxExecutionRepository.save(execution);

        SteerExecutionRequest request = new SteerExecutionRequest("Fix this", List.of());

        mockMvc.perform(post("/api/v1/chats/{chatId}/executions/{executionId}/steer", chat.getId(), execution.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getDiffSummary_unauthorized_returnsForbidden() throws Exception {
        TestDataFactory.TestContext otherCtx = testDataFactory.createUserAndTeam();
        String otherAuthToken = jwtService.generateAccessToken(
                otherCtx.user().getId(), otherCtx.user().getEmail());

        mockMvc.perform(get(
                                "/api/v1/chats/{chatId}/executions/{executionId}/diff/summary",
                                chat.getId(),
                                execution.getId())
                        .header("Authorization", "Bearer " + otherAuthToken))
                .andExpect(status().isForbidden());
    }
}
