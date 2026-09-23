package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.restdto.CreateExecutionEnvironmentRequest;
import com.kratisai.controlplane.api.restdto.CreateExecutionEnvironmentResponse;
import com.kratisai.controlplane.api.restdto.ExecutionEnvironmentDto;
import com.kratisai.controlplane.api.restdto.UpdateExecutionEnvironmentRequest;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.model.event.EnvironmentDisconnectedEvent;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketSession;

@Service
public class ExecutionEnvironmentService {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionEnvironmentService.class);

    private final ExecutionEnvironmentRepository executionEnvironmentRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SandboxOrchestratorService sandboxOrchestratorService;
    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final EnvironmentSessionRegistry sessionRegistry;
    private final VirtualKeyService virtualKeyService;
    private final SandboxExecutionService sandboxExecutionService;
    private final ExecutionActivityPersistenceService executionActivityPersistenceService;
    private final String websocketUrl;
    private final TransactionTemplate terminationTemplate;
    private final TransactionTemplate pollTemplate;

    public ExecutionEnvironmentService(
            ExecutionEnvironmentRepository executionEnvironmentRepository,
            TeamMemberRepository teamMemberRepository,
            TeamRepository teamRepository,
            UserRepository userRepository,
            ApplicationEventPublisher eventPublisher,
            SandboxOrchestratorService sandboxOrchestratorService,
            SandboxExecutionRepository sandboxExecutionRepository,
            EnvironmentSessionRegistry sessionRegistry,
            VirtualKeyService virtualKeyService,
            SandboxExecutionService sandboxExecutionService,
            ExecutionActivityPersistenceService executionActivityPersistenceService,
            @Value("${kratis.server.websocket.url:ws://localhost:8080/ws/env}") String websocketUrl,
            PlatformTransactionManager transactionManager) {
        this.executionEnvironmentRepository = executionEnvironmentRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.sandboxOrchestratorService = sandboxOrchestratorService;
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.sessionRegistry = sessionRegistry;
        this.virtualKeyService = virtualKeyService;
        this.sandboxExecutionService = sandboxExecutionService;
        this.executionActivityPersistenceService = executionActivityPersistenceService;
        this.websocketUrl = websocketUrl;
        this.terminationTemplate = new TransactionTemplate(transactionManager);
        this.terminationTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.pollTemplate = new TransactionTemplate(transactionManager);
        this.pollTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private void requireTeamMembership(UUID userId, UUID teamId) {
        boolean isMember = teamMemberRepository.existsByTeamIdAndUserId(teamId, userId);
        if (!isMember) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }

    public ExecutionEnvironmentDto toDto(ExecutionEnvironment env) {
        return new ExecutionEnvironmentDto(
                env.getId(),
                env.getTeam().getId(),
                env.getName(),
                env.getType(),
                env.getAuthToken(),
                env.getStatus(),
                env.getContainerId(),
                env.getLastHeartbeat());
    }

    public List<ExecutionEnvironmentDto> listEnvironments(UUID userId, UUID teamId) {
        requireTeamMembership(userId, teamId);
        return executionEnvironmentRepository.findByTeamId(teamId).stream()
                .map(this::toDto)
                .toList();
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
                String.format("kratis-connector --mode=daemon --server-url=%s --token=%s", websocketUrl, authToken);

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(team);
        env.setUser(user);
        env.setName(request.name());
        env.setType(ExecutionEnvironmentType.CONNECTOR);
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env.setAuthToken(authToken);
        // containerId is null until the agent connects and registers

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

    @Transactional
    public void terminateEnvironment(UUID userId, UUID teamId, UUID envId) {
        requireTeamMembership(userId, teamId);

        ExecutionEnvironment env = executionEnvironmentRepository
                .findByTeamIdAndId(teamId, envId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found"));

        List<SandboxExecution> runningExecs = sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(
                envId, List.of(SandboxExecutionStatus.RUNNING, SandboxExecutionStatus.IDLE));

        if (!runningExecs.isEmpty()) {
            terminateRunningExecution(runningExecs.getFirst().getId(), envId);
        }

        if (env.getType() == ExecutionEnvironmentType.SANDBOX && env.getContainerId() != null) {
            if (env.getProvider() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Environment has no configured sandbox provider");
            }
            try {
                SandboxProvider sandboxProvider =
                        sandboxOrchestratorService.getProvider(env.getProvider().getType());
                sandboxProvider.terminateSandbox(env.getContainerId());
                env.setStatus(EnvironmentStatus.DISCONNECTED);
                env.setContainerId(null);
                executionEnvironmentRepository.save(env);
                eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.ENVIRONMENTS));
            } catch (Exception e) {
                logger.error("Failed to terminate sandbox container {}", env.getContainerId(), e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to terminate environment");
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Environment is not a running sandbox");
        }
    }

    /**
     * Terminates the running execution in its own {@code REQUIRES_NEW} transaction so the terminal
     * status write and its events commit independently of the container teardown that follows. A
     * teardown failure can therefore never leave the execution stuck in RUNNING with no event for
     * the UI.
     */
    private void terminateRunningExecution(UUID executionId, UUID envId) {
        terminationTemplate.executeWithoutResult(status -> {
            SandboxExecution execution =
                    sandboxExecutionRepository.findById(executionId).orElse(null);
            if (execution == null
                    || (execution.getStatus() != SandboxExecutionStatus.RUNNING
                            && execution.getStatus() != SandboxExecutionStatus.IDLE)) {
                return;
            }
            WebSocketSession envSession = sessionRegistry.getSessionForEnvironment(envId);

            if (envSession != null && envSession.isOpen()) {
                try {
                    sandboxExecutionService.terminateExecution(executionId);
                    boolean completed = waitForExecutionCompletion(executionId, Duration.ofSeconds(10));
                    if (completed) {
                        logger.info("Execution {} terminated gracefully", executionId);
                    } else {
                        logger.warn("Execution {} did not complete within timeout, forcing termination", executionId);
                        forceTerminateExecution(executionId);
                    }
                } catch (Exception e) {
                    logger.error("Failed to gracefully terminate execution {}", executionId, e);
                    forceTerminateExecution(executionId);
                }
            } else {
                logger.info("Environment {} is disconnected, force terminating execution {}", envId, executionId);
                forceTerminateExecution(executionId);
            }
        });
    }

    /**
     * Polls for the execution reaching a non-RUNNING state. Each read runs in its own {@code
     * REQUIRES_NEW} transaction so the poll observes the {@code env.complete} handler's committed
     * update instead of the caller's first-level cache.
     */
    private boolean waitForExecutionCompletion(UUID executionId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Boolean completed = pollTemplate.execute(status -> sandboxExecutionRepository
                    .findById(executionId)
                    .map(execution -> execution.getStatus() != SandboxExecutionStatus.RUNNING
                            && execution.getStatus() != SandboxExecutionStatus.IDLE)
                    .orElse(true));
            if (completed) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void forceTerminateExecution(UUID executionId) {
        SandboxExecution execution =
                sandboxExecutionRepository.findById(executionId).orElse(null);
        if (execution == null
                || (execution.getStatus() != SandboxExecutionStatus.RUNNING
                        && execution.getStatus() != SandboxExecutionStatus.IDLE)) {
            return;
        }
        execution.setStatus(SandboxExecutionStatus.FAILED);
        execution.setCompletedAt(Instant.now());
        sandboxExecutionRepository.save(execution);
        String reason = "execution force-terminated because its environment was deleted";
        executionActivityPersistenceService.recordExecutionError(executionId, reason, 1);

        if (execution.getVirtualKey() != null) {
            try {
                virtualKeyService.revokeKey(execution.getVirtualKey());
                logger.info("Revoked virtual key for execution {}", execution.getId());
            } catch (Exception e) {
                logger.error("Failed to revoke virtual key for execution {}", execution.getId(), e);
            }
        }

        sandboxExecutionService.cancelPendingPermissions(execution, "environment terminated");

        UUID teamId = execution.getChat().getTeam().getId();
        eventPublisher.publishEvent(
                new SandboxExecutionCompleteEvent(teamId, execution.getId(), 1, execution.getStatus(), reason));
        eventPublisher.publishEvent(
                new ExecutionStatusChangedEvent(teamId, execution.getChat().getId(), execution.getId()));
    }

    @Transactional
    public void deleteEnvironment(UUID userId, UUID teamId, UUID envId) {
        requireTeamMembership(userId, teamId);

        ExecutionEnvironment env = executionEnvironmentRepository
                .findByTeamIdAndId(teamId, envId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found"));

        // Terminate container if it's a sandbox with a container ID
        if (env.getType() == ExecutionEnvironmentType.SANDBOX && env.getContainerId() != null) {
            try {
                SandboxProvider sandboxProvider =
                        sandboxOrchestratorService.getProvider(env.getProvider().getType());
                sandboxProvider.terminateSandbox(env.getContainerId());
            } catch (Exception e) {
                logger.warn("Failed to terminate sandbox container {}: {}", env.getContainerId(), e.getMessage());
                // Continue with deletion even if termination fails
            }
        }

        executionEnvironmentRepository.delete(env);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.ENVIRONMENTS));
    }

    @EventListener
    @Transactional
    public void handleEnvironmentDisconnected(EnvironmentDisconnectedEvent event) {
        executionEnvironmentRepository.findById(event.environmentId()).ifPresent(env -> {
            env.setStatus(EnvironmentStatus.DISCONNECTED);
            executionEnvironmentRepository.save(env);
            eventPublisher.publishEvent(new TeamEntityChangedEvent(env.getTeam().getId(), TeamEntityType.ENVIRONMENTS));
        });
    }
}
