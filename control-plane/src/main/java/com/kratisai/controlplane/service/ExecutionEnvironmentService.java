package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.restdto.CreateExecutionEnvironmentRequest;
import com.kratisai.controlplane.api.restdto.CreateExecutionEnvironmentResponse;
import com.kratisai.controlplane.api.restdto.ExecutionEnvironmentDto;
import com.kratisai.controlplane.api.restdto.UpdateExecutionEnvironmentRequest;
import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.SandboxExecutionUsage;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.model.event.EnvironmentDisconnectedEvent;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionSuspendedEvent;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExecutionEnvironmentService {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionEnvironmentService.class);

    private static final Duration HEARTBEAT_STALE_AFTER = Duration.ofSeconds(60);

    private static final String UNAVAILABLE_ENVIRONMENT_REASON =
            "execution abandoned because its environment is no longer available";

    private static final String PURGED_ENVIRONMENT_REASON = "Sandbox resources found to have been unexpectedly purged";

    private final ExecutionEnvironmentRepository executionEnvironmentRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SandboxOrchestratorService sandboxOrchestratorService;
    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final VirtualKeyService virtualKeyService;
    private final SandboxExecutionService sandboxExecutionService;
    private final SandboxProvisioningService sandboxProvisioningService;
    private final ExecutionActivityPersistenceService executionActivityPersistenceService;
    private final String connectUrl;
    private final TransactionTemplate terminationTemplate;
    private final TransactionTemplate resumeIntentTemplate;

    public ExecutionEnvironmentService(
            ExecutionEnvironmentRepository executionEnvironmentRepository,
            TeamMemberRepository teamMemberRepository,
            TeamRepository teamRepository,
            UserRepository userRepository,
            ApplicationEventPublisher eventPublisher,
            SandboxOrchestratorService sandboxOrchestratorService,
            SandboxExecutionRepository sandboxExecutionRepository,
            VirtualKeyService virtualKeyService,
            SandboxExecutionService sandboxExecutionService,
            SandboxProvisioningService sandboxProvisioningService,
            ExecutionActivityPersistenceService executionActivityPersistenceService,
            SandboxProperties sandboxProperties,
            PlatformTransactionManager transactionManager) {
        this.executionEnvironmentRepository = executionEnvironmentRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.sandboxOrchestratorService = sandboxOrchestratorService;
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.virtualKeyService = virtualKeyService;
        this.sandboxExecutionService = sandboxExecutionService;
        this.sandboxProvisioningService = sandboxProvisioningService;
        this.executionActivityPersistenceService = executionActivityPersistenceService;
        this.connectUrl = sandboxProperties.getConnectUrl();
        this.terminationTemplate = new TransactionTemplate(transactionManager);
        this.terminationTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.resumeIntentTemplate = new TransactionTemplate(transactionManager);
        this.resumeIntentTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private void requireTeamMembership(UUID userId, UUID teamId) {
        boolean isMember = teamMemberRepository.existsByTeamIdAndUserId(teamId, userId);
        if (!isMember) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }

    public ExecutionEnvironmentDto toDto(ExecutionEnvironment env) {
        return toDto(env, null, null);
    }

    public ExecutionEnvironmentDto toDto(
            ExecutionEnvironment env, Instant lastActivity, SandboxExecution activeExecution) {
        return new ExecutionEnvironmentDto(
                env.getId(),
                env.getTeam().getId(),
                env.getName(),
                env.getType(),
                env.getAuthToken(),
                env.getStatus(),
                env.getLastHeartbeat(),
                isConnected(env),
                lastActivity,
                activeExecution != null ? activeExecution.getId() : null,
                activeExecution != null ? activeExecution.getChat().getId() : null);
    }

    private boolean isDeletable(ExecutionEnvironment env) {
        return switch (env.getType()) {
            case SANDBOX -> env.getStatus() == EnvironmentStatus.TERMINATED;
            case CONNECTOR -> !isConnected(env) && findActiveExecution(env.getId()) == null;
        };
    }

    private static boolean isConnected(ExecutionEnvironment env) {
        Instant heartbeat = env.getLastHeartbeat();
        return env.getStatus() == EnvironmentStatus.CONNECTED
                && heartbeat != null
                && Duration.between(heartbeat, Instant.now()).compareTo(HEARTBEAT_STALE_AFTER) <= 0;
    }

    public List<ExecutionEnvironmentDto> listEnvironments(UUID userId, UUID teamId) {
        requireTeamMembership(userId, teamId);
        return executionEnvironmentRepository.findByTeamIdOrderByLatestActivityDesc(teamId).stream()
                .map(row -> {
                    ExecutionEnvironment env = (ExecutionEnvironment) row[0];
                    return toDto(env, (Instant) row[1], findActiveExecution(env.getId()));
                })
                .toList();
    }

    private SandboxExecution findActiveExecution(UUID environmentId) {
        return sandboxExecutionRepository
                .findFirstByEnvironmentIdOrderByStartedAtDesc(environmentId)
                .filter(execution -> execution.getStatus() == SandboxExecutionStatus.RUNNING
                        || execution.getStatus() == SandboxExecutionStatus.IDLE)
                .orElse(null);
    }

    @Transactional
    public CreateExecutionEnvironmentResponse createConnector(
            UUID userId, UUID teamId, CreateExecutionEnvironmentRequest request) {
        requireTeamMembership(userId, teamId);

        Team team = teamRepository
                .findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));

        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String authToken = UUID.randomUUID().toString();
        String installCommand =
                String.format("kratis-connector --mode=daemon --server-url=%s --token=%s", connectUrl, authToken);

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(team);
        env.setUser(user);
        env.setName(request.name());
        env.setType(ExecutionEnvironmentType.CONNECTOR);
        transition(env, EnvironmentStatus.PENDING_CONNECT);
        env.setAuthToken(authToken);

        ExecutionEnvironment savedEnv = executionEnvironmentRepository.save(env);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.ENVIRONMENTS));
        return new CreateExecutionEnvironmentResponse(toDto(savedEnv), installCommand);
    }

    @Transactional
    public ExecutionEnvironmentDto updateEnvironment(
            UUID userId, UUID teamId, UUID envId, UpdateExecutionEnvironmentRequest request) {
        requireTeamMembership(userId, teamId);

        ExecutionEnvironment env = executionEnvironmentRepository
                .findByTeamIdAndId(teamId, envId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found"));

        if (request.name() != null) {
            env.setName(request.name());
        }

        executionEnvironmentRepository.save(env);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.ENVIRONMENTS));
        return toDto(env);
    }

    private void forceTerminateExecution(UUID executionId, String reason) {
        SandboxExecution execution =
                sandboxExecutionRepository.findById(executionId).orElse(null);
        if (execution == null
                || (execution.getStatus() != SandboxExecutionStatus.RUNNING
                        && execution.getStatus() != SandboxExecutionStatus.IDLE)) {
            return;
        }
        execution.setStatus(SandboxExecutionStatus.UNKNOWN);
        execution.setCompletedAt(Instant.now());
        sandboxExecutionRepository.save(execution);
        executionActivityPersistenceService.recordExecutionError(executionId, reason, null);

        SandboxExecutionUsage activeUsage = execution.getActiveUsage();
        if (activeUsage != null && activeUsage.getVirtualKey() != null) {
            try {
                virtualKeyService.revokeKey(activeUsage.getVirtualKey());
                activeUsage.setRevokedAt(Instant.now());
                logger.info("Revoked virtual key for execution {}", execution.getId());
            } catch (Exception e) {
                logger.error("Failed to revoke virtual key for execution {}", execution.getId(), e);
            }
        }

        sandboxExecutionService.cancelPendingPermissions(execution, "environment terminated");

        UUID teamId = execution.getChat().getTeam().getId();
        eventPublisher.publishEvent(
                new SandboxExecutionCompleteEvent(teamId, execution.getId(), -1, execution.getStatus(), reason));
        eventPublisher.publishEvent(
                new ExecutionStatusChangedEvent(teamId, execution.getChat().getId(), execution.getId()));
    }

    @Transactional
    public void deleteEnvironment(UUID userId, UUID teamId, UUID envId) {
        requireTeamMembership(userId, teamId);

        ExecutionEnvironment env = executionEnvironmentRepository
                .findByTeamIdAndId(teamId, envId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found"));

        if (!isDeletable(env)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Environment cannot be deleted in its current state");
        }

        if (env.getType() == ExecutionEnvironmentType.SANDBOX && env.getProvider() != null) {
            try {
                SandboxProvider sandboxProvider =
                        sandboxOrchestratorService.getProvider(env.getProvider().getType());
                sandboxProvider.destroy(envId);
            } catch (Exception e) {
                logger.warn("Failed to destroy sandbox environment {}: {}", envId, e.getMessage());
            }
        }

        executionEnvironmentRepository.delete(env);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.ENVIRONMENTS));
    }

    public boolean isSandboxRunning(ExecutionEnvironment env) {
        if (env.getProvider() == null) {
            return false;
        }
        SandboxProvider sandboxProvider =
                sandboxOrchestratorService.getProvider(env.getProvider().getType());
        return sandboxProvider.isSandboxRunning(env.getId());
    }

    @Transactional
    public void failStrandedExecution(UUID executionId) {
        terminationTemplate.executeWithoutResult(
                status -> forceTerminateExecution(executionId, UNAVAILABLE_ENVIRONMENT_REASON));
    }

    @Transactional
    public void sleepEnvironmentInternal(ExecutionEnvironment env) {
        if (env.getType() != ExecutionEnvironmentType.SANDBOX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Environment is not a sandbox");
        }
        if (env.getStatus() == EnvironmentStatus.SLEEPING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Environment is already sleeping");
        }
        if (env.getStatus() != EnvironmentStatus.CONNECTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Environment is not a running sandbox");
        }
        if (env.getProvider() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Environment has no configured sandbox provider");
        }

        UUID envId = env.getId();
        UUID teamId = env.getTeam().getId();

        try {
            SandboxProvider sandboxProvider =
                    sandboxOrchestratorService.getProvider(env.getProvider().getType());
            sandboxProvider.suspend(envId);
            logger.debug("Suspend committed snapshot for env {}; marking SLEEPING", envId);
        } catch (Exception e) {
            logger.error("Failed to suspend sandbox for environment {}", envId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to suspend environment");
        }

        markSuspended(env);
        executionEnvironmentRepository.save(env);

        sandboxExecutionRepository
                .findFirstByEnvironmentIdOrderByStartedAtDesc(envId)
                .ifPresent(execution -> {
                    executionActivityPersistenceService.recordHarnessSleep(execution.getId());
                    eventPublisher.publishEvent(new ExecutionStatusChangedEvent(
                            teamId, execution.getChat().getId(), execution.getId()));
                    eventPublisher.publishEvent(new SandboxExecutionSuspendedEvent(teamId, execution.getId(), envId));
                });

        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.ENVIRONMENTS));
        toDto(env);
    }

    @Transactional
    public ExecutionEnvironmentDto resumeEnvironment(UUID userId, UUID teamId, UUID envId) {
        requireTeamMembership(userId, teamId);

        ExecutionEnvironment env = executionEnvironmentRepository
                .findByTeamIdAndId(teamId, envId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found"));

        if (env.getStatus() == EnvironmentStatus.TERMINATED) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, "Environment was terminated after its suspend snapshot expired");
        }
        if (env.getType() != ExecutionEnvironmentType.SANDBOX || env.getStatus() != EnvironmentStatus.SLEEPING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Environment is not sleeping");
        }
        if (env.getProvider() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Environment has no configured sandbox provider");
        }

        // Two-phase resume: the intent must commit before any Docker call so the
        // connector's registration cannot be overwritten by this transaction.
        resumeIntentTemplate.executeWithoutResult(status -> commitResumeIntent(teamId, envId));

        try {
            sandboxProvisioningService.reprovisionEnvironment(env);
        } catch (ResponseStatusException e) {
            resumeIntentTemplate.executeWithoutResult(status -> rollbackResume(envId, teamId));
            throw e;
        } catch (Exception e) {
            logger.error("Failed to resume sandbox for environment {}", envId, e);
            resumeIntentTemplate.executeWithoutResult(status -> rollbackResume(envId, teamId));
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to resume environment");
        }
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.ENVIRONMENTS));

        sandboxExecutionRepository
                .findFirstByEnvironmentIdOrderByStartedAtDesc(envId)
                .ifPresent(execution -> {
                    executionActivityPersistenceService.recordHarnessResume(execution.getId());
                    eventPublisher.publishEvent(new ExecutionStatusChangedEvent(
                            teamId, execution.getChat().getId(), execution.getId()));
                });

        // The outer transaction loaded `env` before the resume. Read the row in
        // a new transaction so the response reflects the committed status.
        return resumeIntentTemplate.execute(status -> executionEnvironmentRepository
                .findByTeamIdAndId(teamId, envId)
                .map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found")));
    }

    /**
     * Commits PENDING_CONNECT ahead of the Docker spawn. Call through {@link #resumeIntentTemplate};
     * a self-invocation here would join the caller's transaction and let its late PENDING_CONNECT
     * write overwrite the connector's CONNECTED registration.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitResumeIntent(UUID teamId, UUID envId) {
        ExecutionEnvironment env = executionEnvironmentRepository
                .findByTeamIdAndId(teamId, envId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found"));
        transition(env, EnvironmentStatus.PENDING_CONNECT);
        logger.debug("Resumed env {}; idle and retention timers reset", envId);
        executionEnvironmentRepository.save(env);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.ENVIRONMENTS));
    }

    /**
     * Undoes a resume whose spawn failed: any half-spawned resources go, and the SLEEPING row
     * keeps its suspended state for another attempt.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rollbackResume(UUID envId, UUID teamId) {
        try {
            sandboxOrchestratorService
                    .getProvider(executionEnvironmentRepository
                            .findById(envId)
                            .orElseThrow(
                                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found"))
                            .getProvider()
                            .getType())
                    .terminateSandbox(envId);
        } catch (Exception e) {
            logger.warn("Resume rollback could not tear down environment {}: {}", envId, e.getMessage());
        }
        executionEnvironmentRepository.findByTeamIdAndId(teamId, envId).ifPresent(env -> {
            rollbackFailedResume(env);
            logger.debug("Rolled back failed resume of env {}; back to SLEEPING", envId);
        });
    }

    /** A resume that never connected goes back to SLEEPING, keeping its snapshot for another attempt. */
    @Transactional
    public void rollbackFailedResume(ExecutionEnvironment env) {
        markSuspended(env);
        executionEnvironmentRepository.save(env);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(env.getTeam().getId(), TeamEntityType.ENVIRONMENTS));
    }

    /** Destroys a suspend snapshot that outlived its retention window. */
    @Transactional
    public void expireEnvironment(ExecutionEnvironment env) {
        UUID envId = env.getId();
        UUID teamId = env.getTeam().getId();
        logger.debug("Expiring env {} stateChangedAt={}", envId, env.getStateChangedAt());
        SandboxProvider sandboxProvider =
                sandboxOrchestratorService.getProvider(env.getProvider().getType());
        sandboxProvider.destroy(envId);
        transition(env, EnvironmentStatus.TERMINATED);
        executionEnvironmentRepository.save(env);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.ENVIRONMENTS));
        terminateActiveExecution(envId, UNAVAILABLE_ENVIRONMENT_REASON);
    }

    @Transactional
    public void reconcilePurgedEnvironment(ExecutionEnvironment env) {
        UUID envId = env.getId();
        UUID teamId = env.getTeam().getId();
        logger.debug("Reconciling env {} status={} as purged (no runtime, no snapshot)", envId, env.getStatus());
        transition(env, EnvironmentStatus.TERMINATED);
        executionEnvironmentRepository.save(env);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.ENVIRONMENTS));

        SandboxExecution active = findActiveExecution(envId);
        if (active != null) {
            terminationTemplate.executeWithoutResult(
                    status -> forceTerminateExecution(active.getId(), PURGED_ENVIRONMENT_REASON));
            return;
        }

        sandboxExecutionRepository
                .findFirstByEnvironmentIdOrderByStartedAtDesc(envId)
                .ifPresent(execution -> {
                    executionActivityPersistenceService.recordSandboxPurged(execution.getId());
                    eventPublisher.publishEvent(new ExecutionStatusChangedEvent(
                            teamId, execution.getChat().getId(), execution.getId()));
                });
    }

    private static void transition(ExecutionEnvironment env, EnvironmentStatus status) {
        env.setStatus(status);
        env.setStateChangedAt(Instant.now());
    }

    private static void markSuspended(ExecutionEnvironment env) {
        transition(env, EnvironmentStatus.SLEEPING);
    }

    /**
     * Lost runtime that keeps its snapshot: the environment returns to SLEEPING for another
     * resume attempt. RUNNING executions move to UNKNOWN; IDLE executions are unchanged.
     */
    @Transactional
    public void suspendAfterLostRuntime(ExecutionEnvironment env) {
        rollbackFailedResume(env);
        SandboxExecution active = findActiveExecution(env.getId());
        if (active != null) {
            if (active.getStatus() == SandboxExecutionStatus.RUNNING) {
                terminationTemplate.executeWithoutResult(
                        status -> forceTerminateExecution(active.getId(), UNAVAILABLE_ENVIRONMENT_REASON));
            } else if (active.getStatus() == SandboxExecutionStatus.IDLE) {
                eventPublisher.publishEvent(new SandboxExecutionSuspendedEvent(
                        active.getChat().getTeam().getId(), active.getId(), env.getId()));
            }
        }
    }

    private void terminateActiveExecution(UUID envId, String reason) {
        SandboxExecution active = findActiveExecution(envId);
        if (active != null) {
            terminationTemplate.executeWithoutResult(status -> forceTerminateExecution(active.getId(), reason));
        }
    }

    // Disconnect is display-only: it never changes lifecycle state. The DTO derives `connected`
    // from a stale heartbeat, and a reconnect simply refreshes it.
    @EventListener
    @Transactional
    public void handleEnvironmentDisconnected(EnvironmentDisconnectedEvent event) {
        executionEnvironmentRepository
                .findById(event.environmentId())
                .ifPresent(env -> eventPublisher.publishEvent(
                        new TeamEntityChangedEvent(env.getTeam().getId(), TeamEntityType.ENVIRONMENTS)));
    }
}
