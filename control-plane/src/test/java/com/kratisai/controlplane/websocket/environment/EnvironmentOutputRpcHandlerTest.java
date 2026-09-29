package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.OutputStream;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class EnvironmentOutputRpcHandlerTest {

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private SandboxExecutionRepository executionRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EnvironmentOutputRpcHandler handler;

    private final UUID teamId = UUID.randomUUID();
    private final UUID envId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final String sessionId = "ws-session-1";

    @BeforeEach
    void setUp() {
        EnvironmentExecutionGuard executionGuard = new EnvironmentExecutionGuard(sessionRegistry, executionRepository);
        handler = new EnvironmentOutputRpcHandler(executionGuard, eventPublisher);
    }

    private SandboxExecution createExecution() {
        return createExecution(envId);
    }

    private SandboxExecution createExecution(UUID environmentId) {
        Team team = new Team();
        team.setId(teamId);
        ChatEntity chat = new ChatEntity();
        chat.setTeam(team);
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(environmentId);
        SandboxExecution execution = new SandboxExecution();
        execution.setId(executionId);
        execution.setChat(chat);
        execution.setEnvironment(env);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        return execution;
    }

    @Test
    void handle_withValidParams_publishesOutputEvent() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        SandboxExecution execution = createExecution();
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.Output params =
                new EnvironmentRpcPayload.Output("Hello world", OutputStream.STDOUT, executionId.toString());
        JsonRpcInboundRequest request =
                new JsonRpcInboundRequest(EnvironmentRpcPayload.Output.METHOD, objectMapper.valueToTree(params), null);

        handler.handle(sessionId, request, params);
        ArgumentCaptor<SandboxExecutionOutputEvent> captor = ArgumentCaptor.forClass(SandboxExecutionOutputEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        SandboxExecutionOutputEvent event = captor.getValue();
        assertThat(event.teamId()).isEqualTo(teamId);
        assertThat(event.executionId()).isEqualTo(executionId);
        assertThat(event.line()).isEqualTo("Hello world\n");
        assertThat(event.stream()).isEqualTo(OutputStream.STDOUT);
    }

    @Test
    void handle_withUnregisteredSession_throwsRegistrationError() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.empty());

        EnvironmentRpcPayload.Output params =
                new EnvironmentRpcPayload.Output("Hello world", OutputStream.STDOUT, executionId.toString());
        JsonRpcInboundRequest request =
                new JsonRpcInboundRequest(EnvironmentRpcPayload.Output.METHOD, objectMapper.valueToTree(params), null);

        assertThatThrownBy(() -> handler.handle(sessionId, request, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32001));
    }

    @Test
    void handle_withExecutionFromAnotherEnvironment_throwsNotAuthorized() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        SandboxExecution execution = createExecution(UUID.randomUUID());
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.Output params =
                new EnvironmentRpcPayload.Output("Hello world", OutputStream.STDOUT, executionId.toString());
        JsonRpcInboundRequest request =
                new JsonRpcInboundRequest(EnvironmentRpcPayload.Output.METHOD, objectMapper.valueToTree(params), null);

        assertThatThrownBy(() -> handler.handle(sessionId, request, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32002));
    }

    @Test
    void getMethodName_returnsEnvOutput() {
        assertThat(handler.getMethodName()).isEqualTo("env.output");
    }
}
