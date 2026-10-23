package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ActivityDetail;
import com.kratisai.controlplane.api.wsdto.ActivityHitl;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResolverKind;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.time.Instant;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class PendingHitlTimeoutServiceTest {

    @Mock
    private SandboxExecutionActivityRepository activityRepository;

    @Mock
    private SandboxExecutionRepository executionRepository;

    @Mock
    private ExecutionActivityPersistenceService activityPersistenceService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SandboxExecutionService sandboxExecutionService;

    private PendingHitlTimeoutService service;

    @BeforeEach
    void setUp() {
        service = new PendingHitlTimeoutService(
                activityRepository,
                executionRepository,
                activityPersistenceService,
                eventPublisher,
                sandboxExecutionService);
    }

    private SandboxExecutionActivity activity(UUID executionId, String actionId, String description) {
        return new SandboxExecutionActivity(
                executionId, 1, actionId, ActivityType.COMMAND, ActivityStatus.PENDING, description, null);
    }

    private void stubTeam(UUID executionId, UUID teamId) {
        SandboxExecution execution = mock(SandboxExecution.class);
        ChatEntity chat = mock(ChatEntity.class);
        Team team = mock(Team.class);
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(execution.getChat()).thenReturn(chat);
        when(chat.getTeam()).thenReturn(team);
        when(team.getId()).thenReturn(teamId);
    }

    private ActivityDetail detail(HitlKind kind, String message) {
        ActivityHitl hitl =
                new ActivityHitl(kind, null, message, null, null, null, null, null, null, null, null, null, null, null);
        return new ActivityDetail(null, null, null, null, null, null, null, null, null, null, null).withHitl(hitl);
    }

    @Test
    void cleanupExpiredHitl_approvalPublishesTimeoutAndDispatchesSteering() {
        UUID executionId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        SandboxExecutionActivity row = activity(executionId, "tool-call-42", "rm -rf /");
        when(activityRepository.findByStatusAndCreatedAtBefore(eq(ActivityStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(row));
        stubTeam(executionId, teamId);
        when(activityPersistenceService.detailOf(row)).thenReturn(detail(HitlKind.APPROVAL, "Remove build artifacts"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.cleanupExpiredHitl();

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            SandboxExecutionHitlResolvedEvent event = (SandboxExecutionHitlResolvedEvent) eventCaptor.getValue();
            assertThat(event.result().executionId()).isEqualTo(executionId);
            assertThat(event.result().actionId()).isEqualTo("tool-call-42");
            assertThat(event.result().kind()).isEqualTo(HitlKind.APPROVAL);
            assertThat(event.result().response()).isEqualTo(HitlResponse.CANCELLED);
            assertThat(event.result().resolvedBy().kind()).isEqualTo(HitlResolverKind.TIMEOUT);

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(sandboxExecutionService).dispatchSystemSteering(eq(executionId), promptCaptor.capture());
            assertThat(promptCaptor.getValue())
                    .contains("no response was received")
                    .contains("not a rejection")
                    .contains("Continue the task");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cleanupExpiredHitl_questionPublishesTimeoutAndDispatchesSteering() {
        UUID executionId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        SandboxExecutionActivity row = activity(executionId, "el-1", "Choose a target");
        when(activityRepository.findByStatusAndCreatedAtBefore(eq(ActivityStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(row));
        stubTeam(executionId, teamId);
        when(activityPersistenceService.detailOf(row)).thenReturn(detail(HitlKind.QUESTION, "Choose a target"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.cleanupExpiredHitl();

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            SandboxExecutionHitlResolvedEvent event = (SandboxExecutionHitlResolvedEvent) eventCaptor.getValue();
            assertThat(event.result().executionId()).isEqualTo(executionId);
            assertThat(event.result().actionId()).isEqualTo("el-1");
            assertThat(event.result().kind()).isEqualTo(HitlKind.QUESTION);
            assertThat(event.result().response()).isEqualTo(HitlResponse.CANCELLED);
            assertThat(event.result().resolvedBy().kind()).isEqualTo(HitlResolverKind.TIMEOUT);

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(sandboxExecutionService).dispatchSystemSteering(eq(executionId), any(String.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cleanupExpiredHitl_noExpiredEntriesDoesNothing() {
        when(activityRepository.findByStatusAndCreatedAtBefore(eq(ActivityStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of());
        service.cleanupExpiredHitl();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void timeoutPrompt_mentionsRequestMessageAndNoResponseGuidance() {
        String prompt = PendingHitlTimeoutService.timeoutPrompt(HitlKind.APPROVAL, "Remove build artifacts");

        assertThat(prompt)
                .contains("approval request")
                .contains("Remove build artifacts")
                .contains("no response was received")
                .contains("not a rejection")
                .contains("Continue the task");
    }

    @Test
    void timeoutPrompt_abbreviatesLongRequestMessages() {
        String prompt = PendingHitlTimeoutService.timeoutPrompt(HitlKind.QUESTION, "x".repeat(500));

        assertThat(prompt).contains("question request").contains("…");
        assertThat(prompt).doesNotContain("x".repeat(500));
    }
}
