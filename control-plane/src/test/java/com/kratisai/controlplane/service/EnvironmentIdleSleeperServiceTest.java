package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.EnvironmentProvider;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnvironmentIdleSleeperServiceTest {

    private static final UUID ENV_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private ExecutionEnvironmentRepository environmentRepository;

    @Mock
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Mock
    private ExecutionEnvironmentService executionEnvironmentService;

    private EnvironmentIdleSleeperService service;

    @BeforeEach
    void setUp() {
        service = new EnvironmentIdleSleeperService(
                environmentRepository, sandboxExecutionRepository, executionEnvironmentService, sandboxProperties(5));
    }

    private static SandboxProperties sandboxProperties(long idleMinutes) {
        SandboxProperties properties = new SandboxProperties();
        properties.getLifecycle().getSleep().setIdleTimeout(Duration.ofMinutes(idleMinutes));
        return properties;
    }

    private ExecutionEnvironment connectedSandbox() {
        Team team = new Team();
        team.setId(UUID.randomUUID());

        EnvironmentProvider provider = new EnvironmentProvider();
        provider.setId(UUID.randomUUID());
        provider.setTeam(team);

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(ENV_ID);
        env.setTeam(team);
        env.setName("Idle Sandbox");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.CONNECTED);
        env.setProvider(provider);
        return env;
    }

    private void stubEnvironments(ExecutionEnvironment... envs) {
        when(environmentRepository.findByStatus(EnvironmentStatus.CONNECTED)).thenReturn(List.of(envs));
    }

    private void stubNoRunningExecutions() {
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of());
    }

    private void stubRunningExecution() {
        SandboxExecution running = new SandboxExecution();
        running.setId(UUID.randomUUID());
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(running));
    }

    private void stubHasExecution() {
        SandboxExecution old = new SandboxExecution();
        old.setId(UUID.randomUUID());
        when(sandboxExecutionRepository.findFirstByEnvironmentIdOrderByStartedAtDesc(ENV_ID))
                .thenReturn(Optional.of(old));
    }

    private void stubNoExecutions() {
        when(sandboxExecutionRepository.findFirstByEnvironmentIdOrderByStartedAtDesc(ENV_ID))
                .thenReturn(Optional.empty());
    }

    private void stubContainerRunning() {
        when(executionEnvironmentService.isContainerRunning(any(ExecutionEnvironment.class)))
                .thenReturn(true);
    }

    private void stubContainerStopped() {
        when(executionEnvironmentService.isContainerRunning(any(ExecutionEnvironment.class)))
                .thenReturn(false);
    }

    private EnvironmentIdleSleeperService zeroThresholdService() {
        return new EnvironmentIdleSleeperService(
                environmentRepository, sandboxExecutionRepository, executionEnvironmentService, sandboxProperties(0));
    }

    @Test
    void suspendsEnvironmentOnceIdleThresholdElapses() {
        ExecutionEnvironment env = connectedSandbox();
        stubEnvironments(env);
        stubNoRunningExecutions();
        stubHasExecution();
        stubContainerRunning();

        // First pass starts the idle countdown; a real threshold later elapses. A zero-minute
        // threshold simulates the elapsed countdown on the next scheduled pass.
        zeroThresholdService().sleepIdleEnvironments();

        verify(executionEnvironmentService).sleepEnvironmentInternal(env);
    }

    @Test
    void doesNotSuspendBeforeIdleThresholdElapses() {
        ExecutionEnvironment env = connectedSandbox();
        stubEnvironments(env);
        stubNoRunningExecutions();
        stubHasExecution();

        service.sleepIdleEnvironments();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
    }

    @Test
    void neverSuspendsEnvironmentWithRunningExecution() {
        ExecutionEnvironment env = connectedSandbox();
        stubEnvironments(env);
        stubRunningExecution();

        zeroThresholdService().sleepIdleEnvironments();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
    }

    @Test
    void neverSuspendsEnvironmentWithoutExecutions() {
        ExecutionEnvironment env = connectedSandbox();
        stubEnvironments(env);
        stubNoRunningExecutions();
        stubNoExecutions();

        zeroThresholdService().sleepIdleEnvironments();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
    }

    @Test
    void skipsConnectorEnvironments() {
        ExecutionEnvironment env = connectedSandbox();
        env.setType(ExecutionEnvironmentType.CONNECTOR);
        stubEnvironments(env);

        service.sleepIdleEnvironments();

        verifyNoInteractions(sandboxExecutionRepository);
        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
    }

    @Test
    void continuesPastFailingEnvironment() {
        ExecutionEnvironment env = connectedSandbox();
        stubEnvironments(env);
        stubNoRunningExecutions();
        stubHasExecution();
        stubContainerRunning();
        doThrow(new IllegalStateException("docker unavailable"))
                .when(executionEnvironmentService)
                .sleepEnvironmentInternal(env);

        assertThatCode(zeroThresholdService()::sleepIdleEnvironments).doesNotThrowAnyException();
    }

    @Test
    void marksStoppedContainerDisconnectedWithoutSuspending() {
        ExecutionEnvironment env = connectedSandbox();
        stubEnvironments(env);
        stubNoRunningExecutions();
        stubHasExecution();
        stubContainerStopped();

        zeroThresholdService().sleepIdleEnvironments();

        verify(executionEnvironmentService, never()).sleepEnvironmentInternal(any());
        verify(executionEnvironmentService).handleStoppedContainer(env);
    }
}
