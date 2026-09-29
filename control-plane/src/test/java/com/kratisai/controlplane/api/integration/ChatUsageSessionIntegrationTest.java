package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.CreateChatRequest;
import com.kratisai.controlplane.api.restdto.CreateChatResponse;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.ChatUsageSessionService;
import com.kratisai.controlplane.service.JwtService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
class ChatUsageSessionIntegrationTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ChatUsageSessionRepository chatUsageSessionRepository;

    @Autowired
    private ChatUsageSessionService chatUsageSessionService;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private WebApplicationContext wac;

    private String authToken;
    private String teamId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        databaseCleaner.cleanAll();

        var context = testDataFactory.createUserAndTeam();
        UUID userId = context.user().getId();
        teamId = context.team().getId().toString();
        authToken = jwtService.generateAccessToken(userId, context.user().getEmail());
    }

    @Test
    void chatCreationAndUsageSessionFlow() throws Exception {
        Team team = teamRepository.findById(UUID.fromString(teamId)).orElseThrow();
        ModelProvider provider = testDataFactory.createModelProviderWithLiteLLM(
                team, "OpenAI", ProviderType.OPENAI, "sk-dummy", List.of("gpt-4o"));

        CreateChatRequest createRequest =
                new CreateChatRequest(team.getId(), provider.getId(), "gpt-4o", "Hello, usage tracking!");

        MvcResult result = mockMvc.perform(post("/api/v1/chats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest))
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isAccepted())
                .andReturn();

        CreateChatResponse response =
                objectMapper.readValue(result.getResponse().getContentAsString(), CreateChatResponse.class);
        UUID chatId = response.id();

        ChatEntity chat = chatRepository.findById(chatId).orElseThrow();
        String litellmModelName =
                "openai-openai-gpt-4o-" + team.getId().toString().substring(0, 8);

        // Verify a ChatUsageSession was created with a virtual key during chat creation via PlanningAgentService
        List<ChatUsageSession> sessions = chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chatId);
        assertThat(sessions).isNotEmpty();
        ChatUsageSession session = sessions.getFirst();
        assertThat(session.getUsage().getVirtualKey()).isNotNull();
        assertThat(session.isEnded()).isFalse();
        assertThat(session.getModel()).isEqualTo("gpt-4o");

        // Test inactivity timeout / returning after > 4 hours spawns a new session
        UUID oldSessionId = session.getId();
        String oldVirtualKey = session.getUsage().getVirtualKey();
        session.getUsage().setUsageLastUpdatedAt(Instant.now().minus(5, ChronoUnit.HOURS));
        chatUsageSessionRepository.saveAndFlush(session);

        ChatUsageSession newSession =
                chatUsageSessionService.getOrCreateActiveSession(chat, "gpt-4o", litellmModelName);

        assertThat(newSession.getId()).isNotEqualTo(oldSessionId);
        assertThat(newSession.getUsage().getVirtualKey()).isNotNull();
        assertThat(newSession.getUsage().getVirtualKey()).isNotEqualTo(oldVirtualKey);

        // Verify old session was ended
        ChatUsageSession oldSessionReloaded =
                chatUsageSessionRepository.findById(oldSessionId).orElseThrow();
        assertThat(oldSessionReloaded.isEnded()).isTrue();
    }
}
