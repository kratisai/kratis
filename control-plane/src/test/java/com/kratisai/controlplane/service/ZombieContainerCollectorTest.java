package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SlowTest;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

@SpringIntegrationTest
@SlowTest
class ZombieContainerCollectorTest {

    @Autowired
    private ExecutionEnvironmentRepository environmentRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private Team team;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        team = teamRepository.save(new Team("Test Team", "Test description"));
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void testScheduledMethodIsPresent() throws Exception {
        var method = ZombieContainerCollector.class.getMethod("collectZombies");
        assertThat(method.isAnnotationPresent(Scheduled.class)).isTrue();
    }

    @Test
    void testCollectZombiesOrphanedAndDelinquentRespectsGracePeriod() throws Exception {
        SandboxProvider mockSandboxProvider = mock(SandboxProvider.class);
        when(mockSandboxProvider.getProviderType()).thenReturn(ExecutionProviderType.DOCKER);
        when(mockSandboxProvider.getActiveSandboxIds())
                .thenReturn(List.of("container-orphan", "container-delinquent", "container-connected"));

        ZombieContainerCollector collector = new ZombieContainerCollector(
                environmentRepository, List.of(mockSandboxProvider), java.util.Optional.empty(), "test-instance", 1);

        // Create environment in DB for container-delinquent (DISCONNECTED,
        // lastHeartbeat is null)
        ExecutionEnvironment delinquentEnv = new ExecutionEnvironment();
        delinquentEnv.setTeam(team);
        delinquentEnv.setName("Delinquent Env");
        delinquentEnv.setType(ExecutionEnvironmentType.SANDBOX);
        delinquentEnv.setStatus(EnvironmentStatus.DISCONNECTED);
        delinquentEnv.setContainerId("container-delinquent");
        delinquentEnv = environmentRepository.save(delinquentEnv);

        // Create environment in DB for container-connected (CONNECTED)
        ExecutionEnvironment connectedEnv = new ExecutionEnvironment();
        connectedEnv.setTeam(team);
        connectedEnv.setName("Connected Env");
        connectedEnv.setType(ExecutionEnvironmentType.SANDBOX);
        connectedEnv.setStatus(EnvironmentStatus.CONNECTED);
        connectedEnv.setContainerId("container-connected");
        environmentRepository.save(connectedEnv);

        // Run cycle 1: first seen. Grace period is 1s, elapsed is 0s. Nothing should be
        // terminated.
        collector.collectZombies();
        verify(mockSandboxProvider, never()).terminateSandbox(anyString());

        // Wait 1.1 seconds to exceed the 1-second grace period
        Thread.sleep(1100);

        // Run cycle 2: grace period exceeded. Orphan and Delinquent should be
        // terminated.
        collector.collectZombies();

        verify(mockSandboxProvider, times(1)).terminateSandbox("container-orphan");
        verify(mockSandboxProvider, times(1)).terminateSandbox("container-delinquent");
        verify(mockSandboxProvider, never()).terminateSandbox("container-connected");

        // Verify delinquent environment database state is updated (containerId set to
        // null, status DISCONNECTED)
        ExecutionEnvironment updatedDelinquent =
                environmentRepository.findById(delinquentEnv.getId()).orElseThrow();
        assertThat(updatedDelinquent.getContainerId()).isNull();
        assertThat(updatedDelinquent.getStatus()).isEqualTo(EnvironmentStatus.DISCONNECTED);
    }

