package com.kratisai.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.kratisai.controlplane.git.provider.RemoteRepositoryExistsException;
import com.kratisai.controlplane.git.provider.RepoProvider;
import com.kratisai.controlplane.git.provider.RepoProviderRegistry;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.RepositoryVisibility;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SandboxExecutionPublishService {

    private static final Logger logger = LoggerFactory.getLogger(SandboxExecutionPublishService.class);
    private static final long GIT_PUSH_TIMEOUT_SECONDS = 60;
    private static final long GIT_DIFF_TIMEOUT_SECONDS = 15;

    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final ChatRepository chatRepository;
    private final SandboxExecutionActivityRepository activityRepository;
    private final RepositoryRepository repositoryRepository;
    private final EnvironmentRpcClient environmentRpcClient;
    private final GitCredentialResolver credentialResolver;
    private final RepoProviderRegistry providerRegistry;
    private final ChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;

    public SandboxExecutionPublishService(
            SandboxExecutionRepository sandboxExecutionRepository,
            ChatRepository chatRepository,
            SandboxExecutionActivityRepository activityRepository,
            RepositoryRepository repositoryRepository,
            EnvironmentRpcClient environmentRpcClient,
            GitCredentialResolver credentialResolver,
            RepoProviderRegistry providerRegistry,
            ChatModelFactory chatModelFactory,
            ObjectMapper objectMapper) {
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.chatRepository = chatRepository;
        this.activityRepository = activityRepository;
        this.repositoryRepository = repositoryRepository;
        this.environmentRpcClient = environmentRpcClient;
        this.credentialResolver = credentialResolver;
        this.providerRegistry = providerRegistry;
        this.chatModelFactory = chatModelFactory;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PublishCapabilitiesDto getPublishCapabilities(UUID userId, UUID chatId, UUID executionId) {
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        Repository repo = resolveRepository(execution);
        boolean newRepo = repo == null && execution.getNewRepoName() != null;

        boolean supportsPr = false;
        String repoType = "GENERIC";
        String defaultBranch = resolveTargetBranch(execution);
        boolean canCreateRepository = false;
        List<RepositoryVisibility> visibilityOptions = List.of();

        if (repo != null) {
            RepoProvider provider = providerRegistry.getProvider(repo);
            supportsPr = provider.supportsPullRequests();
            repoType = repo.getRepositoryType().name();
        } else if (newRepo) {
            RepositoryType type = resolveNewRepoType(execution);
            if (type != null) {
                repoType = type.name();
                RepoProvider provider = providerRegistry.getProvider(type);
                canCreateRepository = provider.supportsRepositoryCreation();
                supportsPr = canCreateRepository && provider.supportsPullRequests();
                visibilityOptions = visibilityOptionsFor(type);
            }
        }

        ExecutionEnvironment env = execution.getEnvironment();
        if (env == null) {
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
                    execution.getPublishedPrNumber(),
                    execution.getPublishedPrUrl(),
                    newRepo,
                    canCreateRepository,
                    newRepo ? execution.getNewRepoName() : null,
                    visibilityOptions);
        }

        // After the first publish, stats and suggested messages describe only the
        // not-yet-pushed delta (working tree vs the published feature branch).
        // Before any publish they describe the cumulative diff vs the target branch.
        String diffBase = execution.getPublishedBranch() != null ? execution.getPublishedBranch() : defaultBranch;
        EnvironmentConnectorResult.GitDiffSummary summary = fetchGitDiffSummary(env.getId(), executionId, diffBase);

        int commitsAhead = summary.commitsAhead() != null ? summary.commitsAhead() : 0;
        int stagedFiles = summary.stagedFiles() != null ? summary.stagedFiles() : 0;
        int unstagedFiles = summary.unstagedFiles() != null ? summary.unstagedFiles() : 0;
        int additions = summary.totalAdditions();
        int deletions = summary.totalDeletions();
        boolean hasChanges = Boolean.TRUE.equals(summary.hasChanges())
                || commitsAhead > 0
                || stagedFiles > 0
                || unstagedFiles > 0
                || !summary.files().isEmpty();

        String formattedSummary =
                formatStatsSummary(commitsAhead, stagedFiles, unstagedFiles, additions, deletions, hasChanges);

        PublishCapabilitiesDto.PublishStatsDto stats = new PublishCapabilitiesDto.PublishStatsDto(
                commitsAhead, stagedFiles, unstagedFiles, additions, deletions, hasChanges, formattedSummary);

        String suggestedTitle = "";
        String suggestedBody = "";

        if (hasChanges) {
            List<EnvironmentConnectorResult.GitCommitMessage> commits = summary.commitMessages();
            List<SandboxExecutionActivity> activities =
                    activityRepository.findByExecutionIdOrderBySequenceAsc(executionId);
            List<String> agentMessages = activities.stream()
                    .filter(a -> a.getActivityType() == ActivityType.MESSAGE)
                    .map(SandboxExecutionActivity::getDescription)
                    .filter(d -> d != null && !d.isBlank())
                    .toList();

            SuggestedMessage msg = generatePublishSummary(execution, summary, commits, agentMessages);
            suggestedTitle = msg.title();
            suggestedBody = msg.body();
        }

        return new PublishCapabilitiesDto(
                supportsPr,
                repoType,
                defaultBranch,
                stats,
                suggestedTitle,
                suggestedBody,
                execution.getPublishedBranch(),
                execution.getPublishedPrNumber(),
                execution.getPublishedPrUrl(),
                newRepo,
                canCreateRepository,
                newRepo ? execution.getNewRepoName() : null,
                visibilityOptions);
    }

    @Transactional
    public PushBranchResponseDto pushBranch(UUID userId, UUID chatId, UUID executionId, PushBranchRequestDto request) {
        Objects.requireNonNull(request, "request is required");
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        ExecutionEnvironment env = execution.getEnvironment();
        if (env == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Execution has no associated environment");
        }

        ensureRemoteRepository(execution, null, null);

        boolean squash = Boolean.TRUE.equals(request.squash());
        EnvironmentConnectorResult.GitPush pushResult = executeGitPush(
                env.getId(),
                executionId,
                request.branchName(),
                request.commitMessage(),
                false,
                squash,
                resolveTargetBranch(execution));

        execution.setPublishedBranch(pushResult.branchName());
        sandboxExecutionRepository.save(execution);

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

        Repository repo = ensureRemoteRepository(execution, request.repositoryName(), request.visibility());
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

        boolean squash = Boolean.TRUE.equals(request.squash());
        String baseBranch =
                request.baseBranch() != null && !request.baseBranch().isBlank()
                        ? request.baseBranch()
                        : resolveTargetBranch(execution);
        String branchName =
                execution.getPublishedBranch() != null ? execution.getPublishedBranch() : request.branchName();
        boolean republish = execution.getPublishedPrNumber() != null;

        executeGitPush(env.getId(), executionId, branchName, request.title(), republish, squash, baseBranch);

        // Re-publish reuses the persisted branch/PR and never opens a second Pull Request
        if (republish) {
            return new PullRequestResultDto(
                    execution.getPublishedPrNumber(), execution.getPublishedPrUrl(), branchName, baseBranch);
        }

        GitAuthMaterial auth = credentialResolver.resolve(repo);
        CreatePullRequestCommand command = new CreatePullRequestCommand(
                branchName, baseBranch, request.title(), request.body(), Boolean.TRUE.equals(request.draft()));

        PullRequestResultDto result = provider.createPullRequest(repo, auth, command);

        execution.setPublishedBranch(result.headBranch());
        execution.setPublishedPrNumber(result.prNumber());
        execution.setPublishedPrUrl(result.prUrl());
        sandboxExecutionRepository.save(execution);

        return result;
    }

    private EnvironmentConnectorResult.GitDiffSummary fetchGitDiffSummary(
            UUID envId, UUID executionId, String baseBranch) {
        try {
            EnvironmentConnectorResult.GitDiffSummary result = environmentRpcClient.request(
                    envId,
                    new EnvironmentRpcPayload.GitDiffSummary(baseBranch, executionId.toString()),
                    GIT_DIFF_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            if (result != null) {
                return result;
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch git diff summary for execution {}: {}", executionId, e.getMessage());
        }
        return new EnvironmentConnectorResult.GitDiffSummary("", "", 0, 0, List.of());
    }

    private String formatStatsSummary(
            int commitsAhead, int stagedFiles, int unstagedFiles, int additions, int deletions, boolean hasChanges) {
        if (!hasChanges) {
            return "There are no changes to publish";
        }
        StringBuilder sb = new StringBuilder();
        if (commitsAhead > 0) {
            sb.append(commitsAhead).append(commitsAhead == 1 ? " commit" : " commits");
            if (unstagedFiles > 0) {
                sb.append(", ")
                        .append(unstagedFiles)
                        .append(" unstaged ")
                        .append(unstagedFiles == 1 ? "file" : "files");
            }
        } else if (stagedFiles > 0) {
            sb.append(stagedFiles).append(" staged ").append(stagedFiles == 1 ? "file" : "files");
            if (unstagedFiles > 0) {
                sb.append(", ")
                        .append(unstagedFiles)
                        .append(" unstaged ")
                        .append(unstagedFiles == 1 ? "file" : "files");
            }
        } else if (unstagedFiles > 0) {
            sb.append(unstagedFiles).append(" unstaged ").append(unstagedFiles == 1 ? "file" : "files");
        }
        sb.append(" (+").append(additions).append(", -").append(deletions).append(" lines)");
        return sb.toString();
    }

    private record SuggestedMessage(String title, String body) {}

    private SuggestedMessage generatePublishSummary(
            SandboxExecution execution,
            EnvironmentConnectorResult.GitDiffSummary summary,
            List<EnvironmentConnectorResult.GitCommitMessage> commits,
            List<String> agentMessages) {
        ModelProvider provider = execution.getModelProvider();
        if (provider == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "No model provider configured for execution");
        }

        try {
            ChatModel model = chatModelFactory.createChatModel(provider, execution.getModelName());
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

            if (summary.files() != null && !summary.files().isEmpty()) {
                promptBuilder.append("Changed files:\n");
                for (EnvironmentConnectorResult.GitDiffSummaryFile f : summary.files()) {
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

            String response = ChatClient.builder(model)
                    .build()
                    .prompt(promptBuilder.toString())
                    .call()
                    .content();

            if (response != null && !response.isBlank()) {
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
                if (!title.isBlank()) {
                    return new SuggestedMessage(title, body);
                }
            }
            throw new IllegalStateException("LLM response did not contain a valid title in JSON payload: " + response);
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            logger.error("ChatModel generation failed for PR publish summary: {}", e.getMessage(), e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to generate PR publish summary via LLM: " + e.getMessage(),
                    e);
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
                            branchName, commitMessage, force, squash, targetBranch, executionId.toString()),
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
     * execution is not a new-repo execution. The owner is derived from the credential by the
     * provider; the modal only confirms name and visibility.
     */
    private Repository ensureRemoteRepository(
            SandboxExecution execution, String repositoryName, RepositoryVisibility visibility) {
        if (execution.getRepository() != null) {
            return execution.getRepository();
        }
        if (execution.getNewRepoName() == null) {
            return null;
        }

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
                    HttpStatus.BAD_REQUEST, "New repository execution has no credential configured");
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
        String defaultBranch = resolveTargetBranch(execution);
        GitAuthMaterial auth = credentialResolver.resolve(credential);

        RemoteRepositoryDto remote;
        try {
            remote = provider.createRepository(
                    credential, auth, new CreateRepositoryCommand(name, effectiveVisibility, defaultBranch));
        } catch (RemoteRepositoryExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }

        // git_push needs a resolvable origin/<defaultBranch>, so seed it before registering origin.
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

    private static List<RepositoryVisibility> visibilityOptionsFor(RepositoryType type) {
        return switch (type) {
            case GITHUB, BITBUCKET -> List.of(RepositoryVisibility.PRIVATE, RepositoryVisibility.PUBLIC);
            case GITLAB ->
                List.of(RepositoryVisibility.PRIVATE, RepositoryVisibility.INTERNAL, RepositoryVisibility.PUBLIC);
            case AZURE, GENERIC -> List.of();
        };
    }
}
