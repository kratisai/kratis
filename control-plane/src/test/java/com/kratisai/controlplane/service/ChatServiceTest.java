package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.api.restdto.ChatDto;
import com.kratisai.controlplane.api.restdto.TopicStatus;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.AgentChatMemoryRepository;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
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

class ChatServiceTest {

    private ChatRepository chatRepository;
    private TeamMemberRepository teamMemberRepository;
    private ChatUsageSessionRepository chatUsageSessionRepository;
    private SandboxExecutionRepository sandboxExecutionRepository;
    private SandboxExecutionActivityRepository activityRepository;
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
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        chatService = new ChatService(
                chatRepository,
                teamRepository,
                userRepository,
                teamMemberRepository,
                chatMemoryRepository,
                chatUsageSessionRepository,
                sandboxExecutionRepository,
                activityRepository,
                eventPublisher);
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
        when(chatUsageSessionRepository.findByChatId(chat.getId())).thenReturn(List.of());

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

        when(chatUsageSessionRepository.findByChatId(chat.getId())).thenReturn(List.of(session1, session2));

        List<ChatDto> dtos = chatService.listChatsForTeam(teamId, userId, false, "active");

        assertThat(dtos).hasSize(1);
        ChatDto dto = dtos.get(0);
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
        execution.setPublishedPrNumber(42L);
        when(sandboxExecutionRepository.findFirstByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(Optional.of(execution));

        assertThat(listSingleChat(chat).status()).isEqualTo(TopicStatus.MR_PUSHED);
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

        assertThat(listSingleChat(chat).lastActivityAt()).isEqualTo(completedAt);
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
        when(chatUsageSessionRepository.findByChatId(chat.getId())).thenReturn(List.of());
        return chatService.listChatsForTeam(teamId, userId, false, "active").get(0);
    }
}
