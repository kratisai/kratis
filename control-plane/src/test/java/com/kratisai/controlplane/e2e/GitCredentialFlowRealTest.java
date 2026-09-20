package com.kratisai.controlplane.e2e;

import static com.kratisai.controlplane.service.LocalDockerSandboxProvider.SIDECAR_LOG_LOC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SlowTest;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.CreateSandboxExecutionRequest;
import com.kratisai.controlplane.model.AgentHarness;
import com.kratisai.controlplane.model.CanvasType;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.EnvironmentProvider;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.EnvironmentProviderRepository;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.CanvasService;
import com.kratisai.controlplane.service.JwtService;
import com.kratisai.controlplane.service.LocalDockerSandboxProvider;
import com.kratisai.controlplane.service.ProcessExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * E2E test that executes the REAL sidecar binary inside a REAL Docker sandbox
 * container and validates the full git credential flow  against a real git
 * fixture ({@link GitFixtureContainer}).
 */
@SpringIntegrationTest
@SlowTest(slotGroup = "E2E_GIT_CREDENTIAL")
@ExtendWith(OutputCaptureExtension.class)
class GitCredentialFlowRealTest {

    private static final Logger logger = LoggerFactory.getLogger(GitCredentialFlowRealTest.class);

    private static final String FIXTURE_TOKEN = "kratis-fixture-pat-token";
    private static final String TEST_IMAGE = "kratis-runner-base:latest";
    private static final String FIXTURE_FILE_CONTENT = "Hello over HTTPS";

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private static GitFixtureContainer gitFixture;

    @LocalServerPort
    private int port;

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
    private CanvasService canvasService;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private EnvironmentProviderRepository environmentProviderRepository;

    @Autowired
    private LocalDockerSandboxProvider localDockerSandboxProvider;

    @Autowired
    private ProcessExecutor processExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private TestDataFactory.TestContext testContext;
    private String authToken;
    private ChatEntity chat;
    private EnvironmentProvider provider;
    private ModelProvider modelProvider;
    private String spawnedContainerId;
    private String originalServerUrl;

