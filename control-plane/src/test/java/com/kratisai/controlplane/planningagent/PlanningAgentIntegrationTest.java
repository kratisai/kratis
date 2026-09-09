package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.MessageRole;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.AgentChatMemoryRepository;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.service.ScratchpadService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end integration test for the PlanningAgentService's full ReAct loop.
 *
 * <p>Validates the complete PlanningAgent flow: 1. LLM call with tool definitions 2. Tool call
 * detection in AssistantMessage 3. Parallel tool execution via CompletableFuture 4. Tool results
 * returned to LLM 5. Final response streamed to client
 */
@SpringIntegrationTest
class PlanningAgentIntegrationTest extends AgentTestHarness {

    @Autowired
    private PlanningAgentService planningAgentService;

    @Autowired
    private FakeChatModel mockModel;

    @Autowired
    private ScratchpadService scratchpadService;

    @Autowired
    private ModelProviderRepository modelProviderRepo;

    @Autowired
    private AgentChatMemoryRepository chatMemoryRepository;

    private UUID sessionId;
    private UUID teamId;
    private UUID providerId;

    @BeforeEach
    void setUp() throws Exception {
        cleanDatabase();

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
    void shouldStreamResponseWithoutToolCalls() {
        // Simple response with no tool calls - agent should exit after first LLM call
        mockModel.addMatcher(PromptMatcher.builder()
                .response("Hello, I am Kratis, your planning agent.")
                .build());

        var result = planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "Hello, who are you?");

        // Collect all chunks and verify the response is present
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r)
                                .content()
                                .contains("Hello, I am Kratis, your planning agent."));

        // Verify the mock was called
        assertThat(mockModel.getInvocations().getLast().prompt()).isNotNull();
        assertThat(mockModel.getInvocations().size()).isEqualTo(1);
        assertThat(mockModel.getInvocations().getLast().modelName()).contains("gpt-4o");
    }

    @Test
    void shouldExecuteSingleToolAndReturnFinalResponse() {
        // Configure multi-turn ReAct loop:
        // Turn 1: LLM decides to call a tool
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Save a fact")
                .response(
                        "Let me save that fact for you.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "save_to_scratchpad",
                                "{\"sessionId\":\"" + sessionId + "\",\"fact\":\"User likes testing\"}")))
                .maxMatches(1)
                .build());
        // Turn 2: LLM provide final response after seeing tool result
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I've saved the fact. Is there anything else?")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Save a fact that I like testing");

        // Collect all streamed chunks
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool execution markers were emitted
        assertThat(results)
                .anyMatch(ClientPayload.TelemetryResult.class::isInstance)
                .anyMatch(r -> r instanceof ClientPayload.TelemetryResult
                        && ((ClientPayload.TelemetryResult) r)
                                .event()
                                .toString()
                                .contains("ToolStart"));

        // Verify the tool was actually executed (side effect)
        String facts = scratchpadService.getFactsAsString(sessionId);
        assertThat(facts).contains("User likes testing");

        // Verify final response was streamed
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I've saved the fact"));

        // Verify two LLM calls were made (tool call + final response)
        assertThat(mockModel.getInvocations().size()).isEqualTo(2);
    }

    @Test
    void shouldExecuteMultipleToolsInParallel() {
        // Configure mock to request 3 tool calls simultaneously
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Save facts A, B, and C")
                .response(
                        "Saving multiple facts...",
                        List.of(
                                new ToolCall(
                                        "call-1",
                                        "function",
                                        "save_to_scratchpad",
                                        "{\"sessionId\":\"" + sessionId + "\",\"fact\":\"Fact A\"}"),
                                new ToolCall(
                                        "call-2",
                                        "function",
                                        "save_to_scratchpad",
                                        "{\"sessionId\":\"" + sessionId + "\",\"fact\":\"Fact B\"}"),
                                new ToolCall(
                                        "call-3",
                                        "function",
                                        "save_to_scratchpad",
                                        "{\"sessionId\":\"" + sessionId + "\",\"fact\":\"Fact C\"}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("All facts have been saved.")
                .build());

        var result =
                planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "Save facts A, B, and C");

        // Collect all streamed chunks - wait for completion
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify all 3 tool start markers appear
        long toolStartCount = results.stream()
                .filter(ClientPayload.TelemetryResult.class::isInstance)
                .filter(r ->
                        ((ClientPayload.TelemetryResult) r).event().toString().contains("ToolStart"))
                .count();
        assertThat(toolStartCount).isEqualTo(3);

        // Verify all facts were actually saved (side effects from parallel execution)
        String facts = scratchpadService.getFactsAsString(sessionId);
        assertThat(facts).contains("Fact A", "Fact B", "Fact C");

        // Verify final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("All facts have been saved"));
    }

    @Test
    void shouldHandleNonExistentToolError() {
        // Configure mock to call a non-existent tool
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Test error handling")
                .response(
                        "Let me try something...",
                        List.of(new ToolCall("call-1", "function", "non_existent_tool", "{\"param\":\"value\"}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("Done with error handling.")
                .build());

        var result = planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "Test error handling");

        // Collect all streamed chunks
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify error marker is emitted
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.TelemetryResult
                        && ((ClientPayload.TelemetryResult) r)
                                .event()
                                .toString()
                                .contains("ToolError"));

        // Agent should continue and produce final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("Done with error handling"));
    }

    @Test
    void shouldIncludeScratchpadFactsInSystemPrompt() {
        // Pre-populate scratchpad with facts
        scratchpadService.addFact(sessionId, "User prefers Java over Python");
        scratchpadService.addFact(sessionId, "User likes Spring Boot");

        mockModel.addMatcher(PromptMatcher.builder()
                .response("You prefer Java and Spring Boot.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "What language do I prefer?");

        // Collect chunks and verify response
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r)
                                .content()
                                .contains("You prefer Java and Spring Boot."));

        // Verify system prompt contains the scratchpad facts
        var prompt = mockModel.getInvocations().getLast().prompt();
        assertThat(prompt).isNotNull();
        assertThat(prompt.getInstructions()).anyMatch(m -> {
            String text = m.getText();
            return text != null && text.contains("User prefers Java over Python");
        });
        assertThat(prompt.getInstructions()).anyMatch(m -> {
            String text = m.getText();
            return text != null && text.contains("User likes Spring Boot");
        });
    }

    @Test
    void shouldMaintainConversationHistoryAcrossTurns() {
        // First message
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Hello there")
                .response("First response")
                .build());
        var result1 = planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "Hello there");
        result1.collectList().block();

        // Second message - should include history from first
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("What did I just say?")
                .response("Second response")
                .build());
        var result2 =
                planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "What did I just say?");
        result2.collectList().block();

        // Verify the second prompt contains the first message
        var prompt = mockModel.getInvocations().getLast().prompt();
        assertThat(prompt).isNotNull();
        assertThat(prompt.getInstructions()).anyMatch(m -> {
            String text = m.getText();
            return text != null && text.contains("Hello there");
        });
    }

    @Test
    void shouldEmitChatErrorAndPersistWhenLoopExhausts() {
        mockModel.addMatcher(PromptMatcher.builder()
                .response(
                        "Still working...",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "save_to_scratchpad",
                                "{\"sessionId\":\"" + sessionId + "\",\"fact\":\"looping\"}")))
                .build());

        var result = planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "Keep looping");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        ClientPayload.ChatErrorResult error = results.stream()
                .filter(ClientPayload.ChatErrorResult.class::isInstance)
                .map(ClientPayload.ChatErrorResult.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(error.code()).isEqualTo(JsonRpcErrorCodes.MAX_ITERATIONS_EXCEEDED);
        assertThat(error.message()).contains("exceeded maximum iterations");

        var persisted = chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(sessionId);
        assertThat(persisted)
                .anyMatch(e -> e.getMessageType() == MessageRole.ASSISTANT
                        && error.message().equals(e.getMessageText()));
    }

    @Test
    void shouldEmitAuthErrorAndNotRetryOnFatalProviderError() {
        mockModel.addMatcher(PromptMatcher.builder()
                .throwsException(new NonTransientAiException("401 - invalid api key"))
                .build());

        var result = planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "Hello");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        ClientPayload.ChatErrorResult error = results.stream()
                .filter(ClientPayload.ChatErrorResult.class::isInstance)
                .map(ClientPayload.ChatErrorResult.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(error.code()).isEqualTo(JsonRpcErrorCodes.LLM_AUTHENTICATION_FAILED);
        assertThat(error.message()).contains("authentication failed");
        assertThat(mockModel.getInvocations().size()).isEqualTo(1);

        var persisted = chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(sessionId);
        assertThat(persisted)
                .anyMatch(e -> e.getMessageType() == MessageRole.ASSISTANT
                        && error.message().equals(e.getMessageText()));
    }

    @Test
    void shouldExecuteWebSearchToolWithMissingApiKey() {
        // Configure mock to call web search (will fail gracefully due to missing API key)
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Search for Spring Boot testing")
                .response(
                        "Let me search for that.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "web_search",
                                "{\"teamId\":\"" + teamId + "\",\"query\":\"Spring Boot testing\"}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("Search completed.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Search for Spring Boot testing");

        // Collect all streamed chunks
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool execution markers
        assertThat(results)
                .anyMatch(ClientPayload.TelemetryResult.class::isInstance)
                .anyMatch(r -> r instanceof ClientPayload.TelemetryResult
                        && ((ClientPayload.TelemetryResult) r)
                                .event()
                                .toString()
                                .contains("ToolStart"));

        // Verify final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("Search completed"));
    }
}
