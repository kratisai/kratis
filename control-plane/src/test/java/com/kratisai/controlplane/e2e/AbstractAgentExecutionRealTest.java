package com.kratisai.controlplane.e2e;

import static com.kratisai.controlplane.service.LocalDockerSandboxProvider.SIDECAR_LOG_LOC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.*;
import com.kratisai.controlplane.api.restdto.CreateSandboxExecutionRequest;
import com.kratisai.controlplane.api.restdto.ResolveHitlRequest;
import com.kratisai.controlplane.api.wsdto.ActivityDetail;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.ApprovalOptionKind;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.PermissionOption;
import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.*;
import com.kratisai.controlplane.service.ProcessExecutor;
import com.kratisai.controlplane.websocket.ClientWebSocketFixture;
import com.kratisai.controlplane.websocket.LineLogger;
import jakarta.websocket.ContainerProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionEvaluationListener;
import org.awaitility.core.ConditionTimeoutException;
import org.awaitility.core.EvaluatedCondition;
import org.awaitility.core.TimeoutEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/**
 * Abstract base class for E2E tests that verify real agent binary installation
 * and execution inside a Docker sandbox container using the agent-agnostic
 * {@code kratis-runner-base:latest} image.
 * <p>
 * Subclasses provide the {@link AgentHarness} to test and the expected log
 * strings that prove the binary was installed and started successfully.
 * <p>
 * This test connects a {@link ClientWebSocketFixture} as a UI client subscriber
 * to capture all {@code execution_output} messages in real-time via WebSocket,
 * logging them with SLF4J.
 */
@SlowTest(slotGroup = "E2E_DOCKER_CONTAINER")
@UseRealLlmClient
public abstract class AbstractAgentExecutionRealTest {

    private static final Logger logger = LoggerFactory.getLogger(AbstractAgentExecutionRealTest.class);
    protected TestDataFactory.TestContext testContext;

    /**
     * Resolves the go binary path. Tries /usr/local/go/bin/go first, then falls
     * back to "go" on PATH.
     */
    private static String resolveGoBinary() {
        File absoluteGo = new File("/usr/local/go/bin/go");
        if (absoluteGo.exists() && absoluteGo.canExecute()) {
            return absoluteGo.getAbsolutePath();
        }
        return "go";
    }

    /**
     * Returns a PATH string that includes /usr/local/go/bin prepended to the
     * existing PATH.
     */
    private static String goEnhancedPath() {
        String existingPath = System.getenv("PATH");
        if (existingPath == null) {
            return "/usr/local/go/bin";
        }
        return "/usr/local/go/bin:" + existingPath;
    }

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final String TEST_IMAGE_NAME = "kratis-e2e-test:latest";
    private static final String DUMMY_REPO_CONTAINER_PATH = "/kratis/dummy-repo";

    @BeforeAll
    static void beforeAll() throws Exception {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        String goBinary = resolveGoBinary();

        // Compile the Go sidecar locally first (statically linked for Alpine
        // compatibility)
        ProcessBuilder pbGo = new ProcessBuilder(goBinary, "build", "-o", "kratis-connector", "main.go")
                .directory(new File("../sidecar"))
                .inheritIO();
        pbGo.environment().put("CGO_ENABLED", "0");
        pbGo.environment().put("PATH", goEnhancedPath());
        Process pGo = pbGo.start();
        int exitCodeGo = pGo.waitFor();
        if (exitCodeGo != 0) {
            throw new RuntimeException("Failed to compile Go sidecar");
        }

        // Build the runner-base image
        Process p = new ProcessBuilder(
                        "docker",
                        "build",
                        "-t",
                        "kratis-runner-base:latest",
                        "-f",
                        "../build/Dockerfile.runner-base",
                        "..")
                .inheritIO()
                .start();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to build docker image for real test");
        }

        // Create a dummy git repo and bake it into a test image at /kratis/dummy-repo
        // This eliminates the need for /tmp bind mounts — the repo is inside the
        // container
        Path dummyRepoDir = Files.createTempDirectory("kratis-dummy-repo-");
        Files.writeString(dummyRepoDir.resolve("README.md"), "# Dummy E2E Test Repo\n\nHello Kratis!");
        new ProcessBuilder("git", "init")
                .directory(dummyRepoDir.toFile())
                .start()
                .waitFor();
        new ProcessBuilder("git", "add", ".")
                .directory(dummyRepoDir.toFile())
                .start()
                .waitFor();
        new ProcessBuilder(
                        "git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "Initial")
                .directory(dummyRepoDir.toFile())
                .start()
                .waitFor();
        new ProcessBuilder("git", "branch", "-M", "main")
                .directory(dummyRepoDir.toFile())
                .start()
                .waitFor();

        // Build test image with dummy repo at /kratis/dummy-repo.
        Path tempDockerfile = Files.createTempFile("Dockerfile.e2e-", "");
        Files.writeString(tempDockerfile, """
                FROM kratis-runner-base:latest
                USER root
                RUN mkdir -p /kratis/dummy-repo
                COPY . /kratis/dummy-repo/
                RUN chown -R 1000:1000 /kratis/
                USER kratis
                """);
        Process pImage = new ProcessBuilder(
                        "docker",
                        "build",
                        "-t",
                        TEST_IMAGE_NAME,
                        "-f",
                        tempDockerfile.toString(),
                        dummyRepoDir.toString())
                .inheritIO()
                .start();
        int exitCodeImage = pImage.waitFor();
        Files.deleteIfExists(tempDockerfile);
        if (exitCodeImage != 0) {
            throw new RuntimeException("Failed to build E2E test image with dummy repo");
        }
    }