    @BeforeAll
    static void beforeAll() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        // Use pre-built sidecar binary and runner-base image (built by CI / local dev)
        gitFixture = new GitFixtureContainer(FIXTURE_TOKEN);
        gitFixture.start();
    }

    @AfterAll
    static void afterAll() {
        if (gitFixture != null) {
            gitFixture.stop();
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        Mockito.reset(processExecutor);
        databaseCleaner.cleanAll();

        originalServerUrl = "ws://host.docker.internal:8080/ws";

        testContext = testDataFactory.createUserAndTeam();
        authToken = jwtService.generateAccessToken(
                testContext.user().getId(), testContext.user().getEmail());
        chat = chatRepository.save(
                new ChatEntity(testContext.team(), testContext.user(), "Git Credential Flow Real Test"));

        provider = environmentProviderRepository.findByTeamId(testContext.team().getId()).stream()
                .filter(p -> "Default Docker Provider".equals(p.getName()))
                .findFirst()
                .orElseThrow();
        provider.setDockerImage(TEST_IMAGE);
        environmentProviderRepository.save(provider);

        modelProvider = testDataFactory.createModelProviderWithLiteLLM(
                testContext.team(), "Test Provider", ProviderType.OPENAI, "sk-mock-key-123", List.of("gpt-4o"));

        localDockerSandboxProvider.setServerUrl("ws://host.docker.internal:" + port + "/ws/env");
    }

    @AfterEach
    void tearDown() {
        localDockerSandboxProvider.setServerUrl(originalServerUrl);
        Mockito.reset(processExecutor);
        if (spawnedContainerId != null) {
            try {
                localDockerSandboxProvider.terminateSandbox(spawnedContainerId);
            } catch (Exception e) {
                logger.warn("Failed to terminate sandbox {}", spawnedContainerId, e);
            }
            spawnedContainerId = null;
        }
        databaseCleaner.cleanAll();
    }

    @Test
    void realSidecarCheckoutOverHttpWithPatCredentialsSucceeds() throws Exception {
        RepoCredential credential =
                testDataFactory.createCredential(testContext.team(), "Fixture PAT", CredentialType.PAT, FIXTURE_TOKEN);

        launchExecution(credential, "git-cred-canvas");

        DockerExecVerifier verifier = new DockerExecVerifier(processExecutor, spawnedContainerId);
        Awaitility.await()
                .atMost(90, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    ProcessExecutor.ProcessResult result =
                            verifier.executeInContainer("git", "-C", "/kratis/workspace", "rev-parse", "HEAD");
                    assertThat(result.exitCode())
                            .as("git rev-parse in sandbox workspace must succeed (clone failed)")
                            .isEqualTo(0);
                    assertThat(new String(result.output()).trim()).isEqualTo(gitFixture.getExpectedCommitHash());
                });

        verifier.verifyFileContains("/kratis/workspace/hello.txt", FIXTURE_FILE_CONTENT);

        String sidecarLog = extractSidecarLog();
        assertThat(sidecarLog).contains("Registering Git credentials: credType=PAT");
        assertThat(sidecarLog).doesNotContain("could not read Username");
    }

    @Test
    void wrongCredentialsFailCheckoutWithVisibleErrorInControlPlaneLogs(CapturedOutput capturedOutput)
            throws Exception {
        RepoCredential credential = testDataFactory.createCredential(
                testContext.team(), "Wrong PAT", CredentialType.PAT, "definitely-wrong-token");

        launchExecution(credential, "git-cred-wrong-canvas");

        Awaitility.await()
                .atMost(90, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    SandboxExecution execution = sandboxExecutionRepository.findAll().stream()
                            .filter(e -> e.getEnvironment().getId() != null)
                            .findFirst()
                            .orElseThrow();
                    assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
                });

        // The actual cause must be visible in the control-plane logs: the
        // checkout_complete error carries the real git failure.
        String captured = capturedOutput.getOut() + capturedOutput.getErr();
        assertThat(captured)
                .as("control-plane logs must report the checkout failure")
                .contains("Checkout failed for execution");
        assertThat(captured)
                .as("the git authentication failure must be visible in the checkout error")
                .contains("Authentication failed for '" + gitFixture.getGitHttpUrl());
    }

    @Test
    void realSidecarCheckoutOverSshWithKeySucceeds() throws Exception {
        RepoCredential credential = testDataFactory.createCredential(
                testContext.team(), "Fixture SSH Key", CredentialType.SSH_KEY, gitFixture.getSshPrivateKey());

        Repository repository = testDataFactory.createRepository(
                testContext.team(), "fixture-ssh-repo", gitFixture.getGitSshUrl(), "main", credential);
        canvasService.createCanvas(
                chat.getId(), "git-cred-ssh-canvas", "Test Plan", "# Test Plan", CanvasType.SPEC, repository, null);
        launchAndAwaitConnected(new CreateSandboxExecutionRequest(
                provider.getId(), null, AgentHarness.OPENCODE, "git-cred-ssh-canvas", modelProvider.getId(), "gpt-4o"));

        DockerExecVerifier verifier = new DockerExecVerifier(processExecutor, spawnedContainerId);
        Awaitility.await()
                .atMost(90, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    ProcessExecutor.ProcessResult result =
                            verifier.executeInContainer("git", "-C", "/kratis/workspace", "rev-parse", "HEAD");
                    assertThat(result.exitCode())
                            .as("git rev-parse in sandbox workspace must succeed (SSH clone failed)")
                            .isEqualTo(0);
                    assertThat(new String(result.output()).trim()).isEqualTo(gitFixture.getExpectedCommitHash());
                });

        verifier.verifyFileContains("/kratis/workspace/hello.txt", FIXTURE_FILE_CONTENT);

        String sidecarLog = extractSidecarLog();
        assertThat(sidecarLog).contains("Registering Git credentials: credType=SSH_KEY");
        assertThat(sidecarLog).doesNotContain("Permission denied");
    }

    @Test
    void realSidecarInitialisesNewRepository() throws Exception {
        launchNewRepositoryExecution("git-cred-newrepo-canvas", "fresh-repo");

        DockerExecVerifier verifier = new DockerExecVerifier(processExecutor, spawnedContainerId);
        Awaitility.await()
                .atMost(90, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    ProcessExecutor.ProcessResult revParse =
                            verifier.executeInContainer("git", "-C", "/kratis/workspace", "rev-parse", "HEAD");
                    assertThat(revParse.exitCode())
                            .as("workspace must be a git repository after the new-repo init flow")
                            .isEqualTo(0);
                    ProcessExecutor.ProcessResult log =
                            verifier.executeInContainer("git", "-C", "/kratis/workspace", "log", "--format=%s", "-1");
                    assertThat(log.exitCode()).isEqualTo(0);
                    assertThat(new String(log.output()).trim())
                            .as("the initial empty commit must exist")
                            .isEqualTo("Initial commit");
                });
    }

    /**
     * Creates the chat canvas, dispatches the execution via the REST API (the
     * production path: spawn sandbox, inject sidecar, wait for registration),
     * and waits for the environment to reach {@code CONNECTED}.
     */
    private void launchExecution(RepoCredential credential, String canvasId) throws Exception {
        Repository repository = testDataFactory.createRepository(
                testContext.team(), "fixture-repo", gitFixture.getGitHttpUrl(), "main", credential);

        canvasService.createCanvas(
                chat.getId(), canvasId, "Test Plan", "# Test Plan", CanvasType.SPEC, repository, null);

        launchAndAwaitConnected(new CreateSandboxExecutionRequest(
                provider.getId(), null, AgentHarness.OPENCODE, canvasId, modelProvider.getId(), "gpt-4o"));
    }

    private void launchNewRepositoryExecution(String canvasId, String newRepoName) throws Exception {
        canvasService.createCanvas(
                chat.getId(), canvasId, "Test Plan", "# Test Plan", CanvasType.SPEC, null, newRepoName);

        launchAndAwaitConnected(new CreateSandboxExecutionRequest(
                provider.getId(), null, AgentHarness.OPENCODE, canvasId, modelProvider.getId(), "gpt-4o"));
    }

    private void launchAndAwaitConnected(CreateSandboxExecutionRequest request) throws Exception {
        mockMvc.perform(post("/api/v1/chats/{chatId}/executions", chat.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        var env = executionEnvironmentRepository.findByTeamId(chat.getTeam().getId()).stream()
                .filter(e -> e.getName().startsWith("Sandbox -"))
                .findFirst()
                .orElseThrow();
        assertThat(env.getContainerId()).isNotNull();
        spawnedContainerId = env.getContainerId();

        Awaitility.await()
                .atMost(90, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var updated =
                            executionEnvironmentRepository.findById(env.getId()).orElseThrow();
                    assertThat(updated.getStatus()).isEqualTo(EnvironmentStatus.CONNECTED);
                });
    }

    private String extractSidecarLog() {
        Path tempLog;
        try {
            tempLog = Files.createTempFile("kratis-sidecar-", ".log");
        } catch (IOException e) {
            return "";
        }
        try {
            ProcessExecutor.ProcessResult result = processExecutor.execute(
                    List.of("docker", "cp", spawnedContainerId + ":" + SIDECAR_LOG_LOC, tempLog.toString()),
                    null,
                    null);
            if (result.exitCode() != 0) {
                return "";
            }
            return Files.readString(tempLog);
        } catch (Exception e) {
            return "";
        } finally {
            try {
                Files.deleteIfExists(tempLog);
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
