package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.MessageRole;
import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.ChangeSet;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatMemoryEntity;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class ChatRepositoryTest {

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private CanvasRepository canvasRepository;

    @Autowired
    private AgentChatMemoryRepository chatMemoryRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private SandboxExecutionActivityRepository activityRepository;

    @Autowired
    private ChangeSetRepository changeSetRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Team team;
    private User user;
    private ExecutionEnvironment environment;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        team = ctx.team();
        user = ctx.user();

        environment = new ExecutionEnvironment();
        environment.setTeam(team);
        environment.setName("ordering-sandbox");
        environment.setType(ExecutionEnvironmentType.CONNECTOR);
        environment.setStatus(EnvironmentStatus.CONNECTED);
        environment.setAuthToken("token");
        executionEnvironmentRepository.save(environment);
    }

    @Test
    void findRecentByLastActivity_ordersByRealActivityAndIgnoresReconciliationStamps() {
        ChatEntity reconciled = chat("Reconciled stale topic");
        SandboxExecution staleExecution = execution(reconciled, Instant.parse("2026-05-01T10:00:00Z"));
        setCanvasUpdatedAt(reconciled, Instant.parse("2026-04-30T09:00:00Z"));
        staleExecution.setCompletedAt(Instant.parse("2026-05-10T10:00:00Z"));
        sandboxExecutionRepository.save(staleExecution);
        activityRepository.save(activity(
                staleExecution.getId(),
                1,
                ActivityType.MESSAGE,
                "Real work before the MR",
                Instant.parse("2026-05-01T11:00:00Z")));
        activityRepository.save(activity(
                staleExecution.getId(),
                2,
                ActivityType.ERROR,
                "execution abandoned because its environment is no longer available",
                Instant.parse("2026-05-10T10:00:00Z")));

        ChatEntity published = chat("Recently published topic");
        SandboxExecution publishedExecution = execution(published, Instant.parse("2026-05-02T10:00:00Z"));
        setCanvasUpdatedAt(published, Instant.parse("2026-04-30T09:00:00Z"));
        sandboxExecutionRepository.save(publishedExecution);
        activityRepository.save(activity(
                publishedExecution.getId(),
                1,
                ActivityType.MESSAGE,
                "Real work before publishing",
                Instant.parse("2026-05-02T11:00:00Z")));
        changeSetRepository.save(new ChangeSet(
                publishedExecution,
                1,
                "base",
                "push",
                "feature",
                42L,
                "https://pr/42",
                "main",
                Instant.parse("2026-05-02T13:00:00Z")));

        List<ChatRepository.RecentChatRow> recent =
                chatRepository.findRecentByLastActivity(team.getId(), null, false, 20);

        assertThat(recent)
                .extracting(ChatRepository.RecentChatRow::getId)
                .containsExactly(published.getId(), reconciled.getId());
        assertThat(recent.get(0).getLatestExecutionId()).isEqualTo(publishedExecution.getId());
        assertThat(recent.get(0).getLatestExecutionActivityAt()).isEqualTo(Instant.parse("2026-05-02T11:00:00Z"));
        assertThat(recent.get(1).getLatestExecutionId()).isEqualTo(staleExecution.getId());
        assertThat(recent.get(1).getLatestExecutionActivityAt()).isEqualTo(Instant.parse("2026-05-01T11:00:00Z"));
    }

    @Test
    void findSummaryByIdAndTeamId_projectsExecutionActivityAndPendingHitlExcludingErrors() {
        ChatEntity chat = chat("Activity topic");
        chat.setAllowAllPermissions(true);
        chatRepository.save(chat);

        ChatMemoryEntity message = new ChatMemoryEntity(chat.getId(), 0, MessageRole.USER, "Hello", null, null, null);
        message.setCreatedAt(Instant.parse("2026-05-01T09:30:00Z"));
        chatMemoryRepository.save(message);

        SandboxExecution execution = execution(chat, Instant.parse("2026-05-01T10:00:00Z"));
        sandboxExecutionRepository.save(execution);
        activityRepository.save(activity(
                execution.getId(), 1, ActivityType.MESSAGE, "Real work", Instant.parse("2026-05-01T11:00:00Z")));
        SandboxExecutionActivity pendingHitl = new SandboxExecutionActivity(
                execution.getId(),
                2,
                "hitl-1",
                ActivityType.COMMAND,
                ActivityStatus.PENDING,
                "Awaiting approval",
                "{\"hitl\":{\"state\":\"awaiting_human\"}}");
        pendingHitl.setCreatedAt(Instant.parse("2026-05-01T11:05:00Z"));
        activityRepository.save(pendingHitl);
        activityRepository.save(activity(
                execution.getId(),
                3,
                ActivityType.ERROR,
                "Sandbox resources found to have been unexpectedly purged",
                Instant.parse("2026-05-09T11:00:00Z")));

        ChatRepository.RecentChatRow summary = chatRepository
                .findSummaryByIdAndTeamId(chat.getId(), team.getId())
                .orElseThrow();

        assertThat(summary.getId()).isEqualTo(chat.getId());
        assertThat(summary.isAllowAllPermissions()).isTrue();
        assertThat(summary.getLastMessageAt()).isEqualTo(Instant.parse("2026-05-01T09:30:00Z"));
        assertThat(summary.getLatestExecutionId()).isEqualTo(execution.getId());
        assertThat(summary.getLatestExecutionActivityAt()).isEqualTo(Instant.parse("2026-05-01T11:05:00Z"));
        assertThat(summary.isPendingHitl()).isTrue();

        CanvasEntity deletedCanvas = new CanvasEntity(chat.getId(), "deleted-doc", "Deleted", "ignored");
        deletedCanvas.setDeletedAt(Instant.now());
        canvasRepository.save(deletedCanvas);

        assertThat(canvasRepository.findActiveMetadataByChatIdIn(List.of(chat.getId())))
                .extracting(CanvasRepository.CanvasActivity::getDocumentId)
                .containsExactly("spec-" + chat.getId());
    }

    private void setCanvasUpdatedAt(ChatEntity chat, Instant updatedAt) {
        assertThat(jdbcTemplate.update(
                        "UPDATE canvas SET updated_at = ? WHERE chat_id = ?", Timestamp.from(updatedAt), chat.getId()))
                .isEqualTo(1);
    }

    private ChatEntity chat(String title) {
        ChatEntity chat = new ChatEntity(team, user, title);
        return chatRepository.save(chat);
    }

    private SandboxExecution execution(ChatEntity chat, Instant startedAt) {
        CanvasEntity canvas = new CanvasEntity(chat.getId(), "spec-" + chat.getId(), "Spec", "content");
        canvasRepository.save(canvas);
        SandboxExecution execution = new SandboxExecution();
        execution.setEnvironment(environment);
        execution.setCanvas(canvas);
        execution.setChat(chat);
        execution.setStatus(SandboxExecutionStatus.IDLE);
        execution.setStartedAt(startedAt);
        return execution;
    }

    private SandboxExecutionActivity activity(
            UUID executionId, long sequence, ActivityType type, String description, Instant createdAt) {
        SandboxExecutionActivity activity = new SandboxExecutionActivity(
                executionId, sequence, "action-" + sequence, type, ActivityStatus.COMPLETED, description, null);
        activity.setCreatedAt(createdAt);
        return activity;
    }
}
