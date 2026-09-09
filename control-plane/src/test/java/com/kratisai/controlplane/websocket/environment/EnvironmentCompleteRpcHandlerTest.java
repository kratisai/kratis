package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.SandboxExecutionService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class EnvironmentCompleteRpcHandlerTest {

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private SandboxExecutionRepository executionRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SandboxExecutionService sandboxExecutionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EnvironmentCompleteRpcHandler handler;

    private final UUID teamId = UUID.randomUUID();
    private final UUID envId = UUID.randomUUID();
    private final UUID chatId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final String sessionId = "ws-session-1";

    @BeforeEach
    void setUp() {
        EnvironmentExecutionGuard executionGuard = new EnvironmentExecutionGuard(sessionRegistry, executionRepository);
        handler = new EnvironmentCompleteRpcHandler(
                executionRepository, executionGuard, eventPublisher, sandboxExecutionService);
    }

    private SandboxExecution createExecution() {
        Team team = new Team();
        team.setId(teamId);
        ChatEntity chat = new ChatEntity();
        chat.setId(chatId);
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

    private void stubOwnedExecution() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(createExecution()));
    }

    @Test
    void handle_withExitCodeZero_setsCompletedStatus() {
        stubOwnedExecution();

        EnvironmentRpcPayload.Complete params = new EnvironmentRpcPayload.Complete(0, executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Complete.METHOD, objectMapper.valueToTree(params), null);

        handler.handle(sessionId, request, params);
        ArgumentCaptor<SandboxExecution> savedCaptor = ArgumentCaptor.forClass(SandboxExecution.class);
        verify(executionRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo(SandboxExecutionStatus.COMPLETED);
        assertThat(savedCaptor.getValue().getExitCode()).isEqualTo(0);

        ArgumentCaptor<SandboxExecutionCompleteEvent> captor =
                ArgumentCaptor.forClass(SandboxExecutionCompleteEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().exitCode()).isEqualTo(0);

        ArgumentCaptor<ExecutionStatusChangedEvent> statusCaptor =
                ArgumentCaptor.forClass(ExecutionStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(statusCaptor.capture());
        assertThat(statusCaptor.getValue().teamId()).isEqualTo(teamId);
        assertThat(statusCaptor.getValue().chatId()).isEqualTo(chatId);
        assertThat(statusCaptor.getValue().executionId()).isEqualTo(executionId);
    }

    @Test
    void handle_withNonZeroExitCode_setsFailedStatus() {
        stubOwnedExecution();

        EnvironmentRpcPayload.Complete params = new EnvironmentRpcPayload.Complete(1, executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Complete.METHOD, objectMapper.valueToTree(params), null);

        handler.handle(sessionId, request, params);
        ArgumentCaptor<SandboxExecution> savedCaptor = ArgumentCaptor.forClass(SandboxExecution.class);
        verify(executionRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
        assertThat(savedCaptor.getValue().getExitCode()).isEqualTo(1);
    }

    @Test
    void handle_cancelsPendingPermissionsForExecution() {
        stubOwnedExecution();

        EnvironmentRpcPayload.Complete params = new EnvironmentRpcPayload.Complete(0, executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Complete.METHOD, objectMapper.valueToTree(params), null);

        handler.handle(sessionId, request, params);

        ArgumentCaptor<SandboxExecution> execCaptor = ArgumentCaptor.forClass(SandboxExecution.class);
        verify(sandboxExecutionService).cancelPendingPermissions(execCaptor.capture(), eq("execution completed"));
        assertThat(execCaptor.getValue().getId()).isEqualTo(executionId);
    }

    @Test
    void handle_withUnregisteredSession_throwsRegistrationError() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.empty());

        EnvironmentRpcPayload.Complete params = new EnvironmentRpcPayload.Complete(0, executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Complete.METHOD, objectMapper.valueToTree(params), null);

        assertThatThrownBy(() -> handler.handle(sessionId, request, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32001));
    }

    @Test
    void handle_withExecutionFromAnotherEnvironment_throwsNotAuthorized() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        ExecutionEnvironment otherEnv = new ExecutionEnvironment();
        otherEnv.setId(UUID.randomUUID());
        SandboxExecution execution = createExecution();
        execution.setEnvironment(otherEnv);
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.Complete params = new EnvironmentRpcPayload.Complete(0, executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Complete.METHOD, objectMapper.valueToTree(params), null);

        assertThatThrownBy(() -> handler.handle(sessionId, request, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32002));
    }

    @Test
    void getMethodName_returnsEnvComplete() {
        assertThat(handler.getMethodName()).isEqualTo("env.complete");
    }
}
