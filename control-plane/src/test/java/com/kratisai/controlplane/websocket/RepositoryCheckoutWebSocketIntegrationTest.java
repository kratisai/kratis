package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.FakeGitHubApiClientConfig.FakeGitHubApiClient;
import com.kratisai.controlplane.SandboxExecutionScenarioFactory;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.wsdto.CheckoutStatus;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.RepoCredentialRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentRpcClient;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.SandboxExecutionService;
import com.kratisai.controlplane.websocket.environment.EnvironmentCheckoutCompleteRpcHandler;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@SpringIntegrationTest
class RepositoryCheckoutWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private SandboxExecutionScenarioFactory scenarioFactory;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private SandboxExecutionService sandboxExecutionService;

    @Autowired
    private EnvironmentCheckoutCompleteRpcHandler checkoutCompleteRpcHandler;

    @Autowired
    private EnvironmentSessionRegistry sessionRegistry;

    @Autowired
    private EnvironmentRpcClient environmentRpcClient;

    @Autowired
    private RepoCredentialRepository repoCredentialRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private FakeGitHubApiClient fakeGitHubApiClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private TestDataFactory.AuthContext auth;
    private Team team;
    private ChatEntity chat;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        auth = testDataFactory.createProvisionedContext();
        team = auth.team();
        chat = testDataFactory.createChat(team, auth.user(), "Checkout Integration Test Session");
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void testEnvironmentCheckoutCompleteRpcHandler_EdgeCases() {
        UUID dummyExecId = UUID.randomUUID();
        EnvironmentRpcPayload.CheckoutComplete successParams = new EnvironmentRpcPayload.CheckoutComplete(
                CheckoutStatus.SUCCESS, "abcdef1234567890", null, dummyExecId.toString());
        JsonRpcInboundRequest dummyRequest = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.CheckoutComplete.METHOD, objectMapper.valueToTree(successParams), null);

        assertThatThrownBy(() -> checkoutCompleteRpcHandler
                        .handle("unmapped-session-id", dummyRequest, successParams)
                        .blockLast())
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32001));
    }

    @Test
    void testEnvironmentCheckoutCompleteRpcHandler_InvalidParamsAndFailure() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                RepoCredential credential = testDataFactory.createCredential(
                        team, "SSH Key Edge Cases", CredentialType.SSH_KEY, "secret-private-key-content");
                Repository repository = testDataFactory.createRepository(
                        team, "edge-cases-repo", "git@github.com:kratisai/edge-cases.git", "main", credential);
                var scenario = scenarioFactory.startOnConnector(
                        auth, chat, "Checkout Edge Cases Connector", "test-plan-edge", repository);
                UUID envId = scenario.envId();
                UUID executionId = scenario.executionId();

                WebSocketSession envSession = mock(WebSocketSession.class);
                when(envSession.getId()).thenReturn("env-session-id-edge-cases");
                sessionRegistry.registerEnvironmentSession(envSession, envId);

                // Send checkout complete with clone failure status
                EnvironmentRpcPayload.CheckoutComplete failureParams = new EnvironmentRpcPayload.CheckoutComplete(
                        CheckoutStatus.FAILED, "Repository not found or access denied", null, executionId.toString());
                JsonRpcInboundRequest failureRequest = new JsonRpcInboundRequest(
                        EnvironmentRpcPayload.CheckoutComplete.METHOD, objectMapper.valueToTree(failureParams), null);
                checkoutCompleteRpcHandler
                        .handle(envSession.getId(), failureRequest, failureParams)
                        .blockLast();

                // Assert sandbox execution transitions to FAILED
                SandboxExecution execAfterFailure =
                        sandboxExecutionRepository.findById(executionId).orElseThrow();
                assertThat(execAfterFailure.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);

                // Clean up session
                sessionRegistry.removeSession("env-session-id-edge-cases");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void testSandboxExecutionService_DispatchCheckoutErrorPath() {
        UUID executionId = new TransactionTemplate(transactionManager).execute(status -> {
            RepoCredential credential = testDataFactory.createCredential(
                    team, "SSH Key Error Cases", CredentialType.SSH_KEY, "secret-private-key-content");
            Repository repository = testDataFactory.createRepository(
                    team, "error-cases-repo", "git@github.com:kratisai/error-cases.git", "main", credential);
            return scenarioFactory
                    .startOnConnector(auth, chat, "Checkout Error Connector", "test-plan-error", repository)
                    .executionId();
        });

        SandboxExecution execution =
                sandboxExecutionRepository.findById(executionId).orElseThrow();
        WebSocketSession faultySession = mock(WebSocketSession.class);
        when(faultySession.getId()).thenReturn("faulty-checkout-session");
        when(faultySession.isOpen()).thenReturn(true);
        try {
            doThrow(new IOException("WebSocket closed unexpectedly"))
                    .when(faultySession)
                    .sendMessage(any());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        sessionRegistry.replaceSessionForEnvironment(
                faultySession, execution.getEnvironment().getId());
        sandboxExecutionService.dispatchExecution(execution, faultySession);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            SandboxExecution execAfterError =
                    sandboxExecutionRepository.findById(executionId).orElseThrow();
            assertThat(execAfterError.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
            assertThat(execAfterError.getCompletedAt()).isNotNull();
        });
    }

    @Test
    void testSandboxExecutionService_DispatchExecutionErrorPath() {
        UUID executionId = new TransactionTemplate(transactionManager).execute(status -> {
            RepoCredential credential = testDataFactory.createCredential(
                    team, "SSH Key Dispatch", CredentialType.SSH_KEY, "secret-private-key-content");
            Repository repository = testDataFactory.createRepository(
                    team, "dispatch-repo", "git@github.com:kratisai/dispatch.git", "main", credential);
            return scenarioFactory
                    .startOnConnector(auth, chat, "Execution Error Connector", "test-plan-dispatch", repository)
                    .executionId();
        });

        SandboxExecution execution =
                sandboxExecutionRepository.findById(executionId).orElseThrow();
        WebSocketSession faultySession = mock(WebSocketSession.class);
        when(faultySession.getId()).thenReturn("faulty-dispatch-session");
        when(faultySession.isOpen()).thenReturn(true);
        try {
            doThrow(new IOException("WebSocket connection reset"))
                    .when(faultySession)
                    .sendMessage(any());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        sessionRegistry.replaceSessionForEnvironment(
                faultySession, execution.getEnvironment().getId());
        sandboxExecutionService.dispatchExecution(execution, faultySession);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            SandboxExecution execAfterError =
                    sandboxExecutionRepository.findById(executionId).orElseThrow();
            assertThat(execAfterError.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
            assertThat(execAfterError.getCompletedAt()).isNotNull();
        });
    }

    @Test
    void testSandboxExecutionService_GitHubAppTokenAndStandardDecryption() throws Exception {
        // Setup committed fixtures first so afterCommit dispatch can reload them
        record Setup(UUID exec1, UUID exec2, UUID exec3) {}
        Setup setup = new TransactionTemplate(transactionManager).execute(status -> {
            RepoCredential githubCredWithInst = testDataFactory.createCredential(
                    team, "GitHub App Cred", CredentialType.GITHUB_APP, "github-secret-content");
            githubCredWithInst.setProviderMetadata("{\"installationId\": \"inst-123\"}");
            githubCredWithInst = repoCredentialRepository.save(githubCredWithInst);

            Repository githubRepoWithInst = testDataFactory.createRepository(
                    team, "github-repo-inst", "https://github.com/fake/repo-inst.git", "main", githubCredWithInst);

            RepoCredential githubCredNoInst = testDataFactory.createCredential(
                    team, "GitHub PAT Cred", CredentialType.GITHUB_APP, "pat-secret-content");

            Repository githubRepoNoInst = testDataFactory.createRepository(
                    team, "github-repo-no-inst", "https://github.com/fake/repo-no-inst.git", "main", githubCredNoInst);

            UUID exec1 = scenarioFactory
                    .startOnConnector(auth, chat, "GitHub Connector 1", "test-plan-gh1", githubRepoWithInst)
                    .executionId();
            UUID exec2 = scenarioFactory
                    .startOnConnector(auth, chat, "GitHub Connector 2", "test-plan-gh2", githubRepoNoInst)
                    .executionId();
            UUID exec3 = scenarioFactory
                    .startOnConnector(auth, chat, "GitHub Connector 3", "test-plan-gh3", githubRepoWithInst)
                    .executionId();

            return new Setup(exec1, exec2, exec3);
        });

        // Case 1: GITHUB_APP with installationId succeeds and dispatches auth + checkout
        WebSocketSession mockSession1 = mock(WebSocketSession.class);
        when(mockSession1.getId()).thenReturn("mock-gh-session-1");
        when(mockSession1.isOpen()).thenReturn(true);
        final List<TextMessage> capturedMessages1 = new ArrayList<>();
        doAnswer(invocation -> {
                    TextMessage message = invocation.getArgument(0);
                    capturedMessages1.add(message);
                    completeMockResultIfRequest(message);
                    return null;
                })
                .when(mockSession1)
                .sendMessage(any());
        SandboxExecution exec1 =
                sandboxExecutionRepository.findById(setup.exec1()).orElseThrow();
        sessionRegistry.replaceSessionForEnvironment(
                mockSession1, exec1.getEnvironment().getId());
        sandboxExecutionService.dispatchExecution(exec1, mockSession1);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(capturedMessages1).hasSize(2));
        String authPayload1 = capturedMessages1.getFirst().getPayload();
        assertThat(authPayload1).contains("\"credentialType\":\"GITHUB_APP\"");
        assertThat(authPayload1).doesNotContain("\"token\"");

        // Case 2: GITHUB_APP without installationId fails
        sandboxExecutionService.dispatchExecution(
                sandboxExecutionRepository.findById(setup.exec2()).orElseThrow(), mock(WebSocketSession.class));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(sandboxExecutionRepository
                        .findById(setup.exec2())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(SandboxExecutionStatus.FAILED));

        // Case 3: token generation throws
        fakeGitHubApiClient.setShouldThrow(true);
        try {
            sandboxExecutionService.dispatchExecution(
                    sandboxExecutionRepository.findById(setup.exec3()).orElseThrow(), mock(WebSocketSession.class));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(sandboxExecutionRepository
                            .findById(setup.exec3())
                            .orElseThrow()
                            .getStatus())
                    .isEqualTo(SandboxExecutionStatus.FAILED));
        } finally {
            fakeGitHubApiClient.reset();
        }
    }

    @Test
    void testNewRepositoryExecution_skipsAuthRegistrationAndLaunchesAgent() throws Exception {
        var scenario = scenarioFactory.startNewRepoOnConnector(
                auth, chat, "New Repo Connector", "test-plan-newrepo", "fresh-repo");
        UUID executionId = scenario.executionId();

        try (WsPair pair = WsPair.connectSidecar(
                port, scenario, sidecar -> sidecar.whenMethod("env.registerGitAuth", (session, payload) -> {
                            throw new AssertionError("env.registerGitAuth must not be sent for a new-repo execution");
                        })
                        .expectTrigger("env.launch_acp_agent", 1))) {
            assertThat(pair.sidecar().awaitTrigger("env.launch_acp_agent", 30, TimeUnit.SECONDS))
                    .isTrue();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                SandboxExecution exec =
                        sandboxExecutionRepository.findById(executionId).orElseThrow();
                assertThat(exec.getStatus()).isEqualTo(SandboxExecutionStatus.IDLE);
            });
        }
    }

    private void completeMockResultIfRequest(TextMessage message) throws Exception {
        JsonNode envelope = objectMapper.readTree(message.getPayload());
        if (envelope.has("method")
                && envelope.has("id")
                && "env.registerGitAuth".equals(envelope.get("method").asText())) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", envelope.get("id"));
            response.putObject("result").put("status", "success");
            environmentRpcClient.completeResponse(response);
        }
    }
}
