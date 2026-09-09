package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.api.wsdto.StopReason;
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
class EnvironmentAcpPromptCompleteRpcHandlerTest {

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private SandboxExecutionRepository executionRepository;

    @Mock
    private SandboxExecutionService sandboxExecutionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EnvironmentAcpPromptCompleteRpcHandler handler;

    private final UUID envId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final String sessionId = "ws-session-1";

    @BeforeEach
    void setUp() {
        EnvironmentExecutionGuard executionGuard = new EnvironmentExecutionGuard(sessionRegistry, executionRepository);
        handler = new EnvironmentAcpPromptCompleteRpcHandler(executionGuard, sandboxExecutionService);
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
    void handle_withValidParams_completesAcpPrompt() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        SandboxExecution execution = createExecution();
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.AcpPromptComplete params =
                new EnvironmentRpcPayload.AcpPromptComplete("acp-sess-1", StopReason.END_TURN, executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.AcpPromptComplete.METHOD, objectMapper.valueToTree(params), null);

        handler.handle(sessionId, request, params);
        verify(sandboxExecutionService).completeAcpPrompt(executionId, StopReason.END_TURN);
    }

    @Test
    void handle_withUnregisteredSession_throwsRegistrationError() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.empty());

        EnvironmentRpcPayload.AcpPromptComplete params =
                new EnvironmentRpcPayload.AcpPromptComplete("acp-sess-1", StopReason.END_TURN, executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.AcpPromptComplete.METHOD, objectMapper.valueToTree(params), null);

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

        EnvironmentRpcPayload.AcpPromptComplete params =
                new EnvironmentRpcPayload.AcpPromptComplete("acp-sess-1", StopReason.END_TURN, executionId.toString());
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.AcpPromptComplete.METHOD, objectMapper.valueToTree(params), null);

        assertThatThrownBy(() -> handler.handle(sessionId, request, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32002));
    }

    @Test
    void getMethodName_returnsEnvAcpPromptComplete() {
        assertThat(handler.getMethodName()).isEqualTo("env.acp_prompt_complete");
    }
}
