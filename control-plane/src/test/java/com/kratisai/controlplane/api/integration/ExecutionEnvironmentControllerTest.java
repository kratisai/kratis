package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.CreateExecutionEnvironmentRequest;
import com.kratisai.controlplane.api.restdto.CreateTeamRequest;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.config.InstanceProperties;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.EnvironmentProvider;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.EnvironmentProviderRepository;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.service.ExecutionEnvironmentService;
import com.kratisai.controlplane.service.JwtService;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;

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
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private SandboxExecutionActivityRepository sandboxExecutionActivityRepository;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private ExecutionEnvironmentService executionEnvironmentService;

    @Autowired
    private InstanceProperties instanceProperties;

    private User user;
    private String authToken;
    private UUID runnerEnvId;
    private final List<GenericContainer<?>> runnerContainers = new ArrayList<>();

    private UUID teamId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();
        user = ctx.user();
        teamId = team.getId();
        authToken =
                jwtService.generateAccessToken(ctx.user().getId(), ctx.user().getEmail());
    }

    @Test
    void listEnvironments_newTeamHasNoEnvironments() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/environments", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listEnvironments_shouldReturnLastActivityAndOrderCorrectly() throws Exception {
        Team team = teamRepository.findById(teamId).orElseThrow();
        ExecutionEnvironment activeEnv = new ExecutionEnvironment();
        activeEnv.setTeam(team);
        activeEnv.setName("Active Connector");
        activeEnv.setType(ExecutionEnvironmentType.CONNECTOR);
        activeEnv.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.CONNECTED);
        activeEnv.setAuthToken("active-token");
        executionEnvironmentRepository.save(activeEnv);

        ExecutionEnvironment idleEnv = new ExecutionEnvironment();
        idleEnv.setTeam(team);
        idleEnv.setName("Idle Connector");
        idleEnv.setType(ExecutionEnvironmentType.CONNECTOR);
        idleEnv.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.CONNECTED);
        idleEnv.setAuthToken("idle-token");
        executionEnvironmentRepository.save(idleEnv);

        ChatEntity chat = testDataFactory.createChat(team, user, "Controller Test Chat");

        SandboxExecution execution = new SandboxExecution();
        execution.setEnvironment(activeEnv);
        execution.setChat(chat);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution.setStartedAt(Instant.now());
        sandboxExecutionRepository.save(execution);

        SandboxExecutionActivity activity = new SandboxExecutionActivity(
                execution.getId(), 1, "action-1", ActivityType.COMMAND, ActivityStatus.COMPLETED, "npm test", "{}");
        sandboxExecutionActivityRepository.save(activity);

        mockMvc.perform(get("/api/v1/teams/{teamId}/environments", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(activeEnv.getId().toString()))
                .andExpect(jsonPath("$[0].name").value("Active Connector"))
                .andExpect(jsonPath("$[0].lastActivity").isNotEmpty())
                .andExpect(jsonPath("$[1].id").value(idleEnv.getId().toString()))
                .andExpect(jsonPath("$[1].name").value("Idle Connector"))
                .andExpect(jsonPath("$[1].lastActivity").value(nullValue()));
    }

    @Test
    void deleteEnvironment_shouldReturn204AndRemoveItem() throws Exception {
        ExecutionEnvironment customEnv = new ExecutionEnvironment();
        customEnv.setTeam(teamRepository.findById(teamId).orElseThrow());
        customEnv.setName("To Be Deleted");
        customEnv.setType(ExecutionEnvironmentType.SANDBOX);
        customEnv.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.TERMINATED);
        executionEnvironmentRepository.save(customEnv);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/environments/{envId}", teamId, customEnv.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/teams/{teamId}/environments", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deleteEnvironment_nonTerminatedEnvironment_shouldReturn409AndKeepRow() throws Exception {
        for (com.kratisai.controlplane.model.EnvironmentStatus status : List.of(
                com.kratisai.controlplane.model.EnvironmentStatus.CONNECTED,
                com.kratisai.controlplane.model.EnvironmentStatus.SLEEPING,
                com.kratisai.controlplane.model.EnvironmentStatus.PENDING_CONNECT)) {
            ExecutionEnvironment env = new ExecutionEnvironment();
            env.setTeam(teamRepository.findById(teamId).orElseThrow());
            env.setName("Protected " + status);
            env.setType(ExecutionEnvironmentType.SANDBOX);
            env.setStatus(status);
            executionEnvironmentRepository.save(env);

            mockMvc.perform(delete("/api/v1/teams/{teamId}/environments/{envId}", teamId, env.getId())
                            .header("Authorization", "Bearer " + authToken))
                    .andExpect(status().isConflict());

            assertThat(executionEnvironmentRepository.findById(env.getId())).isPresent();
        }
    }

    @Test
    void deleteConnector_offlineWithoutExecution_shouldReturn204() throws Exception {
        ExecutionEnvironment connector = new ExecutionEnvironment();
        connector.setTeam(teamRepository.findById(teamId).orElseThrow());
        connector.setName("Offline Connector");
        connector.setType(ExecutionEnvironmentType.CONNECTOR);
        connector.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.CONNECTED);
        connector.setLastHeartbeat(Instant.now().minusSeconds(600));
        executionEnvironmentRepository.save(connector);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/environments/{envId}", teamId, connector.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        assertThat(executionEnvironmentRepository.findById(connector.getId())).isEmpty();
    }

    @Test
    void deleteConnector_connectedOrRunning_shouldReturn409AndKeepRow() throws Exception {
        ExecutionEnvironment connected = new ExecutionEnvironment();
        connected.setTeam(teamRepository.findById(teamId).orElseThrow());
        connected.setName("Live Connector");
        connected.setType(ExecutionEnvironmentType.CONNECTOR);
        connected.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.CONNECTED);
        connected.setLastHeartbeat(Instant.now());
        executionEnvironmentRepository.save(connected);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/environments/{envId}", teamId, connected.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isConflict());
        assertThat(executionEnvironmentRepository.findById(connected.getId())).isPresent();

        ExecutionEnvironment running = new ExecutionEnvironment();
        running.setTeam(teamRepository.findById(teamId).orElseThrow());
        running.setName("Running Connector");
        running.setType(ExecutionEnvironmentType.CONNECTOR);
        running.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.CONNECTED);
        running.setLastHeartbeat(Instant.now().minusSeconds(600));
        executionEnvironmentRepository.save(running);

        SandboxExecution execution = new SandboxExecution();
        execution.setEnvironment(running);
        execution.setChat(
                testDataFactory.createChat(teamRepository.findById(teamId).orElseThrow(), user, "Running Chat"));
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution.setStartedAt(Instant.now());
        sandboxExecutionRepository.save(execution);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/environments/{envId}", teamId, running.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isConflict());
        assertThat(executionEnvironmentRepository.findById(running.getId())).isPresent();
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

        mockMvc.perform(delete("/api/v1/teams/{teamId}/environments/{envId}", otherTeam.getId(), UUID.randomUUID())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void createTeam_shouldNotProvisionSandboxEnvironment() throws Exception {
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

        mockMvc.perform(get("/api/v1/teams/{teamId}/environments", newTeamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
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
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[?(@.name == 'My Workspace Connector')].type")
                        .value("CONNECTOR"))
                .andExpect(jsonPath("$[?(@.name == 'My Workspace Connector')].status")
                        .value("PENDING_CONNECT"));
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

    private GenericContainer<?> suspendedRunner(EnvironmentProvider provider) {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(teamRepository.findById(teamId).orElseThrow());
        env.setProvider(provider);
        env.setName("Sleeping Sandbox");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.CONNECTED);
        env.setAuthToken("test-token");
        env = executionEnvironmentRepository.save(env);
        runnerEnvId = env.getId();

        GenericContainer<?> runner = new GenericContainer<>("alpine:3.20")
                .withCommand("sleep", "infinity")
                .withCreateContainerCmdModifier(cmd -> cmd.withName("kratis-sandbox-" + runnerEnvId))
                .withLabels(Map.of(
                        "kratis.instance.id",
                        instanceProperties.getId(),
                        "kratis.sandbox.id",
                        runnerEnvId.toString(),
                        "kratis.role",
                        "runner"));
        runner.start();
        runnerContainers.add(runner);
        runDocker(
                "exec",
                runner.getContainerId(),
                "sh",
                "-c",
                "mkdir -p /kratis/workspace /kratis/logs && chown -R 1000:1000 /kratis");
        return runner;
    }

    private String runDocker(String... args) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(args));
        try {
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor(60, TimeUnit.SECONDS);
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("docker CLI failed: " + String.join(" ", args), e);
        }
    }

    private String dockerCli(String... args) {
        return runDocker(args);
    }

    @AfterEach
    void stopRunnerContainers() {
        runnerContainers.forEach(container -> {
            try {
                container.stop();
            } catch (Exception ignored) {
                // Container may already be gone after suspend/delete.
            }
        });
        runnerContainers.clear();
        if (runnerEnvId != null) {
            runDocker("rmi", "-f", "kratis-snapshot-" + runnerEnvId + ":latest");
        }
    }

    @Test
    void sleepEnvironment_suspendsAndMarksSleeping() {
        EnvironmentProvider provider = new EnvironmentProvider();
        provider.setTeam(teamRepository.findById(teamId).orElseThrow());
        provider.setName("Sleep Provider");
        provider.setDockerImage("alpine-unused-image:latest");
        provider = environmentProviderRepository.save(provider);

        GenericContainer<?> runner = suspendedRunner(provider);
        try {
            executionEnvironmentService.sleepEnvironmentInternal(
                    executionEnvironmentRepository.findById(runnerEnvId).orElseThrow());

            ExecutionEnvironment updated =
                    executionEnvironmentRepository.findById(runnerEnvId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(EnvironmentStatus.SLEEPING);
            assertThat(updated.getStateChangedAt()).isNotNull();
            assertThat(dockerCli("ps", "-a", "--filter", "name=kratis-sandbox-" + runnerEnvId, "-q"))
                    .isEmpty();
        } finally {
            runner.stop();
        }
    }

    @Test
    void sleepAndResume_roundTrip_restoresContainerFromSnapshot() throws Exception {
        EnvironmentProvider provider = new EnvironmentProvider();
        provider.setTeam(teamRepository.findById(teamId).orElseThrow());
        provider.setName("Round Trip Provider");
        provider.setDockerImage("alpine-unused-image:latest");
        provider = environmentProviderRepository.save(provider);

        GenericContainer<?> runner = suspendedRunner(provider);
        try {
            executionEnvironmentService.sleepEnvironmentInternal(
                    executionEnvironmentRepository.findById(runnerEnvId).orElseThrow());

            mockMvc.perform(post("/api/v1/teams/{teamId}/environments/{envId}/resume", teamId, runnerEnvId)
                            .header("Authorization", "Bearer " + authToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING_CONNECT"));

            ExecutionEnvironment updated =
                    executionEnvironmentRepository.findById(runnerEnvId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(EnvironmentStatus.PENDING_CONNECT);
            assertThat(updated.getStateChangedAt()).isNotNull();

            ExecutionEnvironment terminated =
                    executionEnvironmentRepository.findById(runnerEnvId).orElseThrow();
            terminated.setStatus(EnvironmentStatus.TERMINATED);
            executionEnvironmentRepository.save(terminated);

            mockMvc.perform(delete("/api/v1/teams/{teamId}/environments/{envId}", teamId, runnerEnvId)
                            .header("Authorization", "Bearer " + authToken))
                    .andExpect(status().isNoContent());
            assertThat(dockerCli("images", "-q", "kratis-snapshot-" + runnerEnvId + ":latest"))
                    .isEmpty();
        } finally {
            runner.stop();
        }
    }

    @Test
    void resumeEnvironment_notSleeping_shouldReturn400() throws Exception {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(teamRepository.findById(teamId).orElseThrow());
        env.setName("Connector Env");
        env.setType(ExecutionEnvironmentType.CONNECTOR);
        env.setStatus(EnvironmentStatus.CONNECTED);
        executionEnvironmentRepository.save(env);

        mockMvc.perform(post("/api/v1/teams/{teamId}/environments/{envId}/resume", teamId, env.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resumeEnvironment_terminatedEnvironment_shouldReturn410() throws Exception {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(teamRepository.findById(teamId).orElseThrow());
        env.setName("Expired Sandbox");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.TERMINATED);
        executionEnvironmentRepository.save(env);

        mockMvc.perform(post("/api/v1/teams/{teamId}/environments/{envId}/resume", teamId, env.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isGone());
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
        sandboxEnv.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.TERMINATED);
        executionEnvironmentRepository.save(sandboxEnv);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/environments/{envId}", teamId, sandboxEnv.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        assertThat(executionEnvironmentRepository.findById(sandboxEnv.getId())).isEmpty();
    }
}