    @AfterAll
    static void afterAll() {}

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    protected TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private EnvironmentProviderRepository environmentProviderRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private LocalDockerSandboxProvider localDockerSandboxProvider;

    @Autowired
    protected LiteLLMProperties liteLLMProperties;

    @Autowired
    private CanvasService canvasService;

    @Autowired
    private SandboxExecutionService sandboxExecutionService;

    @Autowired
    private VirtualKeyService virtualKeyService;

    @Autowired
    private ProcessExecutor processExecutor;

    @Autowired
    private RepositoryRepository repositoryRepository;

    private Repository repository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String authToken;
    private ChatEntity chat;
    private EnvironmentProvider provider;
    private String originalServerUrl;
    private String spawnedContainerId;
    private WebSocketSession uiClientSession;
    private LineLogger lineLogger;
    protected WireMockLlmServer wireMockLlmServer;
    private volatile boolean executionFailedEarly;
    private final AtomicInteger hitlRequestCount = new AtomicInteger(0);
    private final AtomicReference<ClientPayload.ExecutionAcpInitializedResult> acpInitialized = new AtomicReference<>();
    private final Set<ActivityType> receivedActivityTypes = ConcurrentHashMap.newKeySet();

    private final AtomicInteger conformanceToolEmissionsWithoutId = new AtomicInteger();
    private final Set<String> conformanceToolActionIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> conformanceToolTitlesByActionId = new ConcurrentHashMap<>();
    private final Map<String, List<String>> conformanceStatusSequences = new ConcurrentHashMap<>();
    private final Map<String, ActivityStatus> conformanceLastToolStatus = new ConcurrentHashMap<>();
    private final Set<String> conformanceResurrectedActionIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> conformanceKindOnFirstEmission = new ConcurrentHashMap<>();
    private final Set<String> conformanceLateKindActionIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger conformanceChunkEmissions = new AtomicInteger();
    private final AtomicInteger conformanceChunksWithMessageId = new AtomicInteger();
    private final Set<String> conformanceHitlIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean conformanceSawPlanActivity = new AtomicBoolean(false);
    private final AtomicBoolean conformanceSawTodoToolCall = new AtomicBoolean(false);

    /**
     * Returns the {@link AgentHarness} to test.
     */
    protected abstract AgentHarness getHarness();

    /**
     * Expected {@code agentInfo.name} from the ACP initialize response; {@code null} skips the
     * exact-name assertion for harnesses that do not advertise identity.
     */
    protected abstract String getExpectedAgentName();

    /**
     * Activity types that must each be observed at least once; empty asserts nothing.
     */
    protected List<ActivityType> getExpectedActivityTypes() {
        return List.of();
    }

    /**
     * Returns the session name for this test.
     */
    protected String getSessionName() {
        return getHarness().getName() + " Real Execution Session";
    }

    /**
     * Returns the task prompt to send to the agent. Subclasses may override to
     * provide a different task.
     */
    protected String getTaskPrompt() {
        return "Write a shell script that prints 'Hello Kratis - what a lovely day' and then run it";
    }

    /**
     * Configures the WireMock matchers for the agent test. Subclasses MUST override
     * this to register agent-specific request matchers using
     * {@code WireMockLlmServer.addMatcher()}. The default implementation does
     * nothing.
     */
    protected void configureWireMockScenario() {
        // Default: no matchers. Subclasses should override.
    }

    /**
     * Returns the expected number of HITL permission requests for this agent.
     * Subclasses that use HITL should override this. Defaults to 0.
     */
    abstract int getExpectedHitlRequestCount();

    /**
     * Whether this harness sends {@code session/request_permission} with a
     * {@code toolCallId}, as the ACP spec requires. Harnesses that violate this
     * must override to {@code false}; synthesised {@code hitl-*} ids are then
     * expected instead of an actionId match.
     */
    protected boolean permissionRequestsCarryToolCallId() {
        return true;
    }

    /**
     * Returns the list of expected files that should be created by the agent during
     * execution.
     * Subclasses should override this to provide agent-specific file expectations.
     *
     * <p>
     * Each entry specifies a file path and optionally expected content. The
     * verification is performed using docker exec after the agent execution
     * completes.
     *
     * @return list of expected file specifications, empty by default
     */
    abstract List<ExpectedFile> getExpectedFiles();

    /**
     * Returns the expected output substring to verify when no expected files are
     * defined.
     */
    protected String getExpectedOutputSubstring() {
        return "Hello Kratis - what a lovely day";
    }

    /**
     * Returns the minimum number of LLM requests that must have been matched by
     * WireMock for this agent test. Subclasses should override this to specify
     * the expected minimum based on the number of matchers configured in
     * {@link #configureWireMockScenario()}.
     * <p>
     * This assertion catches agents that bypass LiteLLM entirely (0 requests) or
     * that fail to make expected LLM calls. Set to 0 for agents known to complete
     * without making LLM calls (e.g., Pi with its ACP limitation).
     *
     * @return the minimum expected matched LLM request count (default: 1)
     */
    protected int getExpectedMinimumLlmRequests() {
        return 1;
    }

