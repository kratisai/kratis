package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionLifecycleServiceTest {

    private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Mock
    private SandboxExecutionActivityRepository activityRepository;

    private ExecutionLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new ExecutionLifecycleService(sandboxExecutionRepository, activityRepository);
    }

    @Test
    void hasPendingHitl_queriesPersistedPendingActivities() {
        when(activityRepository.existsByEnvironmentIdAndStatus(ENV_ID, ActivityStatus.PENDING))
                .thenReturn(true);

        assertThat(service.hasPendingHitl(ENV_ID)).isTrue();
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
