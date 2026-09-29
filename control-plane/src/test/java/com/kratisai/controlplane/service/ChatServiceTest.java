package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.api.restdto.ChatDto;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.AgentChatMemoryRepository;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ChatServiceTest {

    private ChatRepository chatRepository;
    private TeamMemberRepository teamMemberRepository;
    private ChatUsageSessionRepository chatUsageSessionRepository;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatRepository = mock(ChatRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        AgentChatMemoryRepository chatMemoryRepository = mock(AgentChatMemoryRepository.class);
        chatUsageSessionRepository = mock(ChatUsageSessionRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        chatService = new ChatService(
                chatRepository,
                teamRepository,
                userRepository,
                teamMemberRepository,
                chatMemoryRepository,
                chatUsageSessionRepository,
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
}
