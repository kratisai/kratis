package com.kratisai.controlplane.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.agentloop.ChatResponseAssembler;
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
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionDiffSnapshot;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.PublishedPrStatus;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.RepositoryVisibility;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionActivityEvent;
import com.kratisai.controlplane.repository.ChangeSetRepository;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ExecutionDiffSnapshotRepository;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
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
    private static final String UNREADABLE_WORKSPACE_MESSAGE =
            "Publishing is temporarily unavailable: the workspace state could not be read";

    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final ChangeSetRepository changeSetRepository;
    private final ChatRepository chatRepository;
    private final ExecutionDiffSnapshotRepository executionDiffSnapshotRepository;
    private final SandboxExecutionActivityRepository activityRepository;
    private final ExecutionActivityPersistenceService activityPersistenceService;
    private final ApplicationEventPublisher eventPublisher;
    private final RepositoryRepository repositoryRepository;
    private final RepoCredentialRepository credentialRepository;
    private final EnvironmentRpcClient environmentRpcClient;
    private final GitCredentialResolver credentialResolver;
    private final RepoProviderRegistry providerRegistry;
    private final ChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final SandboxProvisioningService sandboxProvisioningService;
    private final ExecutionEnvironmentService executionEnvironmentService;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public SandboxExecutionPublishService(
            SandboxExecutionRepository sandboxExecutionRepository,
            ChangeSetRepository changeSetRepository,
            ChatRepository chatRepository,
            ExecutionDiffSnapshotRepository executionDiffSnapshotRepository,
            SandboxExecutionActivityRepository activityRepository,
            ExecutionActivityPersistenceService activityPersistenceService,
            ApplicationEventPublisher eventPublisher,
            RepositoryRepository repositoryRepository,
            RepoCredentialRepository credentialRepository,
            EnvironmentRpcClient environmentRpcClient,
            GitCredentialResolver credentialResolver,
            RepoProviderRegistry providerRegistry,
            ChatModelFactory chatModelFactory,
            ObjectMapper objectMapper,
            SandboxProvisioningService sandboxProvisioningService,
            ExecutionEnvironmentService executionEnvironmentService,
            PlatformTransactionManager transactionManager) {
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.changeSetRepository = changeSetRepository;
        this.chatRepository = chatRepository;
        this.executionDiffSnapshotRepository = executionDiffSnapshotRepository;
        this.activityRepository = activityRepository;
        this.activityPersistenceService =
                Objects.requireNonNull(activityPersistenceService, "activityPersistenceService is required");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher is required");
        this.repositoryRepository = repositoryRepository;
        this.credentialRepository = credentialRepository;
        this.environmentRpcClient = environmentRpcClient;
        this.credentialResolver = credentialResolver;
        this.providerRegistry = providerRegistry;
        this.chatModelFactory = chatModelFactory;
        this.objectMapper = objectMapper;
        this.sandboxProvisioningService = sandboxProvisioningService;
        this.executionEnvironmentService = executionEnvironmentService;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public PublishCapabilitiesDto getPublishCapabilities(UUID userId, UUID chatId, UUID executionId) {
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        touchIfConnected(execution.getEnvironment());
        PublishCapabilitiesDto capabilities = buildPublishCapabilities(execution);
        chatRepository.touchUpdatedAt(chatId, Instant.now());
        return capabilities;
    }

    /** Best-effort liveness stamp: a failed activity touch never fails the read it accompanies. */
    private void touchIfConnected(ExecutionEnvironment env) {
        if (env == null || env.getStatus() != EnvironmentStatus.CONNECTED) {
            return;
        }
        try {
            executionEnvironmentService.touchActiveEnvironment(env.getId());
        } catch (Exception e) {
            logger.warn("Failed to record user activity on environment {}: {}", env.getId(), e.getMessage());
        }
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
            // A zeroed capabilities payload would masquerade unpublished work as "nothing to
            // publish"; publishing is only answerable from a live workspace.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Publishing needs a running environment");
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
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNREADABLE_WORKSPACE_MESSAGE);
        }
        if (obsResult == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNREADABLE_WORKSPACE_MESSAGE);
        }

        PullRequestSnapshot prSnapshot = null;
        if (latestChangeSet != null && latestChangeSet.getPrNumber() != null && latestChangeSet.getPrStatus() != null) {
            prSnapshot = new PullRequestSnapshot(
                    latestChangeSet.getPrNumber(),
                    switch (latestChangeSet.getPrStatus()) {
                        case OPEN -> PullRequestSnapshot.PullRequestState.OPEN;
                        case MERGED -> PullRequestSnapshot.PullRequestState.MERGED;
                        case CLOSED -> PullRequestSnapshot.PullRequestState.CLOSED;
                    },
                    latestChangeSet.getPrUrl(),
                    latestChangeSet.getBranchName(),
                    defaultBranch,
                    latestChangeSet.getPushedCommitSha(),
                    latestChangeSet.getPrMergedAt());
        }
        if (latestChangeSet != null
                && latestChangeSet.getPrNumber() != null
                && supportsPr
                && repo != null
                && latestChangeSet.getPrStatus() != PublishedPrStatus.MERGED
                && latestChangeSet.getPrStatus() != PublishedPrStatus.CLOSED) {
            RepoProvider provider = providerRegistry.getProvider(repo);
            try {
                GitAuthMaterial auth = credentialResolver.resolve(repo);
                prSnapshot = provider.getPullRequest(repo, auth, latestChangeSet.getPrNumber());
                PublishedPrStatus previousStatus = latestChangeSet.getPrStatus();
                PublishedPrStatus currentStatus =
                        switch (prSnapshot.state()) {
                            case OPEN -> PublishedPrStatus.OPEN;
                            case MERGED -> PublishedPrStatus.MERGED;
                            case CLOSED -> PublishedPrStatus.CLOSED;
                        };
                latestChangeSet.setPrStatus(currentStatus);
                if (currentStatus == PublishedPrStatus.MERGED) {
                    Instant mergedAt = prSnapshot.mergedAt();
                    if (mergedAt == null) {
                        mergedAt = latestChangeSet.getPrMergedAt();
                    }
                    latestChangeSet.setPrMergedAt(mergedAt != null ? mergedAt : Instant.now());
                }
                if (currentStatus != previousStatus
                        && (currentStatus == PublishedPrStatus.MERGED || currentStatus == PublishedPrStatus.CLOSED)) {
                    eventPublisher.publishEvent(new ExecutionStatusChangedEvent(
                            execution.getChat().getTeam().getId(),
                            execution.getChat().getId(),
                            execution.getId()));
                }
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

        // The summary and branch suggestion ride the same LLM call; the suggestion is a
        // pre-fill only and is never stored before publish.
        String suggestedTitle = "";
        String suggestedBody = "";
        String llmBranchName = "";
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
            llmBranchName = msg.branchName();
        }

        PublishCapabilitiesDto.TargetDto target;
        String suggestedNewBranch = null;

        if (latestChangeSet == null || prSnapshot == null) {
            String initialBranch = targetHeadBranch != null && !targetHeadBranch.equals(defaultBranch)
                    ? targetHeadBranch
                    : resolveSuggestedBranchName(llmBranchName, execution, defaultBranch, 1);
            target = PublishCapabilitiesDto.TargetDto.openPr(initialBranch);
        } else if (prSnapshot.state() == PullRequestSnapshot.PullRequestState.OPEN) {
            target = PublishCapabilitiesDto.TargetDto.updatePr(prSnapshot.number(), prSnapshot.headBranch());
        } else if (prSnapshot.state() == PullRequestSnapshot.PullRequestState.MERGED) {
            suggestedNewBranch =
                    resolveSuggestedBranchName(llmBranchName, execution, defaultBranch, changeSets.size() + 1);
            target = PublishCapabilitiesDto.TargetDto.openPrNewBranch(suggestedNewBranch);
        } else { // CLOSED
            target = PublishCapabilitiesDto.TargetDto.openPr(prSnapshot.headBranch());
        }

        // Digest covers git observables and non-editable plan parts only: the branch, title,
        // and body are user-editable LLM suggestions that vary between fetches, and keeping
        // them as digest inputs would raise spurious PLAN_CHANGED (409) before publish.
        if (latestChangeSet != null && prSnapshot != null) {
            PublishedPrStatus currentStatus = latestChangeSet.getPrStatus();
            PublishedPrStatus observedStatus =
                    switch (prSnapshot.state()) {
                        case OPEN -> PublishedPrStatus.OPEN;
                        case MERGED -> PublishedPrStatus.MERGED;
                        case CLOSED -> PublishedPrStatus.CLOSED;
                    };
            if (currentStatus != observedStatus) {
                latestChangeSet.setPrStatus(observedStatus);
                if (observedStatus == PublishedPrStatus.MERGED) {
                    Instant mergedAt = prSnapshot.mergedAt();
                    if (mergedAt == null) {
                        mergedAt = latestChangeSet.getPrMergedAt();
                    }
                    latestChangeSet.setPrMergedAt(mergedAt != null ? mergedAt : Instant.now());
                }
                if (observedStatus == PublishedPrStatus.MERGED || observedStatus == PublishedPrStatus.CLOSED) {
                    eventPublisher.publishEvent(new ExecutionStatusChangedEvent(
                            execution.getChat().getTeam().getId(),
                            execution.getChat().getId(),
                            execution.getId()));
                }
            }
        }

        String digestInputs = String.format(
                "%s:%s:%s:%s:%s",
                obsResult.defaultSha(),
                obsResult.executionBaseSha(),
                target.kind(),
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

    private String deriveSuggestedBranch(SandboxExecution execution, int iteration) {
        String suffix = execution.getId() != null ? execution.getId().toString().substring(0, 8) : "feature";
        return "kratis/" + suffix + "-" + iteration;
    }

    private String resolveSuggestedBranchName(
            String llmBranchName, SandboxExecution execution, String baseBranch, int fallbackIteration) {
        String candidate = sanitizeBranchName(llmBranchName);
        if (!candidate.isBlank() && !candidate.equals(baseBranch) && isValidGitRefName(candidate)) {
            return candidate;
        }
        return deriveSuggestedBranch(execution, fallbackIteration);
    }

    // Mirrors sanitizeBranchName in web publish-modal.tsx so the suggestion that survives here
    // is what the dialog would show and the push would accept.
    private static String sanitizeBranchName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim()
                .replaceAll("[^a-zA-Z0-9/_.-]", "_")
                .replaceAll("^[-./]+", "_")
                .replaceAll("/{2,}", "/")
                .replaceAll("/\\.{1,}", "/_")
                .replaceAll("\\.{2,}", "_")
                .replaceAll("\\.lock(?=$|/)", "_lock")
                .replaceAll("[/.]*$", "");
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

    // Must stay writable: buildPublishCapabilities persists prStatus/prMergedAt via dirty checking.
    @Transactional
    public PublishCapabilitiesDto rebase(UUID userId, UUID chatId, UUID executionId) {
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        ExecutionEnvironment env = execution.getEnvironment();
        if (env == null || env.getStatus() != EnvironmentStatus.CONNECTED) {
            throw new PublishException(
                    PublishException.ErrorCode.ENVIRONMENT_NOT_RUNNING, "Environment is not running");
        }
        touchIfConnected(env);
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
        String baseBranch = resolveTargetBranch(execution);
        String branchName = request.branchName() != null ? request.branchName().trim() : null;
        validateBranchName(branchName, baseBranch);

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
                env.getId(), executionId, branchName, request.commitMessage(), false, squash, baseBranch);

        int nextSeq =
                changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId).size() + 1;
        ChangeSet changeSet = new ChangeSet(
                execution,
                nextSeq,
                execution.getBaseCommitSha() != null ? execution.getBaseCommitSha() : "unknown",
                pushResult.commitSha() != null ? pushResult.commitSha() : "unknown",
                pushResult.branchName(),
                null,
                null,
                null,
                Instant.now());
        changeSet.setManifestDigest(manifestDigestForChangeSet(executionId, currentCaps));
        changeSetRepository.save(changeSet);
        chatRepository.touchUpdatedAt(chatId, Instant.now());

        String commitSha = pushResult.commitSha();
        String actionId = "pub:cs:" + executionId + ":" + changeSet.getSeq();
        String description =
                "Published changes to branch " + pushResult.branchName() + " (" + shortSha(commitSha) + ")";
        ActivityDetail detail =
                ActivityDetail.forPublish(null, null, pushResult.branchName(), baseBranch, commitSha, null, null);
        recordPublishActivity(execution, actionId, description, detail);

        return new PushBranchResponseDto(
                pushResult.branchName(), pushResult.commitSha(), pushResult.remoteRef(), pushResult.status());
    }

    @Transactional
    public PullRequestResultDto publishPullRequest(
            UUID userId, UUID chatId, UUID executionId, PublishPrRequestDto request) {
        Objects.requireNonNull(request, "request is required");
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        String baseBranch = resolveTargetBranch(execution);
        if (request.branchName() != null && !request.branchName().isBlank()) {
            validateBranchName(request.branchName().trim(), baseBranch);
        }
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
        PublishCapabilitiesDto.TargetDto target = currentCaps.target();
        boolean isUpdate = target.kind() == PublishCapabilitiesDto.TargetDto.TargetKind.UPDATE_PR;
        boolean createNewPr = Boolean.TRUE.equals(request.createNewPr()) || !isUpdate;
        if (createNewPr) {
            isUpdate = false;
        }
        List<ChangeSet> changeSets = changeSetRepository.findByExecutionIdOrderBySeqAsc(executionId);
        String branchName;
        if (Boolean.TRUE.equals(request.createNewPr())) {
            branchName = deriveSuggestedBranch(execution, changeSets.size() + 1);
        } else if (request.branchName() != null && !request.branchName().isBlank()) {
            branchName = request.branchName();
        } else if (target.branchName() != null && !target.branchName().equals(baseBranch)) {
            branchName = target.branchName();
        } else {
            branchName = deriveSuggestedBranch(execution, changeSets.size() + 1);
        }
        validateBranchName(branchName, baseBranch);

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
                    execution.getBaseCommitSha() != null ? execution.getBaseCommitSha() : "unknown",
                    pushedCommitSha,
                    headBranch,
                    target.prNumber(),
                    prUrl,
                    baseBranch,
                    Instant.now());
            changeSet.setManifestDigest(manifestDigestForChangeSet(executionId, currentCaps));
            changeSet.setPrStatus(PublishedPrStatus.OPEN);
            changeSetRepository.save(changeSet);
            chatRepository.touchUpdatedAt(chatId, Instant.now());

            String actionId = "pub:cs:" + executionId + ":" + changeSet.getSeq();
            String description =
                    "Published changes: Updated Pull Request #" + target.prNumber() + " on branch " + headBranch;
            ActivityDetail detail = ActivityDetail.forPublish(
                    target.prNumber(),
                    prUrl,
                    headBranch,
                    baseBranch,
                    pushedCommitSha,
                    true,
                    repo.getRepositoryType() != null ? repo.getRepositoryType().name() : null);
            recordPublishActivity(execution, actionId, description, detail);

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
                execution.getBaseCommitSha() != null ? execution.getBaseCommitSha() : "unknown",
                pushedCommitSha,
                result.headBranch(),
                result.prNumber(),
                result.prUrl(),
                result.baseBranch(),
                Instant.now());
        changeSet.setManifestDigest(manifestDigestForChangeSet(executionId, currentCaps));
        changeSet.setPrStatus(PublishedPrStatus.OPEN);
        changeSetRepository.save(changeSet);
        chatRepository.touchUpdatedAt(chatId, Instant.now());

        String actionId = "pub:cs:" + executionId + ":" + changeSet.getSeq();
        String description = "Published changes: Pull Request #" + result.prNumber() + " created to merge "
                + result.headBranch() + " into " + result.baseBranch();
        ActivityDetail detail = ActivityDetail.forPublish(
                result.prNumber(),
                result.prUrl(),
                result.headBranch(),
                result.baseBranch(),
                pushedCommitSha,
                false,
                repo.getRepositoryType() != null ? repo.getRepositoryType().name() : null);
        recordPublishActivity(execution, actionId, description, detail);

        return result;
    }

    private void recordPublishActivity(
            SandboxExecution execution, String actionId, String description, ActivityDetail detail) {
        UUID teamId = execution.getChat().getTeam().getId();
        UUID executionId = execution.getId();
        chatRepository.touchUpdatedAt(execution.getChat().getId(), Instant.now());
        SandboxExecutionActivity activity;
        try {
            activity = requiresNewTransactionTemplate.execute(status -> activityPersistenceService.recordActivity(
                    executionId, ActivityType.PUBLISH, description, actionId, ActivityStatus.COMPLETED, detail));
        } catch (DataIntegrityViolationException ex) {
            logger.warn(
                    "Unique index collision on publish activity (execution={}, actionId={}), falling back to merge",
                    executionId,
                    actionId,
                    ex);
            activity = requiresNewTransactionTemplate.execute(status -> activityRepository
                    .findByExecutionIdAndActionId(executionId, actionId)
                    .map(existing -> {
                        existing.setActivityType(ActivityType.PUBLISH);
                        existing.setDescription(description);
                        existing.setStatus(ActivityStatus.COMPLETED);
                        if (detail != null) {
                            try {
                                existing.setDetail(objectMapper.writeValueAsString(detail));
                            } catch (JsonProcessingException e) {
                                logger.warn("Failed to serialize publish detail on fallback merge", e);
                            }
                        }
                        return activityRepository.save(existing);
                    })
                    .orElse(null));
        }
        if (activity != null) {
            eventPublisher.publishEvent(new SandboxExecutionActivityEvent(
                    teamId,
                    executionId,
                    ActivityType.PUBLISH,
                    description,
                    actionId,
                    ActivityStatus.COMPLETED,
                    detail,
                    activity.getCreatedAt()));
        }
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "unknown";
        }
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    /**
     * Change-set and diff-manifest digests are compared when deriving chat status, so a published
     * change set must store the current diff manifest digest. The publish observation digest hashes
     * a different payload and is only a fallback when no manifest has been synced yet. This reads
     * the last synced manifest, so a lost post-push sync can briefly report unpublished changes
     * until the sidecar retry or the next publish updates the snapshot.
     */
    private String manifestDigestForChangeSet(UUID executionId, PublishCapabilitiesDto currentCaps) {
        return executionDiffSnapshotRepository
                .findByExecutionId(executionId)
                .map(ExecutionDiffSnapshot::getManifestDigest)
                .filter(digest -> !digest.isBlank())
                .orElse(currentCaps.changeSet().digest());
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

    private record SuggestedMessage(String title, String body, String branchName) {
        private SuggestedMessage {
            Objects.requireNonNull(title, "title is required");
            Objects.requireNonNull(body, "body is required");
            Objects.requireNonNull(branchName, "branchName is required");
        }
    }

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

        String baseBranch = resolveTargetBranch(execution);
        String repoName =
                execution.getRepository() != null ? execution.getRepository().getName() : execution.getNewRepoName();
        String modelName = team.getIngestionModel();
        try {
            ChatModel model = chatModelFactory.createChatModel(provider, modelName);
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append(
                    "You are generating a concise Pull Request title, a structured description, and a suggested git branch name summarizing the changes made by an AI development agent.\n\n");
            if (repoName != null && !repoName.isBlank()) {
                promptBuilder.append("Repository: ").append(repoName).append("\n");
            }
            promptBuilder.append("Base branch: ").append(baseBranch).append("\n\n");

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
                    "{\n  \"title\": \"conventional commit title under 72 chars\",\n  \"body\": \"concise bulleted markdown summary of changes including agent feedback and verification\",\n  \"branchName\": \"suitable git branch name representing the summarized changes\"\n}\n");

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
                JsonNode branchNode = json.path("branchName");
                String branchName = branchNode.isTextual() ? branchNode.asText() : "";
                return new SuggestedMessage(title, body, branchName);
            }
            logger.warn("LLM publish summary had no usable response; returning empty suggestion");
            return new SuggestedMessage("", "", "");
        } catch (Exception e) {
            logger.warn(
                    "ChatModel generation failed for PR publish summary: provider={} model={} error={}",
                    provider.getProviderType(),
                    modelName,
                    e.getMessage(),
                    e);
            return new SuggestedMessage("", "", "");
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

    public static void validateBranchName(String branchName, String baseBranch) {
        if (branchName == null || branchName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch name is required");
        }
        if (branchName.equals(baseBranch)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cannot publish directly to base branch \"" + baseBranch + "\". Please specify a feature branch.");
        }
        if (!isValidGitRefName(branchName)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid git branch name: \"" + branchName + "\"");
        }
    }

    public static boolean isValidGitRefName(String branchName) {
        if (branchName == null || branchName.isBlank()) {
            return false;
        }
        if (branchName.startsWith("-")
                || branchName.startsWith("/")
                || branchName.endsWith("/")
                || branchName.startsWith(".")
                || branchName.endsWith(".")) {
            return false;
        }
        if (branchName.contains("..")
                || branchName.contains("//")
                || branchName.contains("@{")
                || branchName.equals("@")) {
            return false;
        }
        for (int i = 0; i < branchName.length(); i++) {
            char c = branchName.charAt(i);
            if (c <= 0x20 || c == 0x7F || c == '~' || c == '^' || c == ':' || c == '?' || c == '*' || c == '['
                    || c == '\\') {
                return false;
            }
        }
        String[] components = branchName.split("/", -1);
        for (String comp : components) {
            if (comp.isEmpty() || comp.startsWith(".") || comp.endsWith(".lock")) {
                return false;
            }
        }
        return true;
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
