package com.kratisai.controlplane.api.integration;

import static com.kratisai.controlplane.TestDataFactory.DUMMY_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.*;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.ChatFluxRegistry;
import com.kratisai.controlplane.service.JwtService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringIntegrationTest
class ChatControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ChatFluxRegistry chatFluxRegistry;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private WebApplicationContext wac;

    private String authToken;
    private String teamId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // Clear repositories
        databaseCleaner.cleanAll();

        var context = testDataFactory.createUserAndTeam();
        userId = context.user().getId();
        teamId = context.team().getId().toString();
        authToken = jwtService.generateAccessToken(userId, context.user().getEmail());
    }

    // ==================== Happy Path Tests ====================

    @Test
    void createChat_validRequest_registersFluxAndReturns202() throws Exception {
        Team team = teamRepository.findById(UUID.fromString(teamId)).orElseThrow();
        ModelProvider provider = testDataFactory.createModelProviderWithLiteLLM(
                team, "OpenAI", ProviderType.OPENAI, "sk-dummy", List.of("gpt-4o"));

        CreateChatRequest createRequest =
                new CreateChatRequest(team.getId(), provider.getId(), "gpt-4o", "Hello, system!");

        MvcResult result = mockMvc.perform(post("/api/v1/chats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest))
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        CreateChatResponse createChatResponse = objectMapper.readValue(responseJson, CreateChatResponse.class);

        assertThat(chatFluxRegistry.containsKey(createChatResponse.id())).isTrue();
    }

    @Test
    void createChat_validRequest_setsSummaryTitleGeneratedFromInitialPrompt() throws Exception {
        Team team = teamRepository.findById(UUID.fromString(teamId)).orElseThrow();
        ModelProvider provider = testDataFactory.createModelProviderWithLiteLLM(
                team, "OpenAI", ProviderType.OPENAI, "sk-dummy", List.of("gpt-4o"));
        fakeChatModel.addMatcherFirst(PromptMatcher.builder()
                .contains("Output only the title")
                .response("Debug and fix the flaky test suite now")
                .build());

        CreateChatRequest createRequest =
                new CreateChatRequest(team.getId(), provider.getId(), "gpt-4o", "Debug the flaky test suite");

        MvcResult result = mockMvc.perform(post("/api/v1/chats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest))
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isAccepted())
                .andReturn();

        CreateChatResponse createChatResponse =
                objectMapper.readValue(result.getResponse().getContentAsString(), CreateChatResponse.class);
        ChatEntity chat = chatRepository.findById(createChatResponse.id()).orElseThrow();
        assertThat(chat.getTitle()).isEqualTo("Debug and fix the flaky test suite now");
    }

    @Test
    void createChat_validRequest_fallsBackToPromptWhenTitleGenerationFails() throws Exception {
        Team team = teamRepository.findById(UUID.fromString(teamId)).orElseThrow();
        ModelProvider provider = testDataFactory.createModelProviderWithLiteLLM(
                team, "OpenAI", ProviderType.OPENAI, "sk-dummy", List.of("gpt-4o"));
        // The title LLM call fails, so the initial title falls back to the prompt itself.
        fakeChatModel.addMatcherFirst(PromptMatcher.builder()
                .contains("Output only the title")
                .throwsException(new RuntimeException("Simulated LLM failure"))
                .build());
        String userPrompt = "Hello, system!";
        CreateChatRequest createRequest = new CreateChatRequest(team.getId(), provider.getId(), "gpt-4o", userPrompt);

        MvcResult result = mockMvc.perform(post("/api/v1/chats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest))
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isAccepted())
                .andReturn();

        CreateChatResponse createChatResponse =
                objectMapper.readValue(result.getResponse().getContentAsString(), CreateChatResponse.class);
        ChatEntity chat = chatRepository.findById(createChatResponse.id()).orElseThrow();
        assertThat(chat.getTitle()).isEqualTo(userPrompt);
    }

    @Test
    void listChats_mineFilter_shouldReturnOnlyUserChats() throws Exception {
        // Create chats for current user
        Team team = teamRepository.findById(UUID.fromString(teamId)).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();

        chatRepository.save(new ChatEntity(team, user, "My Session 1"));
        chatRepository.save(new ChatEntity(team, user, "My Session 2"));

        // List chats with mine filter
        mockMvc.perform(get("/api/v1/chats")
                        .param("teamId", teamId)
                        .param("filter", "mine")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listChats_allFilter_shouldReturnAllTeamChats() throws Exception {
        Team team = teamRepository.findById(UUID.fromString(teamId)).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();

        // Create chats for current user
        chatRepository.save(new ChatEntity(team, user, "My Session"));

        // Register a second user and add to team
        RegisterUserRequest registerRequest = new RegisterUserRequest("member@example.com", "password123", "Member");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        User member = userRepository.findByEmail("member@example.com").orElseThrow();
        teamMemberRepository.save(new TeamMember(member, team, "member"));

        // Create chat for second user
        chatRepository.save(new ChatEntity(team, member, "Member Session"));

        // List chats with all filter
        mockMvc.perform(get("/api/v1/chats")
                        .param("teamId", teamId)
                        .param("filter", "all")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listChats_defaultFilter_shouldReturnMine() throws Exception {
        Team team = teamRepository.findById(UUID.fromString(teamId)).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();

        chatRepository.save(new ChatEntity(team, user, "My Session"));

        // List chats without filter (should default to mine)
        mockMvc.perform(get("/api/v1/chats").param("teamId", teamId).header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void listChats_noChats_shouldReturnEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/chats")
                        .param("teamId", teamId)
                        .param("filter", "mine")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listChats_limit20_shouldReturnOnly20() throws Exception {
        Team team = teamRepository.findById(UUID.fromString(teamId)).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();

        // Create 25 chats
        for (int i = 0; i < 25; i++) {
            chatRepository.save(new ChatEntity(team, user, "Session " + i));
        }

        mockMvc.perform(get("/api/v1/chats")
                        .param("teamId", teamId)
                        .param("filter", "mine")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20));
    }

    @Test
    void listChats_orderByUpdatedAtDesc_shouldReturnMostRecentFirst() throws Exception {
        Team team = teamRepository.findById(UUID.fromString(teamId)).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();

        ChatEntity chat1 = new ChatEntity(team, user, "Old Chat");
        chatRepository.save(chat1);

        // Small delay to ensure different timestamps
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        ChatEntity chat2 = new ChatEntity(team, user, "New Chat");
        chatRepository.save(chat2);

        mockMvc.perform(get("/api/v1/chats")
                        .param("teamId", teamId)
                        .param("filter", "mine")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("New Chat"))
                .andExpect(jsonPath("$[1].title").value("Old Chat"));
    }

    // ==================== Edge Cases ====================

    @Test
    void listChats_notTeamMember_shouldReturn403() throws Exception {
        // Register a second user not in the team
        RegisterUserRequest registerRequest =
                new RegisterUserRequest("outsider@example.com", "password123", "Outsider");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest("outsider@example.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String outsiderToken = objectMapper
                .readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        mockMvc.perform(get("/api/v1/chats")
                        .param("teamId", teamId)
                        .param("filter", "mine")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void listChats_invalidTeamId_shouldReturn500() throws Exception {
        mockMvc.perform(get("/api/v1/chats")
                        .param("teamId", "not-a-uuid")
                        .param("filter", "mine")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void listChats_missingTeamId_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/chats").param("filter", "mine").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listChats_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/chats").param("teamId", teamId).param("filter", "mine"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listChats_chatsPersistAcrossLogins() throws Exception {
        Team team = teamRepository.findById(UUID.fromString(teamId)).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();

        chatRepository.save(new ChatEntity(team, user, "Persistent Session"));

        // Login again (simulating a new session)
        LoginRequest loginRequest = new LoginRequest(user.getEmail(), DUMMY_PASSWORD);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String newToken = objectMapper
                .readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        // Verify chat still exists
        mockMvc.perform(get("/api/v1/chats")
                        .param("teamId", teamId)
                        .param("filter", "mine")
                        .header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Persistent Session"));
    }
}
