package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.EnvironmentRegisterStatus;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.EnvironmentRegisterResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.EnvironmentRegisteredEvent;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.PendingHitlRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
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
class EnvironmentRegisterRpcHandlerTest {

    @Mock
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private PendingHitlRegistry pendingHitlRegistry;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EnvironmentRegisterRpcHandler handler;

    private final UUID envId = UUID.randomUUID();
    private final UUID teamId = UUID.randomUUID();
    private final String authToken = "test-token";

    @BeforeEach
    void setUp() {
        handler = new EnvironmentRegisterRpcHandler(
                executionEnvironmentRepository, sessionRegistry, pendingHitlRegistry, eventPublisher);
    }

    private ExecutionEnvironment createEnvironment() {
        Team team = new Team();
        team.setId(teamId);
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(envId);
        env.setTeam(team);
        env.setAuthToken(authToken);
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        return env;
    }

    @Test
    void handle_withValidInitialRegistration_shouldRegisterEnvironment() {
        ExecutionEnvironment env = createEnvironment();
        when(executionEnvironmentRepository.findByAuthToken(authToken)).thenReturn(Optional.of(env));

        EnvironmentRpcPayload.Register params = new EnvironmentRpcPayload.Register(
                authToken, "container-123", false, null, false, 0L, null, 1, "0.1.0");
        JsonRpcInboundRequest request =
                new JsonRpcInboundRequest(EnvironmentRpcPayload.Register.METHOD, objectMapper.valueToTree(params), 1);

        EnvironmentResponsePayload response =
                handler.handle("ws-session-1", request, params).blockLast();

        assertThat(response).isNotNull();
        EnvironmentRegisterResult result = (EnvironmentRegisterResult) response;
        assertThat(result.environmentId()).isEqualTo(envId.toString());
        assertThat(result.status()).isEqualTo(EnvironmentRegisterStatus.REGISTERED);

        verify(executionEnvironmentRepository).save(env);
        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.CONNECTED);
        assertThat(env.getContainerId()).isEqualTo("container-123");
        assertThat(env.getLastHeartbeat()).isNotNull();

