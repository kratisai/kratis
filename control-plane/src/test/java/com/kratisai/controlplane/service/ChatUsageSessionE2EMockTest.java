package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.*;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatUsageSessionE2EMockTest {

    private ChatUsageSessionRepository chatUsageSessionRepository;
    private ChatUsageSessionService chatUsageSessionService;

    @BeforeEach
    void setUp() {
        chatUsageSessionRepository = mock(ChatUsageSessionRepository.class);
        LiteLLMClient liteLLMClient = mock(LiteLLMClient.class);
        when(liteLLMClient.generateKey(any())).thenReturn(new GenerateKeyResponse("sk-test-virtual-key", "Test Key"));
        when(liteLLMClient.keyInfo(any()))
                .thenReturn(new KeyInfoResponse("sk-test-virtual-key", new KeyInfoData("Test Key", 2.0)));
        when(liteLLMClient.spendLogs(any())).thenReturn(List.of(new SpendLogEntry(300L, 200L, 100L)));

        VirtualKeyService virtualKeyService = new VirtualKeyService(liteLLMClient);
        chatUsageSessionService = new ChatUsageSessionService(chatUsageSessionRepository, virtualKeyService);
    }

    @Test
    void chatSessionLifecycleAndInactivityTimeoutFlow() {
        Team team = new Team();
        team.setId(UUID.randomUUID());
        User user = new User();
        user.setId(UUID.randomUUID());

        ChatEntity chat = new ChatEntity(team, user, "Test Chat Session");
        chat.setId(UUID.randomUUID());

        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(List.of());
        when(chatUsageSessionRepository.save(any(ChatUsageSession.class))).thenAnswer(inv -> {
            ChatUsageSession s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID());
            }
            return s;
        });

        // 1. Initial session creation & virtual-key assignment
        ChatUsageSession activeSession = chatUsageSessionService.getOrCreateActiveSession(chat, List.of("gpt-4o"));
        assertThat(activeSession).isNotNull();
        assertThat(activeSession.getUsage().getVirtualKey()).isEqualTo("sk-test-virtual-key");
        assertThat(activeSession.isEnded()).isFalse();

        // 2. Simulate activity and usage refresh
        when(chatUsageSessionRepository.findByChatId(chat.getId())).thenReturn(List.of(activeSession));

        chatUsageSessionService.refreshUsageForChat(chat.getId());
        assertThat(activeSession.getUsage().getTotalSpend()).isEqualTo(2.0);
        assertThat(activeSession.getUsage().getTotalTokens()).isEqualTo(300L);

        // 3. Simulate inactivity timeout (> 4 hours gap)
        activeSession.getUsage().setUsageLastUpdatedAt(Instant.now().minus(5, ChronoUnit.HOURS));
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId()))
                .thenReturn(List.of(activeSession));

        ChatUsageSession freshSession = chatUsageSessionService.getOrCreateActiveSession(chat, List.of("gpt-4o"));
        assertThat(freshSession).isNotNull();
        assertThat(freshSession.getId()).isNotEqualTo(activeSession.getId());
        assertThat(activeSession.isEnded()).isTrue();
    }
}
