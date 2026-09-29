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
class WikiToolIntegrationTest extends AgentTestHarness {

    @Autowired
    private PlanningAgentService planningAgentService;

    @Autowired
    private FakeChatModel mockModel;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private CtxWikiPageRepository ctxWikiPageRepository;

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

        ModelProvider ingestionProvider =
                new ModelProvider("Test Ingestion Provider", ProviderType.OPENAI, "test-api-key", null);
        ingestionProvider.setTeam(team);
        ingestionProvider.setActive(true);
        modelProviderRepo.save(ingestionProvider);
        team.setIngestionProvider(ingestionProvider);
        team.setIngestionModel("gpt-4o");

        ModelProvider embeddingProvider =
                new ModelProvider("Test Embedding Provider", ProviderType.OPENAI, "test-api-key", null);
        embeddingProvider.setTeam(team);
        embeddingProvider.setActive(true);
        modelProviderRepo.save(embeddingProvider);
        team.setEmbeddingProvider(embeddingProvider);
        team.setEmbeddingModel("text-embedding-3-small");
        teamRepo.save(team);

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

        // Create test wiki pages
        CtxWikiPage rootPage = new CtxWikiPage(
                testBatch,
                teamId,
                testRepo.getName(),
                null,
                "overview",
                "System Overview",
                0,
                "# Overview\n\nThis is the system overview.");
        ctxWikiPageRepository.save(rootPage);

        CtxWikiPage childPage = new CtxWikiPage(
                testBatch,
                teamId,
                testRepo.getName(),
                rootPage,
                "architecture",
                "Architecture Guide",
                1,
                "# Architecture\n\nThe system uses hexagonal architecture.");
        ctxWikiPageRepository.save(childPage);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void shouldListWikiPages() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("list wiki pages")
                .response(
                        "Let me list the wiki pages.",
                        List.of(new ToolCall("call-1", "function", "list_wiki_pages", "{\"repoName\":\"test-repo\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I found the wiki pages.")
                .build());

        var result =
                planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "Please list wiki pages");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        assertToolInvocationReturnedResponse(mockModel, 1, "overview");

        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I found the wiki pages"));
    }

    @Test
    void shouldReadWikiPage() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("overview")
                .response(
                        "Let me read the wiki page.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "read_wiki_page",
                                "{\"repoName\":\"test-repo\",\"pageSlug\":\"overview\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I read the wiki page.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Read the overview wiki page");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        assertToolInvocationReturnedResponse(mockModel, 1, "System Overview");

        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I read the wiki page"));
    }

    @Test
    void shouldHandleNonexistentWikiPage() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("nonexistent")
                .response(
                        "Let me read the wiki page.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "read_wiki_page",
                                "{\"repoName\":\"test-repo\",\"pageSlug\":\"nonexistent\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Read the nonexistent wiki page");

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
        assertToolInvocationReturnedResponse(mockModel, 1, "Wiki page 'nonexistent' not found");

        // Agent should continue and produce final response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I encountered an error"));
    }

    @Test
    void shouldHandleNonexistentRepositoryInListWikiPages() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("list wiki pages")
                .response(
                        "Let me list the wiki pages.",
                        List.of(new ToolCall(
                                "call-1", "function", "list_wiki_pages", "{\"repoName\":\"nonexistent-repo\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "List wiki pages for nonexistent repo");

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
    void shouldHandleNonexistentRepositoryInReadWikiPage() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("read wiki page")
                .response(
                        "Let me read the wiki page.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "read_wiki_page",
                                "{\"repoName\":\"nonexistent-repo\",\"pageSlug\":\"overview\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Read wiki page from nonexistent repo");

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
    void shouldHandleNonexistentRepositoryInSearchWiki() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("search wiki")
                .response(
                        "Let me search the wiki.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "search_wiki",
                                "{\"repoName\":\"nonexistent-repo\",\"query\":\"architecture\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Search wiki in nonexistent repo");

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
    void shouldSearchWiki() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("search wiki")
                .response(
                        "Let me search the wiki.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "search_wiki",
                                "{\"repoName\":\"test-repo\",\"query\":\"architecture\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I searched the wiki.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Search wiki for architecture");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        assertToolInvocationReturnedResponse(mockModel, 1, "results");

        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("I searched the wiki"));
    }

    @Test
    void shouldHandleListWikiPagesWithNoActiveBatch() {
        // Create a repo without an active batch
        Repository repoWithoutBatch = new Repository(
                "repo-without-batch", "https://github.com/test/repo2.git", "main", RepositoryType.GENERIC);
        repoWithoutBatch.setTeam(teamRepo.findById(teamId).orElseThrow());
        repositoryRepository.save(repoWithoutBatch);

        mockModel.addMatcher(PromptMatcher.builder()
                .contains("list wiki pages")
                .response(
                        "Let me list the wiki pages.",
                        List.of(new ToolCall(
                                "call-1", "function", "list_wiki_pages", "{\"repoName\":\"repo-without-batch\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "List wiki pages for repo without batch");

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
    void shouldHandleReadWikiPageWithNoActiveBatch() {
        // Create a repo without an active batch
        Repository repoWithoutBatch = new Repository(
                "repo-without-batch", "https://github.com/test/repo2.git", "main", RepositoryType.GENERIC);
        repoWithoutBatch.setTeam(teamRepo.findById(teamId).orElseThrow());
        repositoryRepository.save(repoWithoutBatch);

        mockModel.addMatcher(PromptMatcher.builder()
                .contains("read wiki page")
                .response(
                        "Let me read the wiki page.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "read_wiki_page",
                                "{\"repoName\":\"repo-without-batch\",\"pageSlug\":\"overview\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Read wiki page from repo without batch");

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
    void shouldHandleSearchWikiWithNoActiveBatch() {
        // Create a repo without an active batch
        Repository repoWithoutBatch = new Repository(
                "repo-without-batch", "https://github.com/test/repo2.git", "main", RepositoryType.GENERIC);
        repoWithoutBatch.setTeam(teamRepo.findById(teamId).orElseThrow());
        repositoryRepository.save(repoWithoutBatch);

        mockModel.addMatcher(PromptMatcher.builder()
                .contains("search wiki")
                .response(
                        "Let me search the wiki.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "search_wiki",
                                "{\"repoName\":\"repo-without-batch\",\"query\":\"architecture\"}")))
                .maxMatches(1)
                .build());

        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I encountered an error.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Search wiki in repo without batch");

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
