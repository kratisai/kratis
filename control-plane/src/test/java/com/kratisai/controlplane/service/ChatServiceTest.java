package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.api.restdto.ChatDto;
import com.kratisai.controlplane.api.restdto.ChatUsageDto;
import com.kratisai.controlplane.api.restdto.LastActivityType;
import com.kratisai.controlplane.api.restdto.TopicStatus;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.model.CanvasEntity;
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
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
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
    private SandboxExecutionActivityRepository activityRepository;
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
        activityRepository = mock(SandboxExecutionActivityRepository.class);
        diffSnapshotRepository = mock(ExecutionDiffSnapshotRepository.class);
        canvasRepository = mock(CanvasRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        chatFluxRegistry = mock(ChatFluxRegistry.class);

        when(canvasRepository.findByChatIdAndDeletedAtIsNull(any())).thenReturn(List.of());

        chatService = new ChatService(
                chatRepository,
                teamRepository,
                userRepository,
                teamMemberRepository,
                chatMemoryRepository,
                chatUsageSessionRepository,
                sandboxExecutionRepository,
                activityRepository,
                diffSnapshotRepository,
                canvasRepository,
                eventPublisher,
                chatFluxRegistry);
    }

    @Test
    void listChatsForTeam_aggregatesUsageAcrossMultipleSessions() {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);

        Team team = new Team();
        team.setId(teamId);

        User user = new User();
        user.setDisplayName("Test User");

        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());
        chat.setTeam(team);
        chat.setUser(user);
        chat.setTitle("Test Chat");
        chat.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));

        when(chatRepository.findTop20ByTeamIdAndArchivedAtIsNullOrderByUpdatedAtDesc(teamId))
                .thenReturn(List.of(chat));
        ChatUsageSession session1 = new ChatUsageSession(chat, "key-1");
        session1.getUsage().setTotalSpend(1.0);
        session1.getUsage().setTotalTokens(50L);
        session1.getUsage().setPromptTokens(30L);
        session1.getUsage().setCompletionTokens(20L);
        session1.getUsage().setUsageLastUpdatedAt(Instant.now());

        ChatUsageSession session2 = new ChatUsageSession(chat, "key-2");
        session2.getUsage().setTotalSpend(2.5);
        session2.getUsage().setTotalTokens(150L);
        session2.getUsage().setPromptTokens(100L);
        session2.getUsage().setCompletionTokens(50L);
        session2.getUsage().setUsageLastUpdatedAt(Instant.now());

        when(chatUsageSessionRepository.findByChatIdIn(List.of(chat.getId()))).thenReturn(List.of(session1, session2));

        List<ChatDto> dtos = chatService.listChatsForTeam(teamId, userId, false, "active");

        assertThat(dtos).hasSize(1);
        ChatDto dto = dtos.getFirst();
        assertThat(dto.totalSpend()).isEqualTo(3.5);
        assertThat(dto.totalTokens()).isEqualTo(200L);
        assertThat(dto.promptTokens()).isEqualTo(130L);
        assertThat(dto.completionTokens()).isEqualTo(70L);
        assertThat(dto.usageLastUpdatedAt()).isNotNull();
    }

    @Test
    void listChatsForTeam_withoutExecution_returnsIdleAndChatActivity() {
        Instant updatedAt = Instant.parse("2026-05-01T10:00:00Z");
        ChatEntity chat = persistedChat(updatedAt);
        when(sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(Optional.empty());

        ChatDto dto = listSingleChat(chat);

        assertThat(dto.status()).isEqualTo(TopicStatus.IDLE);
        assertThat(dto.lastActivityAt()).isEqualTo(updatedAt);
        assertThat(dto.lastActivityType()).isEqualTo(LastActivityType.CHAT);
        assertThat(dto.lastActivityTargetId()).isEqualTo(chat.getId().toString());
    }

    @Test
    void listChatsForTeam_planningTurnInProgress_returnsPlanning() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        when(chatFluxRegistry.containsKey(chat.getId())).thenReturn(true);
        when(sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(Optional.empty());

        assertThat(listSingleChat(chat).status()).isEqualTo(TopicStatus.PLANNING);
    }

    @Test
    void listChatsForTeam_runningExecution_returnsWorking() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.RUNNING);
        when(sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(Optional.of(execution));
        when(activityRepository.existsByExecutionIdAndStatus(execution.getId(), ActivityStatus.PENDING))
                .thenReturn(false);

        assertThat(listSingleChat(chat).status()).isEqualTo(TopicStatus.WORKING);
    }

    @Test
    void listChatsForTeam_pendingHitl_returnsBlocked() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.RUNNING);
        when(sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(Optional.of(execution));
        when(activityRepository.existsByExecutionIdAndStatus(execution.getId(), ActivityStatus.PENDING))
                .thenReturn(true);

        assertThat(listSingleChat(chat).status()).isEqualTo(TopicStatus.BLOCKED);
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
        when(sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(Optional.of(execution));

        var changeSet = execution.getLatestChangeSet();
        changeSet.setManifestDigest("digest-1");
        changeSet.setPrStatus(PublishedPrStatus.OPEN);
        when(diffSnapshotRepository.findByExecutionId(execution.getId()))
                .thenReturn(Optional.of(
                        new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 0, "[]", "digest-1")));
        when(chatRepository.findLastMessageAt(chat.getId())).thenReturn(Optional.empty());

        assertThat(listSingleChat(chat).status()).isEqualTo(TopicStatus.MR_PUSHED);
    }

    @Test
    void listChatsForTeam_uncommittedDiffWithoutChangeSet_returnsReadyToPublish() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.COMPLETED);
        when(sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(Optional.of(execution));
        when(diffSnapshotRepository.findByExecutionId(execution.getId()))
                .thenReturn(Optional.of(
                        new ExecutionDiffSnapshot(execution.getId(), "base", "head", 1, 0, "[]", "digest")));

        assertThat(listSingleChat(chat).status()).isEqualTo(TopicStatus.READY_TO_PUBLISH);
    }

    @Test
    void listChatsForTeam_changedManifestAfterPublish_returnsReadyToPublish() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.IDLE);
        ChangeSet changeSet = new ChangeSet(execution, 1, "base", "push", "feature", null, null, "main", Instant.now());
        changeSet.setManifestDigest("published-digest");
        execution.addChangeSet(changeSet);
        when(sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(Optional.of(execution));
        when(diffSnapshotRepository.findByExecutionId(execution.getId()))
                .thenReturn(Optional.of(
                        new ExecutionDiffSnapshot(execution.getId(), "base", "head", 2, 0, "[]", "new-digest")));
        when(chatRepository.findLastMessageAt(chat.getId())).thenReturn(Optional.empty());

        assertThat(listSingleChat(chat).status()).isEqualTo(TopicStatus.READY_TO_PUBLISH);
    }

    @Test
    void listChatsForTeam_diffWithoutPublish_returnsReadyToPublish() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.COMPLETED);
        when(sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(Optional.of(execution));
        when(diffSnapshotRepository.findByExecutionId(execution.getId()))
                .thenReturn(Optional.of(
                        new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 3, "[]", "digest")));
        when(chatRepository.findLastMessageAt(chat.getId())).thenReturn(Optional.empty());

        assertThat(listSingleChat(chat).status()).isEqualTo(TopicStatus.READY_TO_PUBLISH);
    }

    @Test
    void listChatsForTeam_mergedPullRequestWithNoDiff_returnsIdle() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.COMPLETED);
        ChangeSet changeSet = new ChangeSet(execution, 1, "base", "push", "feature", 42L, "url", "main", Instant.now());
        changeSet.setManifestDigest("digest-1");
        changeSet.setPrStatus(PublishedPrStatus.MERGED);
        execution.addChangeSet(changeSet);
        when(sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(Optional.of(execution));
        when(diffSnapshotRepository.findByExecutionId(execution.getId()))
                .thenReturn(Optional.of(
                        new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 0, "[]", "digest-1")));
        when(chatRepository.findLastMessageAt(chat.getId())).thenReturn(Optional.empty());

        assertThat(listSingleChat(chat).status()).isEqualTo(TopicStatus.IDLE);
    }

    @Test
    void listChatsForTeam_failedExecution_returnsFailed() {
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.FAILED);
        when(sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(Optional.of(execution));

        assertThat(listSingleChat(chat).status()).isEqualTo(TopicStatus.FAILED);
    }

    @Test
    void listChatsForTeam_usesMostRecentExecutionTimestampForActivity() {
        Instant chatUpdatedAt = Instant.parse("2026-05-01T10:00:00Z");
        Instant completedAt = Instant.parse("2026-05-02T12:00:00Z");
        ChatEntity chat = persistedChat(chatUpdatedAt);
        SandboxExecution execution = execution(chat, SandboxExecutionStatus.COMPLETED);
        execution.setStartedAt(Instant.parse("2026-05-02T11:00:00Z"));
        execution.setCompletedAt(completedAt);
        when(sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(Optional.of(execution));

        when(chatRepository.findLastMessageAt(chat.getId())).thenReturn(Optional.empty());
        ExecutionDiffSnapshot snapshot =
                new ExecutionDiffSnapshot(execution.getId(), "base", "head", 0, 0, "[]", "digest");
        snapshot.setUpdatedAt(completedAt);
        when(diffSnapshotRepository.findByExecutionId(execution.getId())).thenReturn(Optional.of(snapshot));

        ChatDto dto = listSingleChat(chat);
        assertThat(dto.lastActivityAt()).isEqualTo(completedAt);
        assertThat(dto.lastActivityType()).isEqualTo(LastActivityType.EXECUTION);
        assertThat(dto.lastActivityTargetId()).isEqualTo(execution.getId().toString());
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
    void getChatUsage_totalsMatchChatListPath() {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ChatEntity chat = persistedChat(Instant.parse("2026-05-01T10:00:00Z"));
        chat.getTeam().setId(teamId);
        when(chatRepository.findByIdAndTeamId(chat.getId(), teamId)).thenReturn(Optional.of(chat));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        when(chatRepository.findTop20ByTeamIdAndArchivedAtIsNullOrderByUpdatedAtDesc(teamId))
                .thenReturn(List.of(chat));
        when(sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(Optional.empty());
        when(chatUsageSessionRepository.findByChatId(chat.getId())).thenReturn(List.of());
        ChatUsageSession session = new ChatUsageSession(chat, "key-1");
        session.getUsage().setTotalSpend(1.25);
        session.getUsage().setTotalTokens(250L);
        session.getUsage().setPromptTokens(150L);
        session.getUsage().setCompletionTokens(100L);
        session.getUsage().setUsageLastUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(chatUsageSessionRepository.findByChatIdIn(List.of(chat.getId()))).thenReturn(List.of(session));
        when(chatUsageSessionRepository.findByChatId(chat.getId())).thenReturn(List.of(session));

        ChatDto listDto =
                chatService.listChatsForTeam(teamId, userId, false, "active").getFirst();
        ChatUsageDto usage = chatService.getChatUsage(chat.getId(), teamId, userId);

        assertThat(usage.totalSpend()).isEqualTo(listDto.totalSpend());
        assertThat(usage.totalTokens()).isEqualTo(listDto.totalTokens());
        assertThat(usage.promptTokens()).isEqualTo(listDto.promptTokens());
        assertThat(usage.completionTokens()).isEqualTo(listDto.completionTokens());
        assertThat(usage.usageLastUpdatedAt()).isEqualTo(listDto.usageLastUpdatedAt());
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

    private ChatDto listSingleChat(ChatEntity chat) {
        UUID teamId = chat.getTeam().getId();
        UUID userId = UUID.randomUUID();
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        when(chatRepository.findTop20ByTeamIdAndArchivedAtIsNullOrderByUpdatedAtDesc(teamId))
                .thenReturn(List.of(chat));

        List<UUID> chatIds = List.of(chat.getId());
        Instant lastMessageAt = chatRepository.findLastMessageAt(chat.getId()).orElse(null);
        when(chatRepository.findLastMessageAtByChatIds(chatIds))
                .thenReturn(lastMessageAt == null ? List.of() : List.of(new LastMessage(chat.getId(), lastMessageAt)));
        List<ChatUsageSession> usageSessions = chatUsageSessionRepository.findByChatId(chat.getId());
        when(chatUsageSessionRepository.findByChatIdIn(chatIds)).thenReturn(usageSessions);
        List<CanvasEntity> canvases = canvasRepository.findByChatIdAndDeletedAtIsNull(chat.getId());
        when(canvasRepository.findByChatIdInAndDeletedAtIsNull(chatIds)).thenReturn(canvases);

        Optional<SandboxExecution> latest =
                sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId());
        when(sandboxExecutionRepository.findLatestByChatIds(chatIds))
                .thenReturn(latest.map(List::of).orElse(List.of()));
        latest.ifPresent(execution -> {
            List<ExecutionDiffSnapshot> snapshots = diffSnapshotRepository
                    .findByExecutionId(execution.getId())
                    .map(List::of)
                    .orElse(List.of());
            when(diffSnapshotRepository.findByExecutionIdIn(List.of(execution.getId())))
                    .thenReturn(snapshots);
            List<UUID> pending =
                    activityRepository.existsByExecutionIdAndStatus(execution.getId(), ActivityStatus.PENDING)
                            ? List.of(execution.getId())
                            : List.of();
            when(activityRepository.findExecutionIdsWithStatus(List.of(execution.getId()), ActivityStatus.PENDING))
                    .thenReturn(pending);
        });

        return chatService.listChatsForTeam(teamId, userId, false, "active").getFirst();
    }

    private record LastMessage(UUID chatId, Instant lastMessageAt) implements ChatRepository.ChatLastMessage {
        @Override
        public UUID getChatId() {
            return chatId;
        }

        @Override
        public Instant getLastMessageAt() {
            return lastMessageAt;
        }
    }
}
