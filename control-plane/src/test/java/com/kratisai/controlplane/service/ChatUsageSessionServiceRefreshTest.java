package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.ChatUsageChangedEvent;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ChatUsageSessionServiceRefreshTest {

    private ChatUsageSessionRepository chatUsageSessionRepository;
    private ChatRepository chatRepository;
    private VirtualKeyService virtualKeyService;
    private ApplicationEventPublisher eventPublisher;
    private ChatUsageSessionService chatUsageSessionService;

    @BeforeEach
    void setUp() {
        chatUsageSessionRepository = mock(ChatUsageSessionRepository.class);
        chatRepository = mock(ChatRepository.class);
        virtualKeyService = mock(VirtualKeyService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        chatUsageSessionService = new ChatUsageSessionService(
                chatUsageSessionRepository, chatRepository, virtualKeyService, eventPublisher);
    }

    @Test
    void refreshUsageForChat_fetchesUsageFromLiteLLMAndUpdatesActiveSessions() {
        UUID teamId = UUID.randomUUID();
        ChatEntity chat = chat(teamId);
        ChatUsageSession session = new ChatUsageSession(chat, "sk-test-virtual-key");
        when(chatUsageSessionRepository.findByChatId(chat.getId())).thenReturn(List.of(session));
        when(chatRepository.findById(chat.getId())).thenReturn(Optional.of(chat));

        when(virtualKeyService.fetchUsage("sk-test-virtual-key"))
                .thenReturn(new LlmUsageSnapshot(2.25, 450L, 300L, 150L));

        chatUsageSessionService.refreshUsageForChat(chat.getId());

        verify(virtualKeyService).fetchUsage("sk-test-virtual-key");
        assertThat(session.getUsage().getTotalSpend()).isEqualTo(2.25);
        assertThat(session.getUsage().getTotalTokens()).isEqualTo(450L);
        assertThat(session.getUsage().getPromptTokens()).isEqualTo(300L);
        assertThat(session.getUsage().getCompletionTokens()).isEqualTo(150L);
        assertThat(session.getUsage().getUsageLastUpdatedAt()).isNotNull();
        verify(chatUsageSessionRepository).save(session);
        verify(eventPublisher).publishEvent(new ChatUsageChangedEvent(teamId, chat.getId()));
    }

    @Test
    void refreshUsageForChat_doesNotPublishWhenThereAreNoSessions() {
        UUID teamId = UUID.randomUUID();
        ChatEntity chat = chat(teamId);
        when(chatUsageSessionRepository.findByChatId(chat.getId())).thenReturn(List.of());
        chatUsageSessionService.refreshUsageForChat(chat.getId());

        verify(chatRepository, never()).findById(chat.getId());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void refreshUsageForChat_doesNotPublishWhenChatIsMissing() {
        UUID chatId = UUID.randomUUID();
        ChatUsageSession session = new ChatUsageSession(new ChatEntity(), "sk-test-virtual-key");
        when(chatUsageSessionRepository.findByChatId(chatId)).thenReturn(List.of(session));
        when(chatRepository.findById(chatId)).thenReturn(Optional.empty());
        when(virtualKeyService.fetchUsage("sk-test-virtual-key"))
                .thenReturn(new LlmUsageSnapshot(2.25, 450L, 300L, 150L));

        chatUsageSessionService.refreshUsageForChat(chatId);

        verify(eventPublisher, never()).publishEvent(any());
    }

    private static ChatEntity chat(UUID teamId) {
        Team team = new Team();
        team.setId(teamId);
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());
        chat.setTeam(team);
        return chat;
    }

    @Test
    void closeAndRevokeSessions_finalizesUsageAndRevokesKey() {
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());

        ChatUsageSession session = new ChatUsageSession(chat, "sk-test-virtual-key");
        when(chatUsageSessionRepository.findByChatId(chat.getId())).thenReturn(List.of(session));

        when(virtualKeyService.fetchUsage("sk-test-virtual-key"))
                .thenReturn(new LlmUsageSnapshot(3.0, 600L, 400L, 200L));

        chatUsageSessionService.closeAndRevokeSessions(chat.getId());

        verify(virtualKeyService).fetchUsage("sk-test-virtual-key");
        verify(virtualKeyService).revokeKey("sk-test-virtual-key");
        assertThat(session.isEnded()).isTrue();
        assertThat(session.getUsage().getTotalSpend()).isEqualTo(3.0);
        assertThat(session.getUsage().getTotalTokens()).isEqualTo(600L);
        verify(chatUsageSessionRepository).save(session);
    }

    @Test
    void getOrCreateActiveSession_reusesRecentSessionWhenStoredKeyIsValid() {
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());
        ChatUsageSession session = new ChatUsageSession(chat, "sk-valid-key", "gpt-4o");
        session.getUsage().setUsageLastUpdatedAt(Instant.now().minusSeconds(3600));
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(List.of(session));
        when(virtualKeyService.keyExists("sk-valid-key")).thenReturn(true);

        ChatUsageSession activeSession = chatUsageSessionService.getOrCreateActiveSession(chat, List.of("gpt-4o"));

        assertThat(activeSession).isSameAs(session);
        assertThat(session.isEnded()).isFalse();
        verify(virtualKeyService).keyExists("sk-valid-key");
        verify(virtualKeyService, never()).fetchUsage(any());
        verify(chatUsageSessionRepository, never()).save(any());
    }

    @Test
    void getOrCreateActiveSession_rollsOverWhenStoredKeyIsUnknown() {
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());
        ChatUsageSession session = new ChatUsageSession(chat, "sk-unknown-key", "gpt-4o");
        session.getUsage().setUsageLastUpdatedAt(Instant.now().minusSeconds(3600));
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(List.of(session));
        when(virtualKeyService.keyExists("sk-unknown-key")).thenReturn(false);
        when(virtualKeyService.fetchUsage("sk-unknown-key")).thenReturn(new LlmUsageSnapshot(1.0, 100L, 60L, 40L));
        when(virtualKeyService.generateKey(anyString(), anyList())).thenReturn("sk-new-key");
        when(chatUsageSessionRepository.save(any(ChatUsageSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatUsageSession newSession = chatUsageSessionService.getOrCreateActiveSession(chat, List.of("gpt-4o"));

        assertThat(newSession).isNotSameAs(session);
        assertThat(newSession.getUsage().getVirtualKey()).isEqualTo("sk-new-key");
        assertThat(session.isEnded()).isTrue();
        assertThat(session.getUsage().getTotalTokens()).isEqualTo(100L);
        verify(virtualKeyService).keyExists("sk-unknown-key");
        verify(virtualKeyService).fetchUsage("sk-unknown-key");
        verify(virtualKeyService).revokeKey("sk-unknown-key");
    }

    @Test
    void getOrCreateActiveSession_rollsOverWhenStoredKeyIsMissing() {
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());
        ChatUsageSession session = new ChatUsageSession(chat, null, "gpt-4o");
        session.getUsage().setUsageLastUpdatedAt(Instant.now().minusSeconds(3600));
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(List.of(session));
        when(virtualKeyService.generateKey(anyString(), anyList())).thenReturn("sk-new-key");
        when(chatUsageSessionRepository.save(any(ChatUsageSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatUsageSession newSession = chatUsageSessionService.getOrCreateActiveSession(chat, List.of("gpt-4o"));

        assertThat(newSession).isNotSameAs(session);
        assertThat(session.isEnded()).isTrue();
        verify(virtualKeyService).keyExists(null);
        verify(virtualKeyService, never()).fetchUsage(any());
        verify(virtualKeyService, never()).revokeKey(any());
    }
}
