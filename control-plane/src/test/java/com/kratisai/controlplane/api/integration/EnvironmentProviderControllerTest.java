package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.CreateEnvironmentProviderRequest;
import com.kratisai.controlplane.api.restdto.CreateTeamRequest;
import com.kratisai.controlplane.api.restdto.UpdateEnvironmentProviderRequest;
import com.kratisai.controlplane.model.EnvironmentProvider;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.EnvironmentProviderRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.service.JwtService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringIntegrationTest
class EnvironmentProviderControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private EnvironmentProviderRepository environmentProviderRepository;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private String authToken;
    private UUID teamId;
    private UUID defaultProviderId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();
        teamId = team.getId();
        authToken =
                jwtService.generateAccessToken(ctx.user().getId(), ctx.user().getEmail());

        // The default provider is now automatically created by TeamService
        EnvironmentProvider defaultProvider = environmentProviderRepository.findByTeamId(teamId).stream()
                .filter(provider -> "Default Docker Provider".equals(provider.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Default provider not created"));
        defaultProviderId = defaultProvider.getId();
    }

    @Test
    void listProviders_shouldReturnPrePopulatedDefault() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/environment-providers", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Default Docker Provider"))
                .andExpect(jsonPath("$[0].dockerImage").value("kratis-runner-base:latest"));
    }

    @Test
    void updateProvider_shouldReturn200AndReflectChange() throws Exception {
        UpdateEnvironmentProviderRequest request =
                new UpdateEnvironmentProviderRequest("default-updated", "my-custom-image:latest");

        mockMvc.perform(put("/api/v1/teams/{teamId}/environment-providers/{providerId}", teamId, defaultProviderId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("default-updated"))
                .andExpect(jsonPath("$.dockerImage").value("my-custom-image:latest"));

        EnvironmentProvider updatedProvider =
                environmentProviderRepository.findById(defaultProviderId).orElseThrow();
        assertThat(updatedProvider.getName()).isEqualTo("default-updated");
        assertThat(updatedProvider.getDockerImage()).isEqualTo("my-custom-image:latest");
    }

    @Test
    void createProvider_shouldReturn201AndPersist() throws Exception {
        CreateEnvironmentProviderRequest request =
                new CreateEnvironmentProviderRequest("Custom Docker Provider", "custom-image:v1");

        mockMvc.perform(post("/api/v1/teams/{teamId}/environment-providers", teamId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Custom Docker Provider"))
                .andExpect(jsonPath("$.dockerImage").value("custom-image:v1"));

        assertThat(environmentProviderRepository.count()).isEqualTo(2);
    }

    @Test
    void listProviders_afterCreate_shouldReturnBothProviders() throws Exception {
        CreateEnvironmentProviderRequest request =
                new CreateEnvironmentProviderRequest("Custom Docker Provider", "custom-image:v1");
        mockMvc.perform(post("/api/v1/teams/{teamId}/environment-providers", teamId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/teams/{teamId}/environment-providers", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].name")
                        .value(containsInAnyOrder("Default Docker Provider", "Custom Docker Provider")));
    }

    @Test
    void deleteProvider_shouldReturn204AndRemoveItem() throws Exception {
        CreateEnvironmentProviderRequest request =
                new CreateEnvironmentProviderRequest("To Be Deleted", "delete-image:v1");
        MvcResult result = mockMvc.perform(post("/api/v1/teams/{teamId}/environment-providers", teamId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID customId = UUID.fromString(responseNode.get("id").asText());

        mockMvc.perform(delete("/api/v1/teams/{teamId}/environment-providers/{providerId}", teamId, customId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/teams/{teamId}/environment-providers", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Default Docker Provider"));
    }

    @Test
    void listProviders_notTeamMember_shouldReturn403() throws Exception {
        Team otherTeam = new Team("Other Team", "Not accessible");
        teamRepository.save(otherTeam);

        mockMvc.perform(get("/api/v1/teams/{teamId}/environment-providers", otherTeam.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void createProvider_notTeamMember_shouldReturn403() throws Exception {
        Team otherTeam = new Team("Other Team", "Not accessible");
        teamRepository.save(otherTeam);

        CreateEnvironmentProviderRequest request =
                new CreateEnvironmentProviderRequest("Custom Docker Provider", "custom-image:v1");

        mockMvc.perform(post("/api/v1/teams/{teamId}/environment-providers", otherTeam.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateProvider_notTeamMember_shouldReturn403() throws Exception {
        Team otherTeam = new Team("Other Team", "Not accessible");
        teamRepository.save(otherTeam);

        UpdateEnvironmentProviderRequest request =
                new UpdateEnvironmentProviderRequest("default-updated", "my-custom-image:latest");

        mockMvc.perform(put(
                                "/api/v1/teams/{teamId}/environment-providers/{providerId}",
                                otherTeam.getId(),
                                defaultProviderId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteProvider_notTeamMember_shouldReturn403() throws Exception {
        Team otherTeam = new Team("Other Team", "Not accessible");
        teamRepository.save(otherTeam);

        mockMvc.perform(delete(
                                "/api/v1/teams/{teamId}/environment-providers/{providerId}",
                                otherTeam.getId(),
                                defaultProviderId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void createTeam_shouldAutomaticallyProvisionDefaultProvider() throws Exception {
        // Create a new team via the API
        CreateTeamRequest request = new CreateTeamRequest("New Team", "A new test team");
        MvcResult result = mockMvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID newTeamId = UUID.fromString(responseNode.get("id").asText());

        // Verify the new team has exactly one default provider
        mockMvc.perform(get("/api/v1/teams/{teamId}/environment-providers", newTeamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Default Docker Provider"))
                .andExpect(jsonPath("$[0].dockerImage").value("kratis-runner-base:latest"));
    }
}
