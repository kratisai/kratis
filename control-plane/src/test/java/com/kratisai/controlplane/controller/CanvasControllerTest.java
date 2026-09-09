package com.kratisai.controlplane.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.LoginRequest;
import com.kratisai.controlplane.api.restdto.RegisterUserRequest;
import com.kratisai.controlplane.model.CanvasType;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.CanvasService;
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

/** Controller integration test for soft-deleting a canvas document via REST. */
@SpringIntegrationTest
class CanvasControllerTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;
    private String authToken;
    private UUID chatId;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();

        mockMvc =
                MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        testDataFactory.createUserAndTeam();

        String email = "canvas-delete-test-" + UUID.randomUUID() + "@example.com";
        registerAndLogin(email);
        authToken = login(email);
        User authUser = userRepository.findByEmail(email).orElseThrow();
        Team authTeam =
                teamMemberRepository.findByUserId(authUser.getId()).getFirst().getTeam();

        ChatEntity chat = chatRepository.save(new ChatEntity(authTeam, authUser, "Canvas Delete Test"));
        chatId = chat.getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void deleteCanvasDocument_returns204AndSoftDeletes() throws Exception {
        canvasService.createCanvas(chatId, "doc-1", "Doc One", "# Content", CanvasType.DOCUMENT, null, null);

        mockMvc.perform(delete("/api/v1/chats/" + chatId + "/canvas/documents/doc-1")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        assertThat(canvasService.getCanvas(chatId, "doc-1")).isNull();
        assertThat(canvasService.getCanvasesForChat(chatId)).isEmpty();

        // Deleting again is idempotent.
        mockMvc.perform(delete("/api/v1/chats/" + chatId + "/canvas/documents/doc-1")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteCanvasDocument_documentNotFound_returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/chats/" + chatId + "/canvas/documents/missing-doc")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteCanvasDocument_chatNotFound_returns404() throws Exception {
        UUID missingChat = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/chats/" + missingChat + "/canvas/documents/doc-1")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteCanvasDocument_unauthenticated_returns403() throws Exception {
        canvasService.createCanvas(chatId, "doc-1", "Doc One", "# Content", CanvasType.DOCUMENT, null, null);

        mockMvc.perform(delete("/api/v1/chats/" + chatId + "/canvas/documents/doc-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteCanvasDocument_nonMember_returns403() throws Exception {
        canvasService.createCanvas(chatId, "doc-1", "Doc One", "# Content", CanvasType.DOCUMENT, null, null);

        String outsiderEmail = "canvas-delete-outsider-" + UUID.randomUUID() + "@example.com";
        registerAndLogin(outsiderEmail);
        String outsiderToken = login(outsiderEmail);

        mockMvc.perform(delete("/api/v1/chats/" + chatId + "/canvas/documents/doc-1")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    private void registerAndLogin(String email) throws Exception {
        RegisterUserRequest registerRequest = new RegisterUserRequest(email, "password123", "Test User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());
    }

    private String login(String email) throws Exception {
        LoginRequest loginRequest = new LoginRequest(email, "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper
                .readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();
    }
}
