package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.ActivityDetail;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlRequiredResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlResolvedResult;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.PlanEntry;
import com.kratisai.controlplane.api.wsdto.PlanEntryPriority;
import com.kratisai.controlplane.api.wsdto.PlanEntryStatus;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlRequiredEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionActivityPersistenceServiceTest {

    @Mock
    private SandboxExecutionActivityRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExecutionActivityPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new ExecutionActivityPersistenceService(repository, objectMapper);
    }

    private static SandboxExecutionActivity row(
            UUID executionId,
            long sequence,
            String actionId,
            ActivityType type,
            ActivityStatus status,
            String description) {
        return new SandboxExecutionActivity(executionId, sequence, actionId, type, status, description, null);
    }

    private SandboxExecutionActivity capturedSave() {
        ArgumentCaptor<SandboxExecutionActivity> captor = ArgumentCaptor.forClass(SandboxExecutionActivity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private void assertInsertedRow(
            SandboxExecutionActivity saved,
            UUID executionId,
            long sequence,
            String actionId,
            ActivityType type,
            ActivityStatus status,
            String description) {
        assertThat(saved.getExecutionId()).isEqualTo(executionId);
        assertThat(saved.getSequence()).isEqualTo(sequence);
        assertThat(saved.getActionId()).isEqualTo(actionId);
        assertThat(saved.getActivityType()).isEqualTo(type);
        assertThat(saved.getStatus()).isEqualTo(status);
        assertThat(saved.getDescription()).isEqualTo(description);
    }

    @Test
    void recordActivity_withoutActionId_insertsStandaloneRows() {
        UUID executionId = UUID.randomUUID();
        when(repository.nextSequence(executionId)).thenReturn(1L, 2L);

        service.recordActivity(executionId, ActivityType.MESSAGE, "first", null, ActivityStatus.IN_PROGRESS, null);
        service.recordActivity(executionId, ActivityType.MESSAGE, "second", " ", ActivityStatus.IN_PROGRESS, null);

        ArgumentCaptor<SandboxExecutionActivity> captor = ArgumentCaptor.forClass(SandboxExecutionActivity.class);
        verify(repository, times(2)).save(captor.capture());
        assertInsertedRow(
                captor.getAllValues().get(0),
                executionId,
                1,
                null,
                ActivityType.MESSAGE,
                ActivityStatus.IN_PROGRESS,
                "first");
        assertInsertedRow(
                captor.getAllValues().get(1),
                executionId,
                2,
                null,
                ActivityType.MESSAGE,
                ActivityStatus.IN_PROGRESS,
                "second");
    }

    @Test
    void recordActivity_messageEmissionReplacesDescription() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity existing =
                row(executionId, 1, "msg-1", ActivityType.MESSAGE, ActivityStatus.IN_PROGRESS, "Hello ");
        when(repository.findByExecutionIdAndActionId(executionId, "msg-1")).thenReturn(Optional.of(existing));

        service.recordActivity(
                executionId, ActivityType.MESSAGE, "Hello world", "msg-1", ActivityStatus.IN_PROGRESS, null);

        assertThat(existing.getDescription()).isEqualTo("Hello world");
        verify(repository).save(existing);
        verify(repository, never()).nextSequence(executionId);
    }

    @Test
    void recordActivity_thinkingEmissionReplacesDescription() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity existing =
                row(executionId, 1, "thought-1", ActivityType.THINKING, ActivityStatus.IN_PROGRESS, "part one ");
        when(repository.findByExecutionIdAndActionId(executionId, "thought-1")).thenReturn(Optional.of(existing));

        service.recordActivity(
                executionId, ActivityType.THINKING, "part one part two", "thought-1", ActivityStatus.IN_PROGRESS, null);

        assertThat(existing.getDescription()).isEqualTo("part one part two");
        verify(repository).save(existing);
    }

    @Test
    void recordActivity_commandUpdatesStatusAndDescriptionInPlace() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity existing =
                row(executionId, 1, "tc-1", ActivityType.COMMAND, ActivityStatus.IN_PROGRESS, "npm run build");
        when(repository.findByExecutionIdAndActionId(executionId, "tc-1")).thenReturn(Optional.of(existing));

        service.recordActivity(
                executionId, ActivityType.COMMAND, "npm run build", "tc-1", ActivityStatus.COMPLETED, null);

        assertThat(existing.getDescription()).isEqualTo("npm run build");
        assertThat(existing.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        verify(repository).save(existing);
    }

    @Test
    void recordActivity_researchAndEditedStatusesUpdateInPlace() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity research =
                row(executionId, 1, "r-1", ActivityType.RESEARCH, ActivityStatus.IN_PROGRESS, "search");
        SandboxExecutionActivity edited =
                row(executionId, 2, "e-1", ActivityType.EDITED, ActivityStatus.IN_PROGRESS, "edit");
        when(repository.findByExecutionIdAndActionId(executionId, "r-1")).thenReturn(Optional.of(research));
        when(repository.findByExecutionIdAndActionId(executionId, "e-1")).thenReturn(Optional.of(edited));

        service.recordActivity(executionId, ActivityType.RESEARCH, "search", "r-1", ActivityStatus.FAILED, null);
        service.recordActivity(executionId, ActivityType.EDITED, "edit", "e-1", ActivityStatus.COMPLETED, null);

        assertThat(research.getStatus()).isEqualTo(ActivityStatus.FAILED);
        assertThat(edited.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
    }

    @Test
    void recordActivity_withUnknownActionId_insertsNewRow() {
        UUID executionId = UUID.randomUUID();
        when(repository.findByExecutionIdAndActionId(executionId, "tc-1")).thenReturn(Optional.empty());
        when(repository.nextSequence(executionId)).thenReturn(1L);

        service.recordActivity(executionId, ActivityType.COMMAND, "ls", "tc-1", ActivityStatus.PENDING, null);

        assertInsertedRow(capturedSave(), executionId, 1, "tc-1", ActivityType.COMMAND, ActivityStatus.PENDING, "ls");
    }

    @Test
    void recordActivity_lateToolKindRefinesTypeInPlace_insertsOnce() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity existing =
                row(executionId, 1, "tc-1", ActivityType.COMMAND, ActivityStatus.IN_PROGRESS, "write_file");
        when(repository.findByExecutionIdAndActionId(executionId, "tc-1")).thenReturn(Optional.of(existing));

        service.recordActivity(
                executionId, ActivityType.EDITED, "write_file", "tc-1", ActivityStatus.IN_PROGRESS, null);

        assertThat(existing.getActivityType()).isEqualTo(ActivityType.EDITED);
        verify(repository).save(existing);
        verify(repository, never()).nextSequence(executionId);
    }

    @Test
    void recordActivity_serializesDetailIntoJsonb() {
        UUID executionId = UUID.randomUUID();
        when(repository.findByExecutionIdAndActionId(executionId, "tc-1")).thenReturn(Optional.empty());
        when(repository.nextSequence(executionId)).thenReturn(1L);
        ActivityDetail detail = new ActivityDetail(
                null, "echo hello", null, null, null, null, null, null, null, null, null, null, null, null);

        service.recordActivity(executionId, ActivityType.COMMAND, "echo hello", "tc-1", ActivityStatus.PENDING, detail);

        SandboxExecutionActivity saved = capturedSave();
        assertInsertedRow(saved, executionId, 1, "tc-1", ActivityType.COMMAND, ActivityStatus.PENDING, "echo hello");
        assertThat(saved.getDetail()).isEqualTo("{\"title\":\"echo hello\"}");
    }

    @Test
    void detailOf_returnsNullForEmptyDetail() {
        SandboxExecutionActivity activity =
                row(UUID.randomUUID(), 1, "tc-1", ActivityType.COMMAND, ActivityStatus.IN_PROGRESS, "ls");
        activity.setDetail(null);
        assertThat(service.detailOf(activity)).isNull();

        activity.setDetail("  ");
        assertThat(service.detailOf(activity)).isNull();
    }

    @Test
    void detailOf_deserializesStoredJson() {
        SandboxExecutionActivity activity =
                row(UUID.randomUUID(), 1, "tc-1", ActivityType.COMMAND, ActivityStatus.IN_PROGRESS, "ls");
        activity.setDetail("{\"title\":\"echo hello\"}");

        ActivityDetail detail = service.detailOf(activity);

        assertThat(detail.title()).isEqualTo("echo hello");
    }

    @Test
    void recordActivity_planUpdateReplacesDetailInPlace() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity existing =
                row(executionId, 1, "plan", ActivityType.PLAN, ActivityStatus.IN_PROGRESS, "Agent plan updated");
        when(repository.findByExecutionIdAndActionId(executionId, "plan")).thenReturn(Optional.of(existing));
        ActivityDetail detail = new ActivityDetail(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new PlanEntry("Latest task", PlanEntryPriority.MEDIUM, PlanEntryStatus.PENDING)),
                null,
                null);

        service.recordActivity(
                executionId, ActivityType.PLAN, "Agent plan updated", "plan", ActivityStatus.IN_PROGRESS, detail);

        assertThat(existing.getDescription()).isEqualTo("Agent plan updated");
        assertThat(existing.getStatus()).isEqualTo(ActivityStatus.IN_PROGRESS);
        assertThat(existing.getDetail()).contains("Latest task");
        verify(repository).save(existing);
        verify(repository, never()).nextSequence(executionId);
    }

    @Test
    void recordActivity_planUpdateWithNoExistingRow_insertsOnce() {
        UUID executionId = UUID.randomUUID();
        when(repository.findByExecutionIdAndActionId(executionId, "plan")).thenReturn(Optional.empty());
        when(repository.nextSequence(executionId)).thenReturn(1L);
        ActivityDetail detail = new ActivityDetail(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new PlanEntry("Setup repo", PlanEntryPriority.HIGH, PlanEntryStatus.IN_PROGRESS)),
                null,
                null);

        service.recordActivity(
                executionId, ActivityType.PLAN, "Agent plan updated", "plan", ActivityStatus.IN_PROGRESS, detail);

        assertInsertedRow(
                capturedSave(),
                executionId,
                1,
                "plan",
                ActivityType.PLAN,
                ActivityStatus.IN_PROGRESS,
                "Agent plan updated");
    }

    @Test
    void detailOf_deserializesStructuredPlan() {
        SandboxExecutionActivity activity =
                row(UUID.randomUUID(), 1, "plan", ActivityType.PLAN, ActivityStatus.IN_PROGRESS, "Agent plan updated");
        activity.setDetail("{\"plan\":["
                + "{\"content\":\"A\",\"priority\":\"high\",\"status\":\"in_progress\"},"
                + "{\"content\":\"B\",\"priority\":\"medium\",\"status\":\"completed\"}]}");

        ActivityDetail detail = service.detailOf(activity);

        assertThat(detail.plan())
                .containsExactly(
                        new PlanEntry("A", PlanEntryPriority.HIGH, PlanEntryStatus.IN_PROGRESS),
                        new PlanEntry("B", PlanEntryPriority.MEDIUM, PlanEntryStatus.COMPLETED));
    }

    @Test
    void detailOf_invalidJson_throws() {
        SandboxExecutionActivity activity =
                row(UUID.randomUUID(), 1, "tc-1", ActivityType.COMMAND, ActivityStatus.IN_PROGRESS, "ls");
        activity.setDetail("not-json");

        assertThatThrownBy(() -> service.detailOf(activity)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recordActivity_parallelToolCallsBothRemainOpen() {
        UUID executionId = UUID.randomUUID();
        when(repository.findByExecutionIdAndActionId(executionId, "search-1")).thenReturn(Optional.empty());
        when(repository.findByExecutionIdAndActionId(executionId, "search-2")).thenReturn(Optional.empty());
        when(repository.nextSequence(executionId)).thenReturn(1L, 2L);

        service.recordActivity(
                executionId, ActivityType.RESEARCH, "glob", "search-1", ActivityStatus.IN_PROGRESS, null);
        service.recordActivity(
                executionId, ActivityType.RESEARCH, "grep", "search-2", ActivityStatus.IN_PROGRESS, null);

        ArgumentCaptor<SandboxExecutionActivity> captor = ArgumentCaptor.forClass(SandboxExecutionActivity.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(saved -> assertThat(saved.getStatus()).isEqualTo(ActivityStatus.IN_PROGRESS));
        assertThat(captor.getAllValues().get(0).getActionId()).isEqualTo("search-1");
        assertThat(captor.getAllValues().get(1).getActionId()).isEqualTo("search-2");
        verify(repository, never()).saveAll(any());
    }

    @Test
    void recordActivity_continuationChunkMergesIntoItsRow() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity existing =
                row(executionId, 1, "msg-1", ActivityType.MESSAGE, ActivityStatus.IN_PROGRESS, "Hello ");
        when(repository.findByExecutionIdAndActionId(executionId, "msg-1")).thenReturn(Optional.of(existing));

        service.recordActivity(
                executionId, ActivityType.MESSAGE, "Hello world", "msg-1", ActivityStatus.IN_PROGRESS, null);

        assertThat(existing.getDescription()).isEqualTo("Hello world");
        assertThat(existing.getStatus()).isEqualTo(ActivityStatus.IN_PROGRESS);
        verify(repository).save(existing);
        verify(repository, never()).saveAll(any());
    }

    @Test
    void closeOpenStreams_marksAllInProgressMessageThoughtComplete() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity message =
                row(executionId, 1, "msg-1", ActivityType.MESSAGE, ActivityStatus.IN_PROGRESS, "message");
        SandboxExecutionActivity thought =
                row(executionId, 2, "thought-1", ActivityType.THINKING, ActivityStatus.IN_PROGRESS, "reasoning");
        when(repository.findByExecutionIdAndStatusOrderBySequenceAsc(executionId, ActivityStatus.IN_PROGRESS))
                .thenReturn(List.of(message, thought));

        service.closeOpenStreams(executionId);

        assertThat(message.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(thought.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        verify(repository).saveAll(List.of(message, thought));
    }

    @Test
    void closeOpenStreams_closesOpenToolsAndCommandsToo() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity search =
                row(executionId, 1, "search-1", ActivityType.RESEARCH, ActivityStatus.IN_PROGRESS, "glob");
        SandboxExecutionActivity command =
                row(executionId, 2, "cmd-1", ActivityType.COMMAND, ActivityStatus.IN_PROGRESS, "npm test");
        when(repository.findByExecutionIdAndStatusOrderBySequenceAsc(executionId, ActivityStatus.IN_PROGRESS))
                .thenReturn(List.of(search, command));

        service.closeOpenStreams(executionId);

        assertThat(search.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(command.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        verify(repository).saveAll(List.of(search, command));
    }

    @Test
    void closeOpenStreams_withNoOpenStreams_isNoOp() {
        UUID executionId = UUID.randomUUID();
        when(repository.findByExecutionIdAndStatusOrderBySequenceAsc(executionId, ActivityStatus.IN_PROGRESS))
                .thenReturn(List.of());

        service.closeOpenStreams(executionId);

        verify(repository, never()).saveAll(any());
    }

    @Test
    void onExecutionComplete_closesTrailingOpenStreams() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity trailing =
                row(executionId, 1, "msg-1", ActivityType.MESSAGE, ActivityStatus.IN_PROGRESS, "trailing message");
        when(repository.findByExecutionIdAndStatusOrderBySequenceAsc(executionId, ActivityStatus.IN_PROGRESS))
                .thenReturn(List.of(trailing));

        service.onExecutionComplete(
                new SandboxExecutionCompleteEvent(UUID.randomUUID(), executionId, 0, SandboxExecutionStatus.COMPLETED));

        assertThat(trailing.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        verify(repository).saveAll(List.of(trailing));
    }

    @Test
    void onHitlResolved_approvalWithActionId_updatesMatchingRow() {
        UUID executionId = UUID.randomUUID();
        UUID resolvedBy = UUID.randomUUID();
        SandboxExecutionActivity row =
                row(executionId, 1, "tool-call-42", ActivityType.COMMAND, ActivityStatus.PENDING, "rm -rf /");
        when(repository.findByExecutionIdAndActionId(executionId, "tool-call-42"))
                .thenReturn(Optional.of(row));

        service.onHitlResolved(new SandboxExecutionHitlResolvedEvent(
                UUID.randomUUID(),
                new ExecutionHitlResolvedResult(
                        executionId,
                        "tool-call-42",
                        HitlKind.APPROVAL,
                        HitlResponse.APPROVED,
                        "allow-once",
                        null,
                        resolvedBy,
                        "Alice")));

        assertThat(row.getStatus()).isEqualTo(ActivityStatus.IN_PROGRESS);
        verify(repository).save(row);
    }

    @Test
    void onHitlResolved_approvalRejectionMarksRowFailed() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity row =
                row(executionId, 1, "tool-call-42", ActivityType.COMMAND, ActivityStatus.PENDING, "rm -rf /");
        when(repository.findByExecutionIdAndActionId(executionId, "tool-call-42"))
                .thenReturn(Optional.of(row));

        service.onHitlResolved(new SandboxExecutionHitlResolvedEvent(
                UUID.randomUUID(),
                new ExecutionHitlResolvedResult(
                        executionId,
                        "tool-call-42",
                        HitlKind.APPROVAL,
                        HitlResponse.DECLINED,
                        "reject-once",
                        null,
                        null,
                        "Bob")));

        assertThat(row.getStatus()).isEqualTo(ActivityStatus.FAILED);
    }

    @Test
    void onHitlResolved_approvalUnknownId_doesNotTouchPendingRows() {
        UUID executionId = UUID.randomUUID();
        when(repository.findByExecutionIdAndActionId(executionId, "tool-call-42"))
                .thenReturn(Optional.empty());

        service.onHitlResolved(new SandboxExecutionHitlResolvedEvent(
                UUID.randomUUID(),
                new ExecutionHitlResolvedResult(
                        executionId,
                        "tool-call-42",
                        HitlKind.APPROVAL,
                        HitlResponse.APPROVED,
                        "allow-once",
                        null,
                        null,
                        "Alice")));

        verify(repository, never()).save(any());
        verify(repository, never()).findFirstByExecutionIdAndActionIdOrderBySequenceDesc(any(), any());
    }

    @Test
    void onHitlRequired_approvalWithExistingActivity_attachesHitlDetail() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity existing =
                row(executionId, 1, "tool-call-42", ActivityType.EDITED, ActivityStatus.IN_PROGRESS, "Write file");
        when(repository.findByExecutionIdAndActionId(executionId, "tool-call-42"))
                .thenReturn(Optional.of(existing));

        service.onHitlRequired(new SandboxExecutionHitlRequiredEvent(
                UUID.randomUUID(),
                new ExecutionHitlRequiredResult(
                        executionId,
                        "tool-call-42",
                        HitlKind.APPROVAL,
                        "Approve Write file",
                        "Write file",
                        null,
                        "Write",
                        "edit",
                        null,
                        null,
                        null)));

        assertThat(existing.getStatus()).isEqualTo(ActivityStatus.PENDING);
        assertThat(existing.getDetail()).contains("tool-call-42").contains("approval");
        verify(repository).save(existing);
        verify(repository, never()).nextSequence(executionId);
    }

    @Test
    void onHitlRequired_approvalWithoutMatchingRow_isNoOpAndLogs() {
        UUID executionId = UUID.randomUUID();
        when(repository.findByExecutionIdAndActionId(executionId, "tool-call-42"))
                .thenReturn(Optional.empty());

        service.onHitlRequired(new SandboxExecutionHitlRequiredEvent(
                UUID.randomUUID(),
                new ExecutionHitlRequiredResult(
                        executionId,
                        "tool-call-42",
                        HitlKind.APPROVAL,
                        "Approve rm -rf /",
                        "rm -rf /",
                        null,
                        "Remove",
                        "execute",
                        null,
                        null,
                        null)));

        verify(repository, never()).save(any());
        verify(repository, never()).nextSequence(any());
    }

    @Test
    void onHitlRequired_questionInsertsElicitationActivityRow() {
        UUID executionId = UUID.randomUUID();
        when(repository.nextSequence(executionId)).thenReturn(3L);

        service.onHitlRequired(new SandboxExecutionHitlRequiredEvent(
                UUID.randomUUID(),
                new ExecutionHitlRequiredResult(
                        executionId,
                        "el-1",
                        HitlKind.QUESTION,
                        "Pick a target",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of("type", "object"))));

        SandboxExecutionActivity saved = capturedSave();
        assertInsertedRow(
                saved, executionId, 3, "el-1", ActivityType.ELICITATION, ActivityStatus.PENDING, "Pick a target");
        assertThat(saved.getDetail()).contains("el-1").contains("Pick a target");
    }

    @Test
    void onHitlResolved_questionAnswered_setsCompletedAndAnswer() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity pending =
                row(executionId, 1, "el-1", ActivityType.ELICITATION, ActivityStatus.PENDING, "Pick a target");
        pending.setDetail("{\"hitl\":{\"hitlId\":\"el-1\",\"kind\":\"question\",\"message\":\"Pick a target\"}}");
        when(repository.findFirstByExecutionIdAndActionIdOrderBySequenceDesc(executionId, "el-1"))
                .thenReturn(Optional.of(pending));

        service.onHitlResolved(new SandboxExecutionHitlResolvedEvent(
                UUID.randomUUID(),
                new ExecutionHitlResolvedResult(
                        executionId,
                        "el-1",
                        HitlKind.QUESTION,
                        HitlResponse.ANSWERED,
                        null,
                        Map.of("target", "staging"),
                        null,
                        "Alice")));

        assertThat(pending.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(pending.getDetail()).contains("staging").contains("answered");
    }

    @Test
    void onHitlResolved_questionDeclined_setsFailed() {
        UUID executionId = UUID.randomUUID();
        SandboxExecutionActivity pending =
                row(executionId, 1, "el-1", ActivityType.ELICITATION, ActivityStatus.PENDING, "Pick a target");
        when(repository.findFirstByExecutionIdAndActionIdOrderBySequenceDesc(executionId, "el-1"))
                .thenReturn(Optional.of(pending));

        service.onHitlResolved(new SandboxExecutionHitlResolvedEvent(
                UUID.randomUUID(),
                new ExecutionHitlResolvedResult(
                        executionId, "el-1", HitlKind.QUESTION, HitlResponse.DECLINED, null, null, null, "Alice")));

        assertThat(pending.getStatus()).isEqualTo(ActivityStatus.FAILED);
        assertThat(pending.getDetail()).contains("declined");
    }

    @Test
    void onHitlResolved_questionNoMatchingRow_isNoOp() {
        UUID executionId = UUID.randomUUID();
        when(repository.findFirstByExecutionIdAndActionIdOrderBySequenceDesc(executionId, "el-1"))
                .thenReturn(Optional.empty());

        service.onHitlResolved(new SandboxExecutionHitlResolvedEvent(
                UUID.randomUUID(),
                new ExecutionHitlResolvedResult(
                        executionId, "el-1", HitlKind.QUESTION, HitlResponse.ANSWERED, null, null, null, "Alice")));

        verify(repository, never()).save(any());
    }

    @Test
    void getActivities_delegatesOrderedRead() {
        UUID executionId = UUID.randomUUID();
        List<SandboxExecutionActivity> rows =
                List.of(row(executionId, 1, null, ActivityType.MESSAGE, ActivityStatus.IN_PROGRESS, "hi"));
        when(repository.findByExecutionIdOrderBySequenceAsc(executionId)).thenReturn(rows);

        assertThat(service.getActivities(executionId)).isSameAs(rows);
    }
}
