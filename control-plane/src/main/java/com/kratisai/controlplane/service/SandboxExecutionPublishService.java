package com.kratisai.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.agentloop.ChatResponseAssembler;
import com.kratisai.controlplane.api.restdto.PublishCapabilitiesDto;
import com.kratisai.controlplane.api.restdto.PublishPrRequestDto;
import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.restdto.PushBranchRequestDto;
import com.kratisai.controlplane.api.restdto.PushBranchResponseDto;
import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
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
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.RepositoryVisibility;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.ChangeSetRepository;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.RepoCredentialRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SandboxExecutionPublishService {

    private static final Logger logger = LoggerFactory.getLogger(SandboxExecutionPublishService.class);
    private static final long GIT_PUSH_TIMEOUT_SECONDS = 60;
    private static final long GIT_DIFF_TIMEOUT_SECONDS = 15;

    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final ChangeSetRepository changeSetRepository;
    private final ChatRepository chatRepository;
    private final SandboxExecutionActivityRepository activityRepository;
    private final RepositoryRepository repositoryRepository;
    private final RepoCredentialRepository credentialRepository;
    private final EnvironmentRpcClient environmentRpcClient;
    private final GitCredentialResolver credentialResolver;
    private final RepoProviderRegistry providerRegistry;
    private final ChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final SandboxProvisioningService sandboxProvisioningService;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public SandboxExecutionPublishService(
            SandboxExecutionRepository sandboxExecutionRepository,
            ChangeSetRepository changeSetRepository,
            ChatRepository chatRepository,
            SandboxExecutionActivityRepository activityRepository,
            RepositoryRepository repositoryRepository,
            RepoCredentialRepository credentialRepository,
            EnvironmentRpcClient environmentRpcClient,
            GitCredentialResolver credentialResolver,
            RepoProviderRegistry providerRegistry,
            ChatModelFactory chatModelFactory,
            ObjectMapper objectMapper,
            SandboxProvisioningService sandboxProvisioningService,
            PlatformTransactionManager transactionManager) {
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.changeSetRepository = changeSetRepository;
        this.chatRepository = chatRepository;
        this.activityRepository = activityRepository;
        this.repositoryRepository = repositoryRepository;
        this.credentialRepository = credentialRepository;
        this.environmentRpcClient = environmentRpcClient;
        this.credentialResolver = credentialResolver;
        this.providerRegistry = providerRegistry;
        this.chatModelFactory = chatModelFactory;
        this.objectMapper = objectMapper;
        this.sandboxProvisioningService = sandboxProvisioningService;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public PublishCapabilitiesDto getPublishCapabilities(UUID userId, UUID chatId, UUID executionId) {
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        return buildPublishCapabilities(execution);
    }

    private PublishCapabilitiesDto buildPublishCapabilities(SandboxExecution execution) {
        UUID executionId = execution.getId();
        Repository repo = resolveRepository(execution);
        boolean newRepo = repo == null && execution.getNewRepoName() != null;

        boolean supportsPr = false;
        RepositoryType repoType = RepositoryType.GENERIC;
        String defaultBranch = resolveTargetBranch(execution);
        boolean canCreateRepository = false;
        List<RepositoryVisibility> visibilityOptions = List.of();

        if (repo != null) {
            RepoProvider provider = providerRegistry.getProvider(repo);
            supportsPr = provider.supportsPullRequests();
            repoType = repo.getRepositoryType();
        } else if (newRepo) {
            RepositoryType type = resolveNewRepoType(execution);
            if (type != null) {
                repoType = type;
                RepoProvider provider = providerRegistry.getProvider(type);
                canCreateRepository = provider.supportsRepositoryCreation();
                supportsPr = canCreateRepository && provider.supportsPullRequests();
                visibilityOptions = visibilityOptionsFor(type);
            } else {
                for (RepoProvider provider : providerRegistry.allProviders()) {
                    canCreateRepository |= provider.supportsRepositoryCreation();
                    supportsPr |= provider.supportsRepositoryCreation() && provider.supportsPullRequests();
                    if (canCreateRepository && supportsPr) {
                        break;
                    }
                }
            }
        }

        List<ChangeSet> changeSets = changeSetRepository.findByExecutionIdOrderBySeqAsc(execution.getId());
        ChangeSet latestChangeSet = changeSets.stream()
                .filter(cs -> cs.getPrNumber() != null)
                .reduce((first, second) -> second)
                .orElse(null);
        ChangeSet latestBranchChangeSet = changeSets.isEmpty() ? null : changeSets.getLast();

        ExecutionEnvironment env = execution.getEnvironment();
        if (env == null || env.getStatus() != EnvironmentStatus.CONNECTED) {
            return zeroPublishCapabilities(
                    execution,
                    newRepo,
                    supportsPr,
                    repoType,
                    defaultBranch,
                    canCreateRepository,
                    visibilityOptions,
                    changeSets);
        }

        String targetHeadBranch = execution.getPublishedBranch();
        if (latestChangeSet != null) {
            targetHeadBranch = latestChangeSet.getBranchName();
        } else if (latestBranchChangeSet != null) {
            targetHeadBranch = latestBranchChangeSet.getBranchName();
        }
        EnvironmentConnectorResult.GitObservePublish obsResult;
        try {
            obsResult = environmentRpcClient.request(
                    env.getId(),
                    new EnvironmentRpcPayload.GitObservePublish(executionId.toString(), targetHeadBranch),
                    GIT_DIFF_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Failed to observe git state for execution {}: {}", executionId, e.getMessage());
            obsResult = null;
        }

        if (obsResult == null) {
            return zeroPublishCapabilities(
                    execution,
                    newRepo,
                    supportsPr,
                    repoType,
                    defaultBranch,
                    canCreateRepository,
                    visibilityOptions,
                    changeSets);
        }

        PullRequestSnapshot prSnapshot = null;
        if (latestChangeSet != null && supportsPr && repo != null) {
            RepoProvider provider = providerRegistry.getProvider(repo);
            try {
                GitAuthMaterial auth = credentialResolver.resolve(repo);
                prSnapshot = provider.getPullRequest(repo, auth, latestChangeSet.getPrNumber());
            } catch (Exception e) {
                logger.warn(
                        "Failed to fetch live PR snapshot for PR #{}: {}",
                        latestChangeSet.getPrNumber(),
                        e.getMessage());
            }
        }

        PublishCapabilitiesDto.PublishStatus publishStatus = obsResult.incomingCommitCount() > 0
                ? PublishCapabilitiesDto.PublishStatus.REBASE_REQUIRED
                : PublishCapabilitiesDto.PublishStatus.UP_TO_DATE;

        PublishCapabilitiesDto.TargetDto target;
        String suggestedNewBranch = null;

        if (latestChangeSet == null || prSnapshot == null) {
            target = PublishCapabilitiesDto.TargetDto.openPr(
                    targetHeadBranch != null ? targetHeadBranch : defaultBranch);
        } else if (prSnapshot.state() == PullRequestSnapshot.PullRequestState.OPEN) {
            target = PublishCapabilitiesDto.TargetDto.updatePr(prSnapshot.number(), prSnapshot.headBranch());
        } else if (prSnapshot.state() == PullRequestSnapshot.PullRequestState.MERGED) {
            suggestedNewBranch = deriveSuggestedBranch(execution, changeSets.size() + 1);
            target = PublishCapabilitiesDto.TargetDto.openPrNewBranch(suggestedNewBranch);
        } else { // CLOSED
            target = PublishCapabilitiesDto.TargetDto.openPr(prSnapshot.headBranch());
        }

        List<PublishCapabilitiesDto.ChangeSetFileDto> files = obsResult.changeSet().files().stream()
                .map(f ->
                        new PublishCapabilitiesDto.ChangeSetFileDto(f.path(), f.status(), f.additions(), f.deletions()))
                .toList();
        PublishCapabilitiesDto.ChangeSetSummaryDto csSummary = new PublishCapabilitiesDto.ChangeSetSummaryDto(
                obsResult.changeSet().additions(),
                obsResult.changeSet().deletions(),
                files,
                obsResult.changeSet().digest());

        boolean hasChanges = obsResult.commitCount() > 0
                || obsResult.hasUncommittedChanges()
                || !obsResult.changeSet().files().isEmpty();

        int commitsAhead = obsResult.commitCount();
        int additions = obsResult.changeSet().additions();
        int deletions = obsResult.changeSet().deletions();
        int modifiedCount = (int) obsResult.changeSet().files().stream()
                .filter(f -> !"added".equalsIgnoreCase(f.status()) && !"deleted".equalsIgnoreCase(f.status()))
                .count();
        int stagedFiles = (int) obsResult.changeSet().files().stream()
                .filter(f -> "added".equalsIgnoreCase(f.status()))
                .count();

        String formattedSummary =
                formatStatsSummary(commitsAhead, stagedFiles, modifiedCount, additions, deletions, hasChanges);

        PublishCapabilitiesDto.PublishStatsDto stats = new PublishCapabilitiesDto.PublishStatsDto(
                commitsAhead, stagedFiles, modifiedCount, additions, deletions, hasChanges, formattedSummary);

        String suggestedTitle = "";
        String suggestedBody = "";
        if (hasChanges) {
            List<SandboxExecutionActivity> activities =
                    activityRepository.findByExecutionIdOrderBySequenceAsc(executionId);
            List<String> agentMessages = activities.stream()
                    .filter(a -> a.getActivityType() == ActivityType.MESSAGE)
                    .map(SandboxExecutionActivity::getDescription)
                    .filter(d -> d != null && !d.isBlank())
                    .toList();
            SuggestedMessage msg =
                    generatePublishSummary(execution, obsResult.changeSet().files(), List.of(), agentMessages);
            suggestedTitle = msg.title();
            suggestedBody = msg.body();
        }

        String digestInputs = String.format(
                "%s:%s:%s:%s:%s:%s",
                obsResult.defaultSha(),
                obsResult.executionBaseSha(),
                target.kind(),
                target.branchName(),
                target.prNumber(),
                obsResult.changeSet().digest());
        String digest = hexDigest(digestInputs);

        return new PublishCapabilitiesDto(
                supportsPr,
                repoType,
                defaultBranch,
                stats,
                suggestedTitle,
                suggestedBody,
                execution.getPublishedBranch(),
                newRepo,
                canCreateRepository,
                newRepo ? execution.getNewRepoName() : null,
                visibilityOptions,
                List.of(),
                suggestedNewBranch != null,
                suggestedNewBranch,
                publishStatus,
                obsResult.incomingCommitCount(),
                obsResult.commitCount(),
                obsResult.hasUncommittedChanges(),
                csSummary,
                target,
                digest);
    }

    private PublishCapabilitiesDto zeroPublishCapabilities(
            SandboxExecution execution,
            boolean newRepo,
            boolean supportsPr,
            RepositoryType repoType,
            String defaultBranch,
            boolean canCreateRepository,
            List<RepositoryVisibility> visibilityOptions,
            List<ChangeSet> changeSets) {
        PublishCapabilitiesDto.PublishStatsDto zeroStats =
                new PublishCapabilitiesDto.PublishStatsDto(0, 0, 0, 0, 0, false, "There are no changes to publish");
        return new PublishCapabilitiesDto(
                supportsPr,
                repoType,
                defaultBranch,
                zeroStats,
                "",
                "",
                execution.getPublishedBranch(),
                newRepo,
                canCreateRepository,
                newRepo ? execution.getNewRepoName() : null,
                visibilityOptions,
                List.of(),
                false,
                deriveSuggestedBranch(execution, changeSets.size() + 1),
                PublishCapabilitiesDto.PublishStatus.UP_TO_DATE,
                0,
                0,
                false,
                new PublishCapabilitiesDto.ChangeSetSummaryDto(0, 0, List.of(), ""),
                PublishCapabilitiesDto.TargetDto.openPr(defaultBranch),
                "");
    }

    private String deriveSuggestedBranch(SandboxExecution execution, int iteration) {
        String suffix = execution.getId() != null ? execution.getId().toString().substring(0, 8) : "feature";
        return "kratis/" + suffix + "-" + iteration;
    }

    private String hexDigest(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    @Transactional(readOnly = true)
    public PublishCapabilitiesDto rebase(UUID userId, UUID chatId, UUID executionId) {
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        ExecutionEnvironment env = execution.getEnvironment();
        if (env == null || env.getStatus() != EnvironmentStatus.CONNECTED) {
            throw new PublishException(
                    PublishException.ErrorCode.ENVIRONMENT_NOT_RUNNING, "Environment is not running");
        }
        if (execution.getStatus() == SandboxExecutionStatus.RUNNING) {
            throw new PublishException(PublishException.ErrorCode.EXECUTION_RUNNING, "Execution is actively running");
        }

        String defaultBranch = resolveTargetBranch(execution);
        try {
            environmentRpcClient.request(
                    env.getId(),
                    new EnvironmentRpcPayload.GitRebase(executionId.toString(), defaultBranch, ""),
                    GIT_PUSH_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
        } catch (EnvironmentRpcClient.EnvironmentRpcException e) {
            if (e.getMessage() != null && e.getMessage().contains("REBASE_CONFLICT")) {
                throw new PublishException(
                        PublishException.ErrorCode.REBASE_CONFLICT, "Rebase conflict: " + e.getMessage(), e);
            }
            throw new PublishException(
                    PublishException.ErrorCode.GIT_REMOTE_UNAVAILABLE, "Rebase failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new PublishException(
                    PublishException.ErrorCode.GIT_REMOTE_UNAVAILABLE, "Rebase failed: " + e.getMessage(), e);
        }

        return buildPublishCapabilities(execution);
    }

    @Transactional
    public PushBranchResponseDto pushBranch(UUID userId, UUID chatId, UUID executionId, PushBranchRequestDto request) {
        Objects.requireNonNull(request, "request is required");
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        ExecutionEnvironment env = execution.getEnvironment();
        if (env == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Execution has no associated environment");
        }

        applyNewRepoCredential(execution, request.credentialId());
        ensureRemoteRepository(execution, null, null, request.credentialId());

        // Check capabilities to see if rebase is required
        PublishCapabilitiesDto currentCaps = buildPublishCapabilities(execution);
        if (currentCaps.status() == PublishCapabilitiesDto.PublishStatus.REBASE_REQUIRED) {
            throw new PublishException(PublishException.ErrorCode.REBASE_REQUIRED, "Rebase required before push");
        }

        boolean squash = Boolean.TRUE.equals(request.squash());
        EnvironmentConnectorResult.GitPush pushResult = executeGitPush(
                env.getId(),
                executionId,
                request.branchName(),
                request.commitMessage(),
                false,
                squash,
                resolveTargetBranch(execution));

        int nextSeq =
                changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId).size() + 1;
        ChangeSet changeSet = new ChangeSet(
                execution,
                nextSeq,
                currentCaps.changeSet().digest(),
                pushResult.commitSha() != null ? pushResult.commitSha() : "unknown",
                pushResult.branchName(),
                null,
                null,
                null,
                Instant.now());
        changeSetRepository.save(changeSet);

        return new PushBranchResponseDto(
                pushResult.branchName(), pushResult.commitSha(), pushResult.remoteRef(), pushResult.status());
    }

    @Transactional
    public PullRequestResultDto publishPullRequest(
            UUID userId, UUID chatId, UUID executionId, PublishPrRequestDto request) {
        Objects.requireNonNull(request, "request is required");
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        ExecutionEnvironment env = execution.getEnvironment();
        if (env == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Execution has no associated environment");
        }
        if (env.getStatus() != EnvironmentStatus.CONNECTED) {
            throw new PublishException(
                    PublishException.ErrorCode.ENVIRONMENT_NOT_RUNNING, "Environment is not running");
        }
        if (execution.getStatus() == SandboxExecutionStatus.RUNNING) {
            throw new PublishException(PublishException.ErrorCode.EXECUTION_RUNNING, "Execution is actively running");
        }

        Repository repo = ensureRemoteRepository(
                execution, request.repositoryName(), request.visibility(), request.credentialId());
        if (repo == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Execution repository is not configured for PR creation");
        }

        RepoProvider provider = providerRegistry.getProvider(repo);
        if (!provider.supportsPullRequests()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Repository provider " + repo.getRepositoryType()
                            + " does not support Pull Request creation via REST API");
        }

        // Fresh observe check
        PublishCapabilitiesDto currentCaps = buildPublishCapabilities(execution);
        if (request.digest() != null && !request.digest().equals(currentCaps.digest())) {
            throw new PublishException(
                    PublishException.ErrorCode.PLAN_CHANGED, "Publish plan has changed since last check");
        }
        if (currentCaps.status() == PublishCapabilitiesDto.PublishStatus.REBASE_REQUIRED) {
            throw new PublishException(PublishException.ErrorCode.REBASE_REQUIRED, "Rebase required before publish");
        }

        boolean squash = Boolean.TRUE.equals(request.squash());
        String baseBranch = resolveTargetBranch(execution);
        PublishCapabilitiesDto.TargetDto target = currentCaps.target();
        boolean isUpdate = target.kind() == PublishCapabilitiesDto.TargetDto.TargetKind.UPDATE_PR;
        boolean createNewPr = Boolean.TRUE.equals(request.createNewPr()) || !isUpdate;
        if (createNewPr) {
            isUpdate = false;
        }
        String branchName;
        if (Boolean.TRUE.equals(request.createNewPr())) {
            branchName = deriveSuggestedBranch(
                    execution,
                    changeSetRepository
                                    .findByExecutionIdOrderBySeqAsc(executionId)
                                    .size()
                            + 1);
        } else {
            branchName = request.branchName() != null && !request.branchName().isBlank()
                    ? request.branchName()
                    : target.branchName();
        }

        List<ChangeSet> changeSets = changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId);
        ChangeSet latestChangeSet = changeSets.stream()
                .filter(cs -> cs.getPrNumber() != null)
                .reduce((first, second) -> second)
                .orElse(null);

        EnvironmentConnectorResult.GitPush pushResult =
                executeGitPush(env.getId(), executionId, branchName, request.title(), isUpdate, squash, baseBranch);
        String pushedBranch = pushResult.branchName();
        String headBranch = pushedBranch != null && !pushedBranch.isBlank() ? pushedBranch : branchName;

        String pushedCommitSha = pushResult.commitSha() != null ? pushResult.commitSha() : "unknown";
        if (isUpdate) {
            int nextSeq = changeSetRepository
                            .findByExecutionIdOrderBySeqAsc(executionId)
                            .size()
                    + 1;
            String prUrl = latestChangeSet != null ? latestChangeSet.getPrUrl() : "";
            ChangeSet changeSet = new ChangeSet(
                    execution,
                    nextSeq,
                    currentCaps.changeSet().digest(),
                    pushedCommitSha,
                    headBranch,
                    target.prNumber(),
                    prUrl,
                    baseBranch,
                    Instant.now());
            changeSetRepository.save(changeSet);
            return new PullRequestResultDto(target.prNumber(), prUrl, headBranch, baseBranch);
        }

        // Create new PR
        GitAuthMaterial auth = credentialResolver.resolve(repo);
        CreatePullRequestCommand command = new CreatePullRequestCommand(
                headBranch, baseBranch, request.title(), request.body(), Boolean.TRUE.equals(request.draft()));

        PullRequestResultDto result = provider.createPullRequest(repo, auth, command);

        int nextSeq =
                changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId).size() + 1;
        ChangeSet changeSet = new ChangeSet(
                execution,
                nextSeq,
                currentCaps.changeSet().digest(),
                pushedCommitSha,
                result.headBranch(),
                result.prNumber(),
                result.prUrl(),
                result.baseBranch(),
                Instant.now());
        changeSetRepository.save(changeSet);

        return result;
    }

    private String formatStatsSummary(
            int commitsAhead, int stagedFiles, int unstagedFiles, int additions, int deletions, boolean hasChanges) {
        if (!hasChanges) {
            return "There are no changes to publish";
        }
        List<String> parts = new ArrayList<>(3);
        if (commitsAhead > 0) {
            parts.add(commitsAhead + (commitsAhead == 1 ? " unpushed commit" : " unpushed commits"));
        }
        if (stagedFiles > 0) {
            parts.add(stagedFiles + (stagedFiles == 1 ? " staged file" : " staged files"));
        }
        if (unstagedFiles > 0) {
            parts.add(unstagedFiles + (unstagedFiles == 1 ? " unstaged file" : " unstaged files"));
        }
        return String.join(", ", parts) + " (+" + additions + ", -" + deletions + " lines)";
    }

    private record SuggestedMessage(String title, String body) {}

    private SuggestedMessage generatePublishSummary(
            SandboxExecution execution,
            List<EnvironmentConnectorResult.GitChangeSetFile> files,
            List<EnvironmentConnectorResult.GitCommitMessage> commits,
            List<String> agentMessages) {
        Team team = execution.getChat().getTeam();
        ModelProvider provider = team.getIngestionProvider();
        if (provider == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "No ingestion model provider configured for team");
        }

        String modelName = team.getIngestionModel();
        try {
            ChatModel model = chatModelFactory.createChatModel(provider, modelName);
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append(
                    "You are generating a concise Pull Request title and structured description summarizing the changes made by an AI development agent.\n\n");

            if (agentMessages != null && !agentMessages.isEmpty()) {
                promptBuilder.append("Agent messages and feedback:\n");
                for (String msg : agentMessages) {
                    promptBuilder
                            .append("- ")
                            .append(msg.trim().replace("\n", "\n  "))
                            .append("\n");
                }
                promptBuilder.append("\n");
            }

            if (commits != null && !commits.isEmpty()) {
                promptBuilder.append("Commit history:\n");
                for (EnvironmentConnectorResult.GitCommitMessage c : commits) {
                    promptBuilder.append("- ").append(c.subject()).append("\n");
                    if (c.body() != null && !c.body().isBlank()) {
                        promptBuilder
                                .append("  ")
                                .append(c.body().replace("\n", "\n  "))
                                .append("\n");
                    }
                }
                promptBuilder.append("\n");
            }

            if (files != null && !files.isEmpty()) {
                promptBuilder.append("Changed files:\n");
                for (EnvironmentConnectorResult.GitChangeSetFile f : files) {
                    promptBuilder
                            .append("- ")
                            .append(f.status())
                            .append(" ")
                            .append(f.path())
                            .append(" (+")
                            .append(f.additions())
                            .append(", -")
                            .append(f.deletions())
                            .append(")\n");
                }
                promptBuilder.append("\n");
            }

            promptBuilder.append("Return ONLY valid JSON matching this schema:\n");
            promptBuilder.append(
                    "{\n  \"title\": \"conventional commit title under 72 chars\",\n  \"body\": \"concise bulleted markdown summary of changes including agent feedback and verification\"\n}\n");

            String prompt = promptBuilder.toString();
            logger.debug(
                    "Generating publish summary: provider={} model={} promptChars={} agentMessages={} commits={} files={}",
                    provider.getProviderType(),
                    modelName,
                    prompt.length(),
                    agentMessages == null ? 0 : agentMessages.size(),
                    commits == null ? 0 : commits.size(),
                    files == null ? 0 : files.size());
            ChatResponse chatResponse =
                    ChatClient.builder(model).build().prompt(prompt).call().chatResponse();
            String response = ChatResponseAssembler.answerText(chatResponse);

            if (!response.isBlank()) {
                String cleanJson = response.trim();
                if (cleanJson.startsWith("```json")) {
                    cleanJson = cleanJson.substring(7);
                }
                if (cleanJson.startsWith("```")) {
                    cleanJson = cleanJson.substring(3);
                }
                if (cleanJson.endsWith("```")) {
                    cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
                }
                JsonNode json = objectMapper.readTree(cleanJson.trim());
                String title = json.path("title").asText("");
                String body = json.path("body").asText("");
                return new SuggestedMessage(title, body);
            }
            logger.warn("LLM publish summary had no usable response; returning empty suggestion");
            return new SuggestedMessage("", "");
        } catch (Exception e) {
            logger.warn(
                    "ChatModel generation failed for PR publish summary: provider={} model={} error={}",
                    provider.getProviderType(),
                    modelName,
                    e.getMessage(),
                    e);
            return new SuggestedMessage("", "");
        }
    }

    private EnvironmentConnectorResult.GitPush executeGitPush(
            UUID envId,
            UUID executionId,
            String branchName,
            String commitMessage,
            boolean force,
            boolean squash,
            String targetBranch) {
        try {
            EnvironmentConnectorResult.GitPush result = environmentRpcClient.request(
                    envId,
                    new EnvironmentRpcPayload.GitPush(
                            branchName,
                            !force,
                            null,
                            targetBranch,
                            null,
                            null,
                            commitMessage,
                            force,
                            squash,
                            targetBranch,
                            executionId.toString()),
                    GIT_PUSH_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);

            if (result == null) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Empty response from sandbox git push");
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Git push request interrupted", e);
        } catch (TimeoutException e) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Timed out executing git push in sandbox", e);
        } catch (EnvironmentRpcClient.EnvironmentRpcException e) {
            if (e.getMessage() != null && e.getMessage().contains("REBASE_CONFLICT")) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Your changes conflict with the latest commits on the target branch. Ask the agent to rebase and retest before publishing.",
                        e);
            }
            String detail = e.getData() != null && !e.getData().isBlank() ? ": " + e.getData() : "";
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Failed to push git branch: " + e.getMessage() + detail, e);
        }
    }

    private SandboxExecution validateAndGetExecution(UUID userId, UUID chatId, UUID executionId) {
        ChatEntity chat = chatRepository
                .findById(chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));

        if (!chat.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to chat");
        }

        SandboxExecution execution = sandboxExecutionRepository
                .findById(executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"));

        if (!execution.getChat().getId().equals(chatId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Execution does not belong to chat");
        }

        return execution;
    }

    private String resolveTargetBranch(SandboxExecution execution) {
        if (execution.getTargetBranch() != null && !execution.getTargetBranch().isBlank()) {
            return execution.getTargetBranch();
        }
        Repository repo = execution.getRepository();
        if (repo != null && repo.getBranch() != null && !repo.getBranch().isBlank()) {
            return repo.getBranch();
        }
        return "main";
    }

    private Repository resolveRepository(SandboxExecution execution) {
        return execution.getRepository();
    }

    /**
     * Creates the remote repository for a new-repo execution on first publish and links it to the
     * execution. Returns the existing repository for a normal execution, and {@code null} when the
     * execution is not a new-repo execution.
     */
    private Repository ensureRemoteRepository(
            SandboxExecution execution, String repositoryName, RepositoryVisibility visibility, UUID credentialId) {
        if (execution.getRepository() != null) {
            return execution.getRepository();
        }
        if (execution.getNewRepoName() == null) {
            return null;
        }

        applyNewRepoCredential(execution, credentialId);

        RepositoryType type = resolveNewRepoType(execution);
        if (type == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A new repository requires a credential with a provider (github, gitlab, bitbucket, or azure)");
        }
        RepoProvider provider = providerRegistry.getProvider(type);
        if (!provider.supportsRepositoryCreation()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Repository creation is not supported for " + type
                            + " credentials; use a personal access token or app credential");
        }

        RepoCredential credential = execution.getNewRepoCredential();
        if (credential == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A credential is required to create the repository");
        }

        String name = repositoryName != null && !repositoryName.isBlank()
                ? repositoryName.trim()
                : execution.getNewRepoName();
        UUID teamId = execution.getChat().getTeam().getId();
        if (repositoryRepository.existsByTeamIdAndName(teamId, name)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A repository named " + name + " already exists for this team");
        }

        RepositoryVisibility effectiveVisibility = visibility != null ? visibility : RepositoryVisibility.PRIVATE;
        String defaultBranch = "main";
        GitAuthMaterial auth = credentialResolver.resolve(credential);

        RemoteRepositoryDto remote;
        try {
            remote = provider.createRepository(
                    credential, auth, new CreateRepositoryCommand(name, effectiveVisibility, defaultBranch));
        } catch (RemoteRepositoryExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }

        // New-repo executions choose credentials at publish time, so register git auth before the seed push.
        registerNewRepoGitAuth(execution);

        executeGitSetRemote(execution.getEnvironment().getId(), execution.getId(), remote.cloneUrl(), defaultBranch);

        Repository repository = new Repository(name, remote.cloneUrl(), defaultBranch, type);
        repository.setCredential(credential);
        repository.setTeam(execution.getChat().getTeam());
        repository = repositoryRepository.save(repository);

        execution.setRepository(repository);
        execution.setTargetBranch(defaultBranch);
        execution.setNewRepoName(null);
        execution.setNewRepoCredential(null);
        sandboxExecutionRepository.save(execution);

        return repository;
    }

    private void registerNewRepoGitAuth(SandboxExecution execution) {
        try {
            sandboxProvisioningService.registerGitCredentials(execution);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Git auth registration interrupted", e);
        } catch (TimeoutException e) {
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT, "Timed out registering git auth in the sandbox", e);
        } catch (EnvironmentRpcClient.EnvironmentRpcException e) {
            String detail = e.getData() != null && !e.getData().isBlank() ? ": " + e.getData() : "";
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Failed to register git auth in the sandbox: " + e.getMessage() + detail,
                    e);
        }
    }

    private void executeGitSetRemote(UUID envId, UUID executionId, String remoteUrl, String defaultBranch) {
        try {
            EnvironmentConnectorResult.GitSetRemote result = environmentRpcClient.request(
                    envId,
                    new EnvironmentRpcPayload.GitSetRemote(remoteUrl, defaultBranch, executionId.toString()),
                    GIT_PUSH_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            if (result == null) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Empty response from sandbox git set remote");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Git set remote request interrupted", e);
        } catch (TimeoutException e) {
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT, "Timed out configuring the remote repository in the sandbox", e);
        } catch (EnvironmentRpcClient.EnvironmentRpcException e) {
            String detail = e.getData() != null && !e.getData().isBlank() ? ": " + e.getData() : "";
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Failed to configure the remote repository: " + e.getMessage() + detail, e);
        }
    }

    private RepositoryType resolveNewRepoType(SandboxExecution execution) {
        RepoCredential credential = execution.getNewRepoCredential();
        if (credential == null) {
            return null;
        }
        return credential.getMetadata().getProviderType().orElse(null);
    }

    private void applyNewRepoCredential(SandboxExecution execution, UUID credentialId) {
        if (execution.getNewRepoName() == null || execution.getRepository() != null) {
            return;
        }
        if (credentialId == null) {
            if (execution.getNewRepoCredential() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A credential is required to create the repository");
            }
            return;
        }
        RepoCredential current = execution.getNewRepoCredential();
        if (current != null && credentialId.equals(current.getId())) {
            return;
        }
        UUID teamId = execution.getChat().getTeam().getId();
        RepoCredential selected = credentialRepository
                .findByTeamIdAndId(teamId, credentialId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credential not found"));
        execution.setNewRepoCredential(selected);
        // Commit the selection so the token handler's separate transaction sees it.
        requiresNewTransactionTemplate.executeWithoutResult(
                status -> sandboxExecutionRepository.updateNewRepoCredential(execution.getId(), credentialId));
    }

    private static List<RepositoryVisibility> visibilityOptionsFor(RepositoryType type) {
        return switch (type) {
            case GITHUB, BITBUCKET -> List.of(RepositoryVisibility.PRIVATE, RepositoryVisibility.PUBLIC);
            case GITLAB ->
                List.of(RepositoryVisibility.PRIVATE, RepositoryVisibility.INTERNAL, RepositoryVisibility.PUBLIC);
            case AZURE, GENERIC -> List.of();
        };
    }
}