        verify(sessionRegistry).registerEnvironmentSession(any(String.class), eq(envId));
        verify(eventPublisher).publishEvent(any(TeamEntityChangedEvent.class));
        verify(eventPublisher).publishEvent(any(EnvironmentRegisteredEvent.class));
    }

    @Test
    void handle_withValidInitialRegistration_shouldNotPublishReconnectEvents() {
        ExecutionEnvironment env = createEnvironment();
        when(executionEnvironmentRepository.findByAuthToken(authToken)).thenReturn(Optional.of(env));

        EnvironmentRpcPayload.Register params =
                new EnvironmentRpcPayload.Register(authToken, null, false, null, false, 0L, null, 1, "0.1.0");
        JsonRpcInboundRequest request =
                new JsonRpcInboundRequest(EnvironmentRpcPayload.Register.METHOD, objectMapper.valueToTree(params), 1);

        EnvironmentResponsePayload response =
                handler.handle("ws-session-1", request, params).blockLast();

        assertThat(response).isNotNull();
        EnvironmentRegisterResult result = (EnvironmentRegisterResult) response;
        assertThat(result.status()).isEqualTo(EnvironmentRegisterStatus.REGISTERED);

        verify(sessionRegistry).registerEnvironmentSession(any(String.class), eq(envId));
        verify(eventPublisher).publishEvent(any(EnvironmentRegisteredEvent.class));
        verify(sessionRegistry, never()).replaceSessionForEnvironment(any(String.class), any());
        verify(pendingHitlRegistry, never()).rebindBySession(any(), any(String.class));
    }

    @Test
    void handle_withReconnect_shouldReplaceSession() {
        ExecutionEnvironment env = createEnvironment();
        when(executionEnvironmentRepository.findByAuthToken(authToken)).thenReturn(Optional.of(env));
        String oldSessionId = "ws-old";
        when(sessionRegistry.replaceSessionForEnvironment(any(String.class), eq(envId)))
                .thenReturn(oldSessionId);
        when(pendingHitlRegistry.rebindBySession(eq(oldSessionId), any(String.class)))
                .thenReturn(0);

        EnvironmentRpcPayload.Register params = new EnvironmentRpcPayload.Register(
                authToken, null, true, "acp-session-123", true, 42L, null, 1, "0.1.0");
        JsonRpcInboundRequest request =
                new JsonRpcInboundRequest(EnvironmentRpcPayload.Register.METHOD, objectMapper.valueToTree(params), 1);

        EnvironmentResponsePayload response =
                handler.handle("ws-new", request, params).blockLast();

        assertThat(response).isNotNull();
        EnvironmentRegisterResult result = (EnvironmentRegisterResult) response;
        assertThat(result.environmentId()).isEqualTo(envId.toString());
        assertThat(result.status()).isEqualTo(EnvironmentRegisterStatus.RECONNECTED);

        verify(sessionRegistry).replaceSessionForEnvironment(any(String.class), eq(envId));
        verify(pendingHitlRegistry).rebindBySession(eq(oldSessionId), any(String.class));
        verify(executionEnvironmentRepository).save(env);
        assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.CONNECTED);
        assertThat(env.getLastHeartbeat()).isNotNull();

        verify(eventPublisher).publishEvent(any(TeamEntityChangedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(EnvironmentRegisteredEvent.class));
    }

    @Test
    void handle_withReconnect_shouldRebindPendingPermissions() {
        ExecutionEnvironment env = createEnvironment();
        when(executionEnvironmentRepository.findByAuthToken(authToken)).thenReturn(Optional.of(env));
        String oldSessionId = "ws-old";
        when(sessionRegistry.replaceSessionForEnvironment(any(String.class), eq(envId)))
                .thenReturn(oldSessionId);
        when(pendingHitlRegistry.rebindBySession(eq(oldSessionId), any(String.class)))
                .thenReturn(3);

        EnvironmentRpcPayload.Register params = new EnvironmentRpcPayload.Register(
                authToken, null, true, null, false, 0L, List.of("perm-1", "perm-2", "perm-3"), 1, "0.1.0");
        JsonRpcInboundRequest request =
                new JsonRpcInboundRequest(EnvironmentRpcPayload.Register.METHOD, objectMapper.valueToTree(params), 1);

        EnvironmentResponsePayload response =
                handler.handle("ws-new", request, params).blockLast();

        assertThat(response).isNotNull();
        verify(pendingHitlRegistry).rebindBySession(eq(oldSessionId), any(String.class));
    }

    @Test
    void handle_withReconnectAndNoOldSession_shouldNotRebindPermissions() {
        ExecutionEnvironment env = createEnvironment();
        when(executionEnvironmentRepository.findByAuthToken(authToken)).thenReturn(Optional.of(env));
        when(sessionRegistry.replaceSessionForEnvironment(any(String.class), eq(envId)))
                .thenReturn(null);

        EnvironmentRpcPayload.Register params =
                new EnvironmentRpcPayload.Register(authToken, null, true, null, false, 0L, null, 1, "0.1.0");
        JsonRpcInboundRequest request =
                new JsonRpcInboundRequest(EnvironmentRpcPayload.Register.METHOD, objectMapper.valueToTree(params), 1);

        EnvironmentResponsePayload response =
                handler.handle("ws-new", request, params).blockLast();

        assertThat(response).isNotNull();
        verify(sessionRegistry).replaceSessionForEnvironment(any(String.class), eq(envId));
        verify(pendingHitlRegistry, never()).rebindBySession(any(), any(String.class));
    }

    @Test
    void handle_withInvalidToken_shouldReturnError() {
        when(executionEnvironmentRepository.findByAuthToken("invalid-token")).thenReturn(Optional.empty());

        EnvironmentRpcPayload.Register params =
                new EnvironmentRpcPayload.Register("invalid-token", null, false, null, false, 0L, null, 1, "0.1.0");
        JsonRpcInboundRequest request =
                new JsonRpcInboundRequest(EnvironmentRpcPayload.Register.METHOD, objectMapper.valueToTree(params), 1);

        assertThatThrownBy(() -> handler.handle("ws-session-1", request, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(e -> {
                    assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32001);
                    assertThat(((RpcErrorException) e).error().message()).isEqualTo("Invalid environment token");
                });
    }

    @Test
    void handle_withMissingToken_isRejectedByPayloadValidation() {
        // Passing a null token deliberately to verify the record compact constructor rejects it.
        assertThatThrownBy(this::registerWithMissingToken).isInstanceOf(NullPointerException.class);
    }

    @SuppressFBWarnings({
        "NP_NULL_PARAM_DEREF",
        "NP_NULL_PARAM_DEREF_NONVIRTUAL",
        "NP_NULL_PARAM_DEREF_ALL_TARGETS_DANGEROUS"
    })
    private void registerWithMissingToken() {
        new EnvironmentRpcPayload.Register(null, null, false, null, false, 0L, null, 1, "0.1.0");
    }

    @Test
    void handle_withNullParams_throwsNullPointerException() {
        // Passing null params deliberately to verify the handler rejects them.
        assertThatThrownBy(this::handleWithNullParams).isInstanceOf(NullPointerException.class);
    }

    @SuppressFBWarnings({"NP_NULL_PARAM_DEREF", "NP_NULL_PARAM_DEREF_ALL_TARGETS_DANGEROUS"})
    private void handleWithNullParams() {
        handler.handle("ws-session-1", null, null);
    }

    @Test
    void getMethodName_shouldReturnEnvRegister() {
        assertThat(handler.getMethodName()).isEqualTo("env.register");
    }

    @Test
    void handle_withReconnect_shouldPublishTeamEntityChangedEvent() {
        ExecutionEnvironment env = createEnvironment();
        when(executionEnvironmentRepository.findByAuthToken(authToken)).thenReturn(Optional.of(env));
        when(sessionRegistry.replaceSessionForEnvironment(any(String.class), eq(envId)))
                .thenReturn("ws-old");
        when(pendingHitlRegistry.rebindBySession(any(), any(String.class))).thenReturn(0);

        EnvironmentRpcPayload.Register params =
                new EnvironmentRpcPayload.Register(authToken, null, true, null, false, 0L, null, 1, "0.1.0");
        JsonRpcInboundRequest request =
                new JsonRpcInboundRequest(EnvironmentRpcPayload.Register.METHOD, objectMapper.valueToTree(params), 1);

        handler.handle("ws-new", request, params);

        ArgumentCaptor<TeamEntityChangedEvent> captor = ArgumentCaptor.forClass(TeamEntityChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        TeamEntityChangedEvent event = captor.getValue();
        assertThat(event.teamId()).isEqualTo(teamId);
        assertThat(event.type()).isEqualTo(TeamEntityType.ENVIRONMENTS);
    }
}
