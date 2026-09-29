package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end integration test for basic chat flow using AgentTestHarness and
 * MockStreamingChatModel.
 *
 * <p>These tests validate the full e2e flow: PlanningAgentService -> ChatClient ->
 * MockStreamingChatModel -> response streaming
 *
 * <p>Note: Some tests overlap with PlanningAgentIntegrationTest which covers the full ReAct loop.
 * These tests remain as they validate specific streaming behaviors (chunking, error propagation,
 * concurrent sessions) that are complementary to the ReAct-focused tests.
 */
@SpringIntegrationTest
class AgentBasicChatIntegrationTest extends AgentTestHarness {

    @Autowired
    private PlanningAgentService planningAgentService;

    @Autowired
    private FakeChatModel mockModel;

    @Autowired
    private ModelProviderRepository modelProviderRepo;

    private UUID sessionId;
    private UUID teamId;
    private UUID providerId;

    @BeforeEach
    void setUp() throws Exception {
        cleanDatabase();
        mockModel.reset();

        // Create a user via the auth API (needed for team creation)
        setupTeamAndLogin();

        // Find the user that was just created
        var users = userRepo.findAll();
        assertThat(users).isNotEmpty();
        User user = users.getFirst();

        Team team = createTeamWithProvider(user.getId());
        teamId = team.getId();
        providerId =
                modelProviderRepo.findFirstByTeamIdAndIsActiveTrue(teamId).get().getId();
        sessionId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void shouldStreamMultipleChunks() {
        // Configure multi-chunk response
        mockModel.addMatcher(PromptMatcher.builder()
                .responseChunks(List.of("Hello", ", I am", " the planning", " agent."))
                .build());

        // Execute chat through the full e2e pipeline
        var result =
                planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "Tell me about yourself");

        // Collect all results and verify they are all present
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();
        List<String> contentChunks = results.stream()
                .filter(ClientPayload.MessageChunkResult.class::isInstance)
                .map(r -> ((ClientPayload.MessageChunkResult) r).content())
                .toList();
        assertThat(contentChunks).contains("Hello", ", I am", " the planning", " agent.");
    }

    @Test
    void shouldHandleProviderNotFound() {
        // Configure mock response
        mockModel.addMatcher(
                PromptMatcher.builder().response("This should not be returned").build());

        // Use a non-existent provider ID
        UUID nonExistentProviderId = UUID.randomUUID();

        // Execute chat - should throw LlmProviderNotFoundException synchronously
        // (before Flux is even created, because provider resolution fails)
        assertThatThrownBy(() ->
                        planningAgentService.streamMessage(teamId, nonExistentProviderId, null, sessionId, "Hello"))
                .isInstanceOf(PlanningAgentService.LlmProviderNotFoundException.class);
    }

    @Test
    void shouldCollectFullResponseFromChunks() {
        // Configure multi-chunk response
        mockModel.addMatcher(PromptMatcher.builder()
                .responseChunks(List.of("The", " answer", " is", " 42", "."))
                .build());

        // Execute chat and collect all chunks through the full pipeline
        var result = planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "What is the answer?");

        // Collect and verify full response
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();
        List<String> contentChunks = results.stream()
                .filter(ClientPayload.MessageChunkResult.class::isInstance)
                .map(r -> ((ClientPayload.MessageChunkResult) r).content())
                .toList();
        assertThat(contentChunks).contains("The", " answer", " is", " 42", ".");
    }

    @Test
    void shouldHandleMockError() {
        // Configure mock to throw once, then succeed on retry
        mockModel.addMatcher(PromptMatcher.builder()
                .throwsException(new RuntimeException("Simulated LLM failure"))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(
                PromptMatcher.builder().response("Retry success response").build());

        // Execute chat
        var result = planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "Hello");

        // Verify the agent retried and completed successfully
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // No telemetry events are expected here

        // Verify the LLM was called at least twice (once failed, once succeeded)
        assertThat(mockModel.getInvocations().size()).isGreaterThanOrEqualTo(2);
        // Verify at least one throw occurred (exact count depends on Spring AI internals)
        long throwCount = mockModel.getInvocations().stream()
                .filter(inv -> inv.thrownException() != null)
                .count();
        assertThat(throwCount).isGreaterThanOrEqualTo(1);

        // Verify we got a complete result (not an error)
        boolean hasComplete = results.stream().anyMatch(ClientPayload.CompleteResult.class::isInstance);
        assertThat(hasComplete).isTrue();
    }

    @Test
    void shouldSupportConcurrentSessions() throws Exception {
        // Configure mock response
        mockModel.addMatcher(
                PromptMatcher.builder().response("Session response").build());

        UUID session1 = UUID.randomUUID();
        UUID session2 = UUID.randomUUID();

        CountDownLatch latch = new CountDownLatch(2);
        AtomicBoolean session1Complete = new AtomicBoolean(false);
        AtomicBoolean session2Complete = new AtomicBoolean(false);

        // Start session 1
        planningAgentService
                .streamMessage(teamId, providerId, "gpt-4o", session1, "Hello from session 1")
                .subscribe(result -> {}, error -> latch.countDown(), () -> {
                    session1Complete.set(true);
                    latch.countDown();
                });

        // Start session 2
        planningAgentService
                .streamMessage(teamId, providerId, "gpt-4o", session2, "Hello from session 2")
                .subscribe(result -> {}, error -> latch.countDown(), () -> {
                    session2Complete.set(true);
                    latch.countDown();
                });

        // Wait for both to complete
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(session1Complete.get()).isTrue();
        assertThat(session2Complete.get()).isTrue();
    }
}
