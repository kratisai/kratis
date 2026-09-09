package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.OutputStream;
import com.kratisai.controlplane.api.wsdto.SidecarErrorKind;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.SandboxExecutionOutputEvent;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class EnvironmentSidecarErrorRpcHandlerTest {

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private SandboxExecutionRepository executionRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private EnvironmentSidecarErrorRpcHandler handler;

    private final UUID teamId = UUID.randomUUID();
    private final UUID envId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final String sessionId = "ws-session-1";

    @BeforeEach
    void setUp() {
        EnvironmentExecutionGuard executionGuard = new EnvironmentExecutionGuard(sessionRegistry, executionRepository);
        handler = new EnvironmentSidecarErrorRpcHandler(executionGuard, eventPublisher);
    }

    private SandboxExecution createExecution() {
        Team team = new Team();
        team.setId(teamId);
        ChatEntity chat = new ChatEntity();
        chat.setTeam(team);
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(envId);
        SandboxExecution execution = new SandboxExecution();
        execution.setId(executionId);
        execution.setChat(chat);
        execution.setEnvironment(env);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        return execution;
    }

    @Test
    void handle_withResolvableExecution_echoesErrorToStderr() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        SandboxExecution execution = createExecution();
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.SidecarError params = new EnvironmentRpcPayload.SidecarError(
                SidecarErrorKind.CONNECTION_CLOSED,
                "control plane closed the connection unexpectedly (code=1009, reason=\"too big\"); reconnected",
                executionId.toString(),
                1009,
                "too big");

        Flux<EnvironmentResponsePayload> result = handler.handle(sessionId, null, params);
        StepVerifier.create(result).verifyComplete();

        ArgumentCaptor<SandboxExecutionOutputEvent> captor = ArgumentCaptor.forClass(SandboxExecutionOutputEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        SandboxExecutionOutputEvent event = captor.getValue();
        assertThat(event.teamId()).isEqualTo(teamId);
        assertThat(event.executionId()).isEqualTo(executionId);
        assertThat(event.stream()).isEqualTo(OutputStream.STDERR);
        assertThat(event.line())
                .contains("[Kratis] sidecar error (connection_closed):")
                .contains("1009");
    }

    @Test
    void handle_withoutExecutionId_onlyLogs() {
        EnvironmentRpcPayload.SidecarError params = new EnvironmentRpcPayload.SidecarError(
                SidecarErrorKind.FRAME_DROPPED,
                "dropped env.output notification: 13000000 bytes exceeds the limit",
                null,
                null,
                null);

        Flux<EnvironmentResponsePayload> result = handler.handle(sessionId, null, params);
        StepVerifier.create(result).verifyComplete();

        verify(eventPublisher, never()).publishEvent(ArgumentMatchers.any());
    }

    @Test
    void handle_withUnknownExecution_doesNotThrow() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        when(executionRepository.findById(executionId)).thenReturn(Optional.empty());

        EnvironmentRpcPayload.SidecarError params = new EnvironmentRpcPayload.SidecarError(
                SidecarErrorKind.CONNECTION_CLOSED,
                "control plane closed the connection unexpectedly",
                executionId.toString(),
                null,
                null);

        Flux<EnvironmentResponsePayload> result = handler.handle(sessionId, null, params);
        StepVerifier.create(result).verifyComplete();

        verify(eventPublisher, never()).publishEvent(ArgumentMatchers.any());
    }

    @Test
    void handle_withInvalidExecutionId_doesNotThrow() {
        EnvironmentRpcPayload.SidecarError params = new EnvironmentRpcPayload.SidecarError(
                SidecarErrorKind.CONNECTION_CLOSED, "boom", "not-a-uuid", null, null);

        Flux<EnvironmentResponsePayload> result = handler.handle(sessionId, null, params);
        StepVerifier.create(result).verifyComplete();

        verify(eventPublisher, never()).publishEvent(ArgumentMatchers.any());
    }
}
