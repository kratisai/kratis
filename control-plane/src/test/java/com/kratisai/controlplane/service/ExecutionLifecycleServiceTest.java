package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionLifecycleServiceTest {

    private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EXECUTION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Mock
    private PendingHitlRegistry pendingHitlRegistry;

    private ExecutionLifecycleService service;
    private SandboxExecution execution;

    @BeforeEach
    void setUp() {
        service = new ExecutionLifecycleService(sandboxExecutionRepository, pendingHitlRegistry);
        execution = new SandboxExecution();
        execution.setId(EXECUTION_ID);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
    }

    @Test
    void hasPendingHitl_trueWhenAnyExecutionHasPendingRequest() {
        SandboxExecution idle = new SandboxExecution();
        idle.setId(UUID.randomUUID());
        idle.setStatus(SandboxExecutionStatus.IDLE);
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of(execution, idle));
        when(pendingHitlRegistry.hasPendingForExecution(EXECUTION_ID)).thenReturn(false);
        when(pendingHitlRegistry.hasPendingForExecution(idle.getId())).thenReturn(true);

        assertThat(service.hasPendingHitl(ENV_ID)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasPendingHitl_queriesRunningAndIdleExecutions() {
        when(sandboxExecutionRepository.findByEnvironmentIdAndStatusIn(eq(ENV_ID), any()))
                .thenReturn(List.of());
        ArgumentCaptor<Collection<SandboxExecutionStatus>> statuses = ArgumentCaptor.forClass(Collection.class);

        assertThat(service.hasPendingHitl(ENV_ID)).isFalse();

        verify(sandboxExecutionRepository).findByEnvironmentIdAndStatusIn(eq(ENV_ID), statuses.capture());
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(SandboxExecutionStatus.RUNNING, SandboxExecutionStatus.IDLE);
    }

    @Test
    void hasPendingHitl_falseForNullEnvironment() {
        assertThat(service.hasPendingHitl(null)).isFalse();
    }

    @Test
    void hasRunningExecutions_delegatesToRepository() {
        when(sandboxExecutionRepository.existsByEnvironmentIdAndStatus(ENV_ID, SandboxExecutionStatus.RUNNING))
                .thenReturn(true);

        assertThat(service.hasRunningExecutions(ENV_ID)).isTrue();
        assertThat(service.hasRunningExecutions(null)).isFalse();
    }
}
