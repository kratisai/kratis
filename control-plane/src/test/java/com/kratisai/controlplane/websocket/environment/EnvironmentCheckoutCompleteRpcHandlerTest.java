package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.CheckoutStatus;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.SandboxExecutionService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnvironmentCheckoutCompleteRpcHandlerTest {

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private SandboxExecutionRepository executionRepository;

    @Mock
    private SandboxExecutionService sandboxExecutionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EnvironmentCheckoutCompleteRpcHandler handler;

    private final UUID teamId = UUID.randomUUID();
    private final UUID envId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final String sessionId = "ws-session-1";

    @BeforeEach
    void setUp() {
        EnvironmentExecutionGuard executionGuard = new EnvironmentExecutionGuard(sessionRegistry, executionRepository);
        handler = new EnvironmentCheckoutCompleteRpcHandler(executionGuard, sandboxExecutionService);
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
    void handle_withSuccessStatus_delegatesToResumeAfterCheckout() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        SandboxExecution execution = createExecution();
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.CheckoutComplete params = new EnvironmentRpcPayload.CheckoutComplete(
                CheckoutStatus.SUCCESS, null, "abc123", executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.CheckoutComplete.METHOD, objectMapper.valueToTree(params), null);

        handler.handle(sessionId, request, params);
        verify(sandboxExecutionService).resumeAfterCheckout(executionId);
        verify(sandboxExecutionService, never()).failExecution(executionId);
    }

    @Test
    void handle_withFailureStatus_delegatesToFailExecution() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        SandboxExecution execution = createExecution();
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.CheckoutComplete params = new EnvironmentRpcPayload.CheckoutComplete(
                CheckoutStatus.FAILED, "clone failed", null, executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.CheckoutComplete.METHOD, objectMapper.valueToTree(params), null);

        handler.handle(sessionId, request, params);
        verify(sandboxExecutionService).failExecution(executionId);
        verify(sandboxExecutionService, never()).resumeAfterCheckout(executionId);
    }

    @Test
    void handle_withUnregisteredSession_throwsRegistrationError() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.empty());

        EnvironmentRpcPayload.CheckoutComplete params = new EnvironmentRpcPayload.CheckoutComplete(
                CheckoutStatus.SUCCESS, null, "abc123", executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.CheckoutComplete.METHOD, objectMapper.valueToTree(params), null);

        assertThatThrownBy(() -> handler.handle(sessionId, request, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32001));
        verify(sandboxExecutionService, never()).resumeAfterCheckout(any());
        verify(sandboxExecutionService, never()).failExecution(any());
    }

    @Test
    void handle_withExecutionFromAnotherEnvironment_throwsNotAuthorized() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        ExecutionEnvironment otherEnv = new ExecutionEnvironment();
        otherEnv.setId(UUID.randomUUID());
        SandboxExecution execution = createExecution();
        execution.setEnvironment(otherEnv);
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.CheckoutComplete params = new EnvironmentRpcPayload.CheckoutComplete(
                CheckoutStatus.SUCCESS, null, "abc123", executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.CheckoutComplete.METHOD, objectMapper.valueToTree(params), null);

        assertThatThrownBy(() -> handler.handle(sessionId, request, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32002));
        verify(sandboxExecutionService, never()).resumeAfterCheckout(any());
        verify(sandboxExecutionService, never()).failExecution(any());
    }

    @Test
    void getMethodName_returnsEnvCheckoutComplete() {
        assertThat(handler.getMethodName()).isEqualTo("env.checkout_complete");
    }
}