    @Test
    void testCollectZombiesWithMultipleProviders() throws Exception {
        SandboxProvider providerA = mock(SandboxProvider.class);
        when(providerA.getProviderType()).thenReturn(ExecutionProviderType.DOCKER);
        when(providerA.getActiveSandboxIds()).thenReturn(List.of("sandbox-orphan-a", "sandbox-delinquent-a"));

        SandboxProvider providerB = mock(SandboxProvider.class);
        when(providerB.getProviderType()).thenReturn(ExecutionProviderType.DOCKER);
        when(providerB.getActiveSandboxIds()).thenReturn(List.of("sandbox-orphan-b", "sandbox-delinquent-b"));

        ZombieContainerCollector collector = new ZombieContainerCollector(
                environmentRepository, List.of(providerA, providerB), java.util.Optional.empty(), "test-instance", 1);

        // Save DB records for delinquents
        ExecutionEnvironment delinquentA = new ExecutionEnvironment();
        delinquentA.setTeam(team);
        delinquentA.setName("Delinquent A");
        delinquentA.setType(ExecutionEnvironmentType.SANDBOX);
        delinquentA.setStatus(EnvironmentStatus.DISCONNECTED);
        delinquentA.setContainerId("sandbox-delinquent-a");
        environmentRepository.save(delinquentA);

        ExecutionEnvironment delinquentB = new ExecutionEnvironment();
        delinquentB.setTeam(team);
        delinquentB.setName("Delinquent B");
        delinquentB.setType(ExecutionEnvironmentType.SANDBOX);
        delinquentB.setStatus(EnvironmentStatus.DISCONNECTED);
        delinquentB.setContainerId("sandbox-delinquent-b");
        environmentRepository.save(delinquentB);

        // Cycle 1: register
        collector.collectZombies();
        verify(providerA, never()).terminateSandbox(anyString());
        verify(providerB, never()).terminateSandbox(anyString());

        // Wait to exceed grace period
        Thread.sleep(1100);

        // Cycle 2: execute terminations
        collector.collectZombies();

        // Check providerA terminated its own sandboxes
        verify(providerA, times(1)).terminateSandbox("sandbox-orphan-a");
        verify(providerA, times(1)).terminateSandbox("sandbox-delinquent-a");
        verify(providerA, never()).terminateSandbox("sandbox-orphan-b");
        verify(providerA, never()).terminateSandbox("sandbox-delinquent-b");

        // Check providerB terminated its own sandboxes
        verify(providerB, times(1)).terminateSandbox("sandbox-orphan-b");
        verify(providerB, times(1)).terminateSandbox("sandbox-delinquent-b");
        verify(providerB, never()).terminateSandbox("sandbox-orphan-a");
        verify(providerB, never()).terminateSandbox("sandbox-delinquent-a");
    }

    @Test
    void testCollectZombiesDelinquentWithLastHeartbeat() throws Exception {
        SandboxProvider mockSandboxProvider = mock(SandboxProvider.class);
        when(mockSandboxProvider.getProviderType()).thenReturn(ExecutionProviderType.DOCKER);
        when(mockSandboxProvider.getActiveSandboxIds()).thenReturn(List.of("container-delinquent-hb"));

        ZombieContainerCollector collector = new ZombieContainerCollector(
                environmentRepository, List.of(mockSandboxProvider), java.util.Optional.empty(), "test-instance", 5);

        // Create environment in DB with lastHeartbeat set
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(team);
        env.setName("Delinquent HB Env");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env.setContainerId("container-delinquent-hb");
        // Heartbeat is just 1 second ago (within 5 seconds grace)
        env.setLastHeartbeat(Instant.now().minus(1, ChronoUnit.SECONDS));
        env = environmentRepository.save(env);

        // Run cycle: should NOT terminate because it's within the grace period
        collector.collectZombies();
        verify(mockSandboxProvider, never()).terminateSandbox(anyString());

        // Set lastHeartbeat to 10 seconds ago (exceeds 5 seconds grace)
        env.setLastHeartbeat(Instant.now().minus(10, ChronoUnit.SECONDS));
        environmentRepository.save(env);

        // Run cycle: should terminate now
        collector.collectZombies();
        verify(mockSandboxProvider, times(1)).terminateSandbox("container-delinquent-hb");
    }

    @Test
    void testCollectZombiesDelinquentRecovered() throws Exception {
        SandboxProvider mockSandboxProvider = mock(SandboxProvider.class);
        when(mockSandboxProvider.getProviderType()).thenReturn(ExecutionProviderType.DOCKER);
        when(mockSandboxProvider.getActiveSandboxIds()).thenReturn(List.of("container-delinquent-recovered"));

        ZombieContainerCollector collector = new ZombieContainerCollector(
                environmentRepository, List.of(mockSandboxProvider), java.util.Optional.empty(), "test-instance", 1);

        // Create environment in DB for container-delinquent-recovered (DISCONNECTED)
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(team);
        env.setName("Recovered Env");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env.setContainerId("container-delinquent-recovered");
        env = environmentRepository.save(env);

        // Run cycle 1: delinquent first seen tracked
        collector.collectZombies();
        verify(mockSandboxProvider, never()).terminateSandbox(anyString());

        // Simulate recovery: environment becomes CONNECTED
        env.setStatus(EnvironmentStatus.CONNECTED);
        environmentRepository.save(env);

        // Wait to exceed grace period
        Thread.sleep(1100);

        // Run cycle 2: should not be terminated because status is CONNECTED
        collector.collectZombies();
        verify(mockSandboxProvider, never()).terminateSandbox(anyString());
    }

