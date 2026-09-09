package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SlowTest;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.service.ScratchpadService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * These tests validate tool @Tool methods directly (unit-style) and verify they
 * can be wrapped into ToolCallback instances via MethodToolCallbackProvider.
 * For full ReAct loop integration tests (LLM -> tool calls -> tool execution
 * -> final response), see PlanningAgentIntegrationTest.
 */
@SpringIntegrationTest
@SlowTest
class SimpleToolTests extends AgentTestHarness {

    @Autowired
    private WebSearchTool webSearchTool;

    @Autowired
    private ScratchpadTool scratchpadTool;

    @Autowired
    private ScratchpadService scratchpadService;

    @Autowired
    private FakeChatModel mockModel;

    private UUID sessionId;
    private UUID teamId;

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
        sessionId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void webSearchToolShouldBeRegisteredAsComponent() {
        // Verify the web search tool is available as a Spring bean
        assertThat(webSearchTool).isNotNull();
    }

    @Test
    void webSearchToolShouldReturnResponseForQuery() {
        // Create a ToolContext with teamId
        ToolContext toolContext = new PlanningContext(teamId, sessionId).toToolContext();

        // Execute search via @Tool method
        WebSearchTool.WebSearchResponse response = webSearchTool.webSearch("Spring Boot testing", toolContext);

        // Verify response contains the query
        assertThat(response).isNotNull();
        assertThat(response.markdownSummary()).contains("Spring Boot testing");
    }

    @Test
    void saveToScratchpadToolShouldSaveFact() {
        // Create a ToolContext with sessionId
        ToolContext toolContext = new PlanningContext(teamId, sessionId).toToolContext();

        // Execute save via @Tool method
        ScratchpadTool.SaveToScratchpadResponse response =
                scratchpadTool.saveToScratchpad("Test fact for integration", toolContext);

        // Verify response
        assertThat(response).isNotNull();
        assertThat(response.status()).contains("saved");

        // Verify fact was actually saved
        String facts = scratchpadService.getFactsAsString(sessionId);
        assertThat(facts).contains("Test fact for integration");
    }

    @Test
    void deleteFromScratchpadToolShouldDeleteFact() {
        // First save a fact
        scratchpadService.addFact(sessionId, "Fact to delete");

        // Create a ToolContext with sessionId
        ToolContext toolContext = new PlanningContext(teamId, sessionId).toToolContext();

        // Execute delete via @Tool method
        ScratchpadTool.DeleteFromScratchpadResponse response =
                scratchpadTool.deleteFromScratchpad("Fact to delete", toolContext);

        // Verify response
        assertThat(response).isNotNull();
        assertThat(response.status()).contains("deleted");

        // Verify fact was actually deleted
        String facts = scratchpadService.getFactsAsString(sessionId);
        assertThat(facts).doesNotContain("Fact to delete");
    }

    @Test
    void scratchpadShouldPersistFactsAcrossToolCalls() {
        // Create a ToolContext with sessionId
        ToolContext toolContext = new PlanningContext(teamId, sessionId).toToolContext();

        // Save multiple facts via @Tool method
        scratchpadTool.saveToScratchpad("Fact 1", toolContext);
        scratchpadTool.saveToScratchpad("Fact 2", toolContext);
        scratchpadTool.saveToScratchpad("Fact 3", toolContext);

        // Verify all facts are stored
        String facts = scratchpadService.getFactsAsString(sessionId);
        assertThat(facts).contains("Fact 1");
        assertThat(facts).contains("Fact 2");
        assertThat(facts).contains("Fact 3");
    }

    @Test
    void mockModelShouldReturnConfiguredResponse() {
        // Configure mock response
        mockModel.addMatcher(PromptMatcher.builder()
                .response("This is a test response from the mock LLM.")
                .build());

        // Verify the mock model can be called directly
        Prompt prompt = new Prompt("Hello");
        var response = mockModel.call(prompt);

        assertThat(response).isNotNull();
        assertThat(response.getResult()).isNotNull();
        assertThat(response.getResult().getOutput().getText()).contains("This is a test response");
    }

    @Test
    void mockModelShouldStreamConfiguredResponse() {
        // Configure mock response
        mockModel.addMatcher(
                PromptMatcher.builder().response("Streaming test response").build());

        // Verify the mock model can stream
        Prompt prompt = new Prompt("Hello");
        var flux = mockModel.stream(prompt);

        var result = flux.collectList().block();
        assertThat(result).hasSize(1);
    }
}
