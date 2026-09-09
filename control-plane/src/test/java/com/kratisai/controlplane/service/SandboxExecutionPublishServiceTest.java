package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.PublishCapabilitiesDto;
import com.kratisai.controlplane.api.restdto.PublishPrRequestDto;
import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.restdto.PushBranchRequestDto;
import com.kratisai.controlplane.api.restdto.PushBranchResponseDto;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.GitDiffStatus;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.git.provider.CreatePullRequestCommand;
import com.kratisai.controlplane.git.provider.RepoProvider;
import com.kratisai.controlplane.git.provider.RepoProviderRegistry;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class SandboxExecutionPublishServiceTest {

    @Mock
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private SandboxExecutionActivityRepository sandboxExecutionActivityRepository;

    @Mock
    private EnvironmentRpcClient environmentRpcClient;

    @Mock
    private GitCredentialResolver credentialResolver;

    @Mock
    private RepoProviderRegistry providerRegistry;

    @Mock
    private ChatModelFactory chatModelFactory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SandboxExecutionPublishService service;

    private UUID userId;
    private UUID chatId;
    private UUID executionId;
    private UUID envId;
    private User user;
    private Team team;
    private ChatEntity chat;
    private SandboxExecution execution;
    private Repository repository;
    private ModelProvider modelProvider;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        chatId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        envId = UUID.randomUUID();

        user = new User("user@test.com", "User", "hash");
        user.setId(userId);

        team = new Team("Team", "desc");
        team.setId(UUID.randomUUID());

        chat = new ChatEntity(team, user, "Session");
        chat.setId(chatId);

        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setId(envId);
        environment.setTeam(team);

        modelProvider = new ModelProvider("OpenAI", ProviderType.OPENAI, "enc-key", "https://api.openai.com/v1");
        modelProvider.setTeam(team);

        repository = new Repository("my-repo", "https://github.com/test/repo.git", "main", RepositoryType.GITHUB);
        repository.setTeam(team);

        execution = new SandboxExecution();
        execution.setId(executionId);
        execution.setChat(chat);
        execution.setTaskPrompt("Task prompt");
        execution.setEnvironment(environment);
        execution.setRepository(repository);
        execution.setModelProvider(modelProvider);
        execution.setModelName("gpt-4o");

        service = new SandboxExecutionPublishService(
                sandboxExecutionRepository,
                chatRepository,
                sandboxExecutionActivityRepository,
                environmentRpcClient,
                credentialResolver,
                providerRegistry,
                chatModelFactory,
                objectMapper);
    }

    private void stubExecutionLookup() {
        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));
        when(sandboxExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ChatModel createMockChatModel(String responseJson) {
        ChatModel mockChatModel = mock(ChatModel.class);
        ChatOptions mockOptions = mock(ChatOptions.class);
        ChatOptions.Builder mockBuilder = mock(ChatOptions.Builder.class, Answers.RETURNS_SELF);
        when(mockOptions.mutate()).thenReturn(mockBuilder);
        when(mockBuilder.build()).thenReturn(mockOptions);
        when(mockChatModel.getDefaultOptions()).thenReturn(mockOptions);

        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(responseJson))));
        when(mockChatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        return mockChatModel;
    }

    @Test
    void getPublishCapabilities_noEnvironment_returnsZeroStats() {
        execution.setEnvironment(null);
        stubExecutionLookup();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.supportsPullRequests()).isTrue();
        assertThat(result.repositoryType()).isEqualTo("GITHUB");
        assertThat(result.defaultBaseBranch()).isEqualTo("main");
        assertThat(result.stats().hasChanges()).isFalse();
        assertThat(result.stats().formattedSummary()).isEqualTo("There are no changes to publish");
        assertThat(result.suggestedTitle()).isEmpty();
        assertThat(result.suggestedBody()).isEmpty();
    }

    @Test
    void getPublishCapabilities_noRepository_returnsGenericType() {
        execution.setRepository(null);
        execution.setEnvironment(null);
        stubExecutionLookup();

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.supportsPullRequests()).isFalse();
        assertThat(result.repositoryType()).isEqualTo("GENERIC");
        assertThat(result.defaultBaseBranch()).isEqualTo("main");
    }

    @Test
    void getPublishCapabilities_zeroChanges_returnsNoChangesSummary() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(false);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitDiffSummary emptySummary = new EnvironmentConnectorResult.GitDiffSummary(
                "base", "head", 0, 0, 0, 0, 0, false, List.of(), List.of());

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitDiffSummary.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(emptySummary);

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.supportsPullRequests()).isFalse();
        assertThat(result.stats().hasChanges()).isFalse();
        assertThat(result.stats().formattedSummary()).isEqualTo("There are no changes to publish");
        assertThat(result.suggestedTitle()).isEmpty();
        assertThat(result.suggestedBody()).isEmpty();
    }

    @Test
    void getPublishCapabilities_singleCommit_generatesAiSummaryWithAgentMessages() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        List<EnvironmentConnectorResult.GitCommitMessage> commits =
                List.of(new EnvironmentConnectorResult.GitCommitMessage(
                        "abc1234", "feat: add user profile", "Implements user profile page\nand settings"));

        EnvironmentConnectorResult.GitDiffSummary summary =
                new EnvironmentConnectorResult.GitDiffSummary("base", "head", 15, 3, 1, 0, 0, true, commits, List.of());

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitDiffSummary.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(summary);

        when(sandboxExecutionActivityRepository.findByExecutionIdOrderBySequenceAsc(executionId))
                .thenReturn(List.of(new SandboxExecutionActivity(
                        executionId,
                        1,
                        "msg-1",
                        com.kratisai.controlplane.api.wsdto.ActivityType.MESSAGE,
                        com.kratisai.controlplane.api.wsdto.ActivityStatus.COMPLETED,
                        "Implemented profile view and ran full test suite with 100% pass rate.",
                        null)));

        String jsonResponse =
                "```json\n{\n  \"title\": \"feat: add user profile with test verification\",\n  \"body\": \"- Implemented profile view\\n- Ran full test suite\"\n}\n```";
        ChatModel mockChatModel = createMockChatModel(jsonResponse);
        when(chatModelFactory.createChatModel(modelProvider, "gpt-4o")).thenReturn(mockChatModel);

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.stats().hasChanges()).isTrue();
        assertThat(result.stats().commitsAhead()).isEqualTo(1);
        assertThat(result.stats().formattedSummary()).isEqualTo("1 commit (+15, -3 lines)");
        assertThat(result.suggestedTitle()).isEqualTo("feat: add user profile with test verification");
        assertThat(result.suggestedBody()).isEqualTo("- Implemented profile view\n- Ran full test suite");
    }

    @Test
    void getPublishCapabilities_multiCommit_generatesAiSummary() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        List<EnvironmentConnectorResult.GitCommitMessage> commits = List.of(
                new EnvironmentConnectorResult.GitCommitMessage("abc1", "feat: first commit", "Details about first"),
                new EnvironmentConnectorResult.GitCommitMessage("abc2", "fix: second commit", ""));

        EnvironmentConnectorResult.GitDiffSummary summary =
                new EnvironmentConnectorResult.GitDiffSummary("base", "head", 20, 5, 2, 0, 1, true, commits, List.of());

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitDiffSummary.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(summary);

        when(sandboxExecutionActivityRepository.findByExecutionIdOrderBySequenceAsc(executionId))
                .thenReturn(List.of(new SandboxExecutionActivity(
                        executionId,
                        1,
                        "msg-1",
                        com.kratisai.controlplane.api.wsdto.ActivityType.MESSAGE,
                        com.kratisai.controlplane.api.wsdto.ActivityStatus.COMPLETED,
                        "Refactored user controller and fixed regressions.",
                        null)));

        String jsonResponse =
                "```json\n{\n  \"title\": \"feat: synthesized multi-commit title\",\n  \"body\": \"- Summary item 1\\n- Summary item 2\"\n}\n```";
        ChatModel mockChatModel = createMockChatModel(jsonResponse);
        when(chatModelFactory.createChatModel(modelProvider, "gpt-4o")).thenReturn(mockChatModel);

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.stats().commitsAhead()).isEqualTo(2);
        assertThat(result.stats().unstagedFiles()).isEqualTo(1);
        assertThat(result.stats().formattedSummary()).isEqualTo("2 commits, 1 unstaged file (+20, -5 lines)");
        assertThat(result.suggestedTitle()).isEqualTo("feat: synthesized multi-commit title");
        assertThat(result.suggestedBody()).isEqualTo("- Summary item 1\n- Summary item 2");
    }

    @Test
    void getPublishCapabilities_diffSummary_generatesAiDiffSummary() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitDiffSummary summary = new EnvironmentConnectorResult.GitDiffSummary(
                "base",
                "head",
                45,
                12,
                0,
                2,
                0,
                true,
                List.of(),
                List.of(
                        new EnvironmentConnectorResult.GitDiffSummaryFile(
                                "src/App.tsx", GitDiffStatus.MODIFIED, 30, 10, false),
                        new EnvironmentConnectorResult.GitDiffSummaryFile(
                                "src/New.tsx", GitDiffStatus.ADDED, 15, 2, false)));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitDiffSummary.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(summary);

        when(sandboxExecutionActivityRepository.findByExecutionIdOrderBySequenceAsc(executionId))
                .thenReturn(List.of());

        String jsonResponse =
                "{\n  \"title\": \"refactor: update app components\",\n  \"body\": \"- Updates App.tsx\\n- Adds New.tsx\"\n}";
        ChatModel mockChatModel = createMockChatModel(jsonResponse);
        when(chatModelFactory.createChatModel(modelProvider, "gpt-4o")).thenReturn(mockChatModel);

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.stats().stagedFiles()).isEqualTo(2);
        assertThat(result.stats().formattedSummary()).isEqualTo("2 staged files (+45, -12 lines)");
        assertThat(result.suggestedTitle()).isEqualTo("refactor: update app components");
        assertThat(result.suggestedBody()).isEqualTo("- Updates App.tsx\n- Adds New.tsx");
    }

    @Test
    void getPublishCapabilities_throwsWhenAiFails() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        List<EnvironmentConnectorResult.GitCommitMessage> commits = List.of(
                new EnvironmentConnectorResult.GitCommitMessage("abc1", "feat: step 1", "body 1"),
                new EnvironmentConnectorResult.GitCommitMessage("abc2", "fix: step 2", "body 2"));

        EnvironmentConnectorResult.GitDiffSummary summary =
                new EnvironmentConnectorResult.GitDiffSummary("base", "head", 10, 2, 2, 0, 0, true, commits, List.of());

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitDiffSummary.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(summary);

        when(chatModelFactory.createChatModel(modelProvider, "gpt-4o")).thenThrow(new RuntimeException("LLM down"));

        assertThatThrownBy(() -> service.getPublishCapabilities(userId, chatId, executionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Failed to generate PR publish summary via LLM");
    }

    @Test
    void getPublishCapabilities_throwsWhenNoModelProvider() throws Exception {
        execution.setModelProvider(null);
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitDiffSummary summary = new EnvironmentConnectorResult.GitDiffSummary(
                "base",
                "head",
                10,
                2,
                0,
                1,
                0,
                true,
                List.of(),
                List.of(new EnvironmentConnectorResult.GitDiffSummaryFile(
                        "src/App.tsx", GitDiffStatus.MODIFIED, 10, 2, false)));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitDiffSummary.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(summary);

        assertThatThrownBy(() -> service.getPublishCapabilities(userId, chatId, executionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No model provider configured for execution");
    }

    @Test
    void getPublishCapabilities_formattingSummaryVariants() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        String jsonResponse = "{\"title\": \"test: update\", \"body\": \"body\"}";
        ChatModel mockChatModel = createMockChatModel(jsonResponse);
        when(chatModelFactory.createChatModel(modelProvider, "gpt-4o")).thenReturn(mockChatModel);

        // Staged and unstaged
        EnvironmentConnectorResult.GitDiffSummary summary = new EnvironmentConnectorResult.GitDiffSummary(
                "base", "head", 10, 5, 0, 1, 2, true, List.of(), List.of());
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitDiffSummary.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(summary);

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);
        assertThat(result.stats().formattedSummary()).isEqualTo("1 staged file, 2 unstaged files (+10, -5 lines)");

        // Only unstaged
        EnvironmentConnectorResult.GitDiffSummary summary2 = new EnvironmentConnectorResult.GitDiffSummary(
                "base", "head", 8, 1, 0, 0, 2, true, List.of(), List.of());
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitDiffSummary.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(summary2);

        PublishCapabilitiesDto result2 = service.getPublishCapabilities(userId, chatId, executionId);
        assertThat(result2.stats().formattedSummary()).isEqualTo("2 unstaged files (+8, -1 lines)");
    }

    @Test
    void getPublishCapabilities_rpcException_gracefullyDegrades() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitDiffSummary.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(new RuntimeException("RPC timeout"));

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.stats().hasChanges()).isFalse();
        assertThat(result.stats().formattedSummary()).isEqualTo("There are no changes to publish");
    }

    @Test
    void getPublishCapabilities_republished_usesPublishedBranchAsDiffBase() throws Exception {
        execution.setPublishedBranch("kratis/feature-123");
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitDiffSummary summary = new EnvironmentConnectorResult.GitDiffSummary(
                "base", "head", 5, 1, 0, 1, 0, true, List.of(), List.of());

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitDiffSummary.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(summary);

        when(sandboxExecutionActivityRepository.findByExecutionIdOrderBySequenceAsc(executionId))
                .thenReturn(List.of());

        String jsonResponse = "{\"title\": \"fix: incremental delta\", \"body\": \"- fixes\"}";
        ChatModel mockChatModel = createMockChatModel(jsonResponse);
        when(chatModelFactory.createChatModel(modelProvider, "gpt-4o")).thenReturn(mockChatModel);

        service.getPublishCapabilities(userId, chatId, executionId);

        ArgumentCaptor<EnvironmentRpcPayload.GitDiffSummary> summaryCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitDiffSummary.class);
        verify(environmentRpcClient).request(eq(envId), summaryCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(summaryCaptor.getValue().baseBranch()).isEqualTo("kratis/feature-123");
    }

    @Test
    void getPublishCapabilities_firstPublish_usesTargetBranchAsDiffBase() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitDiffSummary summary = new EnvironmentConnectorResult.GitDiffSummary(
                "base", "head", 5, 1, 0, 1, 0, true, List.of(), List.of());

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitDiffSummary.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(summary);

        when(sandboxExecutionActivityRepository.findByExecutionIdOrderBySequenceAsc(executionId))
                .thenReturn(List.of());

        String jsonResponse = "{\"title\": \"feat: first publish\", \"body\": \"- changes\"}";
        ChatModel mockChatModel = createMockChatModel(jsonResponse);
        when(chatModelFactory.createChatModel(modelProvider, "gpt-4o")).thenReturn(mockChatModel);

        service.getPublishCapabilities(userId, chatId, executionId);

        ArgumentCaptor<EnvironmentRpcPayload.GitDiffSummary> summaryCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitDiffSummary.class);
        verify(environmentRpcClient).request(eq(envId), summaryCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(summaryCaptor.getValue().baseBranch()).isEqualTo("main");
    }

    @Test
    void pushBranch_sendsTargetBranchToSandbox() throws Exception {
        stubExecutionLookup();

        EnvironmentConnectorResult.GitPush pushResult = new EnvironmentConnectorResult.GitPush(
                "sha123", "feature-branch", "refs/heads/feature-branch", "SUCCESS");

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(pushResult);

        PushBranchRequestDto request = new PushBranchRequestDto("feature-branch", "my commit message", true);
        service.pushBranch(userId, chatId, executionId, request);

        ArgumentCaptor<EnvironmentRpcPayload.GitPush> pushCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitPush.class);
        verify(environmentRpcClient).request(eq(envId), pushCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(pushCaptor.getValue().targetBranch()).isEqualTo("main");
    }

    @Test
    void pushBranch_rebaseConflict_throwsConflict() throws Exception {
        stubExecutionLookup();

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(new EnvironmentRpcClient.EnvironmentRpcException(-32001, "REBASE_CONFLICT: origin/main"));

        PushBranchRequestDto request = new PushBranchRequestDto("feature-branch", "msg", false);

        assertThatThrownBy(() -> service.pushBranch(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("rebase and retest");
    }

    @Test
    void pushBranch_successfulPush() throws Exception {
        stubExecutionLookup();

        EnvironmentConnectorResult.GitPush pushResult = new EnvironmentConnectorResult.GitPush(
                "sha123", "feature-branch", "refs/heads/feature-branch", "SUCCESS");

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(pushResult);

        PushBranchRequestDto request = new PushBranchRequestDto("feature-branch", "my commit message", true);
        PushBranchResponseDto response = service.pushBranch(userId, chatId, executionId, request);

        assertThat(response.branchName()).isEqualTo("feature-branch");
        assertThat(response.commitSha()).isEqualTo("sha123");
        assertThat(response.remoteRef()).isEqualTo("refs/heads/feature-branch");
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(execution.getPublishedBranch()).isEqualTo("feature-branch");
        verify(sandboxExecutionRepository).save(execution);
    }

    @Test
    void pushBranch_noEnvironment_throwsBadRequest() {
        execution.setEnvironment(null);
        stubExecutionLookup();

        PushBranchRequestDto request = new PushBranchRequestDto("branch", "msg", false);

        assertThatThrownBy(() -> service.pushBranch(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("associated environment");
    }

    @Test
    void pushBranch_nullResult_throwsInternalServerError() throws Exception {
        stubExecutionLookup();

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(null);

        PushBranchRequestDto request = new PushBranchRequestDto("branch", "msg", false);

        assertThatThrownBy(() -> service.pushBranch(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Empty response from sandbox git push");
    }

    @Test
    void pushBranch_interrupted_throwsInternalServerError() throws Exception {
        stubExecutionLookup();

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(new InterruptedException("Interrupted"));

        PushBranchRequestDto request = new PushBranchRequestDto("branch", "msg", false);

        assertThatThrownBy(() -> service.pushBranch(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("interrupted");
    }

    @Test
    void pushBranch_timeout_throwsGatewayTimeout() throws Exception {
        stubExecutionLookup();

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(new TimeoutException("Timeout"));

        PushBranchRequestDto request = new PushBranchRequestDto("branch", "msg", false);

        assertThatThrownBy(() -> service.pushBranch(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Timed out executing git push");
    }

    @Test
    void pushBranch_rpcException_throwsBadGateway() throws Exception {
        stubExecutionLookup();

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(new EnvironmentRpcClient.EnvironmentRpcException(-32603, "Git push failed: rejected"));

        PushBranchRequestDto request = new PushBranchRequestDto("branch", "msg", false);

        assertThatThrownBy(() -> service.pushBranch(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Failed to push git branch");
    }

    @Test
    void publishPullRequest_successfulCreation() throws Exception {
        stubExecutionLookup();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitPush pushResult =
                new EnvironmentConnectorResult.GitPush("sha999", "pr-branch", "refs/heads/pr-branch", "SUCCESS");
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(pushResult);

        GitAuthMaterial auth = GitAuthMaterial.ofToken("pat-token");
        when(credentialResolver.resolve(repository)).thenReturn(auth);

        PullRequestResultDto expectedPr =
                new PullRequestResultDto(101, "https://github.com/test/repo/pull/101", "pr-branch", "main");
        when(repoProvider.createPullRequest(eq(repository), eq(auth), any(CreatePullRequestCommand.class)))
                .thenReturn(expectedPr);

        PublishPrRequestDto request = new PublishPrRequestDto("pr-branch", "main", "PR Title", "PR Body", false, true);
        PullRequestResultDto result = service.publishPullRequest(userId, chatId, executionId, request);

        assertThat(result.prNumber()).isEqualTo(101);
        assertThat(result.prUrl()).isEqualTo("https://github.com/test/repo/pull/101");
        assertThat(execution.getPublishedBranch()).isEqualTo("pr-branch");
        assertThat(execution.getPublishedPrNumber()).isEqualTo(101L);
        assertThat(execution.getPublishedPrUrl()).isEqualTo("https://github.com/test/repo/pull/101");
        verify(sandboxExecutionRepository).save(execution);

        ArgumentCaptor<EnvironmentRpcPayload.GitPush> pushCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitPush.class);
        verify(environmentRpcClient).request(eq(envId), pushCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(pushCaptor.getValue().force()).isFalse();
    }

    @Test
    void publishPullRequest_republish_reusesPersistedBranchAndSkipsCreate() throws Exception {
        execution.setPublishedBranch("existing-branch");
        execution.setPublishedPrNumber(77L);
        execution.setPublishedPrUrl("https://github.com/test/repo/pull/77");
        stubExecutionLookup();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitPush pushResult = new EnvironmentConnectorResult.GitPush(
                "sha777", "existing-branch", "refs/heads/existing-branch", "SUCCESS");
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(pushResult);

        PublishPrRequestDto request =
                new PublishPrRequestDto("edited-branch", "main", "PR Title", "PR Body", false, true);
        PullRequestResultDto result = service.publishPullRequest(userId, chatId, executionId, request);

        assertThat(result.prNumber()).isEqualTo(77L);
        assertThat(result.prUrl()).isEqualTo("https://github.com/test/repo/pull/77");
        assertThat(result.headBranch()).isEqualTo("existing-branch");
        verify(repoProvider, never()).createPullRequest(any(), any(), any());
        verify(sandboxExecutionRepository, never()).save(execution);

        ArgumentCaptor<EnvironmentRpcPayload.GitPush> pushCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitPush.class);
        verify(environmentRpcClient).request(eq(envId), pushCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(pushCaptor.getValue().force()).isTrue();
    }

    @Test
    void publishPullRequest_noRepository_throwsBadRequest() {
        execution.setRepository(null);
        stubExecutionLookup();

        PublishPrRequestDto request = new PublishPrRequestDto("pr-branch", "main", "PR Title", "PR Body", false, false);

        assertThatThrownBy(() -> service.publishPullRequest(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not configured for PR creation");
    }

    @Test
    void publishPullRequest_providerDoesNotSupportPr_throwsBadRequest() {
        stubExecutionLookup();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(false);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        PublishPrRequestDto request = new PublishPrRequestDto("pr-branch", "main", "PR Title", "PR Body", false, false);

        assertThatThrownBy(() -> service.publishPullRequest(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not support Pull Request creation");
    }

    @Test
    void publishPullRequest_noEnvironment_throwsBadRequest() {
        execution.setEnvironment(null);
        stubExecutionLookup();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        PublishPrRequestDto request = new PublishPrRequestDto("pr-branch", "main", "PR Title", "PR Body", false, false);

        assertThatThrownBy(() -> service.publishPullRequest(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("associated environment");
    }

    @Test
    void validateAndGetExecution_chatNotFound_throwsNotFound() {
        when(chatRepository.findById(chatId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPublishCapabilities(userId, chatId, executionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Chat not found");
    }

    @Test
    void validateAndGetExecution_wrongUser_throwsForbidden() {
        User otherUser = new User("other@test.com", "hash", "Other");
        otherUser.setId(UUID.randomUUID());
        chat.setUser(otherUser);
        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> service.getPublishCapabilities(userId, chatId, executionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Access denied to chat");
    }

    @Test
    void validateAndGetExecution_executionNotFound_throwsNotFound() {
        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));
        when(sandboxExecutionRepository.findById(executionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPublishCapabilities(userId, chatId, executionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Execution not found");
    }

    @Test
    void validateAndGetExecution_executionDoesNotBelongToChat_throwsBadRequest() {
        ChatEntity otherChat = new ChatEntity(team, user, "Other");
        otherChat.setId(UUID.randomUUID());
        execution.setChat(otherChat);

        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));
        when(sandboxExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.getPublishCapabilities(userId, chatId, executionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Execution does not belong to chat");
    }
}
