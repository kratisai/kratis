package com.kratisai.controlplane.websocket.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.EnvironmentRpcClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;

class ClientExecutionGetLogsRpcHandlerTest {

    private ClientSessionRegistry sessionRegistry;
    private SandboxExecutionRepository executionRepository;
    private TeamMemberRepository teamMemberRepository;
    private EnvironmentRpcClient environmentRpcClient;
    private ClientExecutionGetLogsRpcHandler handler;
    private UUID userId;
    private UUID teamId;
    private UUID executionId;
    private UUID environmentId;

    @BeforeEach
    void setUp() {
        sessionRegistry = mock(ClientSessionRegistry.class);
        executionRepository = mock(SandboxExecutionRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        environmentRpcClient = mock(EnvironmentRpcClient.class);
        handler = new ClientExecutionGetLogsRpcHandler(
                sessionRegistry, executionRepository, teamMemberRepository, environmentRpcClient);

        userId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        environmentId = UUID.randomUUID();
        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.of(userId.toString()));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
    }

    @Test
    void connected_proxiesToSidecarAndReturnsLinesToRequester() throws Exception {
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution(EnvironmentStatus.CONNECTED)));
        when(environmentRpcClient.request(
                        eq(environmentId), any(EnvironmentRpcPayload.GetLogs.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(new EnvironmentConnectorResult.GetLogs(List.of("a", "b")));

        StepVerifier.create(handler.handle("ws-1", 1, request(50)))
                .assertNext(result -> {
                    assertThat(result.executionId()).isEqualTo(executionId);
                    assertThat(result.environmentId()).isEqualTo(environmentId);
                    assertThat(result.status()).isEqualTo(EnvironmentStatus.CONNECTED);
                    assertThat(result.lines()).containsExactly("a", "b");
                })
                .verifyComplete();

        ArgumentCaptor<EnvironmentRpcPayload.GetLogs> sent =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GetLogs.class);
        verify(environmentRpcClient).request(eq(environmentId), sent.capture(), anyLong(), any(TimeUnit.class));
        assertThat(sent.getValue().tailLines()).isEqualTo(50);
        // Point-to-point: the handler only resolves the caller; it never fans out to other sessions.
        verify(sessionRegistry).getUserId("ws-1");
        verifyNoMoreInteractions(sessionRegistry);
    }

    @Test
    void connected_withoutTailLinesUsesDefault() throws Exception {
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution(EnvironmentStatus.CONNECTED)));
        when(environmentRpcClient.request(
                        eq(environmentId), any(EnvironmentRpcPayload.GetLogs.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(new EnvironmentConnectorResult.GetLogs(List.of()));

        StepVerifier.create(handler.handle("ws-1", 1, request(null)))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<EnvironmentRpcPayload.GetLogs> sent =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GetLogs.class);
        verify(environmentRpcClient).request(eq(environmentId), sent.capture(), anyLong(), any(TimeUnit.class));
        assertThat(sent.getValue().tailLines()).isEqualTo(ClientExecutionGetLogsRpcHandler.DEFAULT_TAIL_LINES);
    }

    @Test
    void connected_sidecarTimeoutFailsWithInternalError() throws Exception {
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution(EnvironmentStatus.CONNECTED)));
        when(environmentRpcClient.request(
                        eq(environmentId), any(EnvironmentRpcPayload.GetLogs.class), anyLong(), any(TimeUnit.class)))
                .thenThrow(new TimeoutException("slow"));

        StepVerifier.create(handler.handle("ws-1", 1, request(10)))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(RpcErrorException.class)
                        .satisfies(ex -> assertThat(
                                        ((RpcErrorException) ex).error().code())
                                .isEqualTo(JsonRpcErrorCodes.INTERNAL_ERROR)))
                .verify();
    }

    @Test
    void sleeping_returnsEmptyWithoutContactingSidecar() {
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution(EnvironmentStatus.SLEEPING)));

        StepVerifier.create(handler.handle("ws-1", 1, request(10)))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo(EnvironmentStatus.SLEEPING);
                    assertThat(result.environmentId()).isEqualTo(environmentId);
                    assertThat(result.lines()).isEmpty();
                })
                .verifyComplete();
        verifyNoInteractions(environmentRpcClient);
    }

    @Test
    void terminated_returnsEmptyWithoutContactingSidecar() {
        when(executionRepository.findById(executionId))
                .thenReturn(Optional.of(execution(EnvironmentStatus.TERMINATED)));

        StepVerifier.create(handler.handle("ws-1", 1, request(10)))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo(EnvironmentStatus.TERMINATED);
                    assertThat(result.lines()).isEmpty();
                })
                .verifyComplete();
        verifyNoInteractions(environmentRpcClient);
    }

    @Test
    void disconnectedStatesAreReportedWithoutContactingSidecar() {
        for (EnvironmentStatus status : List.of(EnvironmentStatus.DISCONNECTED, EnvironmentStatus.PENDING_RECONNECT)) {
            when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution(status)));
            StepVerifier.create(handler.handle("ws-1", 1, request(10)))
                    .assertNext(result -> {
                        assertThat(result.status()).isEqualTo(status);
                        assertThat(result.lines()).isEmpty();
                    })
                    .verifyComplete();
        }
        verifyNoInteractions(environmentRpcClient);
    }

    @Test
    void noEnvironment_throwsNotFound() {
        SandboxExecution execution = execution(EnvironmentStatus.CONNECTED);
        execution.setEnvironment(null);
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        assertErrorCode(request(10), JsonRpcErrorCodes.RESOURCE_NOT_FOUND);
    }

    @Test
    void notAuthenticated_throws() {
        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.empty());

        assertErrorCode(request(10), JsonRpcErrorCodes.NOT_AUTHENTICATED);
    }

    @Test
    void invalidExecutionId_throws() {
        assertErrorCode(new ClientRpcPayload.ExecutionGetLogs("nope", null), JsonRpcErrorCodes.INVALID_PARAMS);
    }

    @Test
    void executionNotFound_throws() {
        when(executionRepository.findById(executionId)).thenReturn(Optional.empty());

        assertErrorCode(request(10), JsonRpcErrorCodes.RESOURCE_NOT_FOUND);
    }

    @Test
    void notTeamMember_throwsWithoutContactingSidecar() {
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution(EnvironmentStatus.CONNECTED)));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(false);

        assertErrorCode(request(10), JsonRpcErrorCodes.NOT_AUTHORIZED);
        verifyNoInteractions(environmentRpcClient);
    }

    @Test
    void payloadRejectsOutOfRangeTailLines() {
        assertThatThrownBy(() -> new ClientRpcPayload.ExecutionGetLogs(executionId.toString(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientRpcPayload.ExecutionGetLogs(
                        executionId.toString(), ClientRpcPayload.ExecutionGetLogs.MAX_TAIL_LINES + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void methodNameAndPayloadType_areCorrect() {
        assertThat(handler.getMethodName()).isEqualTo(ClientRpcPayload.ExecutionGetLogs.METHOD);
        assertThat(handler.getPayloadType()).isEqualTo(ClientRpcPayload.ExecutionGetLogs.class);
    }

    private void assertErrorCode(ClientRpcPayload.ExecutionGetLogs params, int code) {
        assertThatThrownBy(() -> handler.handle("ws-1", 1, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex ->
                        assertThat(((RpcErrorException) ex).error().code()).isEqualTo(code));
    }

    private ClientRpcPayload.ExecutionGetLogs request(Integer tailLines) {
        return new ClientRpcPayload.ExecutionGetLogs(executionId.toString(), tailLines);
    }

    private SandboxExecution execution(EnvironmentStatus status) {
        Team team = new Team();
        team.setId(teamId);
        ChatEntity chat = new ChatEntity();
        chat.setTeam(team);
        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setId(environmentId);
        environment.setStatus(status);
        SandboxExecution execution = new SandboxExecution();
        execution.setId(executionId);
        execution.setChat(chat);
        execution.setEnvironment(environment);
        return execution;
    }
}
