package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.CreateSandboxExecutionRequest;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.CanvasService;
import com.kratisai.controlplane.service.EnvironmentRpcClient;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.JwtService;
import com.kratisai.controlplane.service.LocalDockerSandboxProvider;
import com.kratisai.controlplane.service.ProcessExecutor;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.TextMessage;

@SpringIntegrationTest
class ChatExecutionControllerTest {

    @LocalServerPort
    private int port;

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
    private EnvironmentProviderRepository environmentProviderRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private ProcessExecutor processExecutor;

    @Autowired
    private LocalDockerSandboxProvider localDockerSandboxProvider;

    @Autowired
    private CanvasService canvasService;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private EnvironmentSessionRegistry sessionRegistry;

    @Autowired
    private EnvironmentRpcClient environmentRpcClient;

    private String authToken;
    private ChatEntity chat;
    private EnvironmentProvider provider;
    private ModelProvider modelProvider;
    private Repository repository;
    private String originalServerUrl;
    private String spawnedContainerId;

    @SuppressWarnings("unchecked")
    private static Map<String, String> nullableStringMap() {
        return Mockito.nullable(Map.class);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // Clear DB
        databaseCleaner.cleanAll();

        // Reset processExecutor spy to ensure clean state before each test
        // This is critical when tests run in a suite and share the same Spring context
        Mockito.reset(processExecutor);
        // Ensure the spy calls real methods by default after reset
        try {
            Mockito.doCallRealMethod()
                    .when(processExecutor)
                    .execute(Mockito.anyList(), Mockito.nullable(File.class), nullableStringMap());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Save original serverUrl
        originalServerUrl = "ws://host.docker.internal:8080/ws";

        // Create test user and team
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        authToken =
                jwtService.generateAccessToken(ctx.user().getId(), ctx.user().getEmail());

        // Create a test chat
        chat = chatRepository.save(new ChatEntity(ctx.team(), ctx.user(), "Execution Test Session"));

        repository = testDataFactory.createRepository(
                ctx.team(), "exec-repo", "https://example.com/exec-repo.git", "main", null);

        // Get pre-populated default provider
        provider = environmentProviderRepository.findByTeamId(ctx.team().getId()).stream()
                .filter(p -> "Default Docker Provider".equals(p.getName()))
                .findFirst()
                .orElseThrow();

        // Create a model provider for the team
        modelProvider = new ModelProvider("Test Provider", ProviderType.OPENAI, "sk-key", null);
        modelProvider.setTeam(ctx.team());
        modelProvider.setModels(
                List.of(new ProviderModel("gpt-4o", ModelKind.CHAT), new ProviderModel("gpt-4o-mini", ModelKind.CHAT)));
        modelProvider = modelProviderRepository.save(modelProvider);
    }

    @AfterEach
    void tearDown() {
        // Reset processExecutor spy stubbing
        Mockito.reset(processExecutor);

        // Restore server URL
        localDockerSandboxProvider.setServerUrl(originalServerUrl);

        // Terminate any container that was spawned during test
        if (spawnedContainerId != null) {
            try {
                Process p = new ProcessBuilder("docker", "logs", spawnedContainerId).start();
                p.waitFor();
                String logs = new String(p.getInputStream().readAllBytes());
                String errLogs = new String(p.getErrorStream().readAllBytes());
                System.out.println("====== spawned container logs (stdout): ======\n" + logs);
                System.out.println("====== spawned container logs (stderr): ======\n" + errLogs);
            } catch (Exception e) {
                System.err.println("Failed to read container logs: " + e.getMessage());
            }
            try {
                localDockerSandboxProvider.terminateSandbox(spawnedContainerId);
            } catch (Exception e) {
                // Ignore
            }
            spawnedContainerId = null;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void launchExecution_withMockProcessExecutor_shouldSucceed() throws Exception {
        // 1. Stub the ProcessExecutor to return a mock container ID
        String mockContainerId = "mock-container-id-999888";
        Mockito.doReturn(new ProcessExecutor.ProcessResult(0, mockContainerId.getBytes()))
                .when(processExecutor)
                .execute(Mockito.anyList(), Mockito.nullable(File.class), nullableStringMap());

        // Create a SPEC canvas document for the execution
        canvasService.createCanvas(
                chat.getId(),
                "plan-exec",
                "Execution Plan",
                "# Plan\nExecute stuff",
                CanvasType.SPEC,
                repository,
                null);

        // 2. Perform POST to launch execution
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                provider.getId(), null, AgentHarness.OPENCODE, "plan-exec", modelProvider.getId(), "gpt-4o");

        mockMvc.perform(post("/api/v1/chats/{chatId}/executions", chat.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // 3. Verify process executor was called with the exact Docker command structure
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(processExecutor, Mockito.atLeastOnce())
                .execute(commandCaptor.capture(), Mockito.nullable(File.class), nullableStringMap());

        List<String> dindCommand = commandCaptor.getAllValues().stream()
                .filter(cmd -> cmd.contains("docker:dind-rootless"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Docker run command for dind not found"));
        assertThat(dindCommand)
                .containsSubsequence(
                        "docker",
                        "run",
                        "-d",
                        "--name",
                        "--network",
                        "--security-opt",
                        "seccomp=unconfined",
                        "--security-opt",
                        "systempaths=unconfined",
                        "--device",
                        "/dev/net/tun",
                        "--add-host",
                        "host.docker.internal:host-gateway",
                        "-e",
                        "DOCKER_TLS_CERTDIR=",
                        "--label",
                        "kratis.instance.id=kratis-test",
                        "--label",
                        "kratis.role=dind",
                        "docker:dind-rootless",
                        "dockerd-entrypoint.sh");

        List<String> runCommand = commandCaptor.getAllValues().stream()
                .filter(cmd -> cmd.contains("kratis-runner-base:latest"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Docker run command not found"));
        assertThat(runCommand)
                .containsSubsequence(
                        "docker",
                        "run",
                        "-d",
                        "--name",
                        "--network",
                        "--add-host",
                        "host.docker.internal:host-gateway",
                        "--label",
                        "kratis.instance.id=kratis-test",
                        "--label",
                        "kratis.role=runner",
                        "--cap-drop=ALL",
                        "--cap-add=CHOWN",
                        "--cap-add=DAC_OVERRIDE",
                        "--cap-add=FOWNER",
                        "--cap-add=FSETID",
                        "--cap-add=KILL",
                        "--cap-add=SETGID",
                        "--cap-add=SETUID",
                        "--cap-add=AUDIT_WRITE",
                        "--user=1000",
                        "kratis-runner-base:latest",
                        "sleep",
                        "infinity")
                .anyMatch(arg -> arg.startsWith("TESTCONTAINERS_HOST_OVERRIDE=kratis-dind-"))
                .contains("TESTCONTAINERS_RYUK_DISABLED=true")
                .contains("TESTCONTAINERS_REUSE_ENABLE=true");

        // 4. Verify that the execution environment and container ID are persisted
        List<ExecutionEnvironment> envs =
                executionEnvironmentRepository.findByTeamId(chat.getTeam().getId());
        ExecutionEnvironment env = envs.stream()
                .filter(e -> e.getName().startsWith("Sandbox -"))
                .findFirst()
                .orElseThrow();

        // Use the actual container ID from the persisted environment for the exec
        // command assertion. The mock may or may not intercept the docker run
        // call depending on the test environment, so we verify the exec command
        // uses the same container ID that was persisted.
        String actualContainerId = env.getContainerId();
        assertThat(actualContainerId).isNotNull().isNotEmpty();

        List<String> execCommand = commandCaptor.getAllValues().stream()
                .filter(cmd -> cmd.contains("exec") && cmd.contains("--mode=sidecar"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Docker exec connector command not found"));
        assertThat(execCommand)
                .containsSubsequence(
                        "docker",
                        "exec",
                        "-d",
                        "-w",
                        "/kratis/workspace",
                        actualContainerId,
                        "/usr/local/bin/kratis-connector",
                        "--mode=sidecar",
                        "--server-url");
        int tokenIndex = execCommand.indexOf("--token");
        assertThat(tokenIndex).isGreaterThan(-1);
        assertThat(execCommand.get(tokenIndex + 1)).isNotNull();

        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.DISCONNECTED);

        // Track container ID for cleanup in tearDown
        spawnedContainerId = actualContainerId;
    }

    @Test
    void launchExecution_withRealDocker_shouldConnectAndRegister() throws Exception {
        // 1. Set the server url to local port
        String testServerUrl = "ws://host.docker.internal:" + port + "/ws/env";
        localDockerSandboxProvider.setServerUrl(testServerUrl);

        // Create a SPEC canvas document for the execution
        canvasService.createCanvas(
                chat.getId(),
                "plan-docker",
                "Execution Plan",
                "# Plan\nExecute stuff",
                CanvasType.SPEC,
                repository,
                null);

        // 2. Perform POST to launch execution
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                provider.getId(), null, AgentHarness.OPENCODE, "plan-docker", modelProvider.getId(), "gpt-4o");

        // This will trigger the real docker command since we haven't stubbed
        // processExecutor for this command
        mockMvc.perform(post("/api/v1/chats/{chatId}/executions", chat.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // 3. Find spawned environment
        List<ExecutionEnvironment> envs =
                executionEnvironmentRepository.findByTeamId(chat.getTeam().getId());
        ExecutionEnvironment env = envs.stream()
                .filter(e -> e.getName().startsWith("Sandbox -"))
                .findFirst()
                .orElseThrow();
        assertThat(env.getContainerId()).isNotNull();

        // Track container ID for cleanup in tearDown
        spawnedContainerId = env.getContainerId();

        // 4. Wait for the sidecar container to connect, send env.register and
        // env.heartbeat
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    ExecutionEnvironment updatedEnv =
                            executionEnvironmentRepository.findById(env.getId()).orElseThrow();
                    assertThat(updatedEnv.getStatus()).isEqualTo(EnvironmentStatus.CONNECTED);
                    assertThat(updatedEnv.getLastHeartbeat()).isNotNull();
                    assertThat(updatedEnv.getLastHeartbeat()).isBeforeOrEqualTo(Instant.now());
                });
    }

    @Test
    void listExecutions_authorized_returnsExecutionsOrderedByStartTime() throws Exception {
        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setTeam(chat.getTeam());
        environment.setName("Test Environment");
        environment.setType(ExecutionEnvironmentType.SANDBOX);
        environment.setStatus(EnvironmentStatus.CONNECTED);
        environment = executionEnvironmentRepository.save(environment);

        SandboxExecution running = new SandboxExecution();
        running.setChat(chat);
        running.setEnvironment(environment);
        running.setStatus(SandboxExecutionStatus.RUNNING);
        running.setStartedAt(Instant.now().minusSeconds(60));
        running = sandboxExecutionRepository.save(running);

        SandboxExecution completed = new SandboxExecution();
        completed.setChat(chat);
        completed.setEnvironment(environment);
        completed.setStatus(SandboxExecutionStatus.COMPLETED);
        completed.setStartedAt(Instant.now());
        completed.setExitCode(0);
        sandboxExecutionRepository.save(completed);

        String responseBody = mockMvc.perform(get("/api/v1/chats/{chatId}/executions", chat.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(responseBody);
        assertThat(root.isArray()).isTrue();
        assertThat(root.size()).isEqualTo(2);
        assertThat(root.get(0).get("id").asText()).isEqualTo(running.getId().toString());
        assertThat(root.get(0).get("status").asText()).isEqualTo("RUNNING");
        assertThat(root.get(1).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(root.get(1).get("exitCode").asInt()).isEqualTo(0);
    }

    @Test
    void listExecutions_emptyChat_returnsEmptyArray() throws Exception {
        String responseBody = mockMvc.perform(get("/api/v1/chats/{chatId}/executions", chat.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(responseBody);
        assertThat(root.isArray()).isTrue();
        assertThat(root.size()).isEqualTo(0);
    }

    @Test
    void listExecutions_chatNotFound_returnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/chats/{chatId}/executions", java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void listExecutions_unauthorized_returnsForbidden() throws Exception {
        TestDataFactory.TestContext otherCtx = testDataFactory.createUserAndTeam();
        String otherAuthToken = jwtService.generateAccessToken(
                otherCtx.user().getId(), otherCtx.user().getEmail());

        mockMvc.perform(get("/api/v1/chats/{chatId}/executions", chat.getId())
                        .header("Authorization", "Bearer " + otherAuthToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void terminateExecution_authorized_shouldReturnAccepted() throws Exception {
        // Create an environment first
        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setTeam(chat.getTeam());
        environment.setName("Test Environment");
        environment.setType(ExecutionEnvironmentType.SANDBOX);
        environment.setStatus(EnvironmentStatus.CONNECTED);
        environment = executionEnvironmentRepository.save(environment);

        // Create a running execution
        SandboxExecution execution = new SandboxExecution();
        execution.setChat(chat);
        execution.setEnvironment(environment);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution = sandboxExecutionRepository.save(execution);

        // terminateExecution awaits the env.terminate result, so the mock must answer it.
        org.springframework.web.socket.WebSocketSession mockSession =
                org.mockito.Mockito.mock(org.springframework.web.socket.WebSocketSession.class);
        org.mockito.Mockito.when(mockSession.getId()).thenReturn("mock-chat-id");
        org.mockito.Mockito.when(mockSession.isOpen()).thenReturn(true);
        Mockito.doAnswer(inv -> {
                    TextMessage msg = inv.getArgument(0);
                    JsonNode envelope = objectMapper.readTree(msg.getPayload());
                    if (envelope.has("method")
                            && "env.terminate".equals(envelope.get("method").asText())) {
                        ObjectNode response = objectMapper.createObjectNode();
                        response.put("jsonrpc", "2.0");
                        response.set("id", envelope.get("id"));
                        ObjectNode result = response.putObject("result");
                        result.put("status", "terminated");
                        result.put("exitCode", 0);
                        environmentRpcClient.completeResponse(response);
                    }
                    return null;
                })
                .when(mockSession)
                .sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
        sessionRegistry.registerEnvironmentSession(mockSession, environment.getId());

        // Terminate it
        mockMvc.perform(post(
                                "/api/v1/chats/{chatId}/executions/{executionId}/terminate",
                                chat.getId(),
                                execution.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isAccepted());

        // Verify execution still exists (terminateExecution sends env.terminate but doesn't change status immediately)
        SandboxExecution terminated =
                sandboxExecutionRepository.findById(execution.getId()).orElseThrow();
        assertThat(terminated.getStatus()).isEqualTo(SandboxExecutionStatus.RUNNING);

        // Clean up
        sessionRegistry.removeSession(mockSession.getId());
    }

    @Test
    void terminateExecution_notFound_shouldReturnNotFound() throws Exception {
        // Try to terminate non-existent execution
        mockMvc.perform(post(
                                "/api/v1/chats/{chatId}/executions/{executionId}/terminate",
                                chat.getId(),
                                java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void terminateExecution_alreadyTerminated_shouldReturnConflict() throws Exception {
        // Create an environment first
        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setTeam(chat.getTeam());
        environment.setName("Test Environment");
        environment.setType(ExecutionEnvironmentType.SANDBOX);
        environment.setStatus(EnvironmentStatus.CONNECTED);
        environment = executionEnvironmentRepository.save(environment);

        // Create a completed execution
        SandboxExecution execution = new SandboxExecution();
        execution.setChat(chat);
        execution.setEnvironment(environment);
        execution.setStatus(SandboxExecutionStatus.COMPLETED);
        execution = sandboxExecutionRepository.save(execution);

        // Try to terminate it again
        mockMvc.perform(post(
                                "/api/v1/chats/{chatId}/executions/{executionId}/terminate",
                                chat.getId(),
                                execution.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isConflict());
    }

    @Test
    void terminateExecution_unauthorized_shouldReturnForbidden() throws Exception {
        // Create an environment first
        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setTeam(chat.getTeam());
        environment.setName("Test Environment");
        environment.setType(ExecutionEnvironmentType.SANDBOX);
        environment.setStatus(EnvironmentStatus.CONNECTED);
        environment = executionEnvironmentRepository.save(environment);

        // Create another user
        TestDataFactory.TestContext otherCtx = testDataFactory.createUserAndTeam();
        String otherAuthToken = jwtService.generateAccessToken(
                otherCtx.user().getId(), otherCtx.user().getEmail());

        // Create execution in original chat
        SandboxExecution execution = new SandboxExecution();
        execution.setChat(chat);
        execution.setEnvironment(environment);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution = sandboxExecutionRepository.save(execution);

        // Try to terminate with other user's token
        mockMvc.perform(post(
                                "/api/v1/chats/{chatId}/executions/{executionId}/terminate",
                                chat.getId(),
                                execution.getId())
                        .header("Authorization", "Bearer " + otherAuthToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void terminateExecution_wrongSession_shouldReturnNotFound() throws Exception {
        // Create an environment first
        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setTeam(chat.getTeam());
        environment.setName("Test Environment");
        environment.setType(ExecutionEnvironmentType.SANDBOX);
        environment.setStatus(EnvironmentStatus.CONNECTED);
        environment = executionEnvironmentRepository.save(environment);

        // Create execution in original chat
        SandboxExecution execution = new SandboxExecution();
        execution.setChat(chat);
        execution.setEnvironment(environment);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution = sandboxExecutionRepository.save(execution);

        // Create another chat
        ChatEntity otherSession = chatRepository.save(new ChatEntity(chat.getTeam(), chat.getUser(), "Other Session"));

        // Try to terminate with wrong session ID
        mockMvc.perform(post(
                                "/api/v1/chats/{sessionId}/executions/{executionId}/terminate",
                                otherSession.getId(),
                                execution.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }
}
