package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.EnvironmentProvider;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.ExecutionProviderType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class SnapshotRetentionServiceTest {

    @Mock
    private ExecutionEnvironmentRepository environmentRepository;

    @Mock
    private SandboxOrchestratorService sandboxOrchestratorService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SandboxProvider sandboxProvider;

    private SnapshotRetentionService service;

    @BeforeEach
    void setUp() {
        service = new SnapshotRetentionService(
                environmentRepository, sandboxOrchestratorService, eventPublisher, new SandboxProperties());
    }

    private ExecutionEnvironment sleepingEnvironment(Instant lastHeartbeat) {
        Team team = new Team();
        team.setId(UUID.randomUUID());

        EnvironmentProvider provider = new EnvironmentProvider();
        provider.setId(UUID.randomUUID());
        provider.setTeam(team);

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(UUID.randomUUID());
        env.setTeam(team);
        env.setName("Sleeper");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.SLEEPING);
        env.setLastHeartbeat(lastHeartbeat);
        env.setProvider(provider);
        return env;
    }

    @Test
    void expireStaleSnapshots_terminatesExpiredSleepersAndRemovesSnapshot() {
        ExecutionEnvironment env = sleepingEnvironment(Instant.now().minus(Duration.ofDays(31)));
        when(environmentRepository.findExpiredSleeping(any(Instant.class))).thenReturn(List.of(env));
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        service.expireStaleSnapshots();

        verify(sandboxProvider).destroy(env.getId().toString());
        verify(environmentRepository).save(env);
        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.TERMINATED);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anyMatch(TeamEntityChangedEvent.class::isInstance);
    }

    @Test
    void expireStaleSnapshots_noExpiredEnvironmentsChangesNothing() {
        when(environmentRepository.findExpiredSleeping(any(Instant.class))).thenReturn(List.of());

        service.expireStaleSnapshots();

        verify(environmentRepository, never()).save(any(ExecutionEnvironment.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void expireStaleSnapshots_nullHeartbeatCountsAsUnused() {
        ExecutionEnvironment env = sleepingEnvironment(null);
        when(environmentRepository.findExpiredSleeping(any(Instant.class))).thenReturn(List.of(env));
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);

        service.expireStaleSnapshots();

        verify(sandboxProvider).destroy(env.getId().toString());
        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.TERMINATED);
    }

    @Test
    void expireStaleSnapshots_snapshotRemovalFailureStillTerminates() {
        ExecutionEnvironment env = sleepingEnvironment(Instant.now().minus(Duration.ofDays(45)));
        when(environmentRepository.findExpiredSleeping(any(Instant.class))).thenReturn(List.of(env));
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);
        doThrow(new RuntimeException("docker unavailable"))
                .when(sandboxProvider)
                .destroy(env.getId().toString());

        service.expireStaleSnapshots();

        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.TERMINATED);
        verify(environmentRepository).save(env);
        verify(eventPublisher).publishEvent(any(TeamEntityChangedEvent.class));
    }

    @Test
    void expireStaleSnapshots_continuesWithRemainingEnvironmentsWhenOneFails() {
        ExecutionEnvironment failing = sleepingEnvironment(Instant.now().minus(Duration.ofDays(31)));
        ExecutionEnvironment healthy = sleepingEnvironment(Instant.now().minus(Duration.ofDays(31)));
        when(environmentRepository.findExpiredSleeping(any(Instant.class))).thenReturn(List.of(failing, healthy));
        when(sandboxOrchestratorService.getProvider(ExecutionProviderType.DOCKER))
                .thenReturn(sandboxProvider);
        doThrow(new RuntimeException("docker unavailable"))
                .when(sandboxProvider)
                .destroy(failing.getId().toString());

        service.expireStaleSnapshots();

        verify(sandboxProvider).destroy(healthy.getId().toString());
        assertThat(failing.getStatus()).isEqualTo(EnvironmentStatus.TERMINATED);
        assertThat(healthy.getStatus()).isEqualTo(EnvironmentStatus.TERMINATED);
        verify(environmentRepository, times(2)).save(any(ExecutionEnvironment.class));
    }

    @Test
    void expireStaleSnapshots_withoutProvider_terminatesWithoutSnapshotRemoval() {
        ExecutionEnvironment env = sleepingEnvironment(Instant.now().minus(Duration.ofDays(31)));
        env.setProvider(null);
        when(environmentRepository.findExpiredSleeping(any(Instant.class))).thenReturn(List.of(env));

        service.expireStaleSnapshots();

        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.TERMINATED);
        verify(environmentRepository).save(env);
        verifyNoInteractions(sandboxOrchestratorService);
    }
}
