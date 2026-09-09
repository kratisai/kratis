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
import com.kratisai.controlplane.model.event.SandboxExecutionAcpInitializedEvent;
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
class EnvironmentAcpInitializedRpcHandlerTest {

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private SandboxExecutionRepository executionRepository;

    @Mock
    private SandboxExecutionService sandboxExecutionService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EnvironmentAcpInitializedRpcHandler handler;

    private final UUID envId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final String sessionId = "ws-session-1";

    @BeforeEach
    void setUp() {
        EnvironmentExecutionGuard executionGuard = new EnvironmentExecutionGuard(sessionRegistry, executionRepository);
        handler = new EnvironmentAcpInitializedRpcHandler(executionGuard, sandboxExecutionService, eventPublisher);
    }

    private SandboxExecution createExecution() {
        Team team = new Team();
        team.setId(UUID.randomUUID());
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
    void handle_withValidParams_sendsAcpPrompt() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        SandboxExecution execution = createExecution();
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.AcpInitialized params =
                new EnvironmentRpcPayload.AcpInitialized("acp-sess-1", "claude", "1.0", executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.AcpInitialized.METHOD, objectMapper.valueToTree(params), null);

        handler.handle(sessionId, request, params);
        verify(sandboxExecutionService).dispatchAcpPrompt(eq(execution));

        ArgumentCaptor<SandboxExecutionAcpInitializedEvent> captor =
                ArgumentCaptor.forClass(SandboxExecutionAcpInitializedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        SandboxExecutionAcpInitializedEvent event = captor.getValue();
        assertThat(event.executionId()).isEqualTo(executionId);
        assertThat(event.teamId()).isEqualTo(execution.getChat().getTeam().getId());
        assertThat(event.sessionId()).isEqualTo("acp-sess-1");
        assertThat(event.agentName()).isEqualTo("claude");
        assertThat(event.agentVersion()).isEqualTo("1.0");
    }

    @Test
    void handle_withUnregisteredSession_throwsRegistrationError() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.empty());

        EnvironmentRpcPayload.AcpInitialized params =
                new EnvironmentRpcPayload.AcpInitialized("acp-sess-1", "claude", "1.0", executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.AcpInitialized.METHOD, objectMapper.valueToTree(params), null);

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

        EnvironmentRpcPayload.AcpInitialized params =
                new EnvironmentRpcPayload.AcpInitialized("acp-sess-1", "claude", "1.0", executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.AcpInitialized.METHOD, objectMapper.valueToTree(params), null);

        assertThatThrownBy(() -> handler.handle(sessionId, request, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32002));
    }

    @Test
    void getMethodName_returnsEnvAcpInitialized() {
        assertThat(handler.getMethodName()).isEqualTo("env.acp_initialized");
    }
}
