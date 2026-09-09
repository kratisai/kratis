package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.FakeGitHubApiClientConfig.FakeGitHubApiClient;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class RepositoryToolIntegrationTest extends AgentTestHarness {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryToolIntegrationTest.class);

    @Autowired
    private PlanningAgentService planningAgentService;

    @Autowired
    private FakeChatModel mockModel;

    @Autowired
    private FakeGitHubApiClient fakeGitHubApiClient;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private ModelProviderRepository modelProviderRepo;

    @Autowired
    private TestDataFactory testDataFactory;

    private UUID sessionId;
    private UUID teamId;
    private UUID providerId;
    private Team team;

    @BeforeEach
    void setUp() throws Exception {
        cleanDatabase();
        mockModel.reset();

        setupTeamAndLogin();

        var users = userRepo.findAll();
        assertThat(users).isNotEmpty();
        User user = users.getFirst();

        team = createTeamWithProvider(user.getId());
        teamId = team.getId();
        providerId =
                modelProviderRepo.findFirstByTeamIdAndIsActiveTrue(teamId).get().getId();
        sessionId = UUID.randomUUID();

        // Create test repository and batch
        Repository testRepo =
                new Repository("test-repo", "https://github.com/test/repo.git", "main", RepositoryType.GENERIC);
        testRepo.setTeam(team);
        testRepo = repositoryRepository.save(testRepo);

        IngestionBatch testBatch = new IngestionBatch(testRepo);
        testBatch.setCommitHash("abc123def");
        testBatch.setStatus(IngestionStatus.SUCCESS);
        testBatch.setActive(true);
        testBatch = ingestionBatchRepository.save(testBatch);

        CtxNode fileNode =
                new CtxNode(testBatch, teamId, testRepo.getName(), NodeType.FILE, "src/payment/PaymentService.java");
        fileNode.setSymbolName("PaymentService");
        ctxNodeRepository.save(fileNode);

        CtxNode methodNode =
                new CtxNode(testBatch, teamId, testRepo.getName(), NodeType.METHOD, "src/payment/PaymentService.java");
        methodNode.setSymbolName("processPayment");
        ctxNodeRepository.save(methodNode);

        CtxNode bookingNode = new CtxNode(
                testBatch, teamId, testRepo.getName(), NodeType.CLASS, "src/booking/BookingController.java");
        bookingNode.setSymbolName("BookingController");
        ctxNodeRepository.save(bookingNode);
    }

    @AfterEach
    void tearDown() {
        fakeGitHubApiClient.reset();
        cleanDatabase();
    }

    @Test
    void shouldListRepositories() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("list repositories")
                .response(
                        "Let me list the repositories.",
                        List.of(new ToolCall("call-1", "function", "list_repositories", "{}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I found the repositories.")
                .build());

        var result =
                planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "Please list repositories");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        assertToolInvocationReturnedResponse(mockModel, 1, "test-repo");

        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I found the repositories"));
    }

    @Test
    void shouldSearchFiles() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("search files")
                .response(
                        "Let me search the files.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "search_files",
                                "{\"repoName\":\"test-repo\",\"query\":\"payment\",\"limit\":10}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I found the matching files.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Search files matching payment");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        assertToolInvocationReturnedResponse(mockModel, 1, "src/payment/PaymentService.java");

        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I found the matching files"));
    }

    @Test
    void shouldGetDependencies() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("get dependencies")
                .response(
                        "Let me get the dependencies.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "get_dependencies",
                                "{\"repoName\":\"test-repo\",\"path\":\"src/main/java/Test.java\",\"depth\":2}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I retrieved the dependencies.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Get dependencies for Test.java");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        assertToolInvocationReturnedResponse(mockModel, 1, "downstream");

        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I retrieved the dependencies"));
    }

    @Test
    void shouldHandleMissingRepositoryGracefully() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("get dependencies")
                .response(
                        "Let me get the dependencies.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "get_dependencies",
                                "{\"repoName\":\"nonexistent-repo\",\"path\":\"Test.java\",\"depth\":2}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Get dependencies for nonexistent repo");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool error was emitted
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.TelemetryResult
                        && ((ClientPayload.TelemetryResult) r)
                                .event()
                                .toString()
                                .contains("ToolError"));

        // Verify the error message was included in the response sent back to the LLM
        assertThat(mockModel.getInvocations()).hasSize(2);
        assertToolInvocationReturnedResponse(mockModel, 1, "Repository 'nonexistent-repo' not found");

        // Agent should continue and produce final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I encountered an error"));
    }

    @Test
    void shouldHandleReadRemoteFileWithMissingRepository() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("read remote file")
                .response(
                        "Let me read the file.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "read_remote_file",
                                "{\"repoName\":\"\",\"filePath\":\"src/Test.java\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Read remote file from a repo");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool error was emitted
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.TelemetryResult
                        && ((ClientPayload.TelemetryResult) r)
                                .event()
                                .toString()
                                .contains("ToolError"));

        // Verify the error message was included in the response sent back to the LLM
        assertToolInvocationReturnedResponse(mockModel, 1, "Repository name must not be blank");

        // Agent should continue and produce final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I encountered an error"));
    }

    @Test
    void shouldReportToolErrorWhenReadRemoteFileProviderFails() {
        RepoCredential credential = testDataFactory.createCredential(team, "PAT", CredentialType.PAT, "pat-secret");
        Repository gitRepo =
                new Repository("git-repo", "https://github.com/kratisai/kratis.git", "main", RepositoryType.GITHUB);
        gitRepo.setTeam(team);
        gitRepo.setCredential(credential);
        repositoryRepository.save(gitRepo);

        mockModel.addMatcher(PromptMatcher.builder()
                .contains("read remote file")
                .response(
                        "Let me read the file.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "read_remote_file",
                                "{\"repoName\":\"git-repo\",\"filePath\":\"src/Main.java\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        fakeGitHubApiClient.setShouldThrow(true);

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Read remote file from git-repo");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        fakeGitHubApiClient.reset();

        // Verify tool error was emitted instead of a success result
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.TelemetryResult
                        && ((ClientPayload.TelemetryResult) r)
                                .event()
                                .toString()
                                .contains("ToolError"));

        // Verify the error message was included in the response sent back to the LLM
        assertToolInvocationReturnedResponse(mockModel, 1, "Simulated GitHub API failure");

        // Agent should continue and produce final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I encountered an error"));
    }
}
