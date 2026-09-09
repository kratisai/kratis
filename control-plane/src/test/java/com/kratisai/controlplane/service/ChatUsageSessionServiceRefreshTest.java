package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatUsageSessionServiceRefreshTest {

    private ChatUsageSessionRepository chatUsageSessionRepository;
    private VirtualKeyService virtualKeyService;
    private ChatUsageSessionService chatUsageSessionService;

    @BeforeEach
    void setUp() {
        chatUsageSessionRepository = mock(ChatUsageSessionRepository.class);
        ChatRepository chatRepository = mock(ChatRepository.class);
        virtualKeyService = mock(VirtualKeyService.class);
        chatUsageSessionService =
                new ChatUsageSessionService(chatUsageSessionRepository, chatRepository, virtualKeyService);
    }

    @Test
    void refreshUsageForChat_fetchesUsageFromLiteLLMAndUpdatesActiveSessions() {
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());

        ChatUsageSession session = new ChatUsageSession(chat, "sk-test-virtual-key");
        when(chatUsageSessionRepository.findByChatId(chat.getId())).thenReturn(List.of(session));

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
    void isVirtualKeyValid_returnsFalseForNullOrBlankKey() {
        // Test via getOrCreateActiveSession rollover path with invalid key
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());
        ChatUsageSession session = new ChatUsageSession(chat, "sk-invalid-key");
        session.setId(UUID.randomUUID());
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(List.of(session));
        when(chatUsageSessionRepository.save(any(ChatUsageSession.class))).thenAnswer(inv -> {
            ChatUsageSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        when(virtualKeyService.fetchUsage("sk-invalid-key")).thenThrow(new RuntimeException("404 Not Found"));

        ChatUsageSession newSession = chatUsageSessionService.getOrCreateActiveSession(chat, List.of("gpt-4o"));

        assertThat(newSession.getId()).isNotEqualTo(session.getId());
        assertThat(session.isEnded()).isTrue();
        verify(virtualKeyService, atLeastOnce()).fetchUsage("sk-invalid-key");
    }

    @Test
    void isVirtualKeyValid_returnsFalseForExpiredKey() {
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());
        ChatUsageSession session = new ChatUsageSession(chat, "sk-expired-key");
        session.setId(UUID.randomUUID());
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(List.of(session));
        when(chatUsageSessionRepository.save(any(ChatUsageSession.class))).thenAnswer(inv -> {
            ChatUsageSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        when(virtualKeyService.fetchUsage("sk-expired-key")).thenThrow(new RuntimeException("key expired"));

        ChatUsageSession newSession = chatUsageSessionService.getOrCreateActiveSession(chat, List.of("gpt-4o"));

        assertThat(newSession.getId()).isNotEqualTo(session.getId());
        assertThat(session.isEnded()).isTrue();
        verify(virtualKeyService, atLeastOnce()).fetchUsage("sk-expired-key");
    }

    @Test
    void isVirtualKeyValid_returnsFalseForUnauthorizedKey() {
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());
        ChatUsageSession session = new ChatUsageSession(chat, "sk-unauthorized-key");
        session.setId(UUID.randomUUID());
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(List.of(session));
        when(chatUsageSessionRepository.save(any(ChatUsageSession.class))).thenAnswer(inv -> {
            ChatUsageSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        when(virtualKeyService.fetchUsage("sk-unauthorized-key")).thenThrow(new RuntimeException("401 Unauthorized"));

        ChatUsageSession newSession = chatUsageSessionService.getOrCreateActiveSession(chat, List.of("gpt-4o"));

        assertThat(newSession.getId()).isNotEqualTo(session.getId());
        assertThat(session.isEnded()).isTrue();
        verify(virtualKeyService, atLeastOnce()).fetchUsage("sk-unauthorized-key");
    }

    @Test
    void getOrCreateActiveSession_rollsOverWhenKeyInvalidButWithinTimeThreshold() {
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());
        ChatUsageSession session = new ChatUsageSession(chat, "sk-invalid-key");
        session.setId(UUID.randomUUID());
        // Session is recent (within 4h) but key is invalid
        session.getUsage().setUsageLastUpdatedAt(java.time.Instant.now().minusSeconds(3600)); // 1 hour ago
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(List.of(session));
        when(chatUsageSessionRepository.save(any(ChatUsageSession.class))).thenAnswer(inv -> {
            ChatUsageSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        when(virtualKeyService.fetchUsage("sk-invalid-key")).thenThrow(new RuntimeException("404 Not Found"));

        ChatUsageSession newSession = chatUsageSessionService.getOrCreateActiveSession(chat, List.of("gpt-4o"));

        assertThat(newSession.getId()).isNotEqualTo(session.getId());
        assertThat(session.isEnded()).isTrue();
        verify(virtualKeyService, atLeastOnce()).fetchUsage("sk-invalid-key");
    }
}
