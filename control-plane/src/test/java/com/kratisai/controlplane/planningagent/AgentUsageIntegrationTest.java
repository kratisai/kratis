package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.*;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringIntegrationTest
class AgentUsageIntegrationTest extends AgentTestHarness {

    @Autowired
    private PlanningAgentService planningAgentService;

    @Autowired
    private FakeChatModel mockModel;

    @Autowired
    private ChatUsageSessionRepository chatUsageSessionRepository;

    @Autowired
    private ModelProviderRepository modelProviderRepo;

    @Autowired
    private TestDataFactory testDataFactory;

    @MockitoBean
    private LiteLLMClient liteLLMClient;

    private UUID teamId;
    private UUID providerId;
    private ChatEntity chat;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        setupTeamAndLogin();
        var users = userRepo.findAll();
        assertThat(users).isNotEmpty();
        var user = users.getFirst();
        var team = createTeamWithProvider(user.getId());
        teamId = team.getId();
        var provider =
                modelProviderRepo.findFirstByTeamIdAndIsActiveTrue(teamId).orElseThrow();
        providerId = provider.getId();
        chat = testDataFactory.createChat(team, user, "Usage Test Chat");

        reset(liteLLMClient);
        when(liteLLMClient.generateKey(any()))
                .thenReturn(new GenerateKeyResponse("sk-test-virtual-key", "kratis-chat-" + chat.getId()));
        when(liteLLMClient.keyInfo(any()))
                .thenReturn(new KeyInfoResponse(
                        "sk-test-virtual-key", new KeyInfoData("kratis-chat-" + chat.getId(), 1.5)));
        when(liteLLMClient.spendLogs(any())).thenReturn(List.of(new SpendLogEntry(100L, 80L, 20L)));

        mockModel.addMatcher(
                PromptMatcher.builder().response("Hello from planning agent").build());
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
        reset(liteLLMClient);
    }

    @Test
    void basicChatUsageTrackingAndPersistence() {
        List<ClientPayload.ChatStreamPayload> results = planningAgentService
                .streamMessage(teamId, providerId, "gpt-4o", chat.getId(), "Hello, track usage")
                .collectList()
                .block();

        assertThat(results).isNotNull();
        assertThat(results).anyMatch(r -> r instanceof ClientPayload.MessageChunkResult);

        ArgumentCaptor<GenerateKeyRequest> keyCaptor = ArgumentCaptor.forClass(GenerateKeyRequest.class);
        verify(liteLLMClient, atLeastOnce()).generateKey(keyCaptor.capture());
        GenerateKeyRequest captured = keyCaptor.getValue();
        assertThat(captured.keyAlias()).isEqualTo("kratis-chat-" + chat.getId());
        assertThat(captured.models()).isNotNull();
        assertThat(captured.models()).isNotEmpty();
        assertThat(captured.models().getFirst())
                .isEqualTo("openai-test-provider-gpt-4o-" + teamId.toString().substring(0, 8));

        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    List<ChatUsageSession> sessions = chatUsageSessionRepository.findByChatId(chat.getId());
                    assertThat(sessions).isNotEmpty();
                    ChatUsageSession session = sessions.getFirst();
                    assertThat(session.getUsage().getVirtualKey()).isEqualTo("sk-test-virtual-key");
                    assertThat(session.isEnded()).isFalse();
                    assertThat(session.getUsage().getTotalTokens()).isEqualTo(100L);
                    assertThat(session.getUsage().getTotalSpend()).isEqualTo(1.5);
                });
    }

    @Test
    void fourHourInactivityRollover_createsNewSession() {
        ChatUsageSession existing = new ChatUsageSession(chat, "sk-old-virtual-key");
        existing.setStartedAt(Instant.now().minus(5, ChronoUnit.HOURS));
        existing.getUsage().setUsageLastUpdatedAt(Instant.now().minus(5, ChronoUnit.HOURS));
        chatUsageSessionRepository.save(existing);

        when(liteLLMClient.generateKey(any()))
                .thenReturn(new GenerateKeyResponse("sk-new-virtual-key", "kratis-chat-" + chat.getId()));
        when(liteLLMClient.keyInfo("sk-old-virtual-key"))
                .thenReturn(
                        new KeyInfoResponse("sk-old-virtual-key", new KeyInfoData("kratis-chat-" + chat.getId(), 0.5)));
        when(liteLLMClient.spendLogs("sk-old-virtual-key")).thenReturn(List.of(new SpendLogEntry(50L, 30L, 20L)));
        when(liteLLMClient.keyInfo("sk-new-virtual-key"))
                .thenReturn(
                        new KeyInfoResponse("sk-new-virtual-key", new KeyInfoData("kratis-chat-" + chat.getId(), 0.0)));
        when(liteLLMClient.spendLogs("sk-new-virtual-key")).thenReturn(List.of(new SpendLogEntry(10L, 6L, 4L)));

        List<ClientPayload.ChatStreamPayload> results = planningAgentService
                .streamMessage(teamId, providerId, "gpt-4o", chat.getId(), "After inactivity")
                .collectList()
                .block();

        assertThat(results).isNotNull();

        List<ChatUsageSession> sessions = chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId());
        assertThat(sessions).hasSize(2);
        ChatUsageSession latest = sessions.get(0);
        ChatUsageSession old = sessions.get(1);
        assertThat(latest.getId()).isNotEqualTo(old.getId());
        assertThat(old.isEnded()).isTrue();
        assertThat(latest.getUsage().getVirtualKey()).isEqualTo("sk-new-virtual-key");

        verify(liteLLMClient, atLeastOnce()).generateKey(any());
    }

    @Test
    void liteLLMKeyExpiryRegeneration_continuesStreamWithoutCrashing() {
        ChatUsageSession existing = new ChatUsageSession(chat, "sk-expired-key");
        existing.getUsage().setUsageLastUpdatedAt(Instant.now());
        chatUsageSessionRepository.save(existing);

        when(liteLLMClient.keyInfo("sk-expired-key")).thenThrow(new RuntimeException("404 Not Found - key not found"));
        when(liteLLMClient.spendLogs("sk-expired-key")).thenThrow(new RuntimeException("404 Not Found"));
        when(liteLLMClient.generateKey(any()))
                .thenReturn(new GenerateKeyResponse("sk-regenerated-key", "kratis-chat-" + chat.getId()));
        when(liteLLMClient.keyInfo("sk-regenerated-key"))
                .thenReturn(new KeyInfoResponse(
                        "sk-regenerated-key", new KeyInfoData("kratis-chat-" + chat.getId(), 0.75)));
        when(liteLLMClient.spendLogs("sk-regenerated-key")).thenReturn(List.of(new SpendLogEntry(42L, 22L, 20L)));

        List<ClientPayload.ChatStreamPayload> results = planningAgentService
                .streamMessage(teamId, providerId, "gpt-4o", chat.getId(), "Test key expiry")
                .collectList()
                .block();

        assertThat(results).isNotNull();
        assertThat(results).anyMatch(r -> r instanceof ClientPayload.MessageChunkResult);

        List<ChatUsageSession> sessions = chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId());
        assertThat(sessions).hasSizeGreaterThanOrEqualTo(2);
        ChatUsageSession latest = sessions.getFirst();
        assertThat(latest.getUsage().getVirtualKey()).isEqualTo("sk-regenerated-key");
    }
}
