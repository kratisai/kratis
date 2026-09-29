package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.SandboxExecutionActivityEvent;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.ExecutionActivityPersistenceService;
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
class EnvironmentActivityRpcHandlerTest {

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private SandboxExecutionRepository executionRepository;

    @Mock
    private ExecutionActivityPersistenceService activityPersistenceService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EnvironmentActivityRpcHandler handler;

    private final UUID teamId = UUID.randomUUID();
    private final UUID envId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final String sessionId = "ws-session-1";

    @BeforeEach
    void setUp() {
        EnvironmentExecutionGuard executionGuard = new EnvironmentExecutionGuard(sessionRegistry, executionRepository);
        handler = new EnvironmentActivityRpcHandler(executionGuard, activityPersistenceService, eventPublisher);
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
    void handle_withValidParams_publishesActivityEvent() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        SandboxExecution execution = createExecution();
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.Activity params = new EnvironmentRpcPayload.Activity(
                ActivityType.COMMAND,
                "Writing code",
                executionId.toString(),
                "tool-call-7",
                ActivityStatus.IN_PROGRESS,
                null);
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Activity.METHOD, objectMapper.valueToTree(params), null);

        handler.handle(sessionId, request, params);
        verify(activityPersistenceService)
                .recordActivity(
                        executionId,
                        ActivityType.COMMAND,
                        "Writing code",
                        "tool-call-7",
                        ActivityStatus.IN_PROGRESS,
                        null);
        ArgumentCaptor<SandboxExecutionActivityEvent> captor =
                ArgumentCaptor.forClass(SandboxExecutionActivityEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        SandboxExecutionActivityEvent event = captor.getValue();
        assertThat(event.teamId()).isEqualTo(teamId);
        assertThat(event.executionId()).isEqualTo(executionId);
        assertThat(event.activityType()).isEqualTo(ActivityType.COMMAND);
        assertThat(event.description()).isEqualTo("Writing code");
        assertThat(event.actionId()).isEqualTo("tool-call-7");
        assertThat(event.status()).isEqualTo(ActivityStatus.IN_PROGRESS);
    }

    @Test
    void handle_withoutActionId_publishesEventWithNullActionId() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        SandboxExecution execution = createExecution();
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.Activity params = new EnvironmentRpcPayload.Activity(
                ActivityType.RESEARCH, "Searching", executionId.toString(), null, ActivityStatus.IN_PROGRESS, null);
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Activity.METHOD, objectMapper.valueToTree(params), null);

        handler.handle(sessionId, request, params);
        ArgumentCaptor<SandboxExecutionActivityEvent> captor =
                ArgumentCaptor.forClass(SandboxExecutionActivityEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actionId()).isNull();
    }

    @Test
    void handle_withUnregisteredSession_throwsRegistrationError() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.empty());

        EnvironmentRpcPayload.Activity params = new EnvironmentRpcPayload.Activity(
                ActivityType.COMMAND,
                "Writing code",
                executionId.toString(),
                "tool-call-7",
                ActivityStatus.IN_PROGRESS,
                null);
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Activity.METHOD, objectMapper.valueToTree(params), null);

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

        EnvironmentRpcPayload.Activity params = new EnvironmentRpcPayload.Activity(
                ActivityType.COMMAND,
                "Writing code",
                executionId.toString(),
                "tool-call-7",
                ActivityStatus.IN_PROGRESS,
                null);
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Activity.METHOD, objectMapper.valueToTree(params), null);

        assertThatThrownBy(() -> handler.handle(sessionId, request, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32002));
    }

    @Test
    void getMethodName_returnsEnvActivity() {
        assertThat(handler.getMethodName()).isEqualTo("env.activity");
    }
}
