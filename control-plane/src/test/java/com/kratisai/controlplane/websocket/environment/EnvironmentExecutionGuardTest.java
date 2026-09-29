package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnvironmentExecutionGuardTest {

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private SandboxExecutionRepository executionRepository;

    private EnvironmentExecutionGuard guard;

    private final UUID envId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final String sessionId = "ws-session-1";

    @BeforeEach
    void setUp() {
        guard = new EnvironmentExecutionGuard(sessionRegistry, executionRepository);
    }

    private SandboxExecution executionIn(UUID environmentId) {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(environmentId);
        SandboxExecution execution = new SandboxExecution();
        execution.setId(executionId);
        execution.setEnvironment(env);
        return execution;
    }

    @Test
    void requireEnvironmentId_withRegisteredSession_returnsEnvironmentId() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));

        assertThat(guard.requireEnvironmentId(sessionId)).isEqualTo(envId);
    }

    @Test
    void requireEnvironmentId_withUnregisteredSession_throwsRegistrationError() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireEnvironmentId(sessionId))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32001));
    }

    @Test
    void requireExecutionInEnvironment_withOwnedExecution_returnsExecution() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        SandboxExecution execution = executionIn(envId);
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        assertThat(guard.requireExecutionInEnvironment(sessionId, executionId)).isSameAs(execution);
    }

    @Test
    void requireExecutionInEnvironment_withUnregisteredSession_rejectsBeforeLookup() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireExecutionInEnvironment(sessionId, executionId))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32001));
        verifyNoInteractions(executionRepository);
    }

    @Test
    void requireExecutionInEnvironment_withMissingExecution_throwsIllegalArgumentException() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        when(executionRepository.findById(executionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireExecutionInEnvironment(sessionId, executionId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireExecutionInEnvironment_withForeignExecution_throwsNotAuthorized() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        SandboxExecution execution = executionIn(UUID.randomUUID());
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> guard.requireExecutionInEnvironment(sessionId, executionId))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32002));
    }

    @Test
    void verifyExecutionInEnvironment_withOwnedExecution_doesNotThrow() {
        assertThatCode(() -> guard.verifyExecutionInEnvironment(executionIn(envId), envId))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyExecutionInEnvironment_withForeignExecution_throwsNotAuthorized() {
        assertThatThrownBy(() -> guard.verifyExecutionInEnvironment(executionIn(UUID.randomUUID()), envId))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32002));
    }

    @Test
    void verifyExecutionInEnvironment_withMissingEnvironment_throwsNotAuthorized() {
        SandboxExecution execution = new SandboxExecution();
        execution.setId(executionId);

        assertThatThrownBy(() -> guard.verifyExecutionInEnvironment(execution, envId))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32002));
    }
}
