package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.HitlActivityResult;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlRequestSnapshot;
import com.kratisai.controlplane.api.wsdto.HitlResolution;
import com.kratisai.controlplane.api.wsdto.HitlResolver;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import java.time.Instant;
import java.util.Map;
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
    private PendingHitlRegistry pendingHitlRegistry;

    @Mock
    private EnvironmentRpcClient environmentRpcClient;

    @Mock
    private WebSocketSession session;

    private EnvironmentRealtimeEventListeners listeners;

    @BeforeEach
    void setUp() {
        listeners = new EnvironmentRealtimeEventListeners(pendingHitlRegistry, environmentRpcClient);
        lenient().when(session.getId()).thenReturn("ws-1");
    }

    private PendingHitlRegistry.PendingHitl pendingApproval(Object requestId) {
        return new PendingHitlRegistry.PendingHitl(
                new HitlRequestSnapshot(
                        UUID.randomUUID(),
                        "tool-call-1",
                        HitlKind.APPROVAL,
                        "Approve ls",
                        "ls",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                session,
                requestId,
                Instant.now(),
                UUID.randomUUID());
    }

    private PendingHitlRegistry.PendingHitl pendingQuestion(Object requestId) {
        return new PendingHitlRegistry.PendingHitl(
                new HitlRequestSnapshot(
                        UUID.randomUUID(),
                        "el-1",
                        HitlKind.QUESTION,
                        "Pick a target",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of("type", "object"),
                        null,
                        null),
                session,
                requestId,
                Instant.now(),
                UUID.randomUUID());
    }

    @Test
    void onHitlResolved_sendsApprovedToSidecarWhenPending() {
        UUID executionId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        PendingHitlRegistry.PendingHitl pendingRequest = pendingApproval(42);
        when(pendingHitlRegistry.remove(executionId, "tool-call-1")).thenReturn(pendingRequest);
        when(session.isOpen()).thenReturn(true);

        listeners.onSandboxExecutionHitlResolvedEvent(new SandboxExecutionHitlResolvedEvent(
                teamId,
                new HitlResolution(
                        executionId,
                        "tool-call-1",
                        HitlKind.APPROVAL,
                        HitlResponse.APPROVED,
                        "allow-once",
                        null,
                        HitlResolver.user(UUID.randomUUID(), "Bob"))));

        ArgumentCaptor<HitlActivityResult> captor = ArgumentCaptor.forClass(HitlActivityResult.class);
        verify(environmentRpcClient).reply(any(String.class), eq(42), captor.capture());
        assertThat(captor.getValue().response()).isEqualTo(HitlResponse.APPROVED);
        assertThat(captor.getValue().optionId()).isEqualTo("allow-once");
    }

    @Test
    void onHitlResolved_cancelled_sendsCancelled() {
        UUID executionId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        PendingHitlRegistry.PendingHitl pendingRequest = pendingApproval(42);
        when(pendingHitlRegistry.remove(executionId, "tool-call-1")).thenReturn(pendingRequest);
        when(session.isOpen()).thenReturn(true);

        listeners.onSandboxExecutionHitlResolvedEvent(new SandboxExecutionHitlResolvedEvent(
                teamId,
                new HitlResolution(
                        executionId,
                        "tool-call-1",
                        HitlKind.APPROVAL,
                        HitlResponse.CANCELLED,
                        null,
                        null,
                        HitlResolver.timeout())));

        ArgumentCaptor<HitlActivityResult> captor = ArgumentCaptor.forClass(HitlActivityResult.class);
        verify(environmentRpcClient).reply(any(String.class), eq(42), captor.capture());
        assertThat(captor.getValue().response()).isEqualTo(HitlResponse.CANCELLED);
    }

    @Test
    void onHitlResolved_skipsWhenNoPendingRequest() {
        UUID executionId = UUID.randomUUID();
        when(pendingHitlRegistry.remove(executionId, "tool-call-1")).thenReturn(null);

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

        verify(environmentRpcClient, never()).reply(any(), any(), any());
    }

    @Test
    void replyToSidecar_skipsClosedSession() {
        UUID executionId = UUID.randomUUID();
        PendingHitlRegistry.PendingHitl pendingRequest = pendingApproval(1);
        when(session.isOpen()).thenReturn(false);

        listeners.replyToSidecar(pendingRequest, executionId, HitlResponse.CANCELLED, null, null);

        verify(environmentRpcClient, never()).reply(any(), any(), any());
    }

    @Test
    void onHitlResolved_sendsAnsweredToSidecarWhenPending() {
        UUID executionId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        PendingHitlRegistry.PendingHitl pendingRequest = pendingQuestion(7);
        when(pendingHitlRegistry.remove(executionId, "el-1")).thenReturn(pendingRequest);
        when(session.isOpen()).thenReturn(true);

        listeners.onSandboxExecutionHitlResolvedEvent(new SandboxExecutionHitlResolvedEvent(
                teamId,
                new HitlResolution(
                        executionId,
                        "el-1",
                        HitlKind.QUESTION,
                        HitlResponse.ANSWERED,
                        null,
                        Map.of("target", "staging"),
                        HitlResolver.user(UUID.randomUUID(), "Alice"))));

        ArgumentCaptor<HitlActivityResult> captor = ArgumentCaptor.forClass(HitlActivityResult.class);
        verify(environmentRpcClient).reply(any(String.class), eq(7), captor.capture());
        assertThat(captor.getValue().response()).isEqualTo(HitlResponse.ANSWERED);
        assertThat(captor.getValue().content()).isEqualTo(Map.of("target", "staging"));
    }

    @Test
    void onHitlResolved_declined_sendsCancelledToSidecar() {
        UUID executionId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        PendingHitlRegistry.PendingHitl pendingRequest = pendingQuestion(7);
        when(pendingHitlRegistry.remove(executionId, "el-1")).thenReturn(pendingRequest);
        when(session.isOpen()).thenReturn(true);

        listeners.onSandboxExecutionHitlResolvedEvent(new SandboxExecutionHitlResolvedEvent(
                teamId,
                new HitlResolution(
                        executionId,
                        "el-1",
                        HitlKind.QUESTION,
                        HitlResponse.DECLINED,
                        null,
                        null,
                        HitlResolver.timeout())));

        ArgumentCaptor<HitlActivityResult> captor = ArgumentCaptor.forClass(HitlActivityResult.class);
        verify(environmentRpcClient).reply(any(String.class), eq(7), captor.capture());
        assertThat(captor.getValue().response()).isEqualTo(HitlResponse.CANCELLED);
    }

    @Test
    void onHitlResolved_skipsWhenNoPendingQuestion() {
        UUID executionId = UUID.randomUUID();
        when(pendingHitlRegistry.remove(executionId, "el-1")).thenReturn(null);

        listeners.onSandboxExecutionHitlResolvedEvent(new SandboxExecutionHitlResolvedEvent(
                UUID.randomUUID(),
                new HitlResolution(
                        executionId,
                        "el-1",
                        HitlKind.QUESTION,
                        HitlResponse.CANCELLED,
                        null,
                        null,
                        HitlResolver.timeout())));

        verify(environmentRpcClient, never()).reply(any(), any(), any());
    }

    @Test
    void replyToSidecar_question_skipsClosedSession() {
        UUID executionId = UUID.randomUUID();
        PendingHitlRegistry.PendingHitl pendingRequest = pendingQuestion(2);
        when(session.isOpen()).thenReturn(false);

        listeners.replyToSidecar(pendingRequest, executionId, HitlResponse.DECLINED, null, null);

        verify(environmentRpcClient, never()).reply(any(), any(), any());
    }
}
