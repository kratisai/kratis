package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnvironmentHeartbeatRpcHandlerTest {

    @Mock
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    private EnvironmentHeartbeatRpcHandler handler;

    @BeforeEach
    void setUp() {
        handler = new EnvironmentHeartbeatRpcHandler(executionEnvironmentRepository, sessionRegistry);
    }

    @Test
    void handle_pendingConnect_updatesHeartbeatWithoutMovingActivityClock() {
        UUID envId = UUID.randomUUID();
        Instant initialActivity = Instant.now().minusSeconds(600);
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(envId);
        env.setStatus(EnvironmentStatus.PENDING_CONNECT);
        env.setLastActivityAt(initialActivity);
        when(sessionRegistry.getEnvironmentId("session-1")).thenReturn(Optional.of(envId));
        when(executionEnvironmentRepository.findById(envId)).thenReturn(Optional.of(env));

        handler.handle("session-1", 1L, new EnvironmentRpcPayload.Heartbeat()).blockLast();

        assertThat(env.getLastHeartbeat()).isNotNull();
        assertThat(env.getLastActivityAt()).isEqualTo(initialActivity);
    }

    @Test
    void handle_connected_doesNotRefreshIdleActivity() {
        UUID envId = UUID.randomUUID();
        Instant oldActivity = Instant.now().minusSeconds(600);
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(envId);
        env.setStatus(EnvironmentStatus.CONNECTED);
        env.setLastActivityAt(oldActivity);
        when(sessionRegistry.getEnvironmentId("session-1")).thenReturn(Optional.of(envId));
        when(executionEnvironmentRepository.findById(envId)).thenReturn(Optional.of(env));

        handler.handle("session-1", 1L, new EnvironmentRpcPayload.Heartbeat()).blockLast();

        assertThat(env.getLastActivityAt()).isEqualTo(oldActivity);
    }
}
