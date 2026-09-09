package com.kratisai.controlplane.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class PendingHitlTimeoutServiceTest {

    @Mock
    private PendingHitlRegistry pendingHitlRegistry;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private EnvironmentRealtimeEventListeners environmentListeners;

    @Mock
    private WebSocketSession session;

    private PendingHitlTimeoutService service;

    @BeforeEach
    void setUp() {
        service = new PendingHitlTimeoutService(pendingHitlRegistry, eventPublisher, environmentListeners);
    }

    private PendingHitlRegistry.PendingHitl pending(UUID teamId, HitlKind kind, String hitlId, String command) {
        return new PendingHitlRegistry.PendingHitl(
                kind, session, 7, hitlId, "message", command, null, null, null, null, null, Instant.now(), teamId);
    }

    @Test
    void cleanupExpiredHitl_approvalPublishesTimeoutAndRepliesAfterCommit() {
        UUID executionId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        PendingHitlRegistry.PendingHitl hitl = pending(teamId, HitlKind.APPROVAL, "tool-call-42", "rm -rf /");
        when(pendingHitlRegistry.removeExpired()).thenReturn(Map.of(executionId, hitl));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.cleanupExpiredHitl();

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            SandboxExecutionHitlResolvedEvent event = (SandboxExecutionHitlResolvedEvent) eventCaptor.getValue();
            Assertions.assertThat(event.executionId()).isEqualTo(executionId);
            Assertions.assertThat(event.hitlId()).isEqualTo("tool-call-42");
            Assertions.assertThat(event.kind()).isEqualTo(HitlKind.APPROVAL);
            Assertions.assertThat(event.response()).isEqualTo(HitlResponse.CANCELLED);
            Assertions.assertThat(event.resolvedByDisplayName()).isEqualTo("System (timeout)");

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(environmentListeners)
                    .replyToSidecar(eq(hitl), eq(executionId), eq(HitlResponse.CANCELLED), eq(null), eq(null));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cleanupExpiredHitl_questionPublishesTimeoutAndRepliesAfterCommit() {
        UUID executionId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        PendingHitlRegistry.PendingHitl hitl = pending(teamId, HitlKind.QUESTION, "el-1", null);
        when(pendingHitlRegistry.removeExpired()).thenReturn(Map.of(executionId, hitl));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.cleanupExpiredHitl();

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            SandboxExecutionHitlResolvedEvent event = (SandboxExecutionHitlResolvedEvent) eventCaptor.getValue();
            Assertions.assertThat(event.executionId()).isEqualTo(executionId);
            Assertions.assertThat(event.hitlId()).isEqualTo("el-1");
            Assertions.assertThat(event.kind()).isEqualTo(HitlKind.QUESTION);
            Assertions.assertThat(event.response()).isEqualTo(HitlResponse.CANCELLED);
            Assertions.assertThat(event.resolvedByDisplayName()).isEqualTo("System (timeout)");

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(environmentListeners)
                    .replyToSidecar(eq(hitl), eq(executionId), eq(HitlResponse.CANCELLED), eq(null), eq(null));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cleanupExpiredHitl_noExpiredEntriesDoesNothing() {
        when(pendingHitlRegistry.removeExpired()).thenReturn(Map.of());
        service.cleanupExpiredHitl();
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }
}
