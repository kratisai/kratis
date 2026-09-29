package com.kratisai.controlplane.api.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.restdto.*;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.JwtService;
import com.kratisai.controlplane.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringIntegrationTest
class TeamControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private WebApplicationContext wac;

    private String authToken;
    private String testUserEmail;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        databaseCleaner.cleanAll();

        // Register via UserService (creates default team) then mint JWT directly —
        // avoids the HTTP login round-trip without losing the default team side effect.
        testUserEmail = "teamuser@example.com";
        userService.register(testUserEmail, "password123", "Team User");
        var user = userRepository.findByEmail(testUserEmail).orElseThrow();
        authToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
    }

    @Test
    void createTeam_validRequest_shouldReturn201() throws Exception {
        CreateTeamRequest request = new CreateTeamRequest("My Team", "A test team");

        mockMvc.perform(post("/api/v1/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My Team"))
                .andExpect(jsonPath("$.role").value("owner"))
                .andExpect(jsonPath("$.isDefault").value(false));
    }

    @Test
    void listTeams_shouldReturnTeamsForUser() throws Exception {
        // A default team is created during registration, so we have 1 team already
        // Create another team
        CreateTeamRequest request = new CreateTeamRequest("My Team", "A test team");
        mockMvc.perform(post("/api/v1/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // List teams - should have 2 teams now (default + created)
        mockMvc.perform(get("/api/v1/teams").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].name").value("My Team"))
                .andExpect(jsonPath("$[1].isDefault").value(false));
    }

    @Test
    void listTeams_defaultTeam_shouldReturnIsDefaultTrue() throws Exception {
        // List teams - should have 1 default team from registration
        String responseBody = mockMvc.perform(get("/api/v1/teams").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode teams = objectMapper.readTree(responseBody);
        // Should have exactly 1 team (the default team)
        assert teams.size() == 1 : "Expected 1 team, got " + teams.size();

        // The default team should have isDefault=true
        JsonNode defaultTeam = teams.get(0);
        assert defaultTeam.get("isDefault").asBoolean()
                : "Default team should have isDefault=true, got: " + defaultTeam.toPrettyString();
        assert "owner".equals(defaultTeam.get("role").asText())
                : "User should be owner of default team, got: "
                        + defaultTeam.get("role").asText();
    }

    @Test
    void getTeam_shouldReturnTeamDetails() throws Exception {
        // Create a team
        CreateTeamRequest request = new CreateTeamRequest("My Team", "A test team");
        String teamId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Get team details
        mockMvc.perform(get("/api/v1/teams/" + teamId).header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("My Team"))
                .andExpect(jsonPath("$.members[0].email").value(testUserEmail))
                .andExpect(jsonPath("$.isDefault").value(false));
    }

    @Test
    void getTeam_defaultTeam_shouldReturnIsDefaultTrue() throws Exception {
        // Get the default team (created during registration)
        JsonNode teams = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/teams").header("Authorization", "Bearer " + authToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());

        String teamId = teams.get(0).get("id").asText();

        // Get team details
        mockMvc.perform(get("/api/v1/teams/" + teamId).header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    void updateTeam_shouldReturn200() throws Exception {
        // Create a team
        CreateTeamRequest createRequest = new CreateTeamRequest("My Team", "A test team");
        String teamId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Update team
        UpdateTeamRequest updateRequest =
                new UpdateTeamRequest("Updated Team", "Updated description", null, null, null, null, null);
        mockMvc.perform(put("/api/v1/teams/" + teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Team"));
    }

    @Test
    void deleteTeam_shouldReturn204() throws Exception {
        // Create a team (not default)
        CreateTeamRequest request = new CreateTeamRequest("My Team", "A test team");
        String teamId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Delete team
        mockMvc.perform(delete("/api/v1/teams/" + teamId).header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteDefaultTeam_shouldReturn400() throws Exception {
        // The default team is created during registration, so we need to get its ID
        String responseBody = mockMvc.perform(get("/api/v1/teams").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Find the default team (isDefault = true)
        JsonNode teams = objectMapper.readTree(responseBody);
        String defaultTeamId = null;
        for (JsonNode team : teams) {
            if (team.get("isDefault").asBoolean()) {
                defaultTeamId = team.get("id").asText();
                break;
            }
        }

        // Try to delete the default team - should fail with 400
        mockMvc.perform(delete("/api/v1/teams/" + defaultTeamId).header("Authorization", "Bearer " + authToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addMember_validRequest_shouldReturn201() throws Exception {
        // Create a team
        CreateTeamRequest createRequest = new CreateTeamRequest("My Team", "A test team");
        String teamId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Register a second user (they must exist in the system)
        RegisterUserRequest registerRequest = new RegisterUserRequest("member@example.com", "password123", "Member");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        // Add member to team by email (as owner)
        AddTeamMemberRequest addRequest = new AddTeamMemberRequest("member@example.com", "member");
        mockMvc.perform(post("/api/v1/teams/{id}/members", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(addRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("member@example.com"))
                .andExpect(jsonPath("$.role").value("member"));
    }

    @Test
    void addMember_duplicateMember_shouldReturn409() throws Exception {
        // Create a team
        CreateTeamRequest createRequest = new CreateTeamRequest("My Team", "A test team");
        String teamId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Register a second user
        RegisterUserRequest registerRequest = new RegisterUserRequest("member2@example.com", "password123", "Member 2");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        // Add member first time
        AddTeamMemberRequest addRequest = new AddTeamMemberRequest("member2@example.com", "member");
        mockMvc.perform(post("/api/v1/teams/{id}/members", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(addRequest)))
                .andExpect(status().isCreated());

        // Add same member again - should fail with 409
        mockMvc.perform(post("/api/v1/teams/{id}/members", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(addRequest)))
                .andExpect(status().isConflict());
    }

    @Test
    void removeMember_validRequest_shouldReturn204() throws Exception {
        // Create a team
        CreateTeamRequest createRequest = new CreateTeamRequest("My Team", "A test team");
        String teamId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Register a second user
        RegisterUserRequest registerRequest = new RegisterUserRequest("member3@example.com", "password123", "Member 3");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        // Add member by email
        AddTeamMemberRequest addRequest = new AddTeamMemberRequest("member3@example.com", "member");
        mockMvc.perform(post("/api/v1/teams/{id}/members", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(addRequest)))
                .andExpect(status().isCreated());

        // Get the member's user ID from the database (test has direct repository
        // access)
        String memberUserId = userRepository
                .findByEmail("member3@example.com")
                .orElseThrow()
                .getId()
                .toString();

        // Remove member by userId
        mockMvc.perform(delete("/api/v1/teams/{id}/members/{userId}", teamId, memberUserId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void updateTavilyApiKey_validRequest_shouldReturn204() throws Exception {
        // Create a team
        CreateTeamRequest createRequest = new CreateTeamRequest("My Team", "A test team");
        String teamId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Verify tavilyApiKeyConfigured is false initially
        String teamResponse = mockMvc.perform(
                        get("/api/v1/teams/" + teamId).header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode team = objectMapper.readTree(teamResponse);
        assert !team.get("tavilyApiKeyConfigured").asBoolean() : "Tavily API key should not be configured initially";

        // Update Tavily API key
        TavilyApiKeyRequest apiKeyRequest = new TavilyApiKeyRequest("tvly-test-api-key-12345");
        mockMvc.perform(patch("/api/v1/teams/" + teamId + "/tavily-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(apiKeyRequest)))
                .andExpect(status().isNoContent());

        // Verify tavilyApiKeyConfigured is now true
        teamResponse = mockMvc.perform(get("/api/v1/teams/" + teamId).header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        team = objectMapper.readTree(teamResponse);
        assert team.get("tavilyApiKeyConfigured").asBoolean() : "Tavily API key should be configured after update";
    }

    @Test
    void updateTavilyApiKey_clearKey_shouldReturn204() throws Exception {
        // Create a team
        CreateTeamRequest createRequest = new CreateTeamRequest("My Team", "A test team");
        String teamId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Set Tavily API key
        TavilyApiKeyRequest setRequest = new TavilyApiKeyRequest("tvly-test-api-key-12345");
        mockMvc.perform(patch("/api/v1/teams/" + teamId + "/tavily-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(setRequest)))
                .andExpect(status().isNoContent());

        // Clear Tavily API key
        TavilyApiKeyRequest clearRequest = new TavilyApiKeyRequest(null);
        mockMvc.perform(patch("/api/v1/teams/" + teamId + "/tavily-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(clearRequest)))
                .andExpect(status().isNoContent());

        // Verify tavilyApiKeyConfigured is now false
        String teamResponse = mockMvc.perform(
                        get("/api/v1/teams/" + teamId).header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode team = objectMapper.readTree(teamResponse);
        assert !team.get("tavilyApiKeyConfigured").asBoolean()
                : "Tavily API key should not be configured after clearing";
    }

    @Test
    void updateTavilyApiKey_unauthorized_shouldReturn401() throws Exception {
        // Try to update Tavily API key without authentication
        TavilyApiKeyRequest apiKeyRequest = new TavilyApiKeyRequest("tvly-test-api-key-12345");
        mockMvc.perform(patch("/api/v1/teams/00000000-0000-0000-0000-000000000000/tavily-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(apiKeyRequest)))
                .andExpect(status().isUnauthorized());
    }
}
