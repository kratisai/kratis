package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class SandboxExecutionActivityMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private SandboxExecutionActivityRepository activityRepository;

    private TestDataFactory.TestContext context;

    private SandboxExecution createExecution() {
        context = testDataFactory.createDefaultContext();

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(context.team());
        env.setName("Test Sandbox");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        executionEnvironmentRepository.save(env);

        ChatEntity chat = new ChatEntity(context.team(), context.user(), "Test Chat");
        chatRepository.save(chat);

        entityManager.flush();

        SandboxExecution execution = new SandboxExecution();
        execution.setEnvironment(env);
        execution.setChat(chat);
        execution.setTaskPrompt("Test task");
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        sandboxExecutionRepository.save(execution);
        entityManager.flush();
        return execution;
    }

    @Test
    void savesAndFindsActivityWithJsonbDetailAndHitlColumns() {
        SandboxExecution execution = createExecution();

        SandboxExecutionActivity activity = new SandboxExecutionActivity(
                execution.getId(),
                1,
                "tool-call-7",
                ActivityType.COMMAND,
                ActivityStatus.IN_PROGRESS,
                "npm run build",
                "{\"kind\":\"command\"}");
        activity.setApproved(true);
        activity.setResolvedByUserId(context.user().getId());
        activity.setResolvedAt(Instant.now());
        activityRepository.save(activity);
        entityManager.flush();

        SandboxExecutionActivity found =
                activityRepository.findById(activity.getId()).orElseThrow();
        assertThat(found.getExecutionId()).isEqualTo(execution.getId());
        assertThat(found.getSequence()).isEqualTo(1);
        assertThat(found.getActionId()).isEqualTo("tool-call-7");
        assertThat(found.getActivityType()).isEqualTo(ActivityType.COMMAND);
        assertThat(found.getStatus()).isEqualTo(ActivityStatus.IN_PROGRESS);
        assertThat(found.getDescription()).isEqualTo("npm run build");
        assertThat(found.getDetail()).contains("\"kind\":\"command\"");
        assertThat(found.getApproved()).isTrue();
        assertThat(found.getResolvedByUserId()).isNotNull();
        assertThat(found.getResolvedAt()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void persistsActionIdLongerThan255Chars() {
        SandboxExecution execution = createExecution();

        String longActionId = "call_2638891__thought__" + "a".repeat(600);
        assertThat(longActionId).hasSizeGreaterThan(255);

        SandboxExecutionActivity activity = new SandboxExecutionActivity(
                execution.getId(),
                1,
                longActionId,
                ActivityType.COMMAND,
                ActivityStatus.IN_PROGRESS,
                "npm run build",
                null);
        activityRepository.save(activity);
        entityManager.flush();

        SandboxExecutionActivity found =
                activityRepository.findById(activity.getId()).orElseThrow();
        assertThat(found.getActionId()).isEqualTo(longActionId);
    }

    @Test
    void findByExecutionIdOrderBySequenceAsc_returnsRowsInArrivalOrder() {
        SandboxExecution execution = createExecution();
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 3, "tc-3", ActivityType.RESEARCH, ActivityStatus.COMPLETED, "third", null));
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 1, "tc-1", ActivityType.THINKING, ActivityStatus.IN_PROGRESS, "first", null));
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 2, null, ActivityType.MESSAGE, ActivityStatus.IN_PROGRESS, "second", null));
        entityManager.flush();

        List<SandboxExecutionActivity> found =
                activityRepository.findByExecutionIdOrderBySequenceAsc(execution.getId());

        assertThat(found).hasSize(3);
        assertThat(found.get(0).getDescription()).isEqualTo("first");
        assertThat(found.get(1).getDescription()).isEqualTo("second");
        assertThat(found.get(2).getDescription()).isEqualTo("third");
    }

    @Test
    void findFirstByExecutionIdAndActionIdAndActivityType_mergesChunksIntoSingleRow() {
        SandboxExecution execution = createExecution();
        SandboxExecutionActivity first = new SandboxExecutionActivity(
                execution.getId(), 1, "msg-1", ActivityType.MESSAGE, ActivityStatus.IN_PROGRESS, "Hello ", null);
        activityRepository.save(first);
        entityManager.flush();

        Optional<SandboxExecutionActivity> found =
                activityRepository.findFirstByExecutionIdAndActionIdAndActivityTypeOrderBySequenceDesc(
                        execution.getId(), "msg-1", ActivityType.MESSAGE);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(first.getId());
    }

    @Test
    void findFirstByExecutionIdAndActionId_returnsNewestRowWhenActionIdReusedAcrossTypes() {
        SandboxExecution execution = createExecution();
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 1, "msg-1", ActivityType.THINKING, ActivityStatus.IN_PROGRESS, "thought", null));
        SandboxExecutionActivity message = new SandboxExecutionActivity(
                execution.getId(), 2, "msg-1", ActivityType.MESSAGE, ActivityStatus.IN_PROGRESS, "Hello ", null);
        activityRepository.save(message);
        entityManager.flush();

        Optional<SandboxExecutionActivity> byAction =
                activityRepository.findFirstByExecutionIdAndActionIdOrderBySequenceDesc(execution.getId(), "msg-1");
        Optional<SandboxExecutionActivity> byActionAndType =
                activityRepository.findFirstByExecutionIdAndActionIdAndActivityTypeOrderBySequenceDesc(
                        execution.getId(), "msg-1", ActivityType.MESSAGE);

        assertThat(byAction).isPresent();
        assertThat(byAction.get().getId()).isEqualTo(message.getId());
        assertThat(byActionAndType).isPresent();
        assertThat(byActionAndType.get().getId()).isEqualTo(message.getId());
    }

    @Test
    void findFirstByExecutionIdAndStatusOrderBySequenceDesc_returnsNewestPendingRow() {
        SandboxExecution execution = createExecution();
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 1, "tc-1", ActivityType.COMMAND, ActivityStatus.PENDING, "first", null));
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 2, "tc-2", ActivityType.COMMAND, ActivityStatus.PENDING, "second", null));
        entityManager.flush();

        Optional<SandboxExecutionActivity> found =
                activityRepository.findFirstByExecutionIdAndStatusOrderBySequenceDesc(
                        execution.getId(), ActivityStatus.PENDING);

        assertThat(found).isPresent();
        assertThat(found.get().getDescription()).isEqualTo("second");
    }

    @Test
    void nextSequence_startsAtOneAndAdvancesPerExecution() {
        SandboxExecution execution = createExecution();
        assertThat(activityRepository.nextSequence(execution.getId())).isEqualTo(1);
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 1, null, ActivityType.MESSAGE, ActivityStatus.IN_PROGRESS, "first", null));
        entityManager.flush();
        assertThat(activityRepository.nextSequence(execution.getId())).isEqualTo(2);
    }

    @Test
    void findByExecutionIdAndStatusOrderBySequenceAsc_returnsEveryOpenActivity() {
        SandboxExecution execution = createExecution();
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 1, "msg-1", ActivityType.MESSAGE, ActivityStatus.IN_PROGRESS, "first", null));
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 2, "msg-2", ActivityType.MESSAGE, ActivityStatus.COMPLETED, "done", null));
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 3, "search-1", ActivityType.RESEARCH, ActivityStatus.IN_PROGRESS, "second", null));
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 4, "cmd-1", ActivityType.COMMAND, ActivityStatus.IN_PROGRESS, "third", null));
        entityManager.flush();

        List<SandboxExecutionActivity> found = activityRepository.findByExecutionIdAndStatusOrderBySequenceAsc(
                execution.getId(), ActivityStatus.IN_PROGRESS);

        assertThat(found).hasSize(3);
        assertThat(found.get(0).getDescription()).isEqualTo("first");
        assertThat(found.get(1).getDescription()).isEqualTo("second");
        assertThat(found.get(2).getDescription()).isEqualTo("third");
    }

    @Test
    void findByExecutionIdAndStatusAndOpenActionIdNot_excludesIncomingActionId() {
        SandboxExecution execution = createExecution();
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 1, "search-1", ActivityType.RESEARCH, ActivityStatus.IN_PROGRESS, "first", null));
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 2, "cmd-1", ActivityType.COMMAND, ActivityStatus.IN_PROGRESS, "second", null));
        entityManager.flush();

        List<SandboxExecutionActivity> found = activityRepository.findByExecutionIdAndStatusAndOpenActionIdNot(
                execution.getId(), ActivityStatus.IN_PROGRESS, "cmd-1");

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getDescription()).isEqualTo("first");
    }

    @Test
    void findByExecutionIdAndStatusAndOpenActionIdNot_includesNullActionIdRows() {
        SandboxExecution execution = createExecution();
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 1, null, ActivityType.RESEARCH, ActivityStatus.IN_PROGRESS, "standalone", null));
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 2, "cmd-1", ActivityType.COMMAND, ActivityStatus.IN_PROGRESS, "second", null));
        entityManager.flush();

        List<SandboxExecutionActivity> found = activityRepository.findByExecutionIdAndStatusAndOpenActionIdNot(
                execution.getId(), ActivityStatus.IN_PROGRESS, "cmd-1");

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getDescription()).isEqualTo("standalone");
    }

    @Test
    void findByExecutionIdAndStatusOrderBySequenceAsc_ignoresPendingAndCompleted() {
        SandboxExecution execution = createExecution();
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 1, "tc-1", ActivityType.COMMAND, ActivityStatus.PENDING, "pending", null));
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 2, "el-1", ActivityType.ELICITATION, ActivityStatus.PENDING, "question", null));
        activityRepository.save(new SandboxExecutionActivity(
                execution.getId(), 3, "tc-2", ActivityType.COMMAND, ActivityStatus.COMPLETED, "done", null));
        entityManager.flush();

        List<SandboxExecutionActivity> found = activityRepository.findByExecutionIdAndStatusOrderBySequenceAsc(
                execution.getId(), ActivityStatus.IN_PROGRESS);

        assertThat(found).isEmpty();
    }

    @Test
    void deletingExecutionCascadesToActivities() {
        SandboxExecution execution = createExecution();
        SandboxExecutionActivity activity = new SandboxExecutionActivity(
                execution.getId(), 1, "tc-1", ActivityType.COMMAND, ActivityStatus.IN_PROGRESS, "npm run build", null);
        activityRepository.save(activity);
        entityManager.flush();

        sandboxExecutionRepository.delete(execution);
        entityManager.flush();
        entityManager.clear();

        assertThat(activityRepository.findById(activity.getId())).isEmpty();
    }

    @Test
    void prePersist_preservesExplicitCreatedAt() {
        SandboxExecution execution = createExecution();
        Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
        SandboxExecutionActivity activity = new SandboxExecutionActivity(
                execution.getId(), 1, null, ActivityType.MESSAGE, ActivityStatus.IN_PROGRESS, "hi", null);
        activity.setCreatedAt(fixed);
        activityRepository.save(activity);
        entityManager.flush();
        entityManager.clear();

        SandboxExecutionActivity found =
                activityRepository.findById(activity.getId()).orElseThrow();
        assertThat(found.getCreatedAt()).isEqualTo(fixed);
    }

    @Test
    void gettersAndSetters_roundTripAllFields() {
        UUID executionId = UUID.randomUUID();
        Instant now = Instant.now();
        SandboxExecutionActivity activity = new SandboxExecutionActivity();

        activity.setId(UUID.randomUUID());
        activity.setExecutionId(executionId);
        activity.setSequence(7);
        activity.setActionId("tc-1");
        activity.setActivityType(ActivityType.RESEARCH);
        activity.setStatus(ActivityStatus.FAILED);
        activity.setDescription("search");
        activity.setDetail("{\"kind\":\"search\"}");
        activity.setApproved(false);
        activity.setResolvedByUserId(UUID.randomUUID());
        activity.setResolvedAt(now);
        activity.setCreatedAt(now);
        activity.setUpdatedAt(now);

        assertThat(activity.getId()).isNotNull();
        assertThat(activity.getExecutionId()).isEqualTo(executionId);
        assertThat(activity.getSequence()).isEqualTo(7);
        assertThat(activity.getActionId()).isEqualTo("tc-1");
        assertThat(activity.getActivityType()).isEqualTo(ActivityType.RESEARCH);
        assertThat(activity.getStatus()).isEqualTo(ActivityStatus.FAILED);
        assertThat(activity.getDescription()).isEqualTo("search");
        assertThat(activity.getDetail()).isEqualTo("{\"kind\":\"search\"}");
        assertThat(activity.getApproved()).isFalse();
        assertThat(activity.getResolvedByUserId()).isNotNull();
        assertThat(activity.getResolvedAt()).isEqualTo(now);
        assertThat(activity.getCreatedAt()).isEqualTo(now);
        assertThat(activity.getUpdatedAt()).isEqualTo(now);
    }
}
