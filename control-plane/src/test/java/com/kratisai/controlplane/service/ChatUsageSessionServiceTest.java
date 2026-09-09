package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.*;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatUsageSessionServiceTest {

    private ChatUsageSessionRepository chatUsageSessionRepository;
    private ChatRepository chatRepository;
    private ChatUsageSessionService chatUsageSessionService;
    private LiteLLMClient liteLLMClient;

    @BeforeEach
    void setUp() {
        chatUsageSessionRepository = mock(ChatUsageSessionRepository.class);
        chatRepository = mock(ChatRepository.class);
        liteLLMClient = mock(LiteLLMClient.class);
        when(liteLLMClient.generateKey(any())).thenReturn(new GenerateKeyResponse("sk-test-virtual-key", "Test Key"));
        when(liteLLMClient.keyInfo(any()))
                .thenReturn(new KeyInfoResponse("sk-test-virtual-key", new KeyInfoData("Test Key", 1.5)));
        when(liteLLMClient.spendLogs(any())).thenReturn(List.of(new SpendLogEntry(100L, 80L, 20L)));

        VirtualKeyService virtualKeyService = new VirtualKeyService(liteLLMClient);
        chatUsageSessionService =
                new ChatUsageSessionService(chatUsageSessionRepository, chatRepository, virtualKeyService);
    }

    @Test
    void getOrCreateActiveSession_createsNewSessionWhenNoneExist() {
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());

        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(List.of());
        when(chatUsageSessionRepository.save(any(ChatUsageSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatUsageSession session = chatUsageSessionService.getOrCreateActiveSession(chat, List.of("gpt-4o"));

        assertThat(session).isNotNull();
        assertThat(session.getUsage().getVirtualKey()).isEqualTo("sk-test-virtual-key");
        assertThat(session.getEndedAt()).isNull();
        assertThat(session.getModels()).containsExactly("gpt-4o");
        verify(chatUsageSessionRepository).save(any(ChatUsageSession.class));
    }

    @Test
    void getOrCreateActiveSession_withTeamIdAndSessionId_createsNewWhenChatNotFound() {
        UUID sessionId = UUID.randomUUID();

        when(chatRepository.findById(sessionId)).thenReturn(Optional.empty());
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(sessionId))
                .thenReturn(List.of());
        when(chatUsageSessionRepository.save(any(ChatUsageSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatUsageSession session =
                chatUsageSessionService.getOrCreateActiveSession(sessionId, "gpt-4o", "litellm-alias-gpt-4o");

        assertThat(session).isNotNull();
        assertThat(session.getChat().getId()).isEqualTo(sessionId);
        assertThat(session.getModel()).isEqualTo("gpt-4o");
        assertThat(session.getUsage().getVirtualKey()).isEqualTo("sk-test-virtual-key");
        verify(chatRepository).findById(sessionId);
        verify(liteLLMClient).generateKey(argThat(req -> req.models().contains("litellm-alias-gpt-4o")));
    }

    @Test
    void getOrCreateActiveSession_withTeamIdAndSessionId_usesExistingChat() {
        UUID sessionId = UUID.randomUUID();
        ChatEntity existingChat = new ChatEntity();
        existingChat.setId(sessionId);

        when(chatRepository.findById(sessionId)).thenReturn(Optional.of(existingChat));
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(sessionId))
                .thenReturn(List.of());
        when(chatUsageSessionRepository.save(any(ChatUsageSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatUsageSession session =
                chatUsageSessionService.getOrCreateActiveSession(sessionId, "gpt-4o", "litellm-alias-gpt-4o");

        assertThat(session).isNotNull();
        assertThat(session.getChat().getId()).isEqualTo(sessionId);
        assertThat(session.getModel()).isEqualTo("gpt-4o");
        assertThat(session.getUsage().getVirtualKey()).isEqualTo("sk-test-virtual-key");
        verify(liteLLMClient).generateKey(argThat(req -> req.models().contains("litellm-alias-gpt-4o")));
    }

    @Test
    void refreshUsageForChat_updatesActiveSessions() {
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());

        ChatUsageSession session = new ChatUsageSession(chat, "sk-test-virtual-key");
        when(chatUsageSessionRepository.findByChatId(chat.getId())).thenReturn(List.of(session));

        chatUsageSessionService.refreshUsageForChat(chat.getId());

        assertThat(session.getUsage().getTotalSpend()).isEqualTo(1.5);
        assertThat(session.getUsage().getTotalTokens()).isEqualTo(100L);
        assertThat(session.getUsage().getPromptTokens()).isEqualTo(80L);
        assertThat(session.getUsage().getCompletionTokens()).isEqualTo(20L);
        verify(chatUsageSessionRepository).save(session);
    }

    @Test
    void syncUsage_updatesSessionByVirtualKey() {
        UUID sessionId = UUID.randomUUID();
        String virtualKey = "sk-test-virtual-key";

        ChatUsageSession session = new ChatUsageSession(new ChatEntity(), virtualKey);
        session.setId(UUID.randomUUID());

        when(chatUsageSessionRepository.findByVirtualKey(virtualKey)).thenReturn(Optional.of(session));
        when(chatUsageSessionRepository.save(any(ChatUsageSession.class))).thenAnswer(inv -> inv.getArgument(0));

        chatUsageSessionService.syncUsage(sessionId, virtualKey);

        verify(chatUsageSessionRepository).findByVirtualKey(virtualKey);
        verify(chatUsageSessionRepository).save(session);
        assertThat(session.getUsage().getTotalTokens()).isEqualTo(100L);
    }

    @Test
    void syncUsage_fallsBackToRecordUsageWhenNotFoundByKey() {
        UUID sessionId = UUID.randomUUID();
        String virtualKey = "sk-test-virtual-key";

        when(chatUsageSessionRepository.findByVirtualKey(virtualKey)).thenReturn(Optional.empty());
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(sessionId))
                .thenReturn(List.of());
        when(chatUsageSessionRepository.save(any(ChatUsageSession.class))).thenAnswer(inv -> inv.getArgument(0));

        chatUsageSessionService.syncUsage(sessionId, virtualKey);

        verify(chatUsageSessionRepository).findByVirtualKey(virtualKey);
        // Should create new session via recordUsage path
    }

    @Test
    void recordUsage_updatesActiveSession() {
        UUID sessionId = UUID.randomUUID();
        ChatUsageSession session = new ChatUsageSession(new ChatEntity(), "sk-test-virtual-key");
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(sessionId))
                .thenReturn(List.of(session));

        chatUsageSessionService.recordUsage(sessionId, new LlmUsageSnapshot(2.5, 500L, 300L, 200L));

        assertThat(session.getUsage().getTotalSpend()).isEqualTo(2.5);
        assertThat(session.getUsage().getTotalTokens()).isEqualTo(500L);
        verify(chatUsageSessionRepository).save(session);
    }

    @Test
    void recordUsage_fallsBackToLatestWhenNoActiveSession() {
        UUID sessionId = UUID.randomUUID();
        ChatUsageSession endedSession = new ChatUsageSession(new ChatEntity(), "sk-old-key");
        endedSession.setEndedAt(java.time.Instant.now());
        ChatUsageSession activeSession = new ChatUsageSession(new ChatEntity(), "sk-new-key");
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(sessionId))
                .thenReturn(List.of(activeSession, endedSession));

        chatUsageSessionService.recordUsage(sessionId, new LlmUsageSnapshot(1.0, 100L, 60L, 40L));

        assertThat(activeSession.getUsage().getTotalTokens()).isEqualTo(100L);
        assertThat(endedSession.getUsage().getTotalTokens()).isEqualTo(0L);
        verify(chatUsageSessionRepository).save(activeSession);
    }

    @Test
    void getOrCreateActiveSession_reusesSessionWhenSameModel() {
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());
        ChatUsageSession session = new ChatUsageSession(chat, "sk-test-virtual-key");
        session.getUsage().setUsageLastUpdatedAt(Instant.now());
        session.setModel("provider-model-a-123");
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(List.of(session));
        when(liteLLMClient.keyInfo("sk-test-virtual-key"))
                .thenReturn(new KeyInfoResponse("sk-test-virtual-key", new KeyInfoData("alias", 0.0)));
        when(liteLLMClient.spendLogs("sk-test-virtual-key")).thenReturn(List.of());

        ChatUsageSession result =
                chatUsageSessionService.getOrCreateActiveSession(chat, List.of("provider-model-a-123"));

        assertThat(result).isSameAs(session);
        assertThat(result.getModel()).isEqualTo("provider-model-a-123");
        assertThat(result.getModels()).containsExactly("provider-model-a-123");
        verify(liteLLMClient, never()).updateKey(any(UpdateKeyRequest.class));
    }

    @Test
    void getOrCreateActiveSession_createsNewSessionWhenModelChanged() {
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID());
        ChatUsageSession session = new ChatUsageSession(chat, "sk-test-virtual-key");
        session.getUsage().setUsageLastUpdatedAt(Instant.now());
        session.setModel("provider-model-a-123");
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(List.of(session));
        when(liteLLMClient.keyInfo("sk-test-virtual-key"))
                .thenReturn(new KeyInfoResponse("sk-test-virtual-key", new KeyInfoData("alias", 0.0)));
        when(liteLLMClient.spendLogs("sk-test-virtual-key")).thenReturn(List.of());
        when(liteLLMClient.generateKey(any(GenerateKeyRequest.class)))
                .thenReturn(new GenerateKeyResponse("sk-new-key", "alias"));
        when(chatUsageSessionRepository.save(any(ChatUsageSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatUsageSession result =
                chatUsageSessionService.getOrCreateActiveSession(chat, List.of("provider-model-b-456"));

        assertThat(result).isNotSameAs(session);
        assertThat(result.getModel()).isEqualTo("provider-model-b-456");
        assertThat(session.isEnded()).isTrue();
        verify(liteLLMClient).deleteKey(any());
        verify(liteLLMClient).generateKey(any());
    }
}
