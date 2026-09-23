package com.kratisai.controlplane.service;

import static org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive;
import static org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization;

import com.kratisai.controlplane.api.restdto.CreateSandboxExecutionRequest;
import com.kratisai.controlplane.api.restdto.SandboxExecutionDto;
import com.kratisai.controlplane.api.restdto.SteerCommentDto;
import com.kratisai.controlplane.api.restdto.SteerExecutionRequest;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlResolvedResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.PromptStatus;
import com.kratisai.controlplane.api.wsdto.StopReason;
import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.model.event.EnvironmentRegisteredEvent;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.*;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketSession;

@Service
public class SandboxExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(SandboxExecutionService.class);

    private static final long TERMINATE_TIMEOUT_SECONDS = 30;

    private static final String DOCKER_GUIDANCE = """
            Docker talks to a rootless Docker-in-Docker sibling over TCP (`DOCKER_HOST`; there is no \
            socket) and Testcontainers is preconfigured.

            - Reach inner containers by their published port on `$TESTCONTAINERS_HOST_OVERRIDE`, \
            never by container IP: container IPs are not routable from here.
            - `--privileged` inner containers fail to start; do not reach for it.
            - Ryuk is disabled and container reuse is enabled, so containers persist across runs. \
            Remove the reusable container if state goes stale.
            """;

    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final ChatRepository chatRepository;
    private final ExecutionEnvironmentRepository executionEnvironmentRepository;
    private final EnvironmentProviderRepository environmentProviderRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final EnvironmentSessionRegistry sessionRegistry;
    private final CanvasService canvasService;
    private final ModelProviderRepository modelProviderRepository;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate finalizeUsageTransactionTemplate;
    private final SandboxProvisioningService sandboxProvisioningService;
    private final PendingHitlRegistry pendingHitlRegistry;
    private final VirtualKeyService virtualKeyService;
    private final EnvironmentRpcClient environmentRpcClient;
    private final LiteLLMProperties litellmProperties;
    private final Executor dispatchExecutor;
    private final ExecutionActivityPersistenceService activityPersistenceService;

    public SandboxExecutionService(
            PlatformTransactionManager transactionManager,
            SandboxExecutionRepository sandboxExecutionRepository,
            ChatRepository chatRepository,
            ExecutionEnvironmentRepository executionEnvironmentRepository,
            EnvironmentProviderRepository environmentProviderRepository,
            TeamMemberRepository teamMemberRepository,
            ApplicationEventPublisher eventPublisher,
            EnvironmentSessionRegistry sessionRegistry,
            CanvasService canvasService,
            ModelProviderRepository modelProviderRepository,
            SandboxProvisioningService sandboxProvisioningService,
            PendingHitlRegistry pendingHitlRegistry,
            VirtualKeyService virtualKeyService,
            EnvironmentRpcClient environmentRpcClient,
            LiteLLMProperties litellmProperties,
            @Qualifier("dispatchExecutor") Executor dispatchExecutor,
            ExecutionActivityPersistenceService activityPersistenceService) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.finalizeUsageTransactionTemplate = new TransactionTemplate(transactionManager);
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.chatRepository = chatRepository;
        this.executionEnvironmentRepository = executionEnvironmentRepository;
        this.environmentProviderRepository = environmentProviderRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.eventPublisher = eventPublisher;
        this.sessionRegistry = sessionRegistry;
        this.canvasService = canvasService;
        this.modelProviderRepository = modelProviderRepository;
        this.sandboxProvisioningService = sandboxProvisioningService;
        this.pendingHitlRegistry = pendingHitlRegistry;
        this.virtualKeyService = virtualKeyService;
        this.environmentRpcClient = environmentRpcClient;
        this.litellmProperties = litellmProperties;
        this.dispatchExecutor = dispatchExecutor;
        this.activityPersistenceService = activityPersistenceService;
    }

    private void requireTeamMembership(UUID userId, UUID teamId) {
        boolean isMember = teamMemberRepository.existsByTeamIdAndUserId(teamId, userId);
        if (!isMember) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }

    private record DbResult(
            ExecutionEnvironment environment, SandboxExecution execution, boolean isNewEnvironment, String token) {}

    public SandboxExecutionDto createExecution(UUID userId, UUID chatId, CreateSandboxExecutionRequest request) {
        validateRequest(request);

        DbResult dbResult = transactionTemplate.execute(status -> {
            ChatEntity chat = chatRepository
                    .findById(chatId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));

            requireTeamMembership(userId, chat.getTeam().getId());

            // Resolve canvas document
            CanvasEntity canvas = canvasService.getCanvas(chatId, request.canvasId());
            if (canvas == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Canvas document not found: " + request.canvasId());
            }

            // Resolve or create environment record
            ExecutionEnvironment environment;
            boolean isNewEnvironment = false;
            String token = null;

            if (request.environmentId() != null) {
                environment = executionEnvironmentRepository
                        .findByTeamIdAndId(chat.getTeam().getId(), request.environmentId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found"));
            } else if (request.providerId() != null) {
                EnvironmentProvider provider = environmentProviderRepository
                        .findById(request.providerId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found"));

                if (!provider.getTeam().getId().equals(chat.getTeam().getId())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Provider does not belong to this team");
                }

                // Create a new execution environment of type SANDBOX
                environment = new ExecutionEnvironment();
                environment.setTeam(chat.getTeam());
                environment.setName("Sandbox - " + provider.getName());
                environment.setType(ExecutionEnvironmentType.SANDBOX);
                environment.setStatus(EnvironmentStatus.DISCONNECTED);
                environment.setProvider(provider);

                token = UUID.randomUUID().toString();
                environment.setAuthToken(token);

                environment = executionEnvironmentRepository.save(environment);
                isNewEnvironment = true;
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Must specify environmentId or providerId");
            }

            Repository repository = null;
            String newRepoName = null;
            if (canvas.getCanvasType() == CanvasType.DOCUMENT) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Document canvases cannot be launched; the canvas must be SPEC with a repository definition");
            }
            if (canvas.getRepository() != null) {
                repository = canvas.getRepository();
            } else if (canvas.getNewRepoName() != null) {
                newRepoName = canvas.getNewRepoName();
            }

            // Resolve model provider (required)
            ModelProvider modelProvider = modelProviderRepository
                    .findByTeamIdAndId(chat.getTeam().getId(), request.modelProviderId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model provider not found"));

            SandboxExecution execution =
                    createAndSaveExecution(chat, environment, canvas, repository, newRepoName, modelProvider, request);

            eventPublisher.publishEvent(
                    new ExecutionStatusChangedEvent(chat.getTeam().getId(), chat.getId(), execution.getId()));

            // Eagerly access lazy fields needed outside transaction
            touchEnvironmentProvider(environment);

            return new DbResult(environment, execution, isNewEnvironment, token);
        });

        ExecutionEnvironment environment = dbResult.environment();
        SandboxExecution execution = dbResult.execution();

        if (dbResult.isNewEnvironment()) {
            try {
                sandboxProvisioningService.provisionEnvironment(environment, dbResult.token());

                // Publish inside a short TX so AFTER_COMMIT UI fan-out is deferred correctly.
                transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(
                        new TeamEntityChangedEvent(environment.getTeam().getId(), TeamEntityType.ENVIRONMENTS)));

            } catch (Exception e) {
                handleExecutionFailure(execution, "Failed to spawn or initialize sandbox container", e);
                throw new RuntimeException("Failed to provision environment", e);
            }
        } else {
            dispatchExecutionIfConnected(execution, environment);
        }

        return toDto(execution);
    }

    private void validateRequest(CreateSandboxExecutionRequest request) {
        // Validate canvasId is present
        if (request.canvasId() == null || request.canvasId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "canvasId is required");
        }

        // Validate modelProviderId is present
        if (request.modelProviderId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "modelProviderId is required");
        }

        // Validate modelName is present
        if (request.modelName() == null || request.modelName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "modelName is required");
        }
    }

    private SandboxExecution createAndSaveExecution(
            ChatEntity chat,
            ExecutionEnvironment environment,
            CanvasEntity canvas,
            Repository repository,
            String newRepoName,
            ModelProvider modelProvider,
            CreateSandboxExecutionRequest request) {
        String taskPrompt = "<plan_context>\n" + canvas.getContent() + "\n</plan_context>\n\nExecute the plan.  "
                + "You are running as a non-root user inside a sandbox. To install system packages, prefix the "
                + "command with sudo (e.g. `sudo apt-get update && sudo apt-get install -y <package>`).\n\n"
                + "### Docker\n\n"
                + DOCKER_GUIDANCE;

        SandboxExecution execution = new SandboxExecution();
        execution.setEnvironment(environment);
        execution.setChat(chat);
        execution.setHarness(request.harness());
        execution.setTaskPrompt(taskPrompt);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution.setStartedAt(Instant.now());
        execution.setRepository(repository);
        execution.setTargetBranch(
                repository != null
                                && repository.getBranch() != null
                                && !repository.getBranch().isBlank()
                        ? repository.getBranch()
                        : "main");
        execution.setNewRepoName(newRepoName);
        execution.setModelProvider(modelProvider);
        execution.setModelName(request.modelName());
        return sandboxExecutionRepository.save(execution);
    }

    private void dispatchExecutionIfConnected(SandboxExecution execution, ExecutionEnvironment environment) {
        WebSocketSession envSession = sessionRegistry.getSessionForEnvironment(environment.getId());
        if (envSession != null && envSession.isOpen()) {
            dispatchExecution(execution, envSession);
        }
    }

    public void dispatchExecution(SandboxExecution execution, WebSocketSession session) {
        Objects.requireNonNull(session, "session is required");
        UUID executionId = execution.getId();
        // Run after commit so the worker can reload a committed row.
        Runnable work = () -> dispatchExecutor.execute(() -> runDispatch(executionId, false));
        if (isSynchronizationActive()) {
            registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
        } else {
            work.run();
        }
    }

    public void resumeAfterCheckout(UUID executionId) {
        if (!isSynchronizationActive()) {
            throw new IllegalStateException("resumeAfterCheckout must be invoked within an active transaction");
        }
        registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatchExecutor.execute(() -> runDispatch(executionId, true));
            }
        });
    }

    private void runDispatch(UUID executionId, boolean launchOnly) {
        try {
            SandboxExecution managed = transactionTemplate.execute(status -> {
                SandboxExecution reloaded = sandboxExecutionRepository
                        .findById(executionId)
                        .orElseThrow(() -> new IllegalStateException("Execution not found: " + executionId));
                touchExecutionGraph(reloaded);
                return reloaded;
            });
            if (!launchOnly && managed.getRepository() != null) {
                sandboxProvisioningService.dispatchCheckout(managed);
            } else {
                sandboxProvisioningService.launchAgent(managed);
            }
        } catch (Exception e) {
            handleExecutionFailure(
                    executionId,
                    null,
                    launchOnly ? "Failed to launch agent after checkout" : "Failed to dispatch execution",
                    e);
        }
    }

    // Getter call intentionally forces the lazy provider association to load while
    // the
    // session is open so the detached environment can be used after the
    // transaction.
    @SuppressFBWarnings({"RV_RETURN_VALUE_IGNORED", "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT"})
    private static void touchEnvironmentProvider(ExecutionEnvironment environment) {
        if (environment.getProvider() != null) {
            environment.getProvider().getType();
        }
    }

    // Getter calls intentionally force Hibernate lazy associations to load while
    // the
    // session is open so detached consumers can read them without
    // LazyInitializationException.
    @SuppressFBWarnings({
        "RV_RETURN_VALUE_IGNORED",
        "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT",
        "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE"
    })
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void touchExecutionGraph(SandboxExecution execution) {
        if (execution.getModelProvider() != null) {
            execution.getModelProvider().getModelNames();
            execution.getModelProvider().getDisplayName();
        }
        if (execution.getChat() != null && execution.getChat().getTeam() != null) {
            execution.getChat().getTeam().getId();
        }
        if (execution.getEnvironment() != null) {
            execution.getEnvironment().getId();
        }
        if (execution.getRepository() != null) {
            execution.getRepository().getUrl();
            if (execution.getRepository().getCredential() != null) {
                execution.getRepository().getCredential().getType();
            }
        }
        if (execution.getNewRepoCredential() != null) {
            execution.getNewRepoCredential().getType();
        }
        execution.getHarness();
        execution.getUsage();
    }

    /**
     * Dispatches the prompt on a virtual thread: the ACP prompt response arrives on
     * the environment
     * WebSocket handler thread, so blocking it would deadlock the connection.
     */
    public void dispatchAcpPrompt(SandboxExecution execution) {
        dispatchAcpPrompt(execution, execution.getTaskPrompt() != null ? execution.getTaskPrompt() : "", true, false);
    }

    public void dispatchSteeringPrompt(SandboxExecution execution, String promptText) {
        dispatchAcpPrompt(execution, promptText, false, false);
    }

    /** Prefix prepended to a recovery prompt dispatched after a fatal execution error. */
    static final String RECOVERY_PROMPT_PREFIX = """
            ### Session Recovery

            The previous agent session in this workspace ended unexpectedly. A fresh agent \
            session has been started in the same checkout. Before continuing, inspect the \
            current state yourself (`git status`, `git diff`, recent files) to determine what \
            was already completed, then continue with the request below.

            """;

    private void dispatchAcpPrompt(
            SandboxExecution execution, String promptText, boolean failOnError, boolean relaunch) {
        UUID executionId = execution.getId();
        UUID environmentId = execution.getEnvironment().getId();
        dispatchExecutor.execute(() -> {
            try {
                EnvironmentConnectorResult.AcpPrompt result = environmentRpcClient.request(
                        environmentId,
                        new EnvironmentRpcPayload.AcpPrompt(
                                promptText != null ? promptText : "",
                                executionId.toString(),
                                failOnError ? null : Boolean.TRUE,
                                relaunch ? Boolean.TRUE : null));
                if (result == null || result.status() == PromptStatus.FAILED) {
                    handlePromptDispatchFailure(
                            executionId,
                            "ACP prompt failed for execution " + executionId
                                    + (result != null ? ": " + result.error() : " (no result)"),
                            failOnError);
                } else {
                    logger.info(
                            "Sent ACP prompt to execution environment {} for execution {}{}",
                            environmentId,
                            executionId,
                            relaunch ? " (relaunched agent session)" : "");
                }
            } catch (EnvironmentRpcClient.EnvironmentRpcException e) {
                handlePromptDispatchFailure(
                        executionId,
                        "ACP prompt dispatch failed for execution " + executionId + ": " + e.getMessage(),
                        failOnError);
            } catch (TimeoutException e) {
                logger.warn(
                        "ACP prompt dispatch for execution {} did not complete in time; the agent may still"
                                + " report completion via env.acp_prompt_complete",
                        executionId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                handlePromptDispatchFailure(
                        executionId, "ACP prompt dispatch interrupted for execution " + executionId, failOnError);
            } catch (Exception e) {
                handlePromptDispatchFailure(
                        executionId,
                        "ACP prompt dispatch for execution " + executionId + " raised "
                                + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        failOnError);
            }
        });
    }

    private void handlePromptDispatchFailure(UUID executionId, String message, boolean failOnError) {
        if (failOnError) {
            failExecutionIfStillRunning(executionId, message);
        } else {
            logger.warn(message + " (steering prompt; execution stays RUNNING)");
        }
    }

    @Transactional
    public void steerExecution(UUID userId, UUID chatId, UUID executionId, SteerExecutionRequest request) {
        Objects.requireNonNull(request, "request is required");
        ChatEntity chat = chatRepository
                .findById(chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));

        requireTeamMembership(userId, chat.getTeam().getId());

        SandboxExecution execution = sandboxExecutionRepository
                .findById(executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"));

        if (!execution.getChat().getId().equals(chatId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Execution does not belong to chat");
        }

        if (execution.getStatus() != SandboxExecutionStatus.RUNNING
                && execution.getStatus() != SandboxExecutionStatus.IDLE
                && execution.getStatus() != SandboxExecutionStatus.FAILED) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Cannot steer execution in state " + execution.getStatus());
        }

        ExecutionEnvironment environment = execution.getEnvironment();
        if (environment == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Execution has no associated environment");
        }

        boolean recovering = execution.getStatus() == SandboxExecutionStatus.FAILED;
        touchExecutionGraph(execution);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        sandboxExecutionRepository.save(execution);
        eventPublisher.publishEvent(
                new ExecutionStatusChangedEvent(chat.getTeam().getId(), chatId, executionId));

        String compiledPrompt = compileSteeringPrompt(request);
        if (recovering) {
            if (logger.isInfoEnabled()) {
                logger.info("Recovering failed execution {} with fresh agent session", executionId);
            }
            dispatchAcpPrompt(execution, RECOVERY_PROMPT_PREFIX + compiledPrompt, true, true);
        } else {
            dispatchSteeringPrompt(execution, compiledPrompt);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchSystemSteering(UUID executionId, String promptText) {
        SandboxExecution execution =
                sandboxExecutionRepository.findById(executionId).orElse(null);
        if (execution == null || execution.getEnvironment() == null) {
            logger.warn("Cannot dispatch system steering: execution {} not found or has no environment", executionId);
            return;
        }
        if (execution.getStatus() != SandboxExecutionStatus.RUNNING
                && execution.getStatus() != SandboxExecutionStatus.IDLE) {
            logger.info("Skipping system steering for execution {} in state {}", executionId, execution.getStatus());
            return;
        }

        touchExecutionGraph(execution);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        sandboxExecutionRepository.save(execution);
        eventPublisher.publishEvent(new ExecutionStatusChangedEvent(
                execution.getChat().getTeam().getId(), execution.getChat().getId(), executionId));

        dispatchSteeringPrompt(execution, promptText);
    }

    public static String compileSteeringPrompt(SteerExecutionRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.comments() != null && !request.comments().isEmpty()) {
            sb.append("### Code Review Feedback\n\n");
            int idx = 1;
            for (SteerCommentDto comment : request.comments()) {
                sb.append(idx++).append(". **File:** `").append(comment.path()).append("`");
                if (comment.line() != null) {
                    sb.append(" (Line ").append(comment.line()).append(")");
                }
                sb.append("\n");
                if (comment.codeSnippet() != null && !comment.codeSnippet().isBlank()) {
                    sb.append("   > ")
                            .append(comment.codeSnippet().replace("\n", "\n   > "))
                            .append("\n");
                }
                sb.append("   **Feedback:** ").append(comment.comment()).append("\n\n");
            }
        }

        if (request.prompt() != null && !request.prompt().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("### Additional Guidance\n\n");
            }
            sb.append(request.prompt().trim());
        }

        return sb.toString();
    }

    private void failExecutionIfStillRunning(UUID executionId, String message) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                SandboxExecution execution =
                        sandboxExecutionRepository.findById(executionId).orElse(null);
                if (execution == null
                        || (execution.getStatus() != SandboxExecutionStatus.RUNNING
                                && execution.getStatus() != SandboxExecutionStatus.IDLE)) {
                    return;
                }
                logger.error(message);
                markFailedAndPublish(execution, message);
            });
        } catch (Exception e) {
            logger.error("Failed to mark execution {} failed after prompt dispatch error", executionId, e);
        }
    }

    @Transactional(readOnly = true)
    public List<SandboxExecutionDto> listExecutions(UUID userId, UUID chatId) {
        ChatEntity chat = chatRepository
                .findById(chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));

        requireTeamMembership(userId, chat.getTeam().getId());

        return sandboxExecutionRepository.findByChatIdOrderByStartedAtAsc(chatId).stream()
                .map(this::toDto)
                .toList();
    }

    public void terminateExecution(UUID userId, UUID chatId, UUID executionId) {
        // Validate execution exists
        SandboxExecution execution = sandboxExecutionRepository
                .findById(executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"));

        // Validate execution belongs to the specified chat
        if (!execution.getChat().getId().equals(chatId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found in this chat");
        }

        // Validate team membership
        UUID teamId = execution.getChat().getTeam().getId();
        requireTeamMembership(userId, teamId);

        // Delegate to the internal termination method
        terminateExecution(executionId);
    }

    public void terminateExecution(UUID executionId) {
        SandboxExecution execution = sandboxExecutionRepository
                .findById(executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"));

        if (execution.getStatus() != SandboxExecutionStatus.RUNNING
                && execution.getStatus() != SandboxExecutionStatus.IDLE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Execution is not running");
        }

        WebSocketSession envSession = sessionRegistry.getSessionForEnvironment(
                execution.getEnvironment().getId());
        if (envSession == null || !envSession.isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Environment WebSocket session is not connected");
        }

        // Cancel any pending permissions before terminating; this also updates the UI
        // activity log
        // and persisted activity rows via the CANCELLED resolution event.
        cancelPendingPermissions(execution, "execution terminated");

        try {
            EnvironmentConnectorResult.Terminate result = environmentRpcClient.request(
                    execution.getEnvironment().getId(),
                    new EnvironmentRpcPayload.Terminate(),
                    TERMINATE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            if (result == null) {
                logger.warn("Sidecar returned no terminate result for execution {}", execution.getId());
            } else {
                logger.info(
                        "Sent terminate to execution environment {} for execution {} (status {})",
                        execution.getEnvironment().getId(),
                        execution.getId(),
                        result.status());
            }
        } catch (Exception e) {
            logger.error("Failed to send terminate to environment", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to terminate execution");
        }
    }

    /**
     * Cancels any pending permission/elicitation request for an execution that is ending (user
     * terminate, force-fail, or natural completion). Removes the entry from the registry, notifies
     * the sidecar, and publishes a CANCELLED resolution so the UI activity log and the persisted
     * activity rows are updated for the whole team. Publishing inside a transaction (the default
     * {@code REQUIRED} template) keeps the {@code AFTER_COMMIT} fan-out in sync with the terminal
     * state change.
     */
    public void cancelPendingPermissions(SandboxExecution execution, String reason) {
        PendingHitlRegistry.PendingHitl pendingHitl = pendingHitlRegistry.remove(execution.getId());
        if (pendingHitl == null) {
            return;
        }
        logger.info(
                "Removed pending HITL request for execution {} (kind={}): {}",
                execution.getId(),
                pendingHitl.request().kind(),
                reason);
        UUID teamId = pendingHitl.teamId();
        transactionTemplate.executeWithoutResult(
                status -> eventPublisher.publishEvent(new SandboxExecutionHitlResolvedEvent(
                        teamId,
                        new ExecutionHitlResolvedResult(
                                execution.getId(),
                                pendingHitl.request().hitlId(),
                                pendingHitl.request().kind(),
                                HitlResponse.CANCELLED,
                                null,
                                null,
                                null,
                                null))));
        try {
            environmentRpcClient.replyError(
                    execution.getEnvironment().getId(),
                    pendingHitl.requestId(),
                    new JsonRpcError(-32000, "HITL request cancelled: " + reason, null));
        } catch (Exception e) {
            logger.error("Failed to send cancellation error to environment", e);
        }
    }

    public SandboxExecutionDto toDto(SandboxExecution execution) {
        return new SandboxExecutionDto(
                execution.getId(),
                execution.getChat().getId(),
                execution.getExitCode(),
                execution.getStatus(),
                execution.getStartedAt(),
                execution.getCompletedAt(),
                execution.getRepository() != null ? execution.getRepository().getId() : null,
                execution.getHarness(),
                execution.getTaskPrompt(),
                execution.getTotalTokens(),
                execution.getPromptTokens(),
                execution.getCompletionTokens(),
                execution.getTotalSpend(),
                execution.getUsage().getUsageLastUpdatedAt());
    }

    @Transactional
    public void completeAcpPrompt(UUID executionId, StopReason stopReason) {
        SandboxExecution execution = sandboxExecutionRepository
                .findById(executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"));

        if (execution.getStatus() != SandboxExecutionStatus.RUNNING
                && execution.getStatus() != SandboxExecutionStatus.IDLE) {
            logger.warn(
                    "completeAcpPrompt called for execution {} but status is already {}",
                    executionId,
                    execution.getStatus());
            return;
        }

        UUID teamId = execution.getChat().getTeam().getId();

        if (stopReason == StopReason.REFUSAL) {
            execution.setStatus(SandboxExecutionStatus.FAILED);
            execution.setCompletedAt(Instant.now());
            sandboxExecutionRepository.save(execution);
            cancelPendingPermissions(execution, "execution refused");
            activityPersistenceService.recordExecutionError(executionId, "agent refused the task", 0);

            eventPublisher.publishEvent(new SandboxExecutionCompleteEvent(
                    teamId, execution.getId(), 0, execution.getStatus(), "agent refused the task"));
            eventPublisher.publishEvent(
                    new ExecutionStatusChangedEvent(teamId, execution.getChat().getId(), execution.getId()));

            logger.info("ACP prompt refusal for execution {} → status=FAILED", executionId);
        } else {
            execution.setStatus(SandboxExecutionStatus.IDLE);
            sandboxExecutionRepository.save(execution);

            eventPublisher.publishEvent(
                    new ExecutionStatusChangedEvent(teamId, execution.getChat().getId(), execution.getId()));

            logger.info(
                    "ACP prompt turn complete for execution {} with stopReason='{}' → status=IDLE",
                    executionId,
                    stopReason != null ? stopReason.getValue() : null);
        }
    }

    @Transactional
    public void failExecution(UUID executionId) {
        SandboxExecution execution = sandboxExecutionRepository
                .findById(executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"));
        markFailedAndPublish(execution);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleEnvironmentRegistered(EnvironmentRegisteredEvent event) {
        logger.info("Handling environment registered event for environment {}", event.environmentId());

        List<SandboxExecution> pendingExecutions = sandboxExecutionRepository.findByEnvironmentIdAndStatus(
                event.environmentId(), SandboxExecutionStatus.RUNNING);
        if (pendingExecutions.isEmpty()) {
            return;
        }
        WebSocketSession envSession = sessionRegistry.getSessionForEnvironment(event.environmentId());
        if (envSession != null && envSession.isOpen()) {
            for (SandboxExecution exec : pendingExecutions) {
                dispatchExecution(exec, envSession);
            }
        } else {
            logger.warn(
                    "WebSocket session for environment {} not found or closed during asynchronous dispatch",
                    event.environmentId());
        }
    }

    private void handleExecutionFailure(UUID executionId, SandboxExecution fallback, String logMessage, Exception e) {
        logger.error(logMessage, e);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                SandboxExecution execution =
                        sandboxExecutionRepository.findById(executionId).orElse(null);
                if (execution == null && fallback != null) {
                    markFailedAndPublish(fallback, logMessage);
                    return;
                }
                if (execution == null) {
                    return;
                }
                markFailedAndPublish(execution, logMessage);
            });
        } catch (Exception nested) {
            if (fallback != null) {
                try {
                    transactionTemplate.executeWithoutResult(status -> markFailedAndPublish(fallback, logMessage));
                } catch (Exception ignored) {
                    // best effort for unit tests without a real TM
                }
            }
            logger.error("Failed to persist execution failure for {}", executionId, nested);
        }
    }

    private void handleExecutionFailure(SandboxExecution execution, String logMessage, Exception e) {
        handleExecutionFailure(execution.getId(), execution, logMessage, e);
    }

    private void markFailedAndPublish(SandboxExecution execution) {
        markFailedAndPublish(execution, null);
    }

    private void markFailedAndPublish(SandboxExecution execution, String reason) {
        execution.setStatus(SandboxExecutionStatus.FAILED);
        execution.setCompletedAt(Instant.now());
        sandboxExecutionRepository.save(execution);
        cancelPendingPermissions(execution, "execution failed");
        if (reason != null && !reason.isBlank()) {
            activityPersistenceService.recordExecutionError(execution.getId(), reason, null);
        }
        UUID teamId = execution.getChat().getTeam().getId();
        eventPublisher.publishEvent(
                new SandboxExecutionCompleteEvent(teamId, execution.getId(), -1, execution.getStatus(), reason));
        eventPublisher.publishEvent(
                new ExecutionStatusChangedEvent(teamId, execution.getChat().getId(), execution.getId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSandboxExecutionCompleteEvent(SandboxExecutionCompleteEvent event) {
        // FinalizeUsage blocks for 3+LiteLLM seconds. Don't block the sidecar-rpc
        // thread.
        dispatchExecutor.execute(() -> finalizeUsageAndRevokeKey(event.executionId()));
    }

    private void finalizeUsageAndRevokeKey(UUID executionId) {
        // Step-by-step to avoid holding a transaction open while we sleep.
        String token = null;
        try {
            SandboxExecution execution =
                    sandboxExecutionRepository.findById(executionId).orElse(null);
            if (execution != null && execution.getUsage() != null) {
                token = execution.getUsage().getVirtualKey();
            }
        } catch (Exception e) {
            logger.warn("Failed to retrieve virtual key for execution {}: {}", executionId, e.getMessage());
            return;
        }

        if (token == null) {
            return;
        }

        try {
            Thread.sleep(litellmProperties.getUsageFinalizeDelay().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while awaiting usage finalize delay for execution {}", executionId);
            return;
        }

        LlmUsageSnapshot snapshot = null;
        try {
            snapshot = virtualKeyService.fetchUsage(token);
        } catch (Exception e) {
            logger.warn("Failed to fetch final usage for execution {}: {}", executionId, e.getMessage());
        }

        if (snapshot != null) {
            final LlmUsageSnapshot finalSnapshot = snapshot;
            try {
                finalizeUsageTransactionTemplate.executeWithoutResult(status -> {
                    SandboxExecution execution = sandboxExecutionRepository
                            .findByIdForUpdate(executionId)
                            .orElse(null);
                    if (execution == null) {
                        return;
                    }
                    execution.getUsage().apply(finalSnapshot);
                    sandboxExecutionRepository.save(execution);
                    eventPublisher.publishEvent(new ExecutionStatusChangedEvent(
                            execution.getChat().getTeam().getId(),
                            execution.getChat().getId(),
                            executionId));
                });
            } catch (Exception e) {
                logger.error("Failed to persist final usage for execution {}", executionId, e);
            }
        }

        try {
            virtualKeyService.revokeKey(token);
        } catch (Exception e) {
            logger.warn("Failed to revoke virtual key for execution {}: {}", executionId, e.getMessage());
        }
    }
}
