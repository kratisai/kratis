package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.api.restdto.ChatDto;
import com.kratisai.controlplane.api.restdto.ChatUsageDto;
import com.kratisai.controlplane.api.restdto.LastActivityType;
import com.kratisai.controlplane.api.restdto.TopicStatus;
import com.kratisai.controlplane.model.ChangeSet;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.ExecutionDiffSnapshot;
import com.kratisai.controlplane.model.PublishedPrStatus;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.AgentChatMemoryRepository;
import com.kratisai.controlplane.repository.CanvasRepository;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import com.kratisai.controlplane.repository.ExecutionDiffSnapshotRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ChatServiceTest {

    private ChatRepository chatRepository;
    private TeamMemberRepository teamMemberRepository;
    private ChatUsageSessionRepository chatUsageSessionRepository;
    private SandboxExecutionRepository sandboxExecutionRepository;
    private ExecutionDiffSnapshotRepository diffSnapshotRepository;
    private CanvasRepository canvasRepository;
    private ChatFluxRegistry chatFluxRegistry;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatRepository = mock(ChatRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        AgentChatMemoryRepository chatMemoryRepository = mock(AgentChatMemoryRepository.class);
        chatUsageSessionRepository = mock(ChatUsageSessionRepository.class);
        sandboxExecutionRepository = mock(SandboxExecutionRepository.class);
        diffSnapshotRepository = mock(ExecutionDiffSnapshotRepository.class);
        canvasRepository = mock(CanvasRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        chatFluxRegistry = mock(ChatFluxRegistry.class);

        when(canvasRepository.findActiveMetadataByChatIdIn(any())).thenReturn(List.of());

        chatService = new ChatService(
                chatRepository,
                teamRepository,
                userRepository,
                teamMemberRepository,
                chatMemoryRepository,
                chatUsageSessionRepository,
                sandboxExecutionRepository,
                diffSnapshotRepository,
                canvasRepository,
                eventPublisher,
                chatFluxRegistry);
    }

    @Test
    void listChatsForTeam_doesNotLoadUsageSessions() {
        ChatEntity chat = persistedChat(Instant.parse("2026-01-01T00:00:00Z"));

        ChatDto dto = listSingleChat(chat, null, null, null, null, false);

        assertThat(dto.id()).isEqualTo(chat.getId());
        verify(chatUsageSessionRepository, never()).findByChatIdIn(any());
    }

    @Test
    void listChatsForTeam_withoutExecution_returnsIdleAndChatActivity() {
        Instant updatedAt = Instant.parse("2026-05-01T10:00:00Z");
        ChatEntity chat = persistedChat(updatedAt);

        ChatDto dto = listSingleChat(chat, null, null, null, null, false);

        assertThat(dto.status()).isEqualTo(TopicStatus.IDLE);
        assertThat(dto.lastActivityAt()).isEqualTo(updatedAt);
        assertThat(dto.lastActivityType()).isEqualTo(LastActivityType.CHAT);
        assertThat(dto.lastActivityTargetId()).isEqualTo(chat.getId().toString());
    }

    @Test
    void listChatsForTeam_planningTurnInProgress_returnsPlanning() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        when(chatFluxRegistry.containsKey(chat.getId())).thenReturn(true);

        assertThat(listSingleChat(chat, null, null, null, null, false).status()).isEqualTo(TopicStatus.PLANNING);
    }

    @Test
    void listChatsForTeam_runningExecution_returnsWorking() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.RUNNING);

        assertThat(listSingleChat(chat, execution, null, null, null, false).status())
                .isEqualTo(TopicStatus.WORKING);
    }

    @Test
    void listChatsForTeam_pendingHitl_returnsBlocked() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.RUNNING);

        assertThat(listSingleChat(chat, execution, null, null, null, true).status())
                .isEqualTo(TopicStatus.BLOCKED);
    }

    @Test
    void listChatsForTeam_pendingHitlTakesPrecedenceOverPlanning() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.RUNNING);
        when(chatFluxRegistry.containsKey(chat.getId())).thenReturn(true);

        assertThat(listSingleChat(chat, execution, null, null, null, true).status())
                .isEqualTo(TopicStatus.BLOCKED);
    }

    @Test
    void listChatsForTeam_publishedPullRequest_returnsMrPushed() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);
        execution.addChangeSet(new ChangeSet(
                execution,
                1,
                "base",
                "push",
                "feature",
                42L,
                "https://github.com/test/repo/pull/42",
                "main",
                Instant.now()));
        var changeSet = execution.getLatestChangeSet();
        changeSet.setManifestDigest("digest-1");
        changeSet.setPrStatus(PublishedPrStatus.OPEN);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 0, "[]", "digest-1");

        assertThat(listSingleChat(chat, execution, snapshot, null, null, false).status())
                .isEqualTo(TopicStatus.MR_PUSHED);
    }

    @Test
    void listChatsForTeam_legacyStatsDigestAfterPublish_returnsMrPushed() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);
        ChangeSet changeSet = new ChangeSet(
                execution,
                1,
                "base",
                "push",
                "feature",
                42L,
                "https://github.com/test/repo/pull/42",
                "main",
                Instant.parse("2026-05-01T11:30:00Z"));
        changeSet.setManifestDigest("59472c982ff0720ee43bcc1948efec0912c0e3cd7355c34158ccbd73a746aaef");
        changeSet.setPrStatus(PublishedPrStatus.OPEN);
        execution.addChangeSet(changeSet);
        ExecutionDiffSnapshot snapshot = new ExecutionDiffSnapshot(
                execution.getId(),
                "base",
                "head",
                1,
                0,
                "[{\"path\":\"src/App.tsx\",\"status\":\"MODIFIED\",\"additions\":1,\"deletions\":0}]",
                "diff-manifest-digest");

        assertThat(listSingleChat(chat, execution, snapshot, null, null, false).status())
                .isEqualTo(TopicStatus.MR_PUSHED);
    }

    @Test
    void listChatsForTeam_legacyStatsDigestWithNewChanges_returnsReadyToPublish() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);
        ChangeSet changeSet = new ChangeSet(
                execution,
                1,
                "base",
                "push",
                "feature",
                42L,
                "https://github.com/test/repo/pull/42",
                "main",
                Instant.parse("2026-05-01T11:30:00Z"));
        changeSet.setManifestDigest("59472c982ff0720ee43bcc1948efec0912c0e3cd7355c34158ccbd73a746aaef");
        changeSet.setPrStatus(PublishedPrStatus.OPEN);
        execution.addChangeSet(changeSet);
        ExecutionDiffSnapshot snapshot = new ExecutionDiffSnapshot(
                execution.getId(),
                "base",
                "head",
                2,
                0,
                "[{\"path\":\"src/App.tsx\",\"status\":\"MODIFIED\",\"additions\":2,\"deletions\":0}]",
                "diff-manifest-digest");

        assertThat(listSingleChat(chat, execution, snapshot, null, null, false).status())
                .isEqualTo(TopicStatus.READY_TO_PUBLISH);
    }

    @Test
    void listChatsForTeam_uncommittedDiffWithoutChangeSet_returnsReadyToPublish() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.COMPLETED);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 1, 0, "[]", "digest");

        assertThat(listSingleChat(chat, execution, snapshot, null, null, false).status())
                .isEqualTo(TopicStatus.READY_TO_PUBLISH);
    }

    @Test
    void listChatsForTeam_changedManifestAfterPublish_returnsReadyToPublish() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);
        ChangeSet changeSet = new ChangeSet(execution, 1, "base", "push", "feature", null, null, "main", Instant.now());
        changeSet.setManifestDigest("published-digest");
        execution.addChangeSet(changeSet);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 2, 0, "[]", "new-digest");

        assertThat(listSingleChat(chat, execution, snapshot, null, null, false).status())
                .isEqualTo(TopicStatus.READY_TO_PUBLISH);
    }

    @Test
    void listChatsForTeam_diffWithoutPublish_returnsReadyToPublish() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.COMPLETED);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 3, "[]", "digest");

        assertThat(listSingleChat(chat, execution, snapshot, null, null, false).status())
                .isEqualTo(TopicStatus.READY_TO_PUBLISH);
    }

    @Test
    void listChatsForTeam_mergedPullRequestWithNoDiff_returnsMrMerged() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.COMPLETED);
        ChangeSet changeSet = new ChangeSet(execution, 1, "base", "push", "feature", 42L, "url", "main", Instant.now());
        changeSet.setManifestDigest("digest-1");
        changeSet.setPrStatus(PublishedPrStatus.MERGED);
        execution.addChangeSet(changeSet);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 0, "[]", "digest-1");

        assertThat(listSingleChat(chat, execution, snapshot, null, null, false).status())
                .isEqualTo(TopicStatus.MR_MERGED);
    }

    @Test
    void listChatsForTeam_mergedPullRequestWithNewDiff_returnsReadyToPublish() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.COMPLETED);
        ChangeSet changeSet = new ChangeSet(execution, 1, "base", "push", "feature", 42L, "url", "main", Instant.now());
        changeSet.setManifestDigest("digest-1");
        changeSet.setPrStatus(PublishedPrStatus.MERGED);
        execution.addChangeSet(changeSet);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 7, 2, "[]", "digest-2");

        assertThat(listSingleChat(chat, execution, snapshot, null, null, false).status())
                .isEqualTo(TopicStatus.READY_TO_PUBLISH);
    }

    @Test
    void listChatsForTeam_mergedPullRequestWithLaterChat_returnsIdle() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);
        ChangeSet changeSet = new ChangeSet(
                execution, 1, "base", "push", "feature", 42L, "url", "main", Instant.parse("2026-05-01T12:00:00Z"));
        changeSet.setManifestDigest("digest-1");
        changeSet.setPrStatus(PublishedPrStatus.MERGED);
        changeSet.setPrMergedAt(Instant.parse("2026-05-01T12:05:00Z"));
        execution.addChangeSet(changeSet);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 0, "[]", "digest-1");

        assertThat(listSingleChat(chat, execution, snapshot, Instant.parse("2026-05-01T13:00:00Z"), null, false)
                        .status())
                .isEqualTo(TopicStatus.IDLE);
    }

    @Test
    void listChatsForTeam_mergedPullRequestWithChatBeforeMerge_returnsMrMerged() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);
        ChangeSet changeSet = new ChangeSet(
                execution, 1, "base", "push", "feature", 42L, "url", "main", Instant.parse("2026-05-01T12:00:00Z"));
        changeSet.setManifestDigest("digest-1");
        changeSet.setPrStatus(PublishedPrStatus.MERGED);
        changeSet.setPrMergedAt(Instant.parse("2026-05-01T12:05:00Z"));
        execution.addChangeSet(changeSet);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 0, "[]", "digest-1");

        assertThat(listSingleChat(chat, execution, snapshot, Instant.parse("2026-05-01T11:30:00Z"), null, false)
                        .status())
                .isEqualTo(TopicStatus.MR_MERGED);
    }

    @Test
    void listChatsForTeam_mergedPullRequestWithLaterExecutionCompletion_returnsMrMerged() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);
        execution.setCompletedAt(Instant.parse("2026-05-01T13:00:00Z"));
        ChangeSet changeSet = new ChangeSet(
                execution, 1, "base", "push", "feature", 42L, "url", "main", Instant.parse("2026-05-01T12:00:00Z"));
        changeSet.setManifestDigest("digest-1");
        changeSet.setPrStatus(PublishedPrStatus.MERGED);
        changeSet.setPrMergedAt(Instant.parse("2026-05-01T12:05:00Z"));
        execution.addChangeSet(changeSet);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 0, "[]", "digest-1");

        assertThat(listSingleChat(chat, execution, snapshot, null, null, false).status())
                .isEqualTo(TopicStatus.MR_MERGED);
    }

    @Test
    void listChatsForTeam_mergedPullRequestWithActivePlanning_returnsPlanning() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);
        ChangeSet changeSet = new ChangeSet(
                execution, 1, "base", "push", "feature", 42L, "url", "main", Instant.parse("2026-05-01T12:00:00Z"));
        changeSet.setManifestDigest("digest-1");
        changeSet.setPrStatus(PublishedPrStatus.MERGED);
        changeSet.setPrMergedAt(Instant.parse("2026-05-01T12:05:00Z"));
        execution.addChangeSet(changeSet);
        when(chatFluxRegistry.containsKey(chat.getId())).thenReturn(true);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 0, "[]", "digest-1");

        assertThat(listSingleChat(chat, execution, snapshot, Instant.parse("2026-05-01T13:00:00Z"), null, false)
                        .status())
                .isEqualTo(TopicStatus.PLANNING);
    }

    @Test
    void listChatsForTeam_publishedPullRequestWithLaterChat_returnsIdle() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);
        ChangeSet changeSet = new ChangeSet(
                execution, 1, "base", "push", "feature", 42L, "url", "main", Instant.parse("2026-05-01T12:00:00Z"));
        changeSet.setManifestDigest("digest-1");
        changeSet.setPrStatus(PublishedPrStatus.OPEN);
        execution.addChangeSet(changeSet);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 0, "[]", "digest-1");

        assertThat(listSingleChat(chat, execution, snapshot, Instant.parse("2026-05-01T13:00:00Z"), null, false)
                        .status())
                .isEqualTo(TopicStatus.IDLE);
    }

    @Test
    void listChatsForTeam_closedPullRequestWithNoDiff_returnsIdle() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.COMPLETED);
        ChangeSet changeSet = new ChangeSet(execution, 1, "base", "push", "feature", 42L, "url", "main", Instant.now());
        changeSet.setManifestDigest("digest-1");
        changeSet.setPrStatus(PublishedPrStatus.CLOSED);
        execution.addChangeSet(changeSet);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 0, "[]", "digest-1");

        assertThat(listSingleChat(chat, execution, snapshot, null, null, false).status())
                .isEqualTo(TopicStatus.IDLE);
    }

    @Test
    void listChatsForTeam_legacyMergedChangeSetWithoutDigest_returnsMrMerged() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);
        ChangeSet changeSet = new ChangeSet(execution, 1, "base", "push", "feature", 42L, "url", "main", Instant.now());
        changeSet.setPrStatus(PublishedPrStatus.MERGED);
        execution.addChangeSet(changeSet);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 7, 2, "[]", "current-digest");

        assertThat(listSingleChat(chat, execution, snapshot, null, null, false).status())
                .isEqualTo(TopicStatus.MR_MERGED);
    }

    @Test
    void listChatsForTeam_legacyChangeSetWithoutDigest_returnsMrPushed() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);
        ChangeSet changeSet = new ChangeSet(execution, 1, "base", "push", "feature", 42L, "url", "main", Instant.now());
        execution.addChangeSet(changeSet);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 7, 2, "[]", "current-digest");

        assertThat(listSingleChat(chat, execution, snapshot, null, null, false).status())
                .isEqualTo(TopicStatus.MR_PUSHED);
    }

    @Test
    void listChatsForTeam_ordersByLastActivityRatherThanUpdatedAt() {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);

        ChatEntity recentlyUpdated = persistedChat(Instant.parse("2026-05-02T10:00:00Z"));
        recentlyUpdated.getTeam().setId(teamId);
        ChatEntity recentlyActive = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        recentlyActive.getTeam().setId(teamId);

        SandboxExecution execution = execution(recentlyActive, SandboxExecutionStatus.COMPLETED);
        execution.setStartedAt(Instant.parse("2026-05-03T09:00:00Z"));
        execution.setCompletedAt(Instant.parse("2026-05-03T10:00:00Z"));

        ChatRepository.RecentChatRow updatedRow = summaryRow(recentlyUpdated, null, null, null, false);
        ChatRepository.RecentChatRow activeRow =
                summaryRow(recentlyActive, execution.getId(), null, Instant.parse("2026-05-03T10:30:00Z"), false);

        when(chatRepository.findRecentByLastActivity(teamId, null, false, 20))
                .thenReturn(List.of(updatedRow, activeRow));
        when(sandboxExecutionRepository.findAllById(List.of(execution.getId()))).thenReturn(List.of(execution));
        when(diffSnapshotRepository.findByExecutionIdIn(List.of(execution.getId())))
                .thenReturn(List.of());

        List<ChatDto> dtos = chatService.listChatsForTeam(teamId, userId, false, "active");

        assertThat(dtos).extracting(ChatDto::id).containsExactly(recentlyActive.getId(), recentlyUpdated.getId());
    }

    @Test
    void listChatsForTeam_archivedOnlyMine_filtersByArchivedAndUser() {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        when(chatRepository.findRecentByLastActivity(teamId, userId, true, 20)).thenReturn(List.of());

        assertThat(chatService.listChatsForTeam(teamId, userId, true, "archived"))
                .isEmpty();

        verify(chatRepository).findRecentByLastActivity(teamId, userId, true, 20);
    }

    @Test
    void listChatsForTeam_allUsersAllStatuses_doesNotFilterByUserOrArchive() {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        when(chatRepository.findRecentByLastActivity(teamId, null, null, 20)).thenReturn(List.of());

        assertThat(chatService.listChatsForTeam(teamId, userId, false, "all")).isEmpty();

        verify(chatRepository).findRecentByLastActivity(teamId, null, null, 20);
    }

    @Test
    void listChatsForTeam_idleExecution_returnsIdle() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);

        assertThat(listSingleChat(chat, execution, null, null, null, false).status())
                .isEqualTo(TopicStatus.IDLE);
    }

    @Test
    void listChatsForTeam_usesMostRecentExecutionTimestampForActivity() {
        Instant chatUpdatedAt = Instant.parse("2026-05-01T10:00:00Z");
        Instant completedAt = Instant.parse("2026-05-02T12:00:00Z");
        ChatEntity chat = persistedChat(chatUpdatedAt);
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.COMPLETED);
        execution.setStartedAt(Instant.parse("2026-05-02T11:00:00Z"));
        execution.setCompletedAt(completedAt);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 0, "[]", "digest");
        snapshot.setUpdatedAt(completedAt);

        ChatDto dto = listSingleChat(chat, execution, snapshot, null, null, false);
        assertThat(dto.lastActivityAt()).isEqualTo(completedAt);
        assertThat(dto.lastActivityType()).isEqualTo(LastActivityType.EXECUTION);
        assertThat(dto.lastActivityTargetId()).isEqualTo(execution.getId().toString());
    }

    @Test
    void listChatsForTeam_canvasActivityAfterPublish_evictsMrStatusAndRoutesToCanvas() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);
        execution.setStartedAt(Instant.parse("2026-05-01T11:00:00Z"));
        ChangeSet changeSet = new ChangeSet(
                execution, 1, "base", "push", "feature", 42L, "url", "main", Instant.parse("2026-05-01T12:00:00Z"));
        changeSet.setManifestDigest("digest-1");
        changeSet.setPrStatus(PublishedPrStatus.OPEN);
        execution.addChangeSet(changeSet);
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 0, "[]", "digest-1");
        snapshot.setUpdatedAt(Instant.parse("2026-05-01T11:30:00Z"));

        CanvasRepository.CanvasActivity canvas = mock(CanvasRepository.CanvasActivity.class);
        when(canvas.getChatId()).thenReturn(chat.getId());
        when(canvas.getDocumentId()).thenReturn("plan-doc");
        when(canvas.getUpdatedAt()).thenReturn(Instant.parse("2026-05-01T13:00:00Z"));
        UUID userId = UUID.randomUUID();
        when(canvasRepository.findActiveMetadataByChatIdIn(List.of(chat.getId())))
                .thenReturn(List.of(canvas));
        when(teamMemberRepository.existsByTeamIdAndUserId(chat.getTeam().getId(), userId))
                .thenReturn(true);

        ChatRepository.RecentChatRow row = summaryRow(chat, execution.getId(), null, null, false);
        when(chatRepository.findRecentByLastActivity(chat.getTeam().getId(), null, false, 20))
                .thenReturn(List.of(row));
        when(sandboxExecutionRepository.findAllById(List.of(execution.getId()))).thenReturn(List.of(execution));
        when(diffSnapshotRepository.findByExecutionIdIn(List.of(execution.getId())))
                .thenReturn(List.of(snapshot));

        ChatDto dto = chatService
                .listChatsForTeam(chat.getTeam().getId(), userId, false, "active")
                .getFirst();

        assertThat(dto.status()).isEqualTo(TopicStatus.IDLE);
        assertThat(dto.lastActivityAt()).isEqualTo(Instant.parse("2026-05-01T13:00:00Z"));
        assertThat(dto.lastActivityType()).isEqualTo(LastActivityType.CANVAS);
        assertThat(dto.lastActivityTargetId()).isEqualTo("plan-doc");
    }

    @Test
    void verifyChat_existingChatWithoutHttpSecurityContext_doesNotThrow() {
        UUID chatId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        ChatEntity chat = new ChatEntity();
        chat.setId(chatId);
        when(chatRepository.findByIdAndTeamId(chatId, teamId)).thenReturn(Optional.of(chat));

        chatService.verifyChat(chatId, teamId);
    }

    @Test
    void verifyChat_missingChat_throwsNotFound() {
        UUID chatId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        when(chatRepository.findByIdAndTeamId(chatId, teamId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.verifyChat(chatId, teamId))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getChatUsage_nonMember_throwsForbidden() {
        UUID chatId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ChatEntity chat = persistedChat(Instant.now());
        chat.getTeam().setId(teamId);
        chat.setId(chatId);
        when(chatRepository.findByIdAndTeamId(chatId, teamId)).thenReturn(Optional.of(chat));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(false);

        assertThatThrownBy(() -> chatService.getChatUsage(chatId, teamId, userId))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getChat_doesNotLoadUsageSessions() {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        chat.getTeam().setId(teamId);
        ChatRepository.RecentChatRow row = summaryRow(chat, null, null, null, false);
        when(chatRepository.findSummaryByIdAndTeamId(chat.getId(), teamId)).thenReturn(Optional.of(row));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);

        ChatDto dto = chatService.getChat(chat.getId(), teamId, userId);

        assertThat(dto.id()).isEqualTo(chat.getId());
        verify(chatUsageSessionRepository, never()).findByChatId(chat.getId());
    }

    @Test
    void getChatUsage_aggregatesSessions() {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        chat.getTeam().setId(teamId);
        when(chatRepository.findByIdAndTeamId(chat.getId(), teamId)).thenReturn(Optional.of(chat));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        ChatUsageSession session = new ChatUsageSession(chat, "key-1");
        session.getUsage().setTotalSpend(1.25);
        session.getUsage().setTotalTokens(250L);
        session.getUsage().setPromptTokens(150L);
        session.getUsage().setCompletionTokens(100L);
        session.getUsage().setUsageLastUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(chatUsageSessionRepository.findByChatId(chat.getId())).thenReturn(List.of(session));

        ChatUsageDto usage = chatService.getChatUsage(chat.getId(), teamId, userId);

        assertThat(usage.totalSpend()).isEqualTo(1.25);
        assertThat(usage.totalTokens()).isEqualTo(250L);
        assertThat(usage.promptTokens()).isEqualTo(150L);
        assertThat(usage.completionTokens()).isEqualTo(100L);
        assertThat(usage.usageLastUpdatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    private ChatEntity persistedChat(Instant updatedAt) {
        Team team = new Team();
        team.setId(UUID.randomUUID());
        User user = new User();
        user.setDisplayName("Test User");
        ChatEntity chat = new ChatEntity(team, user, "Test Chat");
        chat.setId(UUID.randomUUID());
        chat.setCreatedAt(updatedAt.minusSeconds(60));
        chat.setUpdatedAt(updatedAt);
        return chat;
    }

    private SandboxExecution execution(ChatEntity chat, SandboxExecutionStatus status) {
        SandboxExecution execution = new SandboxExecution();
        execution.setId(UUID.randomUUID());
        execution.setChat(chat);
        execution.setStatus(status);
        execution.setStartedAt(Instant.parse("2026-05-01T11:00:00Z"));
        return execution;
    }

    private ChatDto listSingleChat(
            ChatEntity chat,
            SandboxExecution execution,
            ExecutionDiffSnapshot snapshot,
            Instant lastMessageAt,
            Instant lastExecutionActivityAt,
            boolean pendingHitl) {
        UUID teamId = chat.getTeam().getId();
        UUID userId = UUID.randomUUID();
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        ChatRepository.RecentChatRow row = summaryRow(
                chat,
                execution != null ? execution.getId() : null,
                lastMessageAt,
                lastExecutionActivityAt,
                pendingHitl);
        when(chatRepository.findRecentByLastActivity(teamId, null, false, 20)).thenReturn(List.of(row));
        when(canvasRepository.findActiveMetadataByChatIdIn(List.of(chat.getId())))
                .thenReturn(List.of());
        if (execution != null) {
            when(sandboxExecutionRepository.findAllById(List.of(execution.getId())))
                    .thenReturn(List.of(execution));
            when(diffSnapshotRepository.findByExecutionIdIn(List.of(execution.getId())))
                    .thenReturn(snapshot != null ? List.of(snapshot) : List.of());
        }

        return chatService.listChatsForTeam(teamId, userId, false, "active").getFirst();
    }

    private static ChatRepository.RecentChatRow summaryRow(
            ChatEntity chat,
            UUID latestExecutionId,
            Instant lastMessageAt,
            Instant latestExecutionActivityAt,
            boolean pendingHitl) {
        return new TestRecentChatRow(
                chat.getId(),
                chat.getTitle(),
                chat.isAllowAllPermissions(),
                chat.getUpdatedAt(),
                lastMessageAt,
                latestExecutionId,
                latestExecutionActivityAt,
                pendingHitl);
    }

    private record TestRecentChatRow(
            UUID id,
            String title,
            boolean allowAllPermissions,
            Instant updatedAt,
            Instant lastMessageAt,
            UUID latestExecutionId,
            Instant latestExecutionActivityAt,
            boolean pendingHitl)
            implements ChatRepository.RecentChatRow {

        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public boolean isAllowAllPermissions() {
            return allowAllPermissions;
        }

        @Override
        public Instant getUpdatedAt() {
            return updatedAt;
        }

        @Override
        public Instant getLastMessageAt() {
            return lastMessageAt;
        }

        @Override
        public UUID getLatestExecutionId() {
            return latestExecutionId;
        }

        @Override
        public Instant getLatestExecutionActivityAt() {
            return latestExecutionActivityAt;
        }

        @Override
        public boolean isPendingHitl() {
            return pendingHitl;
        }
    }
}
