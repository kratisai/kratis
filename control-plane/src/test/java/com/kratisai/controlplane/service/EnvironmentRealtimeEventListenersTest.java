package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResolution;
import com.kratisai.controlplane.api.wsdto.HitlResolver;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class EnvironmentRealtimeEventListenersTest {

    @Mock
    private SandboxExecutionRepository executionRepository;

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private EnvironmentRpcClient environmentRpcClient;

    @Mock
    private WebSocketSession session;

    private EnvironmentRealtimeEventListeners listeners;

    @BeforeEach
    void setUp() {
        listeners = new EnvironmentRealtimeEventListeners(executionRepository, sessionRegistry, environmentRpcClient);
    }

    private void stubOpenSession(UUID environmentId) {
        when(executionRepository.findEnvironmentIdByExecutionId(any())).thenReturn(Optional.of(environmentId));
        when(sessionRegistry.getSessionForEnvironment(environmentId)).thenReturn(session);
        when(session.isOpen()).thenReturn(true);
    }

    @Test
    void onHitlResolved_sendsApprovedToSidecar() {
        UUID executionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        stubOpenSession(environmentId);

        listeners.onSandboxExecutionHitlResolvedEvent(new SandboxExecutionHitlResolvedEvent(
                UUID.randomUUID(),
                new HitlResolution(
                        executionId,
                        "tool-call-1",
                        HitlKind.APPROVAL,
                        HitlResponse.APPROVED,
                        "allow-once",
                        null,
                        HitlResolver.user(UUID.randomUUID(), "Bob"))));

        ArgumentCaptor<EnvironmentRpcPayload.OutboundNotificationPayload> captor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.OutboundNotificationPayload.class);
        verify(environmentRpcClient).send(eq(environmentId), captor.capture());
        EnvironmentRpcPayload.HitlResolved resolved = (EnvironmentRpcPayload.HitlResolved) captor.getValue();
        assertThat(resolved.actionId()).isEqualTo("tool-call-1");
        assertThat(resolved.response()).isEqualTo(HitlResponse.APPROVED);
        assertThat(resolved.optionId()).isEqualTo("allow-once");
    }

    @Test
    void onHitlResolved_sendsAnsweredContent() {
        UUID executionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        stubOpenSession(environmentId);

        listeners.onSandboxExecutionHitlResolvedEvent(new SandboxExecutionHitlResolvedEvent(
                UUID.randomUUID(),
                new HitlResolution(
                        executionId,
                        "el-1",
                        HitlKind.QUESTION,
                        HitlResponse.ANSWERED,
                        null,
                        Map.of("target", "staging"),
                        HitlResolver.user(UUID.randomUUID(), "Alice"))));

        ArgumentCaptor<EnvironmentRpcPayload.OutboundNotificationPayload> captor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.OutboundNotificationPayload.class);
        verify(environmentRpcClient).send(eq(environmentId), captor.capture());
        EnvironmentRpcPayload.HitlResolved resolved = (EnvironmentRpcPayload.HitlResolved) captor.getValue();
        assertThat(resolved.response()).isEqualTo(HitlResponse.ANSWERED);
        assertThat(resolved.content()).isEqualTo(Map.of("target", "staging"));
    }

    @Test
    void onHitlResolved_skipsWhenExecutionHasNoEnvironment() {
        UUID executionId = UUID.randomUUID();
        when(executionRepository.findEnvironmentIdByExecutionId(executionId)).thenReturn(Optional.empty());

        listeners.onSandboxExecutionHitlResolvedEvent(new SandboxExecutionHitlResolvedEvent(
                UUID.randomUUID(),
                new HitlResolution(
                        executionId,
                        "tool-call-1",
                        HitlKind.APPROVAL,
                        HitlResponse.CANCELLED,
                        null,
                        null,
                        HitlResolver.timeout())));

        verify(environmentRpcClient, never()).send(any(), any());
    }

    @Test
    void onHitlResolved_skipsClosedSession() {
        UUID executionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        when(executionRepository.findEnvironmentIdByExecutionId(executionId)).thenReturn(Optional.of(environmentId));
        when(sessionRegistry.getSessionForEnvironment(environmentId)).thenReturn(session);
        when(session.isOpen()).thenReturn(false);

        listeners.onSandboxExecutionHitlResolvedEvent(new SandboxExecutionHitlResolvedEvent(
                UUID.randomUUID(),
                new HitlResolution(
                        executionId,
                        "tool-call-1",
                        HitlKind.APPROVAL,
                        HitlResponse.CANCELLED,
                        null,
                        null,
                        HitlResolver.timeout())));

        verify(environmentRpcClient, never()).send(any(), any());
    }
}