    /**
     * Creates a {@link DockerExecVerifier} for the spawned container.
     *
     * @return a new DockerExecVerifier instance
     * @throws IllegalStateException if no container has been spawned yet
     */
    protected DockerExecVerifier createDockerVerifier() {
        if (spawnedContainerId == null) {
            throw new IllegalStateException(
                    "No container has been spawned yet. Call executeAgentInstallationAndExecutionFlow first.");
        }
        return new DockerExecVerifier(processExecutor, spawnedContainerId);
    }

    public record ExpectedFile(String path, String expectedContent, String expectedPermissions, boolean executable) {

        /**
         * Creates an expected file with only path verification.
         */
        public static ExpectedFile exists(String path) {
            return new ExpectedFile(path, null, null, false);
        }

        /**
         * Creates an expected file with path and content verification.
         */
        public static ExpectedFile withContent(String path, String expectedContent) {
            return new ExpectedFile(path, expectedContent, null, false);
        }
    }

    /**
     * Verifies all expected files in the container using docker exec.
     * This method should be called after agent execution completes.
     */
    protected void verifyExpectedFilesInContainer() {
        List<ExpectedFile> expectedFiles = getExpectedFiles();
        if (expectedFiles.isEmpty()) {
            logger.info(
                    "[test] No expected files to verify for harness '{}'",
                    getHarness().getName());
            return;
        }

        logger.info(
                "[test] Verifying {} expected file(s) in container for harness '{}'",
                expectedFiles.size(),
                getHarness().getName());

        DockerExecVerifier verifier = createDockerVerifier();

        for (ExpectedFile expectedFile : expectedFiles) {
            logger.info("[test] Verifying expected file: {}", expectedFile.path());

            // Verify file exists
            verifier.verifyFileExists(expectedFile.path());

            // Verify content if specified
            if (expectedFile.expectedContent() != null) {
                verifier.verifyFileContains(expectedFile.path(), expectedFile.expectedContent());
            }

            // Verify permissions if specified
            if (expectedFile.expectedPermissions() != null) {
                verifier.verifyFilePermissions(expectedFile.path(), expectedFile.expectedPermissions());
            }

            // Verify executable if specified
            if (expectedFile.executable()) {
                verifier.verifyFileIsExecutable(expectedFile.path());
            }

            logger.info("[test] Successfully verified expected file: {}", expectedFile.path());
        }

        logger.info(
                "[test] All {} expected file(s) verified successfully for harness '{}'",
                expectedFiles.size(),
                getHarness().getName());
    }

    @BeforeEach
    void setUp() throws Exception {
        logger.info(
                "[E2E-Lifecycle] setUp() starting for test: {} on thread {}",
                this.getClass().getSimpleName(),
                Thread.currentThread().getName());
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        Mockito.reset(processExecutor);
        databaseCleaner.cleanAll();
        executionFailedEarly = false;
        hitlRequestCount.set(0);
        acpInitialized.set(null);
        receivedActivityTypes.clear();
        conformanceToolEmissionsWithoutId.set(0);
        conformanceToolActionIds.clear();
        conformanceToolTitlesByActionId.clear();
        conformanceStatusSequences.clear();
        conformanceLastToolStatus.clear();
        conformanceResurrectedActionIds.clear();
        conformanceKindOnFirstEmission.clear();
        conformanceLateKindActionIds.clear();
        conformanceChunkEmissions.set(0);
        conformanceChunksWithMessageId.set(0);
        conformanceHitlIds.clear();
        conformanceSawPlanActivity.set(false);
        conformanceSawTodoToolCall.set(false);

        originalServerUrl = "ws://host.docker.internal:8080/ws";

        testContext = testDataFactory.createUserAndTeam();
        authToken = jwtService.generateAccessToken(
                testContext.user().getId(), testContext.user().getEmail());

        chat = chatRepository.save(new ChatEntity(testContext.team(), testContext.user(), getSessionName()));

        provider = environmentProviderRepository.findByTeamId(testContext.team().getId()).stream()
                .filter(p -> "Default Docker Provider".equals(p.getName()))
                .findFirst()
                .orElseThrow();
        provider.setDockerImage(TEST_IMAGE_NAME);
        environmentProviderRepository.save(provider);

        // Initialize a fresh WireMock instance for this test
        wireMockLlmServer = new WireMockLlmServer();
        configureWireMockScenario();

        // Use the dummy repo baked into the test image at /kratis/dummy-repo
        repository = new Repository();
        repository.setTeam(testContext.team());
        repository.setName("e2e-repo");
        repository.setUrl("file://" + DUMMY_REPO_CONTAINER_PATH);
        repository.setBranch("main");
        repository = repositoryRepository.save(repository);

        // Connect a UI client WebSocket subscriber with typed rules
        lineLogger = new LineLogger("execution");

        ClientWebSocketFixture uiClientFixture = new ClientWebSocketFixture(
                        authToken, testContext.team().getId())
                .whenType(
                        ClientPayload.ExecutionOutputResult.class,
                        (wsSession, msg) -> lineLogger.appendChunk(msg.stream().getValue(), msg.line()))
                .whenType(ClientPayload.ExecutionCompleteResult.class, (wsSession, msg) -> {
                    lineLogger.flushAll();
                    logger.info("[execution] Complete: exitCode={} status={}", msg.exitCode(), msg.status());
                    if (msg.status() == SandboxExecutionStatus.FAILED) {
                        executionFailedEarly = true;
                    }
                })
                .whenType(ClientPayload.ExecutionActivityResult.class, (wsSession, msg) -> {
                    receivedActivityTypes.add(msg.activityType());
                    captureAcpConformance(msg);
                    logger.debug("[execution][activity] {} — {}", msg.activityType(), msg.description());
                })
                .whenType(ClientPayload.ExecutionAcpInitializedResult.class, (wsSession, msg) -> {
                    acpInitialized.set(msg);
                    logger.info(
                            "[execution][acp_initialized] sessionId={} agentName={} agentVersion={}",
                            msg.sessionId(),
                            msg.agentName(),
                            msg.agentVersion());
                })
                // Auto-approve all HITL permission requests so agent execution proceeds without
                // blocking
                .autoApprovePermissions((executionId, hitlId, command, options) -> {
                    try {
                        conformanceHitlIds.add(hitlId);
                        String optionId = firstAllowOptionId(options);
                        ResolveHitlRequest approveRequest =
                                new ResolveHitlRequest(executionId, hitlId, HitlResponse.APPROVED, optionId, null);
                        mockMvc.perform(post("/api/v1/hitl/resolve")
                                        .header("Authorization", "Bearer " + authToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(approveRequest)))
                                .andExpect(status().isNoContent());
                        hitlRequestCount.incrementAndGet();
                        logger.info(
                                "[execution][permission] Auto-approved permission #{} for execution={}, command='{}'",
                                hitlRequestCount.get(),
                                executionId,
                                command);
                    } catch (Exception e) {
                        logger.error(
                                "[execution][permission] Failed to auto-approve permission for execution={}, command='{}'",
                                executionId,
                                command,
                                e);
                        throw new RuntimeException("Failed to auto-approve permission", e);
                    }
                });
        var wsContainer = ContainerProvider.getWebSocketContainer();
        wsContainer.setDefaultMaxTextMessageBufferSize(20 * 1024 * 1024);
        StandardWebSocketClient wsClient = new StandardWebSocketClient(wsContainer);
        uiClientSession = wsClient.execute(uiClientFixture, "ws://localhost:" + port + "/ws/client")
                .get(5, TimeUnit.SECONDS);
        uiClientFixture.awaitSubscription(5, TimeUnit.SECONDS);
        logger.info(
                "[UI] Client WebSocket connected for team {}",
                testContext.team().getId());
    }

