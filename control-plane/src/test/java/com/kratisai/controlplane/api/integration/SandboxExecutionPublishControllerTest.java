package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.FakeGitHubApiClientConfig;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.PublishPrRequestDto;
import com.kratisai.controlplane.api.restdto.PushBranchRequestDto;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.RepoCredentialRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.CredentialService;
import com.kratisai.controlplane.service.EnvironmentRpcClient;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.JwtService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@SpringIntegrationTest
class SandboxExecutionPublishControllerTest {

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
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private RepoCredentialRepository repoCredentialRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private EnvironmentSessionRegistry sessionRegistry;

    @Autowired
    private EnvironmentRpcClient environmentRpcClient;

    @Autowired
    private FakeGitHubApiClientConfig.FakeGitHubApiClient fakeGitHubApiClient;

    @Autowired
    private CredentialService credentialService;

    private String authToken;
    private ChatEntity chat;
    private SandboxExecution execution;
    private boolean rebaseConflict;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();
        fakeGitHubApiClient.reset();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        authToken =
                jwtService.generateAccessToken(ctx.user().getId(), ctx.user().getEmail());
        chat = chatRepository.save(new ChatEntity(ctx.team(), ctx.user(), "Publish Test Chat"));

        RepoCredential credential = new RepoCredential(
                ctx.team(), "test-cred", CredentialType.PAT, credentialService.encrypt("ghp_fake_token"));
        credential.setProviderMetadata("{\"provider\":\"github\"}");
        credential = repoCredentialRepository.save(credential);

        Repository repository = new Repository(
                "test-repo", "https://github.com/test-owner/test-repo.git", "main", RepositoryType.GITHUB);
        repository.setTeam(ctx.team());
        repository.setCredential(credential);
        repository = repositoryRepository.save(repository);

        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setTeam(chat.getTeam());
        environment.setName("Publish Test Env");
        environment.setType(ExecutionEnvironmentType.SANDBOX);
        environment.setStatus(EnvironmentStatus.CONNECTED);
        environment = executionEnvironmentRepository.save(environment);

        ModelProvider modelProvider = testDataFactory.createModelProviderWithLiteLLM(
                ctx.team(), "test-provider", ProviderType.OPENAI, "sk-test", List.of("gpt-4o"));

        execution = new SandboxExecution();
        execution.setChat(chat);
        execution.setEnvironment(environment);
        execution.setRepository(repository);
        execution.setModelProvider(modelProvider);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution = sandboxExecutionRepository.save(execution);

        WebSocketSession mockSession = Mockito.mock(WebSocketSession.class);
        Mockito.when(mockSession.getId()).thenReturn("mock-publish-session-" + UUID.randomUUID());
        Mockito.when(mockSession.isOpen()).thenReturn(true);

        Mockito.doAnswer(inv -> {
                    TextMessage msg = inv.getArgument(0);
                    JsonNode envelope = objectMapper.readTree(msg.getPayload());
                    if (envelope.has("method")) {
                        String method = envelope.get("method").asText();
                        ObjectNode response = objectMapper.createObjectNode();
                        response.put("jsonrpc", "2.0");
                        response.set("id", envelope.get("id"));

                        switch (method) {
                            case "env.registerGitAuth" -> {
                                ObjectNode result = response.putObject("result");
                                result.put("status", "success");
                                environmentRpcClient.completeResponse(response);
                            }
                            case "env.git_push" -> {
                                if (rebaseConflict) {
                                    ObjectNode error = response.putObject("error");
                                    error.put("code", -32001);
                                    error.put("message", "REBASE_CONFLICT: origin/main");
                                    environmentRpcClient.completeResponse(response);
                                    return null;
                                }
                                ObjectNode result = response.putObject("result");
                                result.put("commitSha", "sha-12345");
                                result.put(
                                        "branchName",
                                        envelope.path("params")
                                                .path("branchName")
                                                .asText("kratis/test-branch"));
                                result.put(
                                        "remoteRef",
                                        "refs/heads/"
                                                + envelope.path("params")
                                                        .path("branchName")
                                                        .asText("kratis/test-branch"));
                                result.put("status", "success");
                                environmentRpcClient.completeResponse(response);
                            }
                            case "env.git_diff_summary" -> {
                                ObjectNode result = response.putObject("result");
                                result.put("baseCommit", "base-123");
                                result.put("headCommit", "head-456");
                                result.put("totalAdditions", 10);
                                result.put("totalDeletions", 2);
                                result.put("commitsAhead", 1);
                                result.put("stagedFiles", 0);
                                result.put("unstagedFiles", 0);
                                result.put("hasChanges", true);
                                ArrayNode commits = result.putArray("commitMessages");
                                ObjectNode commit = commits.addObject();
                                commit.put("sha", "c123");
                                commit.put("subject", "feat(auth): implement token verification");
                                commit.put("body", "Added verification logic and tests.");
                                ArrayNode files = result.putArray("files");
                                ObjectNode file = files.addObject();
                                file.put("path", "src/App.java");
                                file.put("status", "MODIFIED");
                                file.put("additions", 10);
                                file.put("deletions", 2);
                                file.put("isCollapsedByDefault", false);
                                environmentRpcClient.completeResponse(response);
                            }
                            case "env.git_file_diff" -> {
                                ObjectNode result = response.putObject("result");
                                result.put("path", "src/App.java");
                                result.put("patch", "@@ -1,3 +1,5 @@\n+line1\n+line2");
                                result.put("additions", 2);
                                result.put("deletions", 0);
                                result.put("totalLines", 50);
                                environmentRpcClient.completeResponse(response);
                            }
                            default -> {
                                // ignore
                            }
                        }
                    }
                    return null;
                })
                .when(mockSession)
                .sendMessage(Mockito.any(TextMessage.class));

