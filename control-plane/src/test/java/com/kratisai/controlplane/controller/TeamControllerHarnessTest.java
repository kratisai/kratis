package com.kratisai.controlplane.controller;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.LoginRequest;
import com.kratisai.controlplane.api.restdto.RegisterUserRequest;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.UserRepository;
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

@SpringIntegrationTest
class TeamControllerHarnessTest {

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;
    private String authToken;
    private UUID teamId;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();

        mockMvc =
                MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        testDataFactory.createUserAndTeam();

        String email = "harness-test-" + UUID.randomUUID() + "@example.com";
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
        teamId = authTeam.getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void shouldReturnListOfHarnesses() throws Exception {
        mockMvc.perform(get("/api/v1/teams/" + teamId + "/harnesses").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].value").exists())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[?(@.value == 'OPENCODE')]").exists());
    }

    @Test
    void shouldReturn403WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/teams/" + teamId + "/harnesses")).andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn403WhenNotTeamMember() throws Exception {
        // Register a second user who is NOT a member of the first user's team
        String email2 = "nonmember-" + UUID.randomUUID() + "@example.com";
        RegisterUserRequest registerRequest2 = new RegisterUserRequest(email2, "password123", "Non Member");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest2)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest2 = new LoginRequest(email2, "password123");
        MvcResult loginResult2 = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest2)))
                .andExpect(status().isOk())
                .andReturn();

        String authToken2 = objectMapper
                .readTree(loginResult2.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        // The second user's own team is different; use the first user's teamId
        mockMvc.perform(get("/api/v1/teams/" + teamId + "/harnesses").header("Authorization", "Bearer " + authToken2))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn404ForNonExistentTeam() throws Exception {
        UUID nonExistentTeamId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/teams/" + nonExistentTeamId + "/harnesses")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }
}
