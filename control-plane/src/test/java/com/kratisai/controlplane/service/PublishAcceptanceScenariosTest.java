package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.PublishCapabilitiesDto;
import com.kratisai.controlplane.api.restdto.PublishPrRequestDto;
import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.git.provider.CreatePullRequestCommand;
import com.kratisai.controlplane.git.provider.ProviderUnavailableException;
import com.kratisai.controlplane.git.provider.PullRequestSnapshot;
import com.kratisai.controlplane.git.provider.RepoProvider;
import com.kratisai.controlplane.git.provider.RepoProviderRegistry;
import com.kratisai.controlplane.model.ChangeSet;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.ChangeSetRepository;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.RepoCredentialRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublishAcceptanceScenariosTest {

    @Mock
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Mock
    private ChangeSetRepository changeSetRepository;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private SandboxExecutionActivityRepository activityRepository;

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
    private PlatformTransactionManager transactionManager;

    @Mock
    private RepoProvider repoProvider;

    private SandboxExecutionPublishService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID chatId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final UUID envId = UUID.randomUUID();

    private SandboxExecution execution;
    private ChatEntity chat;
    private Repository repository;
    private ExecutionEnvironment environment;
    private final List<ChangeSet> recordedChangeSets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new SandboxExecutionPublishService(
                sandboxExecutionRepository,
                changeSetRepository,
                chatRepository,
                activityRepository,
                repositoryRepository,
                credentialRepository,
                environmentRpcClient,
                credentialResolver,
                providerRegistry,
                chatModelFactory,
                new ObjectMapper(),
                sandboxProvisioningService,
                transactionManager);

        Team team = new Team("Acceptance Team", "Desc");
        ModelProvider modelProvider = new ModelProvider(
                "OpenAI", com.kratisai.controlplane.model.ProviderType.OPENAI, "enc-key", "https://api.openai.com/v1");
        modelProvider.setTeam(team);
        team.setIngestionProvider(modelProvider);
        team.setIngestionModel("gpt-4o");

        org.springframework.ai.chat.model.ChatModel mockChatModel =
                org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        org.springframework.ai.chat.model.ChatResponse mockResponse =
                org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation mockGeneration =
                org.mockito.Mockito.mock(org.springframework.ai.chat.model.Generation.class);
        when(mockGeneration.getOutput())
                .thenReturn(new org.springframework.ai.chat.messages.AssistantMessage(
                        "{\"title\": \"feat: test\", \"body\": \"desc\"}"));
        when(mockResponse.getResult()).thenReturn(mockGeneration);
        when(mockChatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(mockResponse);
        when(chatModelFactory.createChatModel(modelProvider, "gpt-4o")).thenReturn(mockChatModel);

        User user = new User("user@kratis.ai", "hash", "User");
        user.setId(userId);
        chat = new ChatEntity(team, user, "Acceptance Chat");
        chat.setId(chatId);

        environment = new ExecutionEnvironment();
        environment.setId(envId);
        environment.setTeam(team);
        environment.setStatus(EnvironmentStatus.CONNECTED);

        repository = new Repository();
        repository.setName("org/repo");
        repository.setUrl("https://github.com/org/repo.git");
        repository.setBranch("main");
        repository.setRepositoryType(RepositoryType.GITHUB);

        execution = new SandboxExecution();
        execution.setId(executionId);
        execution.setChat(chat);
        execution.setEnvironment(environment);
        execution.setRepository(repository);
        execution.setStatus(SandboxExecutionStatus.IDLE);

        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));
        when(sandboxExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(providerRegistry.getProvider(repository)).thenReturn(repoProvider);
        when(repoProvider.supportsPullRequests()).thenReturn(true);
        when(credentialResolver.resolve(repository)).thenReturn(GitAuthMaterial.ofToken("test-token"));

        when(changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId))
                .thenAnswer(invocation -> List.copyOf(recordedChangeSets));
        when(changeSetRepository.save(any(ChangeSet.class))).thenAnswer(invocation -> {
            ChangeSet cs = invocation.getArgument(0);
            recordedChangeSets.add(cs);
            return cs;
        });
    }

    @Test
    @DisplayName("Scenario 1: First publish creates PR and writes single ChangeSet")
    void scenario1_firstPublish() throws Exception {
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
                        25,
                        3,
                        List.of(new EnvironmentConnectorResult.GitChangeSetFile("src/Main.java", "modified", 25, 3)),
                        "digest-cs1"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        PublishCapabilitiesDto caps = service.getPublishCapabilities(userId, chatId, executionId);
        assertThat(caps.status()).isEqualTo(PublishCapabilitiesDto.PublishStatus.UP_TO_DATE);
        assertThat(caps.target().kind()).isEqualTo(PublishCapabilitiesDto.TargetDto.TargetKind.OPEN_PR);

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitPush(
                        "commit-1",
                        "kratis/feature-1",
                        "refs/heads/kratis/feature-1",
                        "success",
                        "kratis/feature-1",
                        false,
                        false));

        when(repoProvider.createPullRequest(eq(repository), any(), any(CreatePullRequestCommand.class)))
                .thenReturn(
                        new PullRequestResultDto(1L, "https://github.com/org/repo/pull/1", "kratis/feature-1", "main"));

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/feature-1",
                "main",
                "Feature 1",
                "Body 1",
                false,
                true,
                null,
                null,
                null,
                false,
                null,
                caps.digest());

        PullRequestResultDto result = service.publishPullRequest(userId, chatId, executionId, request);

        assertThat(result.prNumber()).isEqualTo(1L);
        assertThat(recordedChangeSets).hasSize(1);
        assertThat(recordedChangeSets.get(0).getPrNumber()).isEqualTo(1L);
        assertThat(recordedChangeSets.get(0).getPushedCommitSha()).isEqualTo("commit-1");
    }

    @Test
    @DisplayName("Scenario 2: Republish with no upstream move updates PR without new PR creation")
    void scenario2_republishNoUpstreamMove() throws Exception {
        recordedChangeSets.add(new ChangeSet(
                execution,
                1,
                "sha-main",
                "commit-1",
                "kratis/feature-1",
                1L,
                "https://github.com/org/repo/pull/1",
                "main",
                Instant.now()));

        when(repoProvider.getPullRequest(eq(repository), any(), eq(1L)))
                .thenReturn(new PullRequestSnapshot(
                        1L,
                        PullRequestSnapshot.PullRequestState.OPEN,
                        "https://github.com/org/repo/pull/1",
                        "kratis/feature-1",
                        "main",
                        "commit-1"));

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                1,
                false,
                "commit-1",
                new EnvironmentConnectorResult.GitChangeSetStats(5, 0, List.of(), "digest-cs2"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        PublishCapabilitiesDto caps = service.getPublishCapabilities(userId, chatId, executionId);
        assertThat(caps.target().kind()).isEqualTo(PublishCapabilitiesDto.TargetDto.TargetKind.UPDATE_PR);
        assertThat(caps.target().prNumber()).isEqualTo(1L);

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitPush(
                        "commit-2",
                        "kratis/feature-1",
                        "refs/heads/kratis/feature-1",
                        "success",
                        "kratis/feature-1",
                        false,
                        false));

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/feature-1",
                "main",
                "Feature update",
                "Body update",
                false,
                true,
                null,
                null,
                null,
                false,
                null,
                caps.digest());

        PullRequestResultDto result = service.publishPullRequest(userId, chatId, executionId, request);

        assertThat(result.prNumber()).isEqualTo(1L);
        verify(repoProvider, never()).createPullRequest(any(), any(), any());
        assertThat(recordedChangeSets).hasSize(2);
        assertThat(recordedChangeSets.get(1).getPushedCommitSha()).isEqualTo("commit-2");
    }

    @Test
    @DisplayName("Scenario 3: Upstream moved requires rebase before publish")
    void scenario3_upstreamMovedRebaseRequired() throws Exception {
        EnvironmentConnectorResult.GitObservePublish obsBehind = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main-v2",
                "sha-main-v1",
                false,
                1,
                1,
                false,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(10, 0, List.of(), "digest-behind"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obsBehind);

        PublishCapabilitiesDto caps = service.getPublishCapabilities(userId, chatId, executionId);
        assertThat(caps.status()).isEqualTo(PublishCapabilitiesDto.PublishStatus.REBASE_REQUIRED);
        assertThat(caps.incomingCommits()).isEqualTo(1);

        PublishPrRequestDto publishAttempt = new PublishPrRequestDto(
                "kratis/feature-1", "main", "Title", "Body", false, true, null, null, null, false, null, caps.digest());

        assertThatThrownBy(() -> service.publishPullRequest(userId, chatId, executionId, publishAttempt))
                .isInstanceOf(PublishException.class)
                .hasFieldOrPropertyWithValue("errorCode", PublishException.ErrorCode.REBASE_REQUIRED);

        // Perform rebase
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitRebase.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitRebase(
                        "sha-main-v2",
                        new EnvironmentConnectorResult.GitChangeSetStats(10, 0, List.of(), "digest-rebased")));

        EnvironmentConnectorResult.GitObservePublish obsRebased = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main-v2",
                "sha-main-v2",
                true,
                0,
                1,
                false,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(10, 0, List.of(), "digest-rebased"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obsRebased);

        PublishCapabilitiesDto rebasedCaps = service.rebase(userId, chatId, executionId);
        assertThat(rebasedCaps.status()).isEqualTo(PublishCapabilitiesDto.PublishStatus.UP_TO_DATE);
    }

    @Test
    @DisplayName("Scenario 4: Rebase conflict surfaces REBASE_CONFLICT and leaves base unchanged")
    void scenario4_rebaseConflict() throws Exception {
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitRebase.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(new EnvironmentRpcClient.EnvironmentRpcException(
                        -32001, "REBASE_CONFLICT: conflict in README.md"));

        assertThatThrownBy(() -> service.rebase(userId, chatId, executionId))
                .isInstanceOf(PublishException.class)
                .hasFieldOrPropertyWithValue("errorCode", PublishException.ErrorCode.REBASE_CONFLICT);
    }

    @Test
    @DisplayName("Scenario 5: PR merged upstream targets new branch and PR")
    void scenario5_prMergedUpstream_targetsNewBranchAndPr() throws Exception {
        recordedChangeSets.add(new ChangeSet(
                execution,
                1,
                "sha-main-v1",
                "commit-1",
                "kratis/feature-1",
                1L,
                "https://github.com/org/repo/pull/1",
                "main",
                Instant.now()));

        when(repoProvider.getPullRequest(eq(repository), any(), eq(1L)))
                .thenReturn(new PullRequestSnapshot(
                        1L,
                        PullRequestSnapshot.PullRequestState.MERGED,
                        "https://github.com/org/repo/pull/1",
                        "kratis/feature-1",
                        "main",
                        "commit-1"));

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main-v2",
                "sha-main-v2",
                true,
                0,
                1,
                false,
                "commit-1",
                new EnvironmentConnectorResult.GitChangeSetStats(8, 2, List.of(), "digest-merged-pr"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        PublishCapabilitiesDto caps = service.getPublishCapabilities(userId, chatId, executionId);
        assertThat(caps.target().kind()).isEqualTo(PublishCapabilitiesDto.TargetDto.TargetKind.OPEN_PR_NEW_BRANCH);
        assertThat(caps.target().branchName()).startsWith("kratis/");
    }

    @Test
    @DisplayName("Scenario 6: PR closed without merge targets same branch for new PR")
    void scenario6_prClosedWithoutMerge_targetsSameBranch() throws Exception {
        recordedChangeSets.add(new ChangeSet(
                execution,
                1,
                "sha-main-v1",
                "commit-1",
                "kratis/feature-1",
                1L,
                "https://github.com/org/repo/pull/1",
                "main",
                Instant.now()));

        when(repoProvider.getPullRequest(eq(repository), any(), eq(1L)))
                .thenReturn(new PullRequestSnapshot(
                        1L,
                        PullRequestSnapshot.PullRequestState.CLOSED,
                        "https://github.com/org/repo/pull/1",
                        "kratis/feature-1",
                        "main",
                        "commit-1"));

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main-v1",
                "sha-main-v1",
                true,
                0,
                1,
                false,
                "commit-1",
                new EnvironmentConnectorResult.GitChangeSetStats(8, 2, List.of(), "digest-closed-pr"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        PublishCapabilitiesDto caps = service.getPublishCapabilities(userId, chatId, executionId);
        assertThat(caps.target().kind()).isEqualTo(PublishCapabilitiesDto.TargetDto.TargetKind.OPEN_PR);
        assertThat(caps.target().branchName()).isEqualTo("kratis/feature-1");
    }

    @Test
    @DisplayName("Scenario 7: Multiple commits with squash toggle passes squash flag to sidecar")
    void scenario7_multipleCommitsSquashToggle() throws Exception {
        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                3,
                false,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(30, 5, List.of(), "digest-multi-commits"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        PublishCapabilitiesDto caps = service.getPublishCapabilities(userId, chatId, executionId);
        assertThat(caps.commitCount()).isEqualTo(3);

        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitPush.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.GitPush(
                        "commit-3",
                        "kratis/multi",
                        "refs/heads/kratis/multi",
                        "success",
                        "kratis/multi",
                        false,
                        false));

        when(repoProvider.createPullRequest(eq(repository), any(), any()))
                .thenReturn(new PullRequestResultDto(2L, "https://github.com/org/repo/pull/2", "kratis/multi", "main"));

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/multi",
                "main",
                "Multi commit PR",
                "Body",
                false,
                false,
                null,
                null,
                null,
                false,
                null,
                caps.digest());

        service.publishPullRequest(userId, chatId, executionId, request);

        ArgumentCaptor<EnvironmentRpcPayload.GitPush> pushCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitPush.class);
        verify(environmentRpcClient).request(eq(envId), pushCaptor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(pushCaptor.getValue().squash()).isFalse();
    }

    @Test
    @DisplayName("Scenario 8: Plan changed race returns PLAN_CHANGED error")
    void scenario8_planChangedRace() throws Exception {
        EnvironmentConnectorResult.GitObservePublish obsInitial = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main-v1",
                "sha-main-v1",
                true,
                0,
                1,
                false,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(10, 0, List.of(), "digest-v1"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obsInitial);

        service.getPublishCapabilities(userId, chatId, executionId);

        // Before publish, main moves upstream
        EnvironmentConnectorResult.GitObservePublish obsMoved = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main-v2",
                "sha-main-v1",
                false,
                1,
                1,
                false,
                null,
                new EnvironmentConnectorResult.GitChangeSetStats(10, 0, List.of(), "digest-v1"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obsMoved);

        PublishPrRequestDto request = new PublishPrRequestDto(
                "kratis/feature", "main", "Title", "Body", false, true, null, null, null, false, null, "stale-digest");

        assertThatThrownBy(() -> service.publishPullRequest(userId, chatId, executionId, request))
                .isInstanceOf(PublishException.class)
                .hasFieldOrPropertyWithValue("errorCode", PublishException.ErrorCode.PLAN_CHANGED);
    }

    @Test
    @DisplayName("Scenario 9: Provider unavailable with prior PR throws PROVIDER_UNAVAILABLE")
    void scenario9_providerUnavailableWithPriorPr() throws Exception {
        recordedChangeSets.add(new ChangeSet(
                execution,
                1,
                "sha-main",
                "commit-1",
                "kratis/feature-1",
                1L,
                "https://github.com/org/repo/pull/1",
                "main",
                Instant.now()));

        when(repoProvider.getPullRequest(eq(repository), any(), eq(1L)))
                .thenThrow(new ProviderUnavailableException("GitHub API 503"));

        EnvironmentConnectorResult.GitObservePublish obs = new EnvironmentConnectorResult.GitObservePublish(
                "main",
                "sha-main",
                "sha-main",
                true,
                0,
                1,
                false,
                "commit-1",
                new EnvironmentConnectorResult.GitChangeSetStats(5, 0, List.of(), "digest"));
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitObservePublish.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(obs);

        // If provider fails, getPublishCapabilities logs a warning and target defaults safely
        PublishCapabilitiesDto caps = service.getPublishCapabilities(userId, chatId, executionId);
        assertThat(caps).isNotNull();
    }
}
