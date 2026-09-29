package com.kratisai.controlplane.controller;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.LoginRequest;
import com.kratisai.controlplane.api.restdto.RegisterUserRequest;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.CanvasService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Controller integration test verifying that the chat execution endpoint
 * accepts canvasId in the request body and validates it correctly.
 */
@SpringIntegrationTest
class ChatExecutionControllerCanvasTest {

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private CanvasService canvasService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;
    private String authToken;
    private UUID chatId;
    private UUID environmentId;
    private UUID modelProviderId;
    private UUID repositoryId;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();

        mockMvc =
                MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        testDataFactory.createUserAndTeam();

        // Register and login to get auth token
        String email = "canvas-ctrl-test-" + UUID.randomUUID() + "@example.com";
        RegisterUserRequest registerRequest = new RegisterUserRequest(email, "password123", "Test User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest(email, "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        authToken = objectMapper
                .readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        User authUser = userRepository.findByEmail(email).orElseThrow();
        Team authTeam =
                teamMemberRepository.findByUserId(authUser.getId()).getFirst().getTeam();

        ChatEntity chat = chatRepository.save(new ChatEntity(authTeam, authUser, "Canvas Controller Test"));
        chatId = chat.getId();

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(authTeam);
        env.setName("Test Sandbox");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env = executionEnvironmentRepository.save(env);
        environmentId = env.getId();

        // Create a model provider for the team
        Team team = chatRepository.findById(chatId).orElseThrow().getTeam();
        ModelProvider mp = new ModelProvider("Test Provider", ProviderType.OPENAI, "sk-key", null);
        mp.setTeam(team);
        mp.setModels(List.of(new ProviderModel("gpt-4o", ModelKind.CHAT)));
        mp = modelProviderRepository.save(mp);
        modelProviderId = mp.getId();

        // Create a repository to bind to the SPEC canvas
        Repository repository =
                new Repository("canvas-repo", "https://example.com/canvas-repo.git", "main", RepositoryType.GENERIC);
        repository.setTeam(team);
        repository = repositoryRepository.save(repository);
        repositoryId = repository.getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void shouldReturn201WhenCanvasIdIsProvided() throws Exception {
        // Create a SPEC canvas document bound to a repository
        Repository repository = repositoryRepository.findById(repositoryId).orElseThrow();
        canvasService.createCanvas(
                chatId, "plan-doc", "Execution Plan", "# Plan\n1. Do stuff", CanvasType.SPEC, repository, null);

        Map<String, Object> requestBody = Map.of(
                "environmentId", environmentId.toString(),
                "harness", "OPENCODE",
                "taskPrompt", "Execute the plan",
                "canvasId", "plan-doc",
                "modelProviderId", modelProviderId.toString(),
                "modelName", "gpt-4o");

        mockMvc.perform(post("/api/v1/chats/" + chatId + "/executions")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.taskPrompt").exists())
                .andExpect(jsonPath("$.harness").value("OPENCODE"));
    }

    @Test
    void shouldReturn400WhenCanvasIdIsMissing() throws Exception {
        Map<String, Object> requestBody = Map.of(
                "environmentId", environmentId.toString(),
                "harness", "OPENCODE",
                "taskPrompt", "Execute without canvas");

        mockMvc.perform(post("/api/v1/chats/" + chatId + "/executions")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest());
    }
}