        sessionRegistry.registerEnvironmentSession(mockSession, environment.getId());

        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void getPublishCapabilities_returnsSupportedTrueAndStats() throws Exception {
        mockMvc.perform(get(
                                "/api/v1/chats/{chatId}/executions/{execId}/publish-capabilities",
                                chat.getId(),
                                execution.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportsPullRequests").value(true))
                .andExpect(jsonPath("$.repositoryType").value("GITHUB"))
                .andExpect(jsonPath("$.defaultBaseBranch").value("main"))
                .andExpect(jsonPath("$.stats.commitsAhead").value(1))
                .andExpect(jsonPath("$.stats.hasChanges").value(true))
                .andExpect(jsonPath("$.suggestedTitle").value("feat(auth): implement token verification"))
                .andExpect(jsonPath("$.suggestedBody").value("Added verification logic and tests."));
    }

    @Test
    void pushBranch_sendsEnvGitPushAndReturnsResult() throws Exception {
        PushBranchRequestDto request = new PushBranchRequestDto("kratis/test-branch", "Commit message", false);

        mockMvc.perform(post("/api/v1/chats/{chatId}/executions/{execId}/push-branch", chat.getId(), execution.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchName").value("kratis/test-branch"))
                .andExpect(jsonPath("$.commitSha").value("sha-12345"))
                .andExpect(jsonPath("$.status").value("success"));

        SandboxExecution persisted =
                sandboxExecutionRepository.findById(execution.getId()).orElseThrow();
        assertThat(persisted.getPublishedBranch()).isEqualTo("kratis/test-branch");
        assertThat(persisted.getPublishedPrNumber()).isNull();
    }

    @Test
    void pushBranch_rebaseConflict_returnsConflict() throws Exception {
        rebaseConflict = true;
        PushBranchRequestDto request = new PushBranchRequestDto("kratis/test-branch", "Commit message", false);

        mockMvc.perform(post("/api/v1/chats/{chatId}/executions/{execId}/push-branch", chat.getId(), execution.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void publishPullRequest_pushesBranchAndCallsProvider() throws Exception {
        PublishPrRequestDto request =
                new PublishPrRequestDto("kratis/pr-feature", "main", "Feature Title", "Feature Body", false, true);

        mockMvc.perform(post("/api/v1/chats/{chatId}/executions/{execId}/publish-pr", chat.getId(), execution.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prNumber").value(42))
                .andExpect(jsonPath("$.prUrl").value("https://github.com/test-owner/test-repo/pull/42"))
                .andExpect(jsonPath("$.headBranch").value("kratis/pr-feature"));

        SandboxExecution persisted =
                sandboxExecutionRepository.findById(execution.getId()).orElseThrow();
        assertThat(persisted.getPublishedBranch()).isEqualTo("kratis/pr-feature");
        assertThat(persisted.getPublishedPrNumber()).isEqualTo(42L);
        assertThat(persisted.getPublishedPrUrl()).isEqualTo("https://github.com/test-owner/test-repo/pull/42");
    }

    @Test
    void publishPullRequest_republish_pushesToExistingBranchWithoutNewPr() throws Exception {
        PublishPrRequestDto first =
                new PublishPrRequestDto("kratis/pr-feature", "main", "Feature Title", "Feature Body", false, true);

        mockMvc.perform(post("/api/v1/chats/{chatId}/executions/{execId}/publish-pr", chat.getId(), execution.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prNumber").value(42));

        PublishPrRequestDto republish =
                new PublishPrRequestDto("kratis/edited-branch", "main", "Updated Title", "Updated Body", false, true);

        mockMvc.perform(post("/api/v1/chats/{chatId}/executions/{execId}/publish-pr", chat.getId(), execution.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(republish)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prNumber").value(42))
                .andExpect(jsonPath("$.headBranch").value("kratis/pr-feature"))
                .andExpect(jsonPath("$.prUrl").value("https://github.com/test-owner/test-repo/pull/42"));

        assertThat(fakeGitHubApiClient.createPullRequestInvocations()).isEqualTo(1);
    }
}
