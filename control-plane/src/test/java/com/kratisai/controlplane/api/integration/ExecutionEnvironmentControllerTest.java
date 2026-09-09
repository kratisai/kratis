package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.CreateExecutionEnvironmentRequest;
import com.kratisai.controlplane.api.restdto.CreateTeamRequest;
import com.kratisai.controlplane.model.EnvironmentProvider;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.EnvironmentProviderRepository;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
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
class ExecutionEnvironmentControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

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
    private UUID defaultEnvId;

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

        // The default sandbox provider is now automatically created by TeamService
        ExecutionEnvironment defaultEnv = executionEnvironmentRepository.findByTeamId(teamId).stream()
                .filter(env -> "Default Sandbox".equals(env.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Default environment not created"));
        defaultEnvId = defaultEnv.getId();
    }

    @Test
    void listEnvironments_shouldReturnPrePopulatedDefault() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/environments", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Default Sandbox"))
                .andExpect(jsonPath("$[0].status").value("DISCONNECTED"));
    }

    @Test
    void deleteEnvironment_shouldReturn204AndRemoveItem() throws Exception {
        ExecutionEnvironment customEnv = new ExecutionEnvironment();
        customEnv.setTeam(teamRepository.findById(teamId).orElseThrow());
        customEnv.setName("To Be Deleted");
        customEnv.setType(ExecutionEnvironmentType.SANDBOX);
        customEnv.setContainerId("delete-image:v1");
        customEnv.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.DISCONNECTED);
        executionEnvironmentRepository.save(customEnv);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/environments/{envId}", teamId, customEnv.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/teams/{teamId}/environments", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Default Sandbox"));
    }

    @Test
    void listEnvironments_notTeamMember_shouldReturn403() throws Exception {
        Team otherTeam = new Team("Other Team", "Not accessible");
        teamRepository.save(otherTeam);

        mockMvc.perform(get("/api/v1/teams/{teamId}/environments", otherTeam.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteEnvironment_notTeamMember_shouldReturn403() throws Exception {
        Team otherTeam = new Team("Other Team", "Not accessible");
        teamRepository.save(otherTeam);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/environments/{envId}", otherTeam.getId(), defaultEnvId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void createTeam_shouldAutomaticallyProvisionDefaultEnvironment() throws Exception {
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

        // Verify the new team has exactly one default environment
        mockMvc.perform(get("/api/v1/teams/{teamId}/environments", newTeamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Default Sandbox"))
                .andExpect(jsonPath("$[0].status").value("DISCONNECTED"))
                .andExpect(jsonPath("$[0].containerId").value("kratis-runner-base:latest"));
    }

    @Test
    void createConnector_shouldReturn201AndGenerateInstallCommand() throws Exception {
        CreateExecutionEnvironmentRequest request = new CreateExecutionEnvironmentRequest("My Workspace Connector");

        MvcResult result = mockMvc.perform(post("/api/v1/teams/{teamId}/environments", teamId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        String installCommand = responseNode.get("installCommand").asText();

        // Verify install command contains the token and correct WebSocket URL
        assertThat(installCommand).contains("kratis-connector --mode=daemon --server-url=");
        assertThat(installCommand).contains("--token=");

        // Verify the environment was saved correctly
        mockMvc.perform(get("/api/v1/teams/{teamId}/environments", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.name == 'My Workspace Connector')].type")
                        .value("CONNECTOR"))
                .andExpect(jsonPath("$[?(@.name == 'My Workspace Connector')].status")
                        .value("DISCONNECTED"));
    }

    @Test
    void createConnector_missingName_shouldReturn400() throws Exception {
        CreateExecutionEnvironmentRequest request = new CreateExecutionEnvironmentRequest("");

        mockMvc.perform(post("/api/v1/teams/{teamId}/environments", teamId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createConnector_notTeamMember_shouldReturn403() throws Exception {
        Team otherTeam = new Team("Other Team", "Not accessible");
        teamRepository.save(otherTeam);

        CreateExecutionEnvironmentRequest request = new CreateExecutionEnvironmentRequest("My Workspace Connector");

        mockMvc.perform(post("/api/v1/teams/{teamId}/environments", otherTeam.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void terminateEnvironment_shouldReturn204AndClearContainerId() throws Exception {
        // Create a provider for the sandbox environment
        EnvironmentProvider provider = new EnvironmentProvider();
        provider.setTeam(teamRepository.findById(teamId).orElseThrow());
        provider.setName("Test Docker Provider");
        provider.setDockerImage("test-image:latest");
        provider = environmentProviderRepository.save(provider);

        ExecutionEnvironment sandboxEnv = new ExecutionEnvironment();
        sandboxEnv.setTeam(teamRepository.findById(teamId).orElseThrow());
        sandboxEnv.setProvider(provider);
        sandboxEnv.setName("Terminable Sandbox");
        sandboxEnv.setType(ExecutionEnvironmentType.SANDBOX);
        sandboxEnv.setContainerId("test-container-id-123");
        sandboxEnv.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.CONNECTED);
        executionEnvironmentRepository.save(sandboxEnv);

        mockMvc.perform(post("/api/v1/teams/{teamId}/environments/{envId}/terminate", teamId, sandboxEnv.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        ExecutionEnvironment updatedEnv =
                executionEnvironmentRepository.findById(sandboxEnv.getId()).orElseThrow();
        assertThat(updatedEnv.getContainerId()).isNull();
        assertThat(updatedEnv.getStatus()).isEqualTo(com.kratisai.controlplane.model.EnvironmentStatus.DISCONNECTED);
    }

    @Test
    void terminateEnvironment_notSandbox_shouldReturn400() throws Exception {
        ExecutionEnvironment connectorEnv = new ExecutionEnvironment();
        connectorEnv.setTeam(teamRepository.findById(teamId).orElseThrow());
        connectorEnv.setName("Connector Env");
        connectorEnv.setType(ExecutionEnvironmentType.CONNECTOR);
        connectorEnv.setContainerId("test-container-id-123");
        connectorEnv.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.CONNECTED);
        executionEnvironmentRepository.save(connectorEnv);

        mockMvc.perform(post("/api/v1/teams/{teamId}/environments/{envId}/terminate", teamId, connectorEnv.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteEnvironment_shouldTerminateContainerBeforeDeletion() throws Exception {
        // Create a provider for the sandbox environment
        EnvironmentProvider provider = new EnvironmentProvider();
        provider.setTeam(teamRepository.findById(teamId).orElseThrow());
        provider.setName("Test Docker Provider 2");
        provider.setDockerImage("test-image-2:latest");
        provider = environmentProviderRepository.save(provider);

        ExecutionEnvironment sandboxEnv = new ExecutionEnvironment();
        sandboxEnv.setTeam(teamRepository.findById(teamId).orElseThrow());
        sandboxEnv.setProvider(provider);
        sandboxEnv.setName("To Be Deleted With Termination");
        sandboxEnv.setType(ExecutionEnvironmentType.SANDBOX);
        sandboxEnv.setContainerId("delete-with-terminate:v1");
        sandboxEnv.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.CONNECTED);
        executionEnvironmentRepository.save(sandboxEnv);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/environments/{envId}", teamId, sandboxEnv.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        assertThat(executionEnvironmentRepository.findById(sandboxEnv.getId())).isEmpty();
    }
}
