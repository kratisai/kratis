package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.wsdto.CanvasEvent;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.CanvasType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.service.CanvasService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration test verifying that the planning agent generates and updates plan canvas documents
 * when the user describes a goal or requests refinements.
 */
@SpringIntegrationTest
class PlanCanvasIntegrationTest extends AgentTestHarness {

    @Autowired
    private PlanningAgentService planningAgentService;

    @Autowired
    private FakeChatModel mockModel;

    @Autowired
    private CanvasService canvasService;

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
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void shouldGeneratePlanCanvasWhenUserDescribesGoal() {
        // Use \\n for literal \n in JSON (JSON strings cannot contain actual newlines)
        String planContent =
                "# Execution Plan\\n\\n## Goal Summary\\nBuild a REST API\\n\\n## Steps\\n1. Set up project\\n2. Implement API\\n3. Add tests";

        // Configure ReAct loop:
        // Turn 1: LLM calls write_canvas to create a plan document
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("I want to build a REST API")
                .response(
                        "I'll create a detailed execution plan for you.",
                        List.of(new ToolCall(
                                "call-plan-1",
                                "function",
                                "write_canvas",
                                "{\"documentId\":\"plan-1\",\"title\":\"Execution Plan\",\"content\":\""
                                        + planContent
                                        + "\",\"canvasType\":\"DOCUMENT\"}")))
                .maxMatches(1)
                .build());

        // Turn 2: LLM provides final response telling user to review the plan after seeing tool response
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response(
                        "I've created the plan. You can review it in the canvas panel, discuss refinements, or click 'Run' to authorize execution.")
                .build());

        List<ClientPayload.ChatStreamPayload> results = planningAgentService
                .streamMessage(teamId, providerId, "gpt-4o", sessionId, "I want to build a REST API with Spring Boot")
                .collectList()
                .block();

        assertThat(results).isNotNull();

        // Verify canvas was persisted with expected content
        CanvasEntity canvas = canvasService.getCanvas(sessionId, "plan-1");
        assertThat(canvas).isNotNull();
        assertThat(canvas.getTitle()).isEqualTo("Execution Plan");
        assertThat(canvas.getContent()).contains("Execution Plan");
        assertThat(canvas.getContent()).contains("Set up project");

        // Verify canvas events were emitted
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.CanvasResult
                        && ((ClientPayload.CanvasResult) r).event() instanceof CanvasEvent.Create);
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.CanvasResult
                        && ((ClientPayload.CanvasResult) r).event() instanceof CanvasEvent.Commit);

        // Verify two LLM calls were made (tool call + final response)
        assertThat(mockModel.getInvocations().size()).isEqualTo(2);
    }

    @Test
    void shouldUpdatePlanCanvasOnRefinementRequest() {
        // Create an initial plan document
        String initialContent = "# Execution Plan\n\n## Steps\n1. Set up project\n2. Implement API";
        canvasService.createCanvas(
                sessionId, "plan-1", "Execution Plan", initialContent, CanvasType.DOCUMENT, null, null);

        // Configure ReAct loop:
        // Turn 1: LLM calls patch_canvas to add a testing section
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Add a section about testing")
                .response(
                        "I'll add a testing section to the plan.",
                        List.of(
                                new ToolCall(
                                        "call-patch-1",
                                        "function",
                                        "patch_canvas",
                                        "{\"innerThought\":\"Adding testing section\",\"documentId\":\"plan-1\",\"patches\":[{\"targetText\":\"2. Implement API\",\"replacementText\":\"2. Implement API\\n3. Add tests\"}]}")))
                .maxMatches(1)
                .build());

        // Turn 2: LLM provides final response
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I've updated the plan with a testing section. You can review it in the canvas panel.")
                .build());

        List<ClientPayload.ChatStreamPayload> results = planningAgentService
                .streamMessage(teamId, providerId, "gpt-4o", sessionId, "Add a section about testing")
                .collectList()
                .block();

        assertThat(results).isNotNull();

        // Verify canvas was updated in the database
        CanvasEntity canvas = canvasService.getCanvas(sessionId, "plan-1");
        assertThat(canvas).isNotNull();
        assertThat(canvas.getContent()).contains("Add tests");

        // Verify canvas update events were emitted
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.CanvasResult
                        && ((ClientPayload.CanvasResult) r).event() instanceof CanvasEvent.Update);
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.CanvasResult
                        && ((ClientPayload.CanvasResult) r).event() instanceof CanvasEvent.Commit);

        // Verify two LLM calls were made
        assertThat(mockModel.getInvocations().size()).isEqualTo(2);
    }
}