    @Test
    void testCollectZombiesEmptyOrNullActiveSandboxes() {
        SandboxProvider providerNull = mock(SandboxProvider.class);
        when(providerNull.getProviderType()).thenReturn(ExecutionProviderType.DOCKER);
        when(providerNull.getActiveSandboxIds()).thenReturn(null);

        SandboxProvider providerEmpty = mock(SandboxProvider.class);
        when(providerEmpty.getProviderType()).thenReturn(ExecutionProviderType.DOCKER);
        when(providerEmpty.getActiveSandboxIds()).thenReturn(List.of());

        ZombieContainerCollector collector = new ZombieContainerCollector(
                environmentRepository,
                List.of(providerNull, providerEmpty),
                java.util.Optional.empty(),
                "test-instance",
                30);

        // Should execute cleanly without throwing exceptions
        collector.collectZombies();

        verify(providerNull, never()).terminateSandbox(anyString());
        verify(providerEmpty, never()).terminateSandbox(anyString());
    }

    @Test
    void testCollectZombiesCleanUpDelinquentFirstSeenTracking() throws Exception {
        SandboxProvider mockSandboxProvider = mock(SandboxProvider.class);
        when(mockSandboxProvider.getProviderType()).thenReturn(ExecutionProviderType.DOCKER);
        // Cycle 1 returns the delinquent sandbox
        when(mockSandboxProvider.getActiveSandboxIds()).thenReturn(List.of("sandbox-delinquent"));

        ZombieContainerCollector collector = new ZombieContainerCollector(
                environmentRepository, List.of(mockSandboxProvider), java.util.Optional.empty(), "test-instance", 30);

        // Create environment in DB for sandbox-delinquent (DISCONNECTED)
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(team);
        env.setName("Delinquent Env");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env.setContainerId("sandbox-delinquent");
        env = environmentRepository.save(env);

        // Run cycle 1: delinquent first seen is registered
        collector.collectZombies();

        // Now change mock to return empty list (sandbox is no longer running)
        when(mockSandboxProvider.getActiveSandboxIds()).thenReturn(List.of());

        // Subscenario A: DB record is deleted
        environmentRepository.delete(env);
        collector.collectZombies(); // Runs keySet().removeIf delinquent cleaner, triggering opt.isEmpty() path

        // Recreate environment for Subscenario B
        env = new ExecutionEnvironment();
        env.setTeam(team);
        env.setName("Delinquent Env 2");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env.setContainerId("sandbox-delinquent-2");
        env = environmentRepository.save(env);

        when(mockSandboxProvider.getActiveSandboxIds()).thenReturn(List.of("sandbox-delinquent-2"));
        collector.collectZombies(); // Track again

        // Update environment to set containerId to null
        env.setContainerId(null);
        environmentRepository.save(env);

        when(mockSandboxProvider.getActiveSandboxIds()).thenReturn(List.of());
        collector.collectZombies(); // Runs keySet().removeIf delinquent cleaner, triggering containerId == null
        // path
    }

    @Test
    void testPruneOrphanNetworksCleansDanglingNetworks() throws Exception {
        SandboxProvider mockSandboxProvider = mock(SandboxProvider.class);
        when(mockSandboxProvider.getProviderType()).thenReturn(ExecutionProviderType.DOCKER);
        when(mockSandboxProvider.getActiveSandboxIds()).thenReturn(List.of());

        ProcessExecutor mockProcessExecutor = mock(ProcessExecutor.class);
        // list networks returns dangling-net and active-net
        when(mockProcessExecutor.execute(
                        argThat(cmd -> cmd != null && cmd.contains("network") && cmd.contains("ls")), any(), any()))
                .thenReturn(
                        new ProcessExecutor.ProcessResult(0, "kratis-net-dangling\nkratis-net-active\n".getBytes()));

        // inspect returns 0 containers for dangling, 2 for active
        when(mockProcessExecutor.execute(
                        argThat(cmd -> cmd != null
                                && cmd.contains("network")
                                && cmd.contains("inspect")
                                && cmd.contains("kratis-net-dangling")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "0\n".getBytes()));
        when(mockProcessExecutor.execute(
                        argThat(cmd -> cmd != null
                                && cmd.contains("network")
                                && cmd.contains("inspect")
                                && cmd.contains("kratis-net-active")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "2\n".getBytes()));

        ZombieContainerCollector collector = new ZombieContainerCollector(
                environmentRepository,
                List.of(mockSandboxProvider),
                java.util.Optional.of(mockProcessExecutor),
                "test-instance",
                30);

        collector.collectZombies();

        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-dangling")), any(), any());
        verify(mockProcessExecutor, never())
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-active")), any(), any());
    }
}
