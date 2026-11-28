package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.api.wsdto.ActivityDetail;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.git.provider.CreatePullRequestCommand;
import com.kratisai.controlplane.git.provider.CreateRepositoryCommand;
import com.kratisai.controlplane.git.provider.PullRequestSnapshot;
import com.kratisai.controlplane.git.provider.RemoteRepositoryExistsException;
import com.kratisai.controlplane.git.provider.RepoProvider;
import com.kratisai.controlplane.git.provider.RepoProviderRegistry;
import com.kratisai.controlplane.model.ChangeSet;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.ExecutionDiffSnapshot;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.PublishedPrStatus;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.RepositoryVisibility;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.model.event.SandboxExecutionActivityEvent;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.testsupport.llm.FakeChatModel;
import com.kratisai.controlplane.testsupport.llm.PromptMatcher;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SandboxExecutionPublishServiceTest {

    private static final String PUBLISH_SUMMARY_PROMPT_MARKER = "Return ONLY valid JSON matching this schema";

    @Mock
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Mock
    private ChangeSetRepository changeSetRepository;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private ExecutionDiffSnapshotRepository executionDiffSnapshotRepository;

    @Mock
    private SandboxExecutionActivityRepository sandboxExecutionActivityRepository;

    @Mock
    private SandboxExecutionActivityRepository activityRepository;

    @Mock
    private ExecutionActivityPersistenceService activityPersistenceService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private RepoCredentialRepository credentialRepository;

    @Mock
    private EnvironmentRpcClient environmentRpcClient;

    @Mock
    private GitCredentialResolver credentialResolver;

    @Mock
    private RepoProviderRegistry providerRegistry;

    @Mock
    private ChatModelFactory chatModelFactory;

    @Mock
    private SandboxProvisioningService sandboxProvisioningService;

    @Mock
    private ExecutionEnvironmentService executionEnvironmentService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private FakeChatModel fakeChatModel;

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
        fakeChatModel = new FakeChatModel();

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
        environment.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.CONNECTED);

        modelProvider = new ModelProvider("OpenAI", ProviderType.OPENAI, "enc-key", "https://api.openai.com/v1");
        modelProvider.setTeam(team);
        team.setIngestionProvider(modelProvider);
        team.setIngestionModel("gpt-4o");

        repository = new Repository("my-repo", "https://github.com/test/repo.git", "main", RepositoryType.GITHUB);
        repository.setTeam(team);
        when(providerRegistry.getProvider(any(Repository.class))).thenReturn(mock(RepoProvider.class));

        execution = new SandboxExecution();
        execution.setId(executionId);
        execution.setChat(chat);
        execution.setTaskPrompt("Task prompt");
        execution.setEnvironment(environment);
        execution.setRepository(repository);
        execution.setModelProvider(modelProvider);
        execution.setModelName("gpt-4o");

        when(chatModelFactory.createChatModel(modelProvider, "gpt-4o")).thenReturn(fakeChatModel);

        when(activityPersistenceService.recordActivity(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new SandboxExecutionActivity(
                        inv.getArgument(0),
                        1L,
                        inv.getArgument(3),
                        inv.getArgument(1),
                        inv.getArgument(4),
                        inv.getArgument(2),
                        null));

        service = new SandboxExecutionPublishService(
                sandboxExecutionRepository,
                changeSetRepository,
                chatRepository,
                executionDiffSnapshotRepository,
                activityRepository,
                activityPersistenceService,
                eventPublisher,
                repositoryRepository,
                credentialRepository,
                environmentRpcClient,
                credentialResolver,
                providerRegistry,
                chatModelFactory,
                objectMapper,
                sandboxProvisioningService,
                executionEnvironmentService,
                transactionManager);
    }

    private void stubExecutionLookup() {
        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));
        when(sandboxExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));
    }

    private void stubWorkspaceObserve() throws Exception {
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitObservePublish(
                        "main",
                        "sha-main",
                        "sha-main",
                        true,
                        0,
                        0,
                        false,
                        "sha-head",
                        new EnvironmentConnectorResult.GitChangeSetStats(0, 0, List.of(), "digest-empty")));
    }

    private void stubObserveWithChanges() throws Exception {
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitObservePublish(
                        "main",
                        "sha-main",
                        "sha-main",
                        true,
                        0,
                        1,
                        false,
                        null,
                        new EnvironmentConnectorResult.GitChangeSetStats(
                                15,
                                3,
                                List.of(new EnvironmentConnectorResult.GitChangeSetFile(
                                        "src/App.tsx", "modified", 10, 2)),
                                "digest-changes")));
    }

    private void stubPublishSummary(String responseJson) {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains(PUBLISH_SUMMARY_PROMPT_MARKER)
                .response(responseJson)
                .maxMatches(1)
                .build());
    }

    private void stubPublishSummaryChunks(List<String> responseChunks) {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains(PUBLISH_SUMMARY_PROMPT_MARKER)
                .responseChunks(responseChunks)
                .maxMatches(1)
                .build());
    }

    private String publishPromptText() {
        List<FakeChatModel.Invocation> invocations = fakeChatModel.getInvocations();
        assertThat(invocations).isNotEmpty();
        Prompt prompt = invocations.getFirst().prompt();
        assertThat(prompt).isNotNull();
        return prompt.getContents();
    }

    @Test
    void getPublishCapabilities_noEnvironment_conflicts() {
        execution.setEnvironment(null);
        stubExecutionLookup();

        // A zeroed payload would masquerade unpublished work as "nothing to publish".
        assertThatThrownBy(() -> service.getPublishCapabilities(userId, chatId, executionId))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("Publishing needs a running environment");
    }

    @Test
    void getPublishCapabilities_noRepository_returnsGenericType() throws Exception {
        execution.setRepository(null);
        stubExecutionLookup();
        stubWorkspaceObserve();

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.supportsPullRequests()).isFalse();
        assertThat(result.repositoryType()).isEqualTo(RepositoryType.GENERIC);
        assertThat(result.defaultBaseBranch()).isEqualTo("main");
    }

    @Test
    void getPublishCapabilities_zeroChanges_returnsNoChangesSummary() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(false);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitObservePublish emptyObs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                0,
                false,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(0, 0, List.of(), "digest-empty"));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(emptyObs);

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

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                1,
                false,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(15, 3, List.of(), "digest-single"));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

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
        stubPublishSummary(jsonResponse);

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.stats().hasChanges()).isTrue();
        assertThat(result.stats().commitsAhead()).isEqualTo(1);
        assertThat(result.stats().formattedSummary()).isEqualTo("1 unpushed commit (+15, -3 lines)");
        assertThat(result.suggestedTitle()).isEqualTo("feat: add user profile with test verification");
        assertThat(result.suggestedBody()).isEqualTo("- Implemented profile view\n- Ran full test suite");
    }

    @Test
    void getPublishCapabilities_multiCommit_generatesAiSummary() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                2,
                true,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(
                        20,
                        5,
                        List.of(new EnvironmentConnectorResult.GitChangeSetFile("README.md", "modified", 20, 5)),
                        "digest-multi"));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

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
        stubPublishSummary(jsonResponse);

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.stats().commitsAhead()).isEqualTo(2);
        assertThat(result.suggestedTitle()).isEqualTo("feat: synthesized multi-commit title");
        assertThat(result.stats().formattedSummary()).isEqualTo("2 unpushed commits, 1 unstaged file (+20, -5 lines)");
        assertThat(result.suggestedTitle()).isEqualTo("feat: synthesized multi-commit title");
        assertThat(result.suggestedBody()).isEqualTo("- Summary item 1\n- Summary item 2");
    }

    @Test
    void getPublishCapabilities_diffSummary_generatesAiDiffSummary() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                0,
                true,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(
                        45,
                        12,
                        List.of(
                                new EnvironmentConnectorResult.GitChangeSetFile("src/App.tsx", "added", 30, 10),
                                new EnvironmentConnectorResult.GitChangeSetFile("src/New.tsx", "added", 15, 2)),
                        "digest-files"));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        when(sandboxExecutionActivityRepository.findByExecutionIdOrderBySequenceAsc(executionId))
                .thenReturn(List.of());

        String jsonResponse =
                "{\n  \"title\": \"refactor: update app components\",\n  \"body\": \"- Updates App.tsx\\n- Adds New.tsx\"\n}";
        stubPublishSummary(jsonResponse);

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.stats().additions()).isEqualTo(45);
        assertThat(result.stats().formattedSummary()).isEqualTo("2 staged files (+45, -12 lines)");
        assertThat(result.suggestedTitle()).isEqualTo("refactor: update app components");
        assertThat(result.suggestedBody()).isEqualTo("- Updates App.tsx\n- Adds New.tsx");
    }

    @Test
    void getPublishCapabilities_concatenatesMultiPartAiResponse() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                0,
                true,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(
                        45,
                        12,
                        List.of(new EnvironmentConnectorResult.GitChangeSetFile("src/App.tsx", "modified", 30, 10)),
                        "digest-multi"));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        when(sandboxExecutionActivityRepository.findByExecutionIdOrderBySequenceAsc(executionId))
                .thenReturn(List.of());

        stubPublishSummaryChunks(List.of(
                "{\n  \"title\": \"refactor: update app ", "components\",\n  \"body\": \"- Updates App.tsx\"\n}"));

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.suggestedTitle()).isEqualTo("refactor: update app components");
        assertThat(result.suggestedBody()).isEqualTo("- Updates App.tsx");
    }

    @Test
    void getPublishCapabilities_returnsEmptySuggestionWhenAiFails() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                2,
                false,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(
                        10,
                        2,
                        List.of(new EnvironmentConnectorResult.GitChangeSetFile("README.md", "modified", 10, 2)),
                        "digest-ai-fail"));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        when(chatModelFactory.createChatModel(modelProvider, "gpt-4o")).thenThrow(new RuntimeException("LLM down"));

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.stats().hasChanges()).isTrue();
        assertThat(result.suggestedTitle()).isEmpty();
        assertThat(result.suggestedBody()).isEmpty();
        // The failing LLM call contributes no branch either; the target falls back to the
        // mechanical derivation so the dialog still pre-fills a publishable name.
        String mechanicalBranch = "kratis/" + executionId.toString().substring(0, 8) + "-1";
        assertThat(result.target().branchName()).isEqualTo(mechanicalBranch);
    }

    @Test
    void getPublishCapabilities_throwsWhenNoModelProvider() throws Exception {
        team.setIngestionProvider(null);
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                1,
                false,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(
                        10,
                        2,
                        List.of(new EnvironmentConnectorResult.GitChangeSetFile("src/App.tsx", "modified", 10, 2)),
                        "digest-no-model"));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        assertThatThrownBy(() -> service.getPublishCapabilities(userId, chatId, executionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No ingestion model provider configured for team");
    }

    @Test
    void getPublishCapabilities_formattingSummaryVariants() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        String jsonResponse = "{\"title\": \"test: update\", \"body\": \"body\"}";
        stubPublishSummary(jsonResponse);

        EnvironmentConnectorResult.GitObservePublish obs1 = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                0,
                true,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(10, 5, List.of(), "d1"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs1);

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);
        assertThat(result.stats().additions()).isEqualTo(10);
        assertThat(result.stats().deletions()).isEqualTo(5);

        EnvironmentConnectorResult.GitObservePublish obs2 = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                3,
                true,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(12, 4, List.of(), "d2"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs2);

        PublishCapabilitiesDto result3 = service.getPublishCapabilities(userId, chatId, executionId);
        assertThat(result3.stats().commitsAhead()).isEqualTo(3);
    }

    @Test
    void getPublishCapabilities_rpcFailure_returnsServiceUnavailable() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(new RuntimeException("RPC timeout"));

        assertThatThrownBy(() -> service.getPublishCapabilities(userId, chatId, executionId))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void getPublishCapabilities_republished_usesPublishedBranchAsDiffBase() throws Exception {
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId))
                .thenReturn(List.of(new ChangeSet(
                        execution,
                        1,
                        "base",
                        "push",
                        "kratis/feature-123",
                        42L,
                        "https://pr/42",
                        "main",
                        Instant.now())));
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                1,
                false,
                "sha-head",
                new EnvironmentConnectorResult.GitChangeSetStats(5, 1, List.of(), "d"));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        when(sandboxExecutionActivityRepository.findByExecutionIdOrderBySequenceAsc(executionId))
                .thenReturn(List.of());

        String jsonResponse =
                "{\"title\": \"fix: incremental delta\", \"body\": \"- fixes\", \"branchName\": \"feat/from-llm\"}";
        stubPublishSummary(jsonResponse);

        service.getPublishCapabilities(userId, chatId, executionId);

        ArgumentCaptor<EnvironmentRpcPayload.GitObservePublish> obsCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitObservePublish.class);
        verify(environmentRpcClient).request(eq(envId), obsCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(obsCaptor.getValue().headBranch()).isEqualTo("kratis/feature-123");
    }

    @Test
    void getPublishCapabilities_afterPublish_preFillsFromPersistedBranchOverLlmSuggestion() throws Exception {
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId))
                .thenReturn(List.of(new ChangeSet(
                        execution,
                        1,
                        "base",
                        "push",
                        "kratis/feature-123",
                        42L,
                        "https://pr/42",
                        "main",
                        Instant.now())));
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);
        // Provider fetch fails, so prSnapshot is null and the persisted branch must win.
        when(repoProvider.getPullRequest(eq(repository), any(), eq(42L)))
                .thenThrow(new RuntimeException("provider down"));

        stubObserveWithChanges();

        stubPublishSummary("{\"title\": \"feat: persisted\", \"body\": \"- b\", \"branchName\": \"feat/from-llm\"}");

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.target().kind()).isEqualTo(PublishCapabilitiesDto.TargetDto.TargetKind.OPEN_PR);
        assertThat(result.target().branchName()).isEqualTo("kratis/feature-123");
        assertThat(result.suggestedTitle()).isEqualTo("feat: persisted");
    }

    @Test
    void getPublishCapabilities_afterPushOnly_usesPushedBranchAsDiffBase() throws Exception {
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId))
                .thenReturn(List.of(new ChangeSet(
                        execution, 1, "base", "push", "kratis/pushed-branch", null, null, null, Instant.now())));
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(false);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                0,
                false,
                "sha-head",
                new EnvironmentConnectorResult.GitChangeSetStats(0, 0, List.of(), "digest-empty"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        service.getPublishCapabilities(userId, chatId, executionId);

        ArgumentCaptor<EnvironmentRpcPayload.GitObservePublish> obsCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitObservePublish.class);
        verify(environmentRpcClient).request(eq(envId), obsCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(obsCaptor.getValue().headBranch()).isEqualTo("kratis/pushed-branch");
    }

    @Test
    void getPublishCapabilities_branchAtRemoteHead_reportsNoChanges() throws Exception {
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId))
                .thenReturn(List.of(new ChangeSet(
                        execution,
                        1,
                        "base",
                        "push",
                        "kratis/feature-123",
                        42L,
                        "https://pr/42",
                        "main",
                        Instant.now())));
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(false);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                0,
                false,
                "sha-head",
                new EnvironmentConnectorResult.GitChangeSetStats(0, 0, List.of(), "digest-empty"));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.stats().hasChanges()).isFalse();
        assertThat(result.stats().commitsAhead()).isZero();
        assertThat(result.stats().formattedSummary()).isEqualTo("There are no changes to publish");
        assertThat(result.changeSet().files()).isEmpty();
    }

    @Test
    void getPublishCapabilities_firstPublish_usesTargetBranchAsDiffBase() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                1,
                false,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(5, 1, List.of(), "d"));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        when(sandboxExecutionActivityRepository.findByExecutionIdOrderBySequenceAsc(executionId))
                .thenReturn(List.of());

        String jsonResponse = "{\"title\": \"feat: first publish\", \"body\": \"- changes\"}";
        stubPublishSummary(jsonResponse);

        service.getPublishCapabilities(userId, chatId, executionId);

        ArgumentCaptor<EnvironmentRpcPayload.GitObservePublish> obsCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitObservePublish.class);
        verify(environmentRpcClient).request(eq(envId), obsCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(obsCaptor.getValue().headBranch()).isNull();
    }

    @Test
    void getPublishCapabilities_firstPublish_derivesFeatureBranchNotDefaultBranch() throws Exception {
        stubExecutionLookup();
        stubWorkspaceObserve();

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        String expectedBranch = "kratis/" + executionId.toString().substring(0, 8) + "-1";
        assertThat(result.target().branchName()).isEqualTo(expectedBranch);
        assertThat(result.target().branchName()).isNotEqualTo("main");
    }

    private RepoCredential githubNewRepoCredential() {
        RepoCredential credential = new RepoCredential(team, "kratis-cred", CredentialType.PAT, "encrypted");
        credential.setProviderMetadata("{\"provider\":\"github\"}");
        return credential;
    }

    private RepoProvider newRepoProvider(boolean supportsCreation, boolean supportsPr) {
        RepoProvider provider = mock(RepoProvider.class);
        when(provider.supportsRepositoryCreation()).thenReturn(supportsCreation);
        if (supportsPr) {
            when(provider.supportsPullRequests()).thenReturn(true);
        }
        return provider;
    }

    @Test
    void getPublishCapabilities_newRepository_reportsCreationCapabilities() throws Exception {
        execution.setRepository(null);
        execution.setNewRepoName("fresh-repo");
        execution.setNewRepoCredential(githubNewRepoCredential());
        stubExecutionLookup();
        stubWorkspaceObserve();

        RepoProvider provider = newRepoProvider(true, true);
        when(providerRegistry.getProvider(RepositoryType.GITHUB)).thenReturn(provider);

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.newRepo()).isTrue();
        assertThat(result.canCreateRepository()).isTrue();
        assertThat(result.supportsPullRequests()).isTrue();
        assertThat(result.repositoryType()).isEqualTo(RepositoryType.GITHUB);
        assertThat(result.suggestedRepositoryName()).isEqualTo("fresh-repo");
        assertThat(result.visibilityOptions())
                .containsExactly(RepositoryVisibility.PRIVATE, RepositoryVisibility.PUBLIC);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void publishPullRequest_newRepository_createsRemoteSeedsDefaultBranchAndLinksRepository() throws Exception {
        execution.setRepository(null);
        execution.setNewRepoName("fresh-repo");
        RepoCredential credential = githubNewRepoCredential();
        execution.setNewRepoCredential(credential);
        stubExecutionLookup();
        stubWorkspaceObserve();

        RepoProvider provider = newRepoProvider(true, true);
        when(providerRegistry.getProvider(RepositoryType.GITHUB)).thenReturn(provider);
        when(providerRegistry.getProvider(any(Repository.class))).thenReturn(provider);
        when(credentialResolver.resolve(credential)).thenReturn(GitAuthMaterial.ofToken("token"));
        when(credentialResolver.resolve(any(Repository.class))).thenReturn(GitAuthMaterial.ofToken("token"));
        when(repositoryRepository.existsByTeamIdAndName(team.getId(), "fresh-repo"))
                .thenReturn(false);
        when(repositoryRepository.save(any(Repository.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RemoteRepositoryDto remote = new RemoteRepositoryDto(
                "fake-user/fresh-repo",
                "https://github.com/fake-user/fresh-repo.git",
                "git@github.com:fake.git",
                "main");
        when(provider.createRepository(eq(credential), any(GitAuthMaterial.class), any(CreateRepositoryCommand.class)))
                .thenReturn(remote);
        when(provider.createPullRequest(
                        any(Repository.class), any(GitAuthMaterial.class), any(CreatePullRequestCommand.class)))
                .thenReturn(new PullRequestResultDto(
                        42L, "https://github.com/fake-user/fresh-repo/pull/42", "kratis/feature", "main"));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitSetRemote.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitSetRemote("success", "main", "seedsha"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitPush(
                        "sha123", "kratis/feature", "refs/heads/kratis/feature", "SUCCESS"));

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/feature", null, "feat: init", "body", false, false, "fresh-repo", RepositoryVisibility.PRIVATE);

        PullRequestResultDto result = service.publishPullRequest(userId, chatId, executionId, request);

        assertThat(result.prNumber()).isEqualTo(42L);
        assertThat(execution.getRepository()).isNotNull();
        assertThat(execution.getRepository().getUrl()).isEqualTo(remote.cloneUrl());
        assertThat(execution.getNewRepoName()).isNull();
        assertThat(execution.getNewRepoCredential()).isNull();

        ArgumentCaptor<EnvironmentRpcPayload.OutboundRequestPayload> payloadCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.OutboundRequestPayload.class);
        verify(environmentRpcClient, atLeastOnce())
                .request(eq(envId), payloadCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        EnvironmentRpcPayload.GitSetRemote setRemote = (EnvironmentRpcPayload.GitSetRemote)
                payloadCaptor.getAllValues().getFirst();
        assertThat(setRemote.remoteUrl()).isEqualTo(remote.cloneUrl());
        assertThat(setRemote.defaultBranch()).isEqualTo("main");

        ArgumentCaptor<CreateRepositoryCommand> createCaptor = ArgumentCaptor.forClass(CreateRepositoryCommand.class);
        verify(provider).createRepository(eq(credential), any(GitAuthMaterial.class), createCaptor.capture());
        assertThat(createCaptor.getValue().defaultBranch()).isEqualTo("main");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void publishPullRequest_newRepository_alwaysTargetsMainBranch() throws Exception {
        execution.setRepository(null);
        execution.setNewRepoName("fresh-repo");
        execution.setTargetBranch("not-main");
        RepoCredential credential = githubNewRepoCredential();
        execution.setNewRepoCredential(credential);
        stubExecutionLookup();
        stubWorkspaceObserve();

        RepoProvider provider = newRepoProvider(true, true);
        when(providerRegistry.getProvider(RepositoryType.GITHUB)).thenReturn(provider);
        when(providerRegistry.getProvider(any(Repository.class))).thenReturn(provider);
        when(credentialResolver.resolve(credential)).thenReturn(GitAuthMaterial.ofToken("token"));
        when(credentialResolver.resolve(any(Repository.class))).thenReturn(GitAuthMaterial.ofToken("token"));
        when(repositoryRepository.existsByTeamIdAndName(team.getId(), "fresh-repo"))
                .thenReturn(false);
        when(repositoryRepository.save(any(Repository.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RemoteRepositoryDto remote = new RemoteRepositoryDto(
                "fake-user/fresh-repo",
                "https://github.com/fake-user/fresh-repo.git",
                "git@github.com:fake.git",
                "main");
        when(provider.createRepository(eq(credential), any(GitAuthMaterial.class), any(CreateRepositoryCommand.class)))
                .thenReturn(remote);
        when(provider.createPullRequest(
                        any(Repository.class), any(GitAuthMaterial.class), any(CreatePullRequestCommand.class)))
                .thenReturn(new PullRequestResultDto(
                        42L, "https://github.com/fake-user/fresh-repo/pull/42", "kratis/feature", "main"));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitSetRemote.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitSetRemote("success", "main", "seedsha"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitPush(
                        "sha123", "kratis/feature", "refs/heads/kratis/feature", "SUCCESS"));

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/feature", null, "feat: init", "body", false, false, "fresh-repo", RepositoryVisibility.PRIVATE);

        service.publishPullRequest(userId, chatId, executionId, request);

        ArgumentCaptor<EnvironmentRpcPayload.OutboundRequestPayload> payloadCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.OutboundRequestPayload.class);
        verify(environmentRpcClient, atLeastOnce())
                .request(eq(envId), payloadCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        EnvironmentRpcPayload.GitSetRemote setRemote = (EnvironmentRpcPayload.GitSetRemote)
                payloadCaptor.getAllValues().getFirst();
        assertThat(setRemote.defaultBranch()).isEqualTo("main");
        assertThat(execution.getTargetBranch()).isEqualTo("main");
        assertThat(execution.getRepository().getBranch()).isEqualTo("main");
    }

    @Test
    void publishPullRequest_newRepository_credentialSelect_setsCredentialAtPublish() throws Exception {
        execution.setRepository(null);
        execution.setNewRepoName("fresh-repo");
        execution.setNewRepoCredential(null);
        stubExecutionLookup();
        stubWorkspaceObserve();

        RepoCredential selected = githubNewRepoCredential();
        selected.setId(UUID.randomUUID());
        when(credentialRepository.findByTeamIdAndId(team.getId(), selected.getId()))
                .thenReturn(Optional.of(selected));

        RepoProvider provider = newRepoProvider(true, true);
        when(providerRegistry.getProvider(RepositoryType.GITHUB)).thenReturn(provider);
        when(providerRegistry.getProvider(any(Repository.class))).thenReturn(provider);
        when(credentialResolver.resolve(selected)).thenReturn(GitAuthMaterial.ofToken("selected-token"));
        when(credentialResolver.resolve(any(Repository.class))).thenReturn(GitAuthMaterial.ofToken("token"));
        when(repositoryRepository.existsByTeamIdAndName(team.getId(), "fresh-repo"))
                .thenReturn(false);
        when(repositoryRepository.save(any(Repository.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RemoteRepositoryDto remote = new RemoteRepositoryDto(
                "fake-user/fresh-repo",
                "https://github.com/fake-user/fresh-repo.git",
                "git@github.com:fake.git",
                "main");
        when(provider.createRepository(eq(selected), any(GitAuthMaterial.class), any(CreateRepositoryCommand.class)))
                .thenReturn(remote);
        when(provider.createPullRequest(
                        any(Repository.class), any(GitAuthMaterial.class), any(CreatePullRequestCommand.class)))
                .thenReturn(new PullRequestResultDto(
                        43L, "https://github.com/fake-user/fresh-repo/pull/43", "kratis/feature", "main"));

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitSetRemote.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitSetRemote("success", "main", "seedsha"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitPush(
                        "sha123", "kratis/feature", "refs/heads/kratis/feature", "SUCCESS"));

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/feature",
                null,
                "feat: init",
                "body",
                false,
                false,
                "fresh-repo",
                RepositoryVisibility.PRIVATE,
                selected.getId());

        PullRequestResultDto result = service.publishPullRequest(userId, chatId, executionId, request);

        assertThat(result.prNumber()).isEqualTo(43L);
        verify(provider).createRepository(eq(selected), any(GitAuthMaterial.class), any(CreateRepositoryCommand.class));
        assertThat(execution.getRepository().getCredential()).isEqualTo(selected);
        InOrder inOrder = inOrder(sandboxExecutionRepository, sandboxProvisioningService, environmentRpcClient);
        inOrder.verify(sandboxExecutionRepository).updateNewRepoCredential(executionId, selected.getId());
        inOrder.verify(sandboxProvisioningService).registerGitCredentials(execution);
        inOrder.verify(environmentRpcClient)
                .request(eq(envId), any(EnvironmentRpcPayload.GitSetRemote.class), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void pushBranch_newRepository_credentialSelect_setsCredentialAtPublish() throws Exception {
        execution.setRepository(null);
        execution.setNewRepoName("fresh-repo");
        execution.setNewRepoCredential(null);
        stubExecutionLookup();
        stubWorkspaceObserve();

        RepoCredential selected = githubNewRepoCredential();
        selected.setId(UUID.randomUUID());
        when(credentialRepository.findByTeamIdAndId(team.getId(), selected.getId()))
                .thenReturn(Optional.of(selected));

        RepoProvider provider = newRepoProvider(true, false);
        when(providerRegistry.getProvider(RepositoryType.GITHUB)).thenReturn(provider);
        when(credentialResolver.resolve(selected)).thenReturn(GitAuthMaterial.ofToken("selected-token"));
        when(repositoryRepository.existsByTeamIdAndName(team.getId(), "fresh-repo"))
                .thenReturn(false);
        when(repositoryRepository.save(any(Repository.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RemoteRepositoryDto remote = new RemoteRepositoryDto(
                "fake-user/fresh-repo",
                "https://github.com/fake-user/fresh-repo.git",
                "git@github.com:fake.git",
                "main");
        when(provider.createRepository(eq(selected), any(GitAuthMaterial.class), any(CreateRepositoryCommand.class)))
                .thenReturn(remote);
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitSetRemote.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitSetRemote("success", "main", "seedsha"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitPush(
                        "sha123", "feature-branch", "refs/heads/feature-branch", "SUCCESS"));

        PushBranchRequestDto request = new PushBranchRequestDto("feature-branch", "commit", true, selected.getId());
        service.pushBranch(userId, chatId, executionId, request);

        verify(provider).createRepository(eq(selected), any(GitAuthMaterial.class), any(CreateRepositoryCommand.class));
        assertThat(execution.getNewRepoName()).isNull();
        verify(sandboxExecutionRepository).updateNewRepoCredential(executionId, selected.getId());
        verify(sandboxProvisioningService).registerGitCredentials(execution);
    }

    @Test
    void publishPullRequest_newRepository_unsupportedCreation_throwsBadRequest() {
        execution.setRepository(null);
        execution.setNewRepoName("fresh-repo");
        execution.setNewRepoCredential(githubNewRepoCredential());
        stubExecutionLookup();

        RepoProvider provider = newRepoProvider(false, false);
        when(providerRegistry.getProvider(RepositoryType.GITHUB)).thenReturn(provider);

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/feature", null, "feat: init", "body", false, false, "fresh-repo", RepositoryVisibility.PRIVATE);

        assertThatThrownBy(() -> service.publishPullRequest(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Repository creation is not supported");
    }

    @Test
    void publishPullRequest_newRepository_nameCollision_throwsConflict() {
        execution.setRepository(null);
        execution.setNewRepoName("fresh-repo");
        execution.setNewRepoCredential(githubNewRepoCredential());
        stubExecutionLookup();

        RepoProvider provider = newRepoProvider(true, false);
        when(providerRegistry.getProvider(RepositoryType.GITHUB)).thenReturn(provider);
        when(repositoryRepository.existsByTeamIdAndName(team.getId(), "fresh-repo"))
                .thenReturn(true);

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/feature", null, "feat: init", "body", false, false, "fresh-repo", RepositoryVisibility.PRIVATE);

        assertThatThrownBy(() -> service.publishPullRequest(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists for this team");
    }

    @Test
    void publishPullRequest_newRepository_existingNonEmptyRemote_throwsConflict() {
        execution.setRepository(null);
        execution.setNewRepoName("fresh-repo");
        RepoCredential credential = githubNewRepoCredential();
        execution.setNewRepoCredential(credential);
        stubExecutionLookup();

        RepoProvider provider = newRepoProvider(true, false);
        when(providerRegistry.getProvider(RepositoryType.GITHUB)).thenReturn(provider);
        when(credentialResolver.resolve(credential)).thenReturn(GitAuthMaterial.ofToken("token"));
        when(repositoryRepository.existsByTeamIdAndName(team.getId(), "fresh-repo"))
                .thenReturn(false);
        when(provider.createRepository(eq(credential), any(GitAuthMaterial.class), any(CreateRepositoryCommand.class)))
                .thenThrow(new RemoteRepositoryExistsException("Repository fake-user/fresh-repo already exists"));

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/feature", null, "feat: init", "body", false, false, "fresh-repo", RepositoryVisibility.PRIVATE);

        assertThatThrownBy(() -> service.publishPullRequest(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void publishPullRequest_newRepository_credentialWithoutProvider_throwsBadRequest() {
        execution.setRepository(null);
        execution.setNewRepoName("fresh-repo");
        RepoCredential credential = new RepoCredential(team, "kratis-cred", CredentialType.PAT, "encrypted");
        execution.setNewRepoCredential(credential);
        stubExecutionLookup();

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/feature",
                null,
                "feat: init",
                "body",
                false,
                false,
                "fresh-repo",
                RepositoryVisibility.PRIVATE,
                null);

        assertThatThrownBy(() -> service.publishPullRequest(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void publishPullRequest_newRepository_unknownCredential_throwsNotFound() {
        execution.setRepository(null);
        execution.setNewRepoName("fresh-repo");
        execution.setNewRepoCredential(githubNewRepoCredential());
        stubExecutionLookup();

        UUID unknown = UUID.randomUUID();
        when(credentialRepository.findByTeamIdAndId(team.getId(), unknown)).thenReturn(Optional.empty());

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/feature",
                null,
                "feat: init",
                "body",
                false,
                false,
                "fresh-repo",
                RepositoryVisibility.PRIVATE,
                unknown);

        assertThatThrownBy(() -> service.publishPullRequest(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Credential not found");
    }

    @Test
    void publishPullRequest_newRepository_setRemoteFailure_throwsBadGateway() throws Exception {
        execution.setRepository(null);
        execution.setNewRepoName("fresh-repo");
        RepoCredential credential = githubNewRepoCredential();
        execution.setNewRepoCredential(credential);
        stubExecutionLookup();

        RepoProvider provider = newRepoProvider(true, false);
        when(providerRegistry.getProvider(RepositoryType.GITHUB)).thenReturn(provider);
        when(credentialResolver.resolve(credential)).thenReturn(GitAuthMaterial.ofToken("token"));
        when(repositoryRepository.existsByTeamIdAndName(team.getId(), "fresh-repo"))
                .thenReturn(false);
        when(provider.createRepository(eq(credential), any(GitAuthMaterial.class), any(CreateRepositoryCommand.class)))
                .thenReturn(new RemoteRepositoryDto(
                        "fake-user/fresh-repo", "https://github.com/fake-user/fresh-repo.git", "", "main"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitSetRemote.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(new EnvironmentRpcClient.EnvironmentRpcException(-32000, "origin rejected"));

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/feature", null, "feat: init", "body", false, false, "fresh-repo", RepositoryVisibility.PRIVATE);

        assertThatThrownBy(() -> service.publishPullRequest(userId, chatId, executionId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Failed to configure the remote repository");
    }

    @Test
    void pushBranch_sendsTargetBranchToSandbox() throws Exception {
        stubExecutionLookup();
        stubWorkspaceObserve();

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
        stubWorkspaceObserve();

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
        stubWorkspaceObserve();
        when(executionDiffSnapshotRepository.findByExecutionId(executionId))
                .thenReturn(Optional.of(
                        new ExecutionDiffSnapshot(executionId, "base", "head", 1, 0, "[]", "diff-manifest-digest")));

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

        ArgumentCaptor<ChangeSet> changeSetCaptor = ArgumentCaptor.forClass(ChangeSet.class);
        verify(changeSetRepository).save(changeSetCaptor.capture());
        assertThat(changeSetCaptor.getValue().getManifestDigest()).isEqualTo("diff-manifest-digest");
    }

    @Test
    void pushBranch_withoutDiffSnapshot_storesObservedChangeSetDigest() throws Exception {
        stubExecutionLookup();
        stubWorkspaceObserve();
        when(executionDiffSnapshotRepository.findByExecutionId(executionId)).thenReturn(Optional.empty());

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitPush(
                        "sha123", "feature-branch", "refs/heads/feature-branch", "SUCCESS"));

        service.pushBranch(userId, chatId, executionId, new PushBranchRequestDto("feature-branch", "msg", true));

        ArgumentCaptor<ChangeSet> changeSetCaptor = ArgumentCaptor.forClass(ChangeSet.class);
        verify(changeSetRepository).save(changeSetCaptor.capture());
        assertThat(changeSetCaptor.getValue().getManifestDigest()).isEqualTo("digest-empty");
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
        stubWorkspaceObserve();

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
        stubWorkspaceObserve();

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
        stubWorkspaceObserve();

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
        stubWorkspaceObserve();

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
        stubWorkspaceObserve();
        when(executionDiffSnapshotRepository.findByExecutionId(executionId))
                .thenReturn(Optional.of(
                        new ExecutionDiffSnapshot(executionId, "base", "head", 1, 0, "[]", "diff-manifest-digest")));

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

        ArgumentCaptor<ChangeSet> changeSetCaptor = ArgumentCaptor.forClass(ChangeSet.class);
        verify(changeSetRepository).save(changeSetCaptor.capture());
        assertThat(changeSetCaptor.getValue().getManifestDigest()).isEqualTo("diff-manifest-digest");

        ArgumentCaptor<EnvironmentRpcPayload.GitPush> pushCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitPush.class);
        verify(environmentRpcClient).request(eq(envId), pushCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(pushCaptor.getValue().force()).isFalse();
    }

    @Test
    void publishPullRequest_noCustomBranchAndTargetMatchesBaseBranch_derivesBranch() throws Exception {
        stubExecutionLookup();
        stubWorkspaceObserve();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        GitAuthMaterial auth = GitAuthMaterial.ofToken("pat-token");
        when(credentialResolver.resolve(repository)).thenReturn(auth);

        String expectedBranch = "kratis/" + executionId.toString().substring(0, 8) + "-1";
        EnvironmentConnectorResult.GitPush pushResult = new EnvironmentConnectorResult.GitPush(
                "sha999", expectedBranch, "refs/heads/" + expectedBranch, "SUCCESS");
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(pushResult);

        PullRequestResultDto expectedPr =
                new PullRequestResultDto(102, "https://github.com/test/repo/pull/102", expectedBranch, "main");
        when(repoProvider.createPullRequest(eq(repository), eq(auth), any(CreatePullRequestCommand.class)))
                .thenReturn(expectedPr);

        PublishPrRequestDto request =
                new PublishPrRequestDto("", "main", "PR Title", "PR Body", false, false, null, null, null, true, null);
        PullRequestResultDto result = service.publishPullRequest(userId, chatId, executionId, request);

        assertThat(result.headBranch()).isEqualTo(expectedBranch);
        ArgumentCaptor<EnvironmentRpcPayload.GitPush> pushCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitPush.class);
        verify(environmentRpcClient).request(eq(envId), pushCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(pushCaptor.getValue().branchName()).isEqualTo(expectedBranch);
    }

    @Test
    void publishPullRequest_customBranchEqualsBaseBranch_throwsBadRequest() {
        stubExecutionLookup();

        PublishPrRequestDto request = new PublishPrRequestDto(
                "main", "main", "PR Title", "PR Body", false, false, null, null, null, false, null);

        org.springframework.web.server.ResponseStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.publishPullRequest(userId, chatId, executionId, request));

        assertThat(ex.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).contains("Cannot publish directly to base branch");
    }

    @Test
    void publishPullRequest_invalidGitRefBranch_throwsBadRequest() {
        stubExecutionLookup();

        PublishPrRequestDto request = new PublishPrRequestDto(
                "-option-flag", "main", "PR Title", "PR Body", false, false, null, null, null, false, null);

        org.springframework.web.server.ResponseStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.publishPullRequest(userId, chatId, executionId, request));

        assertThat(ex.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).contains("Invalid git branch name");
    }

    @Test
    void pushBranch_invalidOrBaseBranch_throwsBadRequest() {
        stubExecutionLookup();

        com.kratisai.controlplane.api.restdto.PushBranchRequestDto baseBranchRequest =
                new com.kratisai.controlplane.api.restdto.PushBranchRequestDto("main", "commit msg", false, null);

        org.springframework.web.server.ResponseStatusException exBase = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.pushBranch(userId, chatId, executionId, baseBranchRequest));
        assertThat(exBase.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        assertThat(exBase.getReason()).contains("Cannot publish directly to base branch");

        com.kratisai.controlplane.api.restdto.PushBranchRequestDto invalidRefRequest =
                new com.kratisai.controlplane.api.restdto.PushBranchRequestDto("--flag", "commit msg", false, null);

        org.springframework.web.server.ResponseStatusException exRef = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.pushBranch(userId, chatId, executionId, invalidRefRequest));
        assertThat(exRef.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        assertThat(exRef.getReason()).contains("Invalid git branch name");
    }

    @Test
    void isValidGitRefName_enforcesGitRefRules() {
        // Valid
        assertThat(SandboxExecutionPublishService.isValidGitRefName("feature/add-login"))
                .isTrue();
        assertThat(SandboxExecutionPublishService.isValidGitRefName("kratis/feature-1_2.0"))
                .isTrue();

        // Invalid: leading dash/slash/dot
        assertThat(SandboxExecutionPublishService.isValidGitRefName("-option")).isFalse();
        assertThat(SandboxExecutionPublishService.isValidGitRefName("/leading-slash"))
                .isFalse();
        assertThat(SandboxExecutionPublishService.isValidGitRefName(".leading-dot"))
                .isFalse();

        // Invalid: trailing slash/dot
        assertThat(SandboxExecutionPublishService.isValidGitRefName("trailing/"))
                .isFalse();
        assertThat(SandboxExecutionPublishService.isValidGitRefName("trailing."))
                .isFalse();

        // Invalid: consecutive dots or slashes
        assertThat(SandboxExecutionPublishService.isValidGitRefName("foo..bar")).isFalse();
        assertThat(SandboxExecutionPublishService.isValidGitRefName("foo//bar")).isFalse();

        // Invalid: .lock component
        assertThat(SandboxExecutionPublishService.isValidGitRefName("feature.lock"))
                .isFalse();
        assertThat(SandboxExecutionPublishService.isValidGitRefName("feat/foo.lock/bar"))
                .isFalse();
        assertThat(SandboxExecutionPublishService.isValidGitRefName("feat/.dot/bar"))
                .isFalse();

        // Invalid: spaces or invalid chars
        assertThat(SandboxExecutionPublishService.isValidGitRefName("feat branch"))
                .isFalse();
        assertThat(SandboxExecutionPublishService.isValidGitRefName("feat~1")).isFalse();
        assertThat(SandboxExecutionPublishService.isValidGitRefName("feat^1")).isFalse();
        assertThat(SandboxExecutionPublishService.isValidGitRefName("feat:1")).isFalse();
        assertThat(SandboxExecutionPublishService.isValidGitRefName("feat?1")).isFalse();
        assertThat(SandboxExecutionPublishService.isValidGitRefName("feat*1")).isFalse();
        assertThat(SandboxExecutionPublishService.isValidGitRefName("feat[1]")).isFalse();
        assertThat(SandboxExecutionPublishService.isValidGitRefName("feat@{1}")).isFalse();
    }

    @Test
    void publishPullRequest_republish_reusesPersistedBranchAndSkipsCreate() throws Exception {
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId))
                .thenReturn(List.of(new ChangeSet(
                        execution,
                        1,
                        "base",
                        "push",
                        "existing-branch",
                        77L,
                        "https://github.com/test/repo/pull/77",
                        "main",
                        Instant.now())));
        stubExecutionLookup();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);
        when(repoProvider.getPullRequest(eq(repository), any(), eq(77L)))
                .thenReturn(new PullRequestSnapshot(
                        77L,
                        PullRequestSnapshot.PullRequestState.OPEN,
                        "https://github.com/test/repo/pull/77",
                        "existing-branch",
                        "main",
                        "sha777",
                        null));

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                1,
                false,
                "sha777",
                new EnvironmentConnectorResult.GitChangeSetStats(5, 0, List.of(), "digest-republish"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

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

    @Test
    void getPublishCapabilities_previousPrMerged_offersNewBranchAndHistory() throws Exception {
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId))
                .thenReturn(List.of(new ChangeSet(
                        execution,
                        1,
                        "base",
                        "push",
                        "existing-branch",
                        77L,
                        "https://github.com/test/repo/pull/77",
                        "main",
                        Instant.now())));
        stubExecutionLookup();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        when(repoProvider.getPullRequest(eq(repository), any(), eq(77L)))
                .thenReturn(new PullRequestSnapshot(
                        77L,
                        PullRequestSnapshot.PullRequestState.MERGED,
                        "https://github.com/test/repo/pull/77",
                        "existing-branch",
                        "main",
                        "sha777",
                        null));

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                1,
                false,
                "sha777",
                new EnvironmentConnectorResult.GitChangeSetStats(5, 0, List.of(), "digest"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        stubPublishSummary(
                "{\"title\": \"feat: follow-up\", \"body\": \"- next round\", \"branchName\": \"feat/after-merge\"}");

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.previousPrMerged()).isTrue();
        assertThat(result.suggestedNewBranch()).isEqualTo("feat/after-merge");
        assertThat(result.target().branchName()).isEqualTo("feat/after-merge");
    }

    @Test
    void getPublishCapabilities_llmBranchName_preFillsSanitizedTargetBranch() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        stubObserveWithChanges();

        stubPublishSummary(
                "{\"title\": \"feat: add login\", \"body\": \"- adds login\", \"branchName\": \"feat/add user profile!\"}");

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        // Same character replacement rules as the web sanitizeBranchName, then git-ref checks.
        assertThat(result.target().branchName()).isEqualTo("feat/add_user_profile_");
        assertThat(result.suggestedTitle()).isEqualTo("feat: add login");
        assertThat(result.suggestedBody()).isEqualTo("- adds login");

        String prompt = publishPromptText();
        assertThat(prompt).contains("Repository: my-repo");
        assertThat(prompt).contains("Base branch: main");
        assertThat(prompt).contains("suitable git branch name representing the summarized changes");
    }

    @Test
    void getPublishCapabilities_llmBranchNameBlank_fallsBackToMechanicalBranch() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        stubObserveWithChanges();

        stubPublishSummary("{\"title\": \"feat: blank branch\", \"body\": \"- b\", \"branchName\": \"   \"}");

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        String mechanicalBranch = "kratis/" + executionId.toString().substring(0, 8) + "-1";
        assertThat(result.target().branchName()).isEqualTo(mechanicalBranch);
        assertThat(result.suggestedTitle()).isEqualTo("feat: blank branch");
    }

    @Test
    void getPublishCapabilities_llmBranchNameEqualsBase_fallsBackToMechanicalBranch() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        stubObserveWithChanges();

        stubPublishSummary("{\"title\": \"feat: base\", \"body\": \"- b\", \"branchName\": \"main\"}");

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        String mechanicalBranch = "kratis/" + executionId.toString().substring(0, 8) + "-1";
        assertThat(result.target().branchName()).isEqualTo(mechanicalBranch);
    }

    @Test
    void getPublishCapabilities_llmBranchNameInvalidAfterSanitize_fallsBackToMechanicalBranch() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        stubObserveWithChanges();

        // "feat.lock." sanitizes to "feat.lock" (trailing dots are trimmed after the .lock
        // replacement), which still fails isValidGitRefName.
        stubPublishSummary("{\"title\": \"feat: locky\", \"body\": \"- b\", \"branchName\": \"feat.lock.\"}");

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        String mechanicalBranch = "kratis/" + executionId.toString().substring(0, 8) + "-1";
        assertThat(result.target().branchName()).isEqualTo(mechanicalBranch);
    }

    @Test
    void getPublishCapabilities_llmBranchNameWrongJsonType_fallsBackToMechanicalBranch() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        stubObserveWithChanges();

        stubPublishSummary("{\"title\": \"feat: numeric\", \"body\": \"- b\", \"branchName\": 42}");

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        String mechanicalBranch = "kratis/" + executionId.toString().substring(0, 8) + "-1";
        assertThat(result.target().branchName()).isEqualTo(mechanicalBranch);
        assertThat(result.suggestedTitle()).isEqualTo("feat: numeric");
    }

    @Test
    void getPublishCapabilities_llmBranchNameMissing_keepsSuggestionsAndFallsBack() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        stubObserveWithChanges();

        stubPublishSummary("{\"title\": \"feat: no branch field\", \"body\": \"- b\"}");

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        String mechanicalBranch = "kratis/" + executionId.toString().substring(0, 8) + "-1";
        assertThat(result.target().branchName()).isEqualTo(mechanicalBranch);
        assertThat(result.suggestedTitle()).isEqualTo("feat: no branch field");
        assertThat(result.suggestedBody()).isEqualTo("- b");
    }

    @Test
    void getPublishCapabilities_digestStableAcrossDifferentLlmSuggestions() throws Exception {
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        stubObserveWithChanges();

        stubPublishSummary("{\"title\": \"feat: first take\", \"body\": \"- b\", \"branchName\": \"feat/one\"}");
        PublishCapabilitiesDto first = service.getPublishCapabilities(userId, chatId, executionId);
        assertThat(first.target().branchName()).isEqualTo("feat/one");

        fakeChatModel.reset();
        stubPublishSummary("{\"title\": \"fix: second take\", \"body\": \"- c\", \"branchName\": \"feat/two\"}");
        PublishCapabilitiesDto second = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(second.target().branchName()).isEqualTo("feat/two");
        assertThat(second.suggestedTitle()).isEqualTo("fix: second take");
        assertThat(second.digest()).isEqualTo(first.digest());
    }

    @Test
    void getPublishCapabilities_updatePr_keepsLiveHeadBranchOverLlmSuggestion() throws Exception {
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId))
                .thenReturn(List.of(new ChangeSet(
                        execution,
                        1,
                        "base",
                        "push",
                        "existing-branch",
                        77L,
                        "https://github.com/test/repo/pull/77",
                        "main",
                        Instant.now())));
        stubExecutionLookup();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);
        when(repoProvider.getPullRequest(eq(repository), any(), eq(77L)))
                .thenReturn(new PullRequestSnapshot(
                        77L,
                        PullRequestSnapshot.PullRequestState.OPEN,
                        "https://github.com/test/repo/pull/77",
                        "existing-branch",
                        "main",
                        "sha777",
                        null));

        stubObserveWithChanges();

        stubPublishSummary("{\"title\": \"feat: live pr\", \"body\": \"- b\", \"branchName\": \"feat/from-llm\"}");

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.target().kind()).isEqualTo(PublishCapabilitiesDto.TargetDto.TargetKind.UPDATE_PR);
        assertThat(result.target().branchName()).isEqualTo("existing-branch");
        assertThat(result.suggestedTitle()).isEqualTo("feat: live pr");
    }

    @Test
    void getPublishCapabilities_closedPr_keepsPrHeadBranchOverLlmSuggestion() throws Exception {
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId))
                .thenReturn(List.of(new ChangeSet(
                        execution,
                        1,
                        "base",
                        "push",
                        "kratis/feature-1",
                        77L,
                        "https://github.com/test/repo/pull/77",
                        "main",
                        Instant.now())));
        stubExecutionLookup();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);
        when(repoProvider.getPullRequest(eq(repository), any(), eq(77L)))
                .thenReturn(new PullRequestSnapshot(
                        77L,
                        PullRequestSnapshot.PullRequestState.CLOSED,
                        "https://github.com/test/repo/pull/77",
                        "kratis/feature-1",
                        "main",
                        "sha777",
                        null));

        stubObserveWithChanges();

        stubPublishSummary("{\"title\": \"feat: closed pr\", \"body\": \"- b\", \"branchName\": \"feat/from-llm\"}");

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(result.target().kind()).isEqualTo(PublishCapabilitiesDto.TargetDto.TargetKind.OPEN_PR);
        assertThat(result.target().branchName()).isEqualTo("kratis/feature-1");
    }

    @Test
    void getPublishCapabilities_newRepoExecution_promptNamesTheNewRepository() throws Exception {
        execution.setRepository(null);
        execution.setNewRepoName("fresh-repo");
        execution.setNewRepoCredential(githubNewRepoCredential());
        stubExecutionLookup();

        RepoProvider provider = newRepoProvider(true, true);
        when(providerRegistry.getProvider(RepositoryType.GITHUB)).thenReturn(provider);

        stubObserveWithChanges();

        PublishCapabilitiesDto result = service.getPublishCapabilities(userId, chatId, executionId);

        assertThat(publishPromptText()).contains("Repository: fresh-repo");
        assertThat(publishPromptText()).contains("Base branch: main");
        String mechanicalBranch = "kratis/" + executionId.toString().substring(0, 8) + "-1";
        assertThat(result.target().branchName()).isEqualTo(mechanicalBranch);
    }

    @Test
    void getPublishCapabilities_noRepository_promptSkipsRepositoryLine() throws Exception {
        execution.setRepository(null);
        stubExecutionLookup();
        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(false);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        stubObserveWithChanges();

        service.getPublishCapabilities(userId, chatId, executionId);

        String prompt = publishPromptText();
        assertThat(prompt).doesNotContain("Repository:");
        assertThat(prompt).contains("Base branch: main");
    }

    @Test
    void publishPullRequest_republishAfterMerge_createsNewPrAndAppendsHistory() throws Exception {
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId))
                .thenReturn(List.of(new ChangeSet(
                        execution,
                        1,
                        "base",
                        "push",
                        "existing-branch",
                        77L,
                        "https://github.com/test/repo/pull/77",
                        "main",
                        Instant.now())));
        stubExecutionLookup();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);
        GitAuthMaterial auth = GitAuthMaterial.ofToken("pat-token");
        when(credentialResolver.resolve(repository)).thenReturn(auth);
        when(repoProvider.createPullRequest(eq(repository), eq(auth), any(CreatePullRequestCommand.class)))
                .thenReturn(new PullRequestResultDto(
                        88L, "https://github.com/test/repo/pull/88", "kratis/exec-next-2", "main"));

        when(repoProvider.getPullRequest(eq(repository), any(), eq(77L)))
                .thenReturn(new PullRequestSnapshot(
                        77L,
                        PullRequestSnapshot.PullRequestState.MERGED,
                        "https://github.com/test/repo/pull/77",
                        "existing-branch",
                        "main",
                        "sha777",
                        null));

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                1,
                false,
                "sha777",
                new EnvironmentConnectorResult.GitChangeSetStats(5, 0, List.of(), "digest-merged-2"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        EnvironmentConnectorResult.GitPush pushResult = new EnvironmentConnectorResult.GitPush(
                "sha888",
                "kratis/exec-next-2",
                "refs/heads/kratis/exec-next-2",
                "success",
                "existing-branch",
                true,
                true);
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(pushResult);

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/exec-next-2",
                "main",
                "Follow-up",
                "Body",
                false,
                false,
                null,
                null,
                null,
                true,
                "kratis/exec-next-2");

        PullRequestResultDto result = service.publishPullRequest(userId, chatId, executionId, request);

        assertThat(result.prNumber()).isEqualTo(88L);
        verify(changeSetRepository).save(any(ChangeSet.class));
    }

    @Test
    void publishPullRequest_createNewPrOnOpenPr_derivesFreshBranchAndKeepsHistory() throws Exception {
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId))
                .thenReturn(List.of(new ChangeSet(
                        execution,
                        1,
                        "base",
                        "push",
                        "existing-branch",
                        77L,
                        "https://github.com/test/repo/pull/77",
                        "main",
                        Instant.now())));
        stubExecutionLookup();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);
        GitAuthMaterial auth = GitAuthMaterial.ofToken("pat-token");
        when(credentialResolver.resolve(repository)).thenReturn(auth);
        when(repoProvider.createPullRequest(eq(repository), eq(auth), any(CreatePullRequestCommand.class)))
                .thenReturn(new PullRequestResultDto(
                        99L, "https://github.com/test/repo/pull/99", "kratis/derived-2", "main"));

        when(repoProvider.getPullRequest(eq(repository), any(), eq(77L)))
                .thenReturn(new PullRequestSnapshot(
                        77L,
                        PullRequestSnapshot.PullRequestState.MERGED,
                        "https://github.com/test/repo/pull/77",
                        "existing-branch",
                        "main",
                        "sha777",
                        null));

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                1,
                false,
                "sha777",
                new EnvironmentConnectorResult.GitChangeSetStats(5, 0, List.of(), "digest-merged"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        EnvironmentConnectorResult.GitPush pushResult = new EnvironmentConnectorResult.GitPush(
                "sha999", "kratis/derived-2", "refs/heads/kratis/derived-2", "success", "existing-branch", true, false);
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(pushResult);

        PublishPrRequestDto request = new PublishPrRequestDto(
                "existing-branch", "main", "Second PR", "Body", false, false, null, null, null, true, null);

        PullRequestResultDto result = service.publishPullRequest(userId, chatId, executionId, request);

        assertThat(result.prNumber()).isEqualTo(99L);
        verify(changeSetRepository).save(any(ChangeSet.class));

        ArgumentCaptor<EnvironmentRpcPayload.GitPush> pushCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitPush.class);
        verify(environmentRpcClient).request(eq(envId), pushCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(pushCaptor.getValue().branchName()).startsWith("kratis/");
    }

    @Test
    void publishPullRequest_storedStatusClosed_liveStatusOpen_targetsExistingPr() throws Exception {
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId))
                .thenReturn(List.of(new ChangeSet(
                        execution,
                        1,
                        "base",
                        "push",
                        "canvas-repos",
                        383L,
                        "https://github.com/test/repo/pull/383",
                        "main",
                        Instant.now())));
        stubExecutionLookup();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);
        when(repoProvider.getPullRequest(eq(repository), any(), eq(383L)))
                .thenReturn(new PullRequestSnapshot(
                        383L,
                        PullRequestSnapshot.PullRequestState.OPEN,
                        "https://github.com/test/repo/pull/383",
                        "canvas-repos",
                        "main",
                        "head-sha",
                        null));

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                1,
                false,
                "head-sha",
                new EnvironmentConnectorResult.GitChangeSetStats(1, 0, List.of(), "digest-open"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        EnvironmentConnectorResult.GitPush pushResult = new EnvironmentConnectorResult.GitPush(
                "sha-commit", "canvas-repos", "refs/heads/canvas-repos", "success", "canvas-repos", false, false);
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(pushResult);

        PublishPrRequestDto request = new PublishPrRequestDto(
                "canvas-repos", "main", "Fix issue", "Body", false, false, null, null, null, false, null);

        PullRequestResultDto result = service.publishPullRequest(userId, chatId, executionId, request);

        assertThat(result.prNumber()).isEqualTo(383L);
        verify(repoProvider, never()).createPullRequest(any(), any(), any());
    }

    @Test
    void publishPullRequest_recordsSinglePublishActivityAndPublishesEvent() throws Exception {
        stubExecutionLookup();
        stubWorkspaceObserve();
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId)).thenReturn(List.of());

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

        String expectedActionId = "pub:cs:" + executionId + ":1";
        ArgumentCaptor<ActivityDetail> detailCaptor = ArgumentCaptor.forClass(ActivityDetail.class);
        verify(activityPersistenceService)
                .recordActivity(
                        eq(executionId),
                        eq(ActivityType.PUBLISH),
                        eq("Published changes: Pull Request #101 created to merge pr-branch into main"),
                        eq(expectedActionId),
                        eq(ActivityStatus.COMPLETED),
                        detailCaptor.capture());

        ActivityDetail detail = detailCaptor.getValue();
        assertThat(detail.prNumber()).isEqualTo(101L);
        assertThat(detail.prUrl()).isEqualTo("https://github.com/test/repo/pull/101");
        assertThat(detail.headBranch()).isEqualTo("pr-branch");
        assertThat(detail.baseBranch()).isEqualTo("main");
        assertThat(detail.commitSha()).isEqualTo("sha999");
        assertThat(detail.isUpdate()).isFalse();

        ArgumentCaptor<SandboxExecutionActivityEvent> eventCaptor =
                ArgumentCaptor.forClass(SandboxExecutionActivityEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        SandboxExecutionActivityEvent event = eventCaptor.getValue();
        assertThat(event.teamId()).isEqualTo(team.getId());
        assertThat(event.executionId()).isEqualTo(executionId);
        assertThat(event.activityType()).isEqualTo(ActivityType.PUBLISH);
        assertThat(event.actionId()).isEqualTo(expectedActionId);
        assertThat(event.status()).isEqualTo(ActivityStatus.COMPLETED);
    }

    @Test
    void publishPullRequest_updatePr_recordsSinglePublishActivityWithUpdateDetail() throws Exception {
        stubExecutionLookup();
        stubWorkspaceObserve();
        when(executionDiffSnapshotRepository.findByExecutionId(executionId))
                .thenReturn(Optional.of(
                        new ExecutionDiffSnapshot(executionId, "base", "head", 1, 0, "[]", "diff-manifest-digest")));

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        ChangeSet existingChangeSet = new ChangeSet(
                execution,
                1,
                "base",
                "sha111",
                "pr-branch",
                202L,
                "https://github.com/test/repo/pull/202",
                "main",
                Instant.now());
        existingChangeSet.setManifestDigest("digest-empty");
        existingChangeSet.setPrStatus(PublishedPrStatus.OPEN);
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId)).thenReturn(List.of(existingChangeSet));

        when(repoProvider.getPullRequest(eq(repository), any(), eq(202L)))
                .thenReturn(new PullRequestSnapshot(
                        202L,
                        PullRequestSnapshot.PullRequestState.OPEN,
                        "https://github.com/test/repo/pull/202",
                        "pr-branch",
                        "main",
                        "sha111",
                        null));

        EnvironmentConnectorResult.GitPush pushResult =
                new EnvironmentConnectorResult.GitPush("sha222", "pr-branch", "refs/heads/pr-branch", "SUCCESS");
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(pushResult);

        PublishPrRequestDto request = new PublishPrRequestDto(
                "pr-branch", "main", "Updated PR Title", "Updated Body", false, false, null, null, null, false, null);
        PullRequestResultDto result = service.publishPullRequest(userId, chatId, executionId, request);

        assertThat(result.prNumber()).isEqualTo(202);

        ArgumentCaptor<ChangeSet> changeSetCaptor = ArgumentCaptor.forClass(ChangeSet.class);
        verify(changeSetRepository).save(changeSetCaptor.capture());
        assertThat(changeSetCaptor.getValue().getManifestDigest()).isEqualTo("diff-manifest-digest");

        String expectedActionId = "pub:cs:" + executionId + ":2";
        ArgumentCaptor<ActivityDetail> detailCaptor = ArgumentCaptor.forClass(ActivityDetail.class);
        verify(activityPersistenceService)
                .recordActivity(
                        eq(executionId),
                        eq(ActivityType.PUBLISH),
                        eq("Published changes: Updated Pull Request #202 on branch pr-branch"),
                        eq(expectedActionId),
                        eq(ActivityStatus.COMPLETED),
                        detailCaptor.capture());

        ActivityDetail detail = detailCaptor.getValue();
        assertThat(detail.prNumber()).isEqualTo(202L);
        assertThat(detail.isUpdate()).isTrue();
        assertThat(detail.commitSha()).isEqualTo("sha222");

        verify(eventPublisher).publishEvent(any(SandboxExecutionActivityEvent.class));
    }

    @Test
    void pushBranch_recordsSinglePublishActivityAndPublishesEvent() throws Exception {
        stubExecutionLookup();
        stubWorkspaceObserve();
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId)).thenReturn(List.of());

        EnvironmentConnectorResult.GitPush pushResult = new EnvironmentConnectorResult.GitPush(
                "sha1234567890", "feature-branch", "refs/heads/feature-branch", "SUCCESS");

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(pushResult);

        PushBranchRequestDto request = new PushBranchRequestDto("feature-branch", "commit msg", true);
        PushBranchResponseDto response = service.pushBranch(userId, chatId, executionId, request);

        assertThat(response.branchName()).isEqualTo("feature-branch");

        String expectedActionId = "pub:cs:" + executionId + ":1";
        ArgumentCaptor<ActivityDetail> detailCaptor = ArgumentCaptor.forClass(ActivityDetail.class);
        verify(activityPersistenceService)
                .recordActivity(
                        eq(executionId),
                        eq(ActivityType.PUBLISH),
                        eq("Published changes to branch feature-branch (sha1234)"),
                        eq(expectedActionId),
                        eq(ActivityStatus.COMPLETED),
                        detailCaptor.capture());

        ActivityDetail detail = detailCaptor.getValue();
        assertThat(detail.headBranch()).isEqualTo("feature-branch");
        assertThat(detail.commitSha()).isEqualTo("sha1234567890");

        ArgumentCaptor<SandboxExecutionActivityEvent> eventCaptor =
                ArgumentCaptor.forClass(SandboxExecutionActivityEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        SandboxExecutionActivityEvent event = eventCaptor.getValue();
        assertThat(event.actionId()).isEqualTo(expectedActionId);
        assertThat(event.activityType()).isEqualTo(ActivityType.PUBLISH);
    }

    @Test
    void publishPullRequest_gitPushFails_doesNotRecordPublishActivity() throws Exception {
        stubExecutionLookup();
        stubWorkspaceObserve();

        RepoProvider repoProvider = mock(RepoProvider.class);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(new RuntimeException("Git push network failure"));

        PublishPrRequestDto request = new PublishPrRequestDto("pr-branch", "main", "PR Title", "PR Body", false, true);

        assertThatThrownBy(() -> service.publishPullRequest(userId, chatId, executionId, request))
                .isInstanceOf(RuntimeException.class);

        verify(activityPersistenceService, never())
                .recordActivity(any(), eq(ActivityType.PUBLISH), any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(SandboxExecutionActivityEvent.class));
    }

    @Test
    void publishPullRequest_concurrentDuplicatePublishUniqueViolation_fallsBackToMerge() throws Exception {
        stubExecutionLookup();
        stubWorkspaceObserve();

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

        String expectedActionId = "pub:cs:" + executionId + ":1";
        when(activityPersistenceService.recordActivity(
                        eq(executionId), eq(ActivityType.PUBLISH), any(), eq(expectedActionId), any(), any()))
                .thenThrow(new DataIntegrityViolationException("Unique constraint violation"));

        SandboxExecutionActivity existingActivity = new SandboxExecutionActivity(
                executionId, 1L, expectedActionId, ActivityType.PUBLISH, ActivityStatus.PENDING, "Old", null);
        when(activityRepository.findByExecutionIdAndActionId(executionId, expectedActionId))
                .thenReturn(Optional.of(existingActivity));
        when(activityRepository.save(any(SandboxExecutionActivity.class))).thenAnswer(inv -> inv.getArgument(0));

        PublishPrRequestDto request = new PublishPrRequestDto("pr-branch", "main", "PR Title", "PR Body", false, true);
        PullRequestResultDto result = service.publishPullRequest(userId, chatId, executionId, request);

        assertThat(result.prNumber()).isEqualTo(101L);
        verify(changeSetRepository).save(any(ChangeSet.class));
        verify(activityRepository).save(existingActivity);
        assertThat(existingActivity.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        verify(eventPublisher).publishEvent(any(SandboxExecutionActivityEvent.class));
    }

    @Test
    void publishPullRequest_commitTimeUniqueViolation_fallsBackToMerge() throws Exception {
        stubExecutionLookup();
        stubWorkspaceObserve();

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

        String expectedActionId = "pub:cs:" + executionId + ":1";
        when(activityPersistenceService.recordActivity(
                        eq(executionId), eq(ActivityType.PUBLISH), any(), eq(expectedActionId), any(), any()))
                .thenReturn(new SandboxExecutionActivity(
                        executionId,
                        1L,
                        expectedActionId,
                        ActivityType.PUBLISH,
                        ActivityStatus.COMPLETED,
                        "New",
                        null));

        // Simulate commit-time flush unique collision on the initial REQUIRES_NEW transaction;
        // the fallback merge REQUIRES_NEW transaction commit succeeds.
        doThrow(new DataIntegrityViolationException("Unique constraint violation"))
                .doNothing()
                .when(transactionManager)
                .commit(any());

        SandboxExecutionActivity existingActivity = new SandboxExecutionActivity(
                executionId, 1L, expectedActionId, ActivityType.PUBLISH, ActivityStatus.PENDING, "Old", null);
        when(activityRepository.findByExecutionIdAndActionId(executionId, expectedActionId))
                .thenReturn(Optional.of(existingActivity));
        when(activityRepository.save(any(SandboxExecutionActivity.class))).thenAnswer(inv -> inv.getArgument(0));

        PublishPrRequestDto request = new PublishPrRequestDto("pr-branch", "main", "PR Title", "PR Body", false, true);
        PullRequestResultDto result = service.publishPullRequest(userId, chatId, executionId, request);

        assertThat(result.prNumber()).isEqualTo(101L);
        verify(changeSetRepository).save(any(ChangeSet.class));
        verify(activityRepository).save(existingActivity);
        assertThat(existingActivity.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        verify(eventPublisher).publishEvent(any(SandboxExecutionActivityEvent.class));
    }

    @Test
    void pushBranch_commitTimeUniqueViolation_fallsBackToMerge() throws Exception {
        stubExecutionLookup();
        stubWorkspaceObserve();
        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId)).thenReturn(List.of());

        EnvironmentConnectorResult.GitPush pushResult = new EnvironmentConnectorResult.GitPush(
                "sha1234567890", "feature-branch", "refs/heads/feature-branch", "SUCCESS");

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(pushResult);

        String expectedActionId = "pub:cs:" + executionId + ":1";

        when(activityPersistenceService.recordActivity(
                        eq(executionId), eq(ActivityType.PUBLISH), any(), eq(expectedActionId), any(), any()))
                .thenReturn(new SandboxExecutionActivity(
                        executionId,
                        1L,
                        expectedActionId,
                        ActivityType.PUBLISH,
                        ActivityStatus.COMPLETED,
                        "New",
                        null));

        // Simulate commit-time flush unique collision on the initial REQUIRES_NEW transaction
        doThrow(new DataIntegrityViolationException("Unique constraint violation"))
                .doNothing()
                .when(transactionManager)
                .commit(any());

        SandboxExecutionActivity existingActivity = new SandboxExecutionActivity(
                executionId, 1L, expectedActionId, ActivityType.PUBLISH, ActivityStatus.PENDING, "Old", null);
        when(activityRepository.findByExecutionIdAndActionId(executionId, expectedActionId))
                .thenReturn(Optional.of(existingActivity));
        when(activityRepository.save(any(SandboxExecutionActivity.class))).thenAnswer(inv -> inv.getArgument(0));

        PushBranchRequestDto request = new PushBranchRequestDto("feature-branch", "commit msg", true);
        PushBranchResponseDto response = service.pushBranch(userId, chatId, executionId, request);

        assertThat(response.branchName()).isEqualTo("feature-branch");
        verify(changeSetRepository).save(any(ChangeSet.class));
        verify(activityRepository).save(existingActivity);
        assertThat(existingActivity.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        verify(eventPublisher).publishEvent(any(SandboxExecutionActivityEvent.class));
    }
}
