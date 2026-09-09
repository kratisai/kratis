package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class DimensionToolIntegrationTest extends AgentTestHarness {

    @Autowired
    private PlanningAgentService planningAgentService;

    @Autowired
    private FakeChatModel mockModel;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private CtxDimensionRepository ctxDimensionRepository;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxNodeDimensionRepository ctxNodeDimensionRepository;

    @Autowired
    private CtxArchitecturePatternRepository ctxArchitecturePatternRepository;

    @Autowired
    private ModelProviderRepository modelProviderRepo;

    private UUID sessionId;
    private UUID teamId;
    private UUID providerId;

    @BeforeEach
    void setUp() throws Exception {
        cleanDatabase();
        mockModel.reset();

        setupTeamAndLogin();

        var users = userRepo.findAll();
        assertThat(users).isNotEmpty();
        User user = users.getFirst();

        Team team = createTeamWithProvider(user.getId());
        teamId = team.getId();
        providerId =
                modelProviderRepo.findFirstByTeamIdAndIsActiveTrue(teamId).get().getId();
        sessionId = UUID.randomUUID();

        // Create test repository and batch
        Repository testRepo =
                new Repository("test-repo", "https://github.com/test/repo.git", "main", RepositoryType.GENERIC);
        testRepo.setTeam(team);
        testRepo = repositoryRepository.save(testRepo);

        IngestionBatch testBatch = new IngestionBatch(testRepo);
        testBatch.setCommitHash("abc123def");
        testBatch.setStatus(IngestionStatus.SUCCESS);
        testBatch.setActive(true);
        testBatch = ingestionBatchRepository.save(testBatch);

        // Create test dimensions
        CtxDimension domain = new CtxDimension(
                testBatch,
                teamId,
                DimensionCategory.DOMAIN,
                "Booking Management",
                "Handles flight bookings",
                List.of("**/booking/**"));
        ctxDimensionRepository.save(domain);

        CtxDimension archetype = new CtxDimension(
                testBatch,
                teamId,
                DimensionCategory.ARCHETYPE,
                "Controllers",
                "REST API layer",
                List.of("**/controller/**"));
        ctxDimensionRepository.save(archetype);

        // Create test nodes
        CtxNode node1 =
                new CtxNode(testBatch, teamId, testRepo.getName(), NodeType.FILE, "src/booking/BookingService.java");
        node1 = ctxNodeRepository.save(node1);

        CtxNode node2 = new CtxNode(
                testBatch, teamId, testRepo.getName(), NodeType.FILE, "src/controller/BookingController.java");
        node2 = ctxNodeRepository.save(node2);

        // Create node-dimension mappings
        CtxNodeDimension nd1 = new CtxNodeDimension(node1, domain, 0.95);
        ctxNodeDimensionRepository.save(nd1);

        CtxNodeDimension nd2 = new CtxNodeDimension(node2, archetype, 0.88);
        ctxNodeDimensionRepository.save(nd2);

        // Create test architecture pattern
        CtxArchitecturePattern pattern = new CtxArchitecturePattern(
                testBatch, teamId, "Hexagonal Architecture", "Ports and adapters pattern", List.of(node1));
        ctxArchitecturePatternRepository.save(pattern);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void shouldListDimensions() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("list dimensions")
                .response(
                        "Let me list the dimensions.",
                        List.of(new ToolCall("call-1", "function", "list_dimensions", "{\"repoName\":\"test-repo\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I found the dimensions.")
                .build());

        var result =
                planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "Please list dimensions");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        assertToolInvocationReturnedResponse(mockModel, 1, "Booking Management");

        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I found the dimensions"));
    }

    @Test
    void shouldGetDimensionDetails() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Booking Management")
                .response(
                        "Let me get the dimension details.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "get_dimension",
                                "{\"repoName\":\"test-repo\",\"dimensionName\":\"Booking Management\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I retrieved the dimension details.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Get details for Booking Management dimension");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        assertToolInvocationReturnedResponse(mockModel, 1, "Booking Management");

        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r)
                                .content()
                                .contains("I retrieved the dimension details"));
    }

    @Test
    void shouldListArchitecturePatterns() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("list architecture patterns")
                .response(
                        "Let me list the architecture patterns.",
                        List.of(new ToolCall(
                                "call-1", "function", "list_architecture_patterns", "{\"repoName\":\"test-repo\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I found the architecture patterns.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "List architecture patterns");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        assertToolInvocationReturnedResponse(mockModel, 1, "Hexagonal Architecture");

        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r)
                                .content()
                                .contains("I found the architecture patterns"));
    }

    @Test
    void shouldHandleNonexistentDimension() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("nonexistent")
                .response(
                        "Let me get the dimension details.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "get_dimension",
                                "{\"repoName\":\"test-repo\",\"dimensionName\":\"Nonexistent Dimension\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Get details for nonexistent dimension");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool error was emitted
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.TelemetryResult
                        && ((ClientPayload.TelemetryResult) r)
                                .event()
                                .toString()
                                .contains("ToolError"));

        // Verify the error message was included in the response sent back to the LLM
        assertToolInvocationReturnedResponse(mockModel, 1, "Dimension 'Nonexistent Dimension' not found");

        // Agent should continue and produce final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I encountered an error"));
    }

    @Test
    void shouldHandleNonexistentRepositoryInListDimensions() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("list dimensions")
                .response(
                        "Let me list the dimensions.",
                        List.of(new ToolCall(
                                "call-1", "function", "list_dimensions", "{\"repoName\":\"nonexistent-repo\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "List dimensions for nonexistent repo");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool error was emitted
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.TelemetryResult
                        && ((ClientPayload.TelemetryResult) r)
                                .event()
                                .toString()
                                .contains("ToolError"));

        // Verify the error message was included in the response sent back to the LLM
        assertToolInvocationReturnedResponse(mockModel, 1, "Repository 'nonexistent-repo' not found");

        // Agent should continue and produce final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I encountered an error"));
    }

    @Test
    void shouldHandleNonexistentRepositoryInListPatterns() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("list architecture patterns")
                .response(
                        "Let me list the architecture patterns.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "list_architecture_patterns",
                                "{\"repoName\":\"nonexistent-repo\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "List architecture patterns for nonexistent repo");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool error was emitted
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.TelemetryResult
                        && ((ClientPayload.TelemetryResult) r)
                                .event()
                                .toString()
                                .contains("ToolError"));

        // Verify the error message was included in the response sent back to the LLM
        assertToolInvocationReturnedResponse(mockModel, 1, "Repository 'nonexistent-repo' not found");

        // Agent should continue and produce final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I encountered an error"));
    }

    @Test
    void shouldHandleGetDimensionWithNonexistentRepository() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("get dimension")
                .response(
                        "Let me get the dimension details.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "get_dimension",
                                "{\"repoName\":\"nonexistent-repo\",\"dimensionName\":\"Booking Management\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Get dimension from nonexistent repo");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool error was emitted
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.TelemetryResult
                        && ((ClientPayload.TelemetryResult) r)
                                .event()
                                .toString()
                                .contains("ToolError"));

        // Verify the error message was included in the response sent back to the LLM
        assertToolInvocationReturnedResponse(mockModel, 1, "Repository 'nonexistent-repo' not found");

        // Agent should continue and produce final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I encountered an error"));
    }

    @Test
    void shouldHandleListDimensionsWithNoActiveBatch() {
        // Create a repo without an active batch
        Repository repoWithoutBatch = new Repository(
                "repo-without-batch", "https://github.com/test/repo2.git", "main", RepositoryType.GENERIC);
        repoWithoutBatch.setTeam(teamRepo.findById(teamId).orElseThrow());
        repositoryRepository.save(repoWithoutBatch);

        mockModel.addMatcher(PromptMatcher.builder()
                .contains("list dimensions")
                .response(
                        "Let me list the dimensions.",
                        List.of(new ToolCall(
                                "call-1", "function", "list_dimensions", "{\"repoName\":\"repo-without-batch\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "List dimensions for repo without batch");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool error was emitted
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.TelemetryResult
                        && ((ClientPayload.TelemetryResult) r)
                                .event()
                                .toString()
                                .contains("ToolError"));

        // Verify the error message was included in the response sent back to the LLM
        assertToolInvocationReturnedResponse(
                mockModel, 1, "No active ingestion batch for repository 'repo-without-batch'");

        // Agent should continue and produce final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I encountered an error"));
    }

    @Test
    void shouldHandleGetDimensionWithNoActiveBatch() {
        // Create a repo without an active batch
        Repository repoWithoutBatch = new Repository(
                "repo-without-batch", "https://github.com/test/repo2.git", "main", RepositoryType.GENERIC);
        repoWithoutBatch.setTeam(teamRepo.findById(teamId).orElseThrow());
        repositoryRepository.save(repoWithoutBatch);

        mockModel.addMatcher(PromptMatcher.builder()
                .contains("get dimension")
                .response(
                        "Let me get the dimension details.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "get_dimension",
                                "{\"repoName\":\"repo-without-batch\",\"dimensionName\":\"Booking Management\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Get dimension from repo without batch");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool error was emitted
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.TelemetryResult
                        && ((ClientPayload.TelemetryResult) r)
                                .event()
                                .toString()
                                .contains("ToolError"));

        // Verify the error message was included in the response sent back to the LLM
        assertToolInvocationReturnedResponse(
                mockModel, 1, "No active ingestion batch for repository 'repo-without-batch'");

        // Agent should continue and produce final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I encountered an error"));
    }

    @Test
    void shouldHandleListArchitecturePatternsWithNoActiveBatch() {
        // Create a repo without an active batch
        Repository repoWithoutBatch = new Repository(
                "repo-without-batch", "https://github.com/test/repo2.git", "main", RepositoryType.GENERIC);
        repoWithoutBatch.setTeam(teamRepo.findById(teamId).orElseThrow());
        repositoryRepository.save(repoWithoutBatch);

        mockModel.addMatcher(PromptMatcher.builder()
                .contains("list architecture patterns")
                .response(
                        "Let me list the architecture patterns.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "list_architecture_patterns",
                                "{\"repoName\":\"repo-without-batch\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "List architecture patterns for repo without batch");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool error was emitted
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.TelemetryResult
                        && ((ClientPayload.TelemetryResult) r)
                                .event()
                                .toString()
                                .contains("ToolError"));

        // Verify the error message was included in the response sent back to the LLM
        assertToolInvocationReturnedResponse(
                mockModel, 1, "No active ingestion batch for repository 'repo-without-batch'");

        // Agent should continue and produce final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I encountered an error"));
    }
}