    private static String firstAllowOptionId(List<PermissionOption> options) {
        return options.stream()
                .filter(option -> option != null
                        && (option.kind() == ApprovalOptionKind.ALLOW_ONCE
                                || option.kind() == ApprovalOptionKind.ALLOW_ALWAYS))
                .map(PermissionOption::optionId)
                .findFirst()
                .orElse(null);
    }

    private void captureAcpConformance(ClientPayload.ExecutionActivityResult msg) {
        ActivityDetail detail = msg.detail();
        switch (msg.activityType()) {
            case COMMAND, RESEARCH, EDITED -> {
                String title = detail != null && detail.title() != null ? detail.title() : msg.description();
                if (title != null && title.toLowerCase(Locale.ROOT).contains("todo")) {
                    conformanceSawTodoToolCall.set(true);
                }
                String actionId = msg.actionId();
                if (actionId == null || actionId.isBlank()) {
                    conformanceToolEmissionsWithoutId.incrementAndGet();
                    return;
                }
                conformanceToolActionIds.add(actionId);
                if (title != null) {
                    conformanceToolTitlesByActionId
                            .computeIfAbsent(actionId, k -> ConcurrentHashMap.newKeySet())
                            .add(title);
                }
                ActivityStatus previous = conformanceLastToolStatus.put(actionId, msg.status());
                conformanceStatusSequences
                        .computeIfAbsent(actionId, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(title + " → " + msg.status());
                if (previous != null
                        && (previous == ActivityStatus.COMPLETED || previous == ActivityStatus.FAILED)
                        && (msg.status() == ActivityStatus.PENDING || msg.status() == ActivityStatus.IN_PROGRESS)) {
                    conformanceResurrectedActionIds.add(actionId);
                }
                boolean hasKind = detail != null && detail.kind() != null;
                Boolean firstEmissionHadKind = conformanceKindOnFirstEmission.putIfAbsent(actionId, hasKind);
                if (Boolean.FALSE.equals(firstEmissionHadKind) && hasKind) {
                    conformanceLateKindActionIds.add(actionId);
                }
            }
            case MESSAGE, THINKING -> {
                conformanceChunkEmissions.incrementAndGet();
                if (detail != null
                        && detail.messageId() != null
                        && !detail.messageId().isBlank()) {
                    conformanceChunksWithMessageId.incrementAndGet();
                }
            }
            case PLAN -> conformanceSawPlanActivity.set(true);
            case ELICITATION -> {}
        }
    }

    private void assertAcpConformanceAndReportMatrix() {
        String harness = getHarness().getName();

        assertThat(conformanceToolEmissionsWithoutId.get())
                .as(
                        "ACP conformance for harness '%s': every tool activity emission must carry "
                                + "an actionId (ACP toolCallId)",
                        harness)
                .isZero();

        assertThat(conformanceResurrectedActionIds)
                .as(
                        "ACP conformance for harness '%s': suspected toolCallId reuse — actionIds that "
                                + "reopened after reaching a terminal status. Observed emission sequences: %s",
                        harness,
                        conformanceResurrectedActionIds.stream()
                                .collect(Collectors.toMap(id -> id, conformanceStatusSequences::get)))
                .isEmpty();

        Set<String> unmatchedHitlIds = new TreeSet<>(conformanceHitlIds);
        unmatchedHitlIds.removeAll(conformanceToolActionIds);
        Set<String> synthesisedHitlIds = unmatchedHitlIds.stream()
                .filter(id -> id.startsWith("hitl-"))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> unexplainedHitlIds = new TreeSet<>(unmatchedHitlIds);
        unexplainedHitlIds.removeAll(synthesisedHitlIds);
        assertThat(unexplainedHitlIds)
                .as(
                        "ACP conformance for harness '%s': permission hitlIds that match no tool "
                                + "activity and are not sidecar-synthesised",
                        harness)
                .isEmpty();
        if (permissionRequestsCarryToolCallId()) {
            assertThat(synthesisedHitlIds)
                    .as(
                            "ACP conformance for harness '%s': every permission request must carry the "
                                    + "toolCallId (a synthesised hitl-* id means it was omitted; if this is the "
                                    + "harness's real behaviour, override permissionRequestsCarryToolCallId())",
                            harness)
                    .isEmpty();
        } else {
            logger.warn(
                    "[conformance-matrix] harness={} omits the toolCallId on permission requests; "
                            + "synthesised hitl ids this run: {}, matched real ids: {}",
                    harness,
                    synthesisedHitlIds,
                    conformanceHitlIds.size() - synthesisedHitlIds.size());
        }

        long kindOnFirst = conformanceKindOnFirstEmission.values().stream()
                .filter(Boolean::booleanValue)
                .count();
        Map<String, Set<String>> refinedTitles = new TreeMap<>();
        conformanceToolTitlesByActionId.forEach((actionId, titles) -> {
            if (titles.size() > 1) {
                refinedTitles.put(actionId, titles);
            }
        });
        Map<String, List<String>> sequences = new TreeMap<>();
        conformanceStatusSequences.forEach(sequences::put);
        logger.info(
                """
                [conformance-matrix] harness={}
                  1. tool emissions missing toolCallId: {} (asserted zero)
                  2. distinct tool actionIds: {}; reopened after terminal status: {} (asserted none)
                  3. permission hitlIds: {}; matched tool actionIds: {}
                  4. kind on first emission: {}/{} actionIds; late kind on: {}
                  5. chunk emissions: {}; with raw messageId: {}
                  6. plan activities seen: {}; todo-titled tool calls seen: {}
                  +. actionIds with refined titles across updates (informational): {}
                  +. per-actionId emission sequences: {}""",
                harness,
                conformanceToolEmissionsWithoutId.get(),
                conformanceToolActionIds.size(),
                conformanceResurrectedActionIds.stream().sorted().toList(),
                conformanceHitlIds.stream().sorted().toList(),
                conformanceHitlIds.size(),
                kindOnFirst,
                conformanceKindOnFirstEmission.size(),
                conformanceLateKindActionIds.stream().sorted().toList(),
                conformanceChunkEmissions.get(),
                conformanceChunksWithMessageId.get(),
                conformanceSawPlanActivity.get(),
                conformanceSawTodoToolCall.get(),
                refinedTitles,
                sequences);
    }

    @AfterEach
    void tearDown() {
        logger.info(
                "[E2E-Lifecycle] tearDown() starting for test: {} on thread {}",
                this.getClass().getSimpleName(),
                Thread.currentThread().getName());
        localDockerSandboxProvider.setServerUrl(originalServerUrl);
        Mockito.reset(processExecutor);

        if (wireMockLlmServer != null) {
            wireMockLlmServer.stop();
        }

        // Close UI client WebSocket

        if (uiClientSession != null && uiClientSession.isOpen()) {
            try {
                uiClientSession.close();
            } catch (Exception e) {
                logger.warn("Failed to close UI client WebSocket", e);
            }
        }

        if (spawnedContainerId != null) {
            // Extract container logs before terminating the sandbox
            extractAndLogContainerLogs(spawnedContainerId);

            try {
                localDockerSandboxProvider.terminateSandbox(spawnedContainerId);
            } catch (Exception e) {
                // Ignore
            }
            spawnedContainerId = null;
        }
    }

    /**
     * Extracts and logs the sidecar's log file from the container. The sidecar
     * writes its logs to {@code /kratis/logs/sidecar.log} (via the
     * {@code --log-file} flag) because it runs in detached mode (
     * {@code docker exec -d}) where stdout/stderr is not captured by Docker's
     * logging driver. Useful for diagnosing WebSocket disconnections (e.g.,
     * close code 1009 - message too big).
     */
    private void extractAndLogContainerLogs(String containerId) {
        try {
            // First try to extract the sidecar log file via docker cp
            java.nio.file.Path tempLog = java.nio.file.Files.createTempFile("kratis-sidecar-", ".log");
            try {
                ProcessExecutor.ProcessResult cpResult = processExecutor.execute(
                        List.of("docker", "cp", containerId + ":" + SIDECAR_LOG_LOC, tempLog.toString()), null, null);
                if (cpResult.exitCode() == 0) {
                    String logs = java.nio.file.Files.readString(tempLog);
                    if (!logs.isBlank()) {
                        logger.info("[test] Sidecar logs for {}:\n{}", containerId, logs);
                    } else {
                        logger.info("[test] Sidecar log file is empty for {}", containerId);
                    }
                } else {
                    logger.warn(
                            "[test] Failed to extract sidecar log file (exit code {}): {}",
                            cpResult.exitCode(),
                            new String(cpResult.output()));
                }
            } finally {
                java.nio.file.Files.deleteIfExists(tempLog);
            }
        } catch (Exception e) {
            logger.warn("[test] Failed to extract container logs for {}: {}", containerId, e.getMessage());
        }
    }

    /**
     * Collects all execution_output lines received by the UI client WebSocket into
     * a single string for assertion purposes. Uses the LineLogger which
     * reconstructs complete lines from word-by-word streaming chunks.
     */
    private String collectUiOutputMessages() {
        lineLogger.flushAll();
        return lineLogger.getOutput();
    }

    /**
     * Awaitility poll target: false until a payload with a non-blank sessionId arrives, and the
     * expected agent name matches when configured.
     */
    private boolean acpInitializedPayloadReceived() {
        ClientPayload.ExecutionAcpInitializedResult result = acpInitialized.get();
        if (result == null || result.sessionId() == null || result.sessionId().isBlank()) {
            return false;
        }
        String expectedAgentName = getExpectedAgentName();
        return expectedAgentName == null || expectedAgentName.equals(result.agentName());
    }

    /** Wait for the (async) failure broadcast so the fail-fast assertion can report the output. */
    private void awaitFailureBroadcastIfFailed(UUID envId) {
        if (isExecutionFailed(envId)) {
            try {
                Awaitility.await("failure broadcast for harness " + getHarness().getName())
                        .atMost(15, TimeUnit.SECONDS)
                        .pollInterval(Duration.ofMillis(200))
                        .until(() -> executionFailedEarly);
            } catch (ConditionTimeoutException e) {
                logger.warn(
                        "[test] Execution failed but the failure broadcast did not reach the fixture within 15s "
                                + "for harness '{}'; the subsequent assertion will report the collected output.",
                        getHarness().getName());
            }
        }
    }

    /** Log the execution state on timeout — the bare ConditionTimeoutException is otherwise opaque. */
    private ConditionEvaluationListener<Object> awaitTimeoutDiagnostics(String what, UUID envId) {
        return new ConditionEvaluationListener<>() {
            @Override
            public void conditionEvaluated(EvaluatedCondition<Object> condition) {
                // No-op; only the timeout hook is used.
            }

            @Override
            public void onTimeout(TimeoutEvent timeoutEvent) {
                logger.error(
                        "[test] Timed out waiting for {}. executionFailedEarly={}, terminal={}, status={}, "
                                + "acpInitialized={}, uiSessionOpen={}. UI output:\n{}",
                        what,
                        executionFailedEarly,
                        isExecutionTerminalOrFailed(envId),
                        getExecutionStatus(envId),
                        acpInitialized.get(),
                        uiClientSession != null && uiClientSession.isOpen(),
                        collectUiOutputMessages());
            }
        };
    }

    /**
     * Checks whether all expected files exist in the container via
     * {@code docker exec}. This is a success criteria (not completion) — it
     * verifies the agent produced the expected output files.
     */
    private boolean allExpectedFilesExist() {
        if (spawnedContainerId == null) {
            return false;
        }
        List<ExpectedFile> expectedFiles = getExpectedFiles();
        if (expectedFiles.isEmpty()) {
            return true;
        }
        try {
            DockerExecVerifier verifier = new DockerExecVerifier(processExecutor, spawnedContainerId);
            for (ExpectedFile file : expectedFiles) {
                if (!verifier.fileExists(file.path())) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            logger.debug("[test] Error checking expected files: {}", e.getMessage());
            return false;
        }
    }

    private boolean isExecutionFailed(UUID envId) {
        try {
            return sandboxExecutionRepository.findAll().stream()
                    .filter(e -> e.getEnvironment().getId().equals(envId))
                    .findFirst()
                    .map(e -> e.getStatus() == SandboxExecutionStatus.FAILED)
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isExecutionTerminalOrFailed(UUID envId) {
        try {
            return sandboxExecutionRepository.findAll().stream()
                    .filter(e -> e.getEnvironment().getId().equals(envId))
                    .findFirst()
                    .map(e -> e.getStatus() == SandboxExecutionStatus.FAILED
                            || e.getStatus() == SandboxExecutionStatus.COMPLETED)
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private SandboxExecutionStatus getExecutionStatus(UUID envId) {
        try {
            return sandboxExecutionRepository.findAll().stream()
                    .filter(e -> e.getEnvironment().getId().equals(envId))
                    .findFirst()
                    .map(SandboxExecution::getStatus)
                    .orElse(null);
        } catch (Exception e) {
            logger.debug("[test] Error getting execution status: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Core test method that verifies the agent binary installation and execution
     * flow.
     */
    protected void executeAgentInstallationAndExecutionFlow(ModelProvider modelProvider) throws Exception {
        // Point localDockerSandboxProvider to this running spring boot server port
        String testServerUrl = "ws://host.docker.internal:" + port + "/ws/env";
        localDockerSandboxProvider.setServerUrl(testServerUrl);

        // Create a SPEC canvas bound to the dummy repo and launch execution
        String canvasId = "test-plan-" + getHarness().name().toLowerCase();
        canvasService.createCanvas(
                chat.getId(), canvasId, "Test Plan", getTaskPrompt(), CanvasType.SPEC, repository, null);
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                provider.getId(),
                null,
                getHarness(),
                canvasId,
                modelProvider.getId(),
                modelProvider.getModelNames().getFirst());

        mockMvc.perform(post("/api/v1/chats/{chatId}/executions", chat.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Find spawned environment
        List<ExecutionEnvironment> envs =
                executionEnvironmentRepository.findByTeamId(chat.getTeam().getId());
        ExecutionEnvironment env = envs.stream()
                .filter(e -> e.getName().startsWith("Sandbox -"))
                .findFirst()
                .orElseThrow();
        assertThat(env.getContainerId()).isNotNull();
        spawnedContainerId = env.getContainerId();

        // Wait for the sidecar container to connect
        Awaitility.await()
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    ExecutionEnvironment updatedEnv =
                            executionEnvironmentRepository.findById(env.getId()).orElseThrow();
                    assertThat(updatedEnv.getStatus()).isEqualTo(EnvironmentStatus.CONNECTED);
                });

        // Wait for the ACP agent to initialize. Also accept a persisted IDLE/terminal status:
        // a normal ACP turn transitions the status to IDLE, and the fan-out may not have drained yet.
        Awaitility.await("ACP agent initialization for harness " + getHarness().getName())
                .atMost(180, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .conditionEvaluationListener(awaitTimeoutDiagnostics("ACP agent initialization", env.getId()))
                .until(() -> executionFailedEarly
                        || isExecutionTerminalOrFailed(env.getId())
                        || getExecutionStatus(env.getId()) == SandboxExecutionStatus.IDLE
                        || acpInitializedPayloadReceived());

        // Fail fast on FAILED (the complete fan-out is async, so give the broadcast a moment).
        awaitFailureBroadcastIfFailed(env.getId());
        if (executionFailedEarly) {
            // Extract container logs immediately at point of failure for diagnostics
            if (spawnedContainerId != null) {
                extractAndLogContainerLogs(spawnedContainerId);
            }
        }
        assertThat(executionFailedEarly)
                .as(
                        "Execution for harness '%s' failed early during agent initialization. UI output:\n%s",
                        getHarness().getName(), collectUiOutputMessages())
                .isFalse();

        // Wait for prompt execution to complete: either all expected files exist, or the execution
        // reaches IDLE/terminal state (indicating the ACP agent finished its turn).
        Awaitility.await("ACP prompt completion for harness " + getHarness().getName())
                .atMost(300, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(2))
                .conditionEvaluationListener(awaitTimeoutDiagnostics("ACP prompt completion", env.getId()))
                .until(() -> allExpectedFilesExist()
                        || isExecutionTerminalOrFailed(env.getId())
                        || getExecutionStatus(env.getId()) == SandboxExecutionStatus.IDLE);

        // Fail fast if the execution was reported as FAILED via WebSocket
        awaitFailureBroadcastIfFailed(env.getId());
        if (executionFailedEarly) {
            logger.warn(
                    "[test] Execution failed early while waiting for ACP completion. UI output:\n{}",
                    collectUiOutputMessages());
            // Extract container logs immediately at point of failure for diagnostics
            if (spawnedContainerId != null) {
                extractAndLogContainerLogs(spawnedContainerId);
            }
        }
        assertThat(executionFailedEarly)
                .as(
                        "Execution for harness '%s' failed early while waiting for ACP completion. UI output:\n%s",
                        getHarness().getName(), collectUiOutputMessages())
                .isFalse();

        // Verify ACP prompt completed (status transitioned to IDLE or COMPLETED)
        Awaitility.await("ACP prompt completion status for harness "
                        + getHarness().getName())
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    SandboxExecutionStatus status = getExecutionStatus(env.getId());
                    assertThat(status)
                            .as(
                                    "ACP prompt for harness '%s' must complete (status should be IDLE or COMPLETED). "
                                            + "This indicates env.acp_prompt_complete was not received from the sidecar.",
                                    getHarness().getName())
                            .isIn(SandboxExecutionStatus.IDLE, SandboxExecutionStatus.COMPLETED);
                });
        SandboxExecutionStatus currentStatus = getExecutionStatus(env.getId());
        logger.info(
                "[test] ACP prompt completed with status='{}' for harness '{}'",
                currentStatus,
                getHarness().getName());

        // Fan-out is async, so the payload may arrive just after completion was detected; poll.
        Awaitility.await("execution_acp_initialized for harness " + getHarness().getName())
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(acpInitialized.get())
                        .as(
                                "execution_acp_initialized must be received for harness '%s'. "
                                        + "This indicates env.acp_initialized was not relayed to web clients.",
                                getHarness().getName())
                        .isNotNull());
        ClientPayload.ExecutionAcpInitializedResult initialized = acpInitialized.get();
        assertThat(initialized.sessionId())
                .as(
                        "ACP sessionId must be non-blank for harness '%s'",
                        getHarness().getName())
                .isNotBlank();
        String expectedAgentName = getExpectedAgentName();
        if (expectedAgentName != null) {
            assertThat(initialized.agentName())
                    .as("agentName for harness '%s'", getHarness().getName())
                    .isEqualTo(expectedAgentName);
        }
        logger.info(
                "[test] ACP initialized for harness '{}': sessionId={}, agentName={}, agentVersion={}",
                getHarness().getName(),
                initialized.sessionId(),
                initialized.agentName(),
                initialized.agentVersion());

        List<ActivityType> expectedActivities = getExpectedActivityTypes();
        if (!expectedActivities.isEmpty()) {
            Awaitility.await("expected activities for harness " + getHarness().getName())
                    .atMost(30, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> assertThat(receivedActivityTypes)
                            .as(
                                    "Harness '%s' must produce at least one activity of each expected type. Received: %s",
                                    getHarness().getName(), receivedActivityTypes)
                            .containsAll(expectedActivities));
        }

        // Verify HITL permission request count matches expected
        int expectedHitl = getExpectedHitlRequestCount();
        Awaitility.await("HITL request count for harness " + getHarness().getName())
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(hitlRequestCount.get())
                        .as(
                                "Harness '%s' HITL permission request count mismatch. "
                                        + "Expected %d requests but got %d.",
                                getHarness().getName(), expectedHitl, hitlRequestCount.get())
                        .isEqualTo(expectedHitl));

        assertAcpConformanceAndReportMatrix();

        // SUCCESS VERIFICATION: After completion is detected, verify expected files
        // exist on disk. This confirms the agent actually produced the expected output.
        List<ExpectedFile> expectedFiles = getExpectedFiles();
        if (!expectedFiles.isEmpty()) {
            logger.info(
                    "[test] Verifying {} expected file(s) in container for harness '{}'",
                    expectedFiles.size(),
                    getHarness().getName());

            // Poll for files to appear (agent may still be writing)
            Awaitility.await()
                    .atMost(30, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofSeconds(1))
                    .until(this::allExpectedFilesExist);

            // Verify files with full content/permissions checks
            verifyExpectedFilesInContainer();
        } else {
            // No expected files defined; the last output chunks may still be draining (async fan-out).
            String expectedAgentOutput = getExpectedOutputSubstring();
            logger.info(
                    "[test] No expected files for harness '{}', verifying output contains '{}'",
                    getHarness().getName(),
                    expectedAgentOutput);
            Awaitility.await("expected output in UI stream for harness "
                            + getHarness().getName())
                    .atMost(30, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> assertThat(collectUiOutputMessages())
                            .as(
                                    "Agent completed (status='%s') but expected output '%s' not found in UI output",
                                    currentStatus, expectedAgentOutput)
                            .contains(expectedAgentOutput));
        }

        // Verify that LLM requests actually reached WireMock
        int totalMatchedRequests = wireMockLlmServer.getTotalMatchedRequests();
        int expectedMinRequests = getExpectedMinimumLlmRequests();
        logger.info(
                "[test] WireMock matched {} request(s) for harness '{}' (expected minimum: {})",
                totalMatchedRequests,
                getHarness().getName(),
                expectedMinRequests);
        assertThat(totalMatchedRequests)
                .as(
                        "WireMock should have received at least %d matched LLM request(s) for harness '%s', "
                                + "but only %d were matched. This may indicate the agent bypassed LiteLLM.",
                        expectedMinRequests, getHarness().getName(), totalMatchedRequests)
                .isGreaterThanOrEqualTo(expectedMinRequests);

        // Verify LiteLLM recorded non-zero usage against the execution's virtual key.
        if (expectedMinRequests > 0) {
            SandboxExecution usageExec = sandboxExecutionRepository.findAll().stream()
                    .filter(e -> e.getEnvironment().getId().equals(env.getId()))
                    .findFirst()
                    .orElseThrow();
            String virtualKey = usageExec.getUsage().getVirtualKey();
            assertThat(virtualKey)
                    .as(
                            "Execution for harness '%s' must have a LiteLLM virtual key",
                            getHarness().getName())
                    .isNotBlank();
            Awaitility.await()
                    .atMost(60, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofSeconds(2))
                    .untilAsserted(() -> {
                        LlmUsageSnapshot snapshot = virtualKeyService.fetchUsage(virtualKey);
                        assertThat(snapshot.totalTokens())
                                .as(
                                        "LiteLLM must record non-zero token usage for harness '%s' "
                                                + "(prompt=%d, completion=%d, spend=%s, wireMockMatched=%d). "
                                                + "Zero usage means the agent bypassed LiteLLM and its virtual key never "
                                                + "received the LLM calls.",
                                        getHarness().getName(),
                                        snapshot.promptTokens(),
                                        snapshot.completionTokens(),
                                        snapshot.spend(),
                                        wireMockLlmServer.getTotalMatchedRequests())
                                .isGreaterThan(0L);
                        assertThat(snapshot.promptTokens())
                                .as(
                                        "LiteLLM prompt tokens for harness '%s' must be non-zero",
                                        getHarness().getName())
                                .isGreaterThan(0L);
                        assertThat(snapshot.completionTokens())
                                .as(
                                        "LiteLLM completion tokens for harness '%s' must be non-zero",
                                        getHarness().getName())
                                .isGreaterThan(0L);
                    });
        }

        // Terminate the ACP session if still running
        SandboxExecution execution = sandboxExecutionRepository.findAll().stream()
                .filter(e -> e.getEnvironment().getId().equals(env.getId()))
                .findFirst()
                .orElseThrow();

        if (execution.getStatus() == SandboxExecutionStatus.RUNNING
                || execution.getStatus() == SandboxExecutionStatus.IDLE) {
            sandboxExecutionService.terminateExecution(execution.getId());
        }

        // Wait for the execution to reach a terminal state
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    SandboxExecution updatedExecution = sandboxExecutionRepository.findAll().stream()
                            .filter(e -> e.getEnvironment().getId().equals(env.getId()))
                            .findFirst()
                            .orElseThrow();

                    assertThat(updatedExecution.getStatus())
                            .as(
                                    "Execution for harness '%s' must reach terminal state",
                                    getHarness().getName())
                            .isIn(SandboxExecutionStatus.COMPLETED, SandboxExecutionStatus.FAILED);
                });
    }
}
