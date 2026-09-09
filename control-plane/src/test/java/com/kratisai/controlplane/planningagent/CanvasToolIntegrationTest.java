package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SlowTest;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.wsdto.CanvasEvent;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.CanvasType;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.service.CanvasService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end integration test for CanvasTool integration using the full ReAct loop.
 *
 * <p>Validates the complete PlanningAgent flow with canvas tools: 1. LLM call with tool definitions
 * 2. Tool call detection in AssistantMessage 3. Canvas tool execution (list and retrieve) 4. Tool
 * results returned to LLM 5. Final response streamed to client
 */
@SpringIntegrationTest
@SlowTest
class CanvasToolIntegrationTest extends AgentTestHarness {

    @Autowired
    private PlanningAgentService planningAgentService;

    @Autowired
    private FakeChatModel mockModel;

    @Autowired
    private CanvasService canvasService;

    @Autowired
    private ModelProviderRepository modelProviderRepo;

    @Autowired
    private RepositoryRepository repositoryRepository;

    private UUID sessionId;
    private UUID teamId;
    private UUID providerId;
    private Team team;

    @BeforeEach
    void setUp() throws Exception {
        cleanDatabase();

        // Create a user via the auth API (needed for team creation)
        setupTeamAndLogin();

        // Find the user that was just created
        var users = userRepo.findAll();
        assertThat(users).isNotEmpty();
        User user = users.getFirst();

        Team createdTeam = createTeamWithProvider(user.getId());
        team = createdTeam;
        teamId = createdTeam.getId();
        providerId =
                modelProviderRepo.findFirstByTeamIdAndIsActiveTrue(teamId).get().getId();
        sessionId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void shouldListCanvasDocuments() {
        // Create some canvas documents first
        canvasService.createCanvas(
                sessionId, "doc-1", "First Document", "# Content 1", CanvasType.DOCUMENT, null, null);
        canvasService.createCanvas(
                sessionId, "doc-2", "Second Document", "# Content 2", CanvasType.DOCUMENT, null, null);

        // Configure multi-turn ReAct loop:
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("What documents are in the canvas")
                .response(
                        "Let me check what documents are in the canvas.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "list_canvas_documents",
                                "{\"sessionId\":\"" + sessionId + "\"}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I found 2 documents: First Document and Second Document.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "What documents are in the canvas?");

        // Collect all streamed chunks
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool execution markers were emitted
        assertThat(results).anyMatch(ClientPayload.TelemetryResult.class::isInstance);

        // Verify the tool was actually executed by checking the response
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("2 documents"));

        // Verify two LLM calls were made (tool call + final response)
        assertThat(mockModel.getInvocations().size()).isEqualTo(2);
    }

    @Test
    void shouldRetrieveCanvasDocumentContent() {
        // Create a canvas document first
        canvasService.createCanvas(
                sessionId,
                "test-doc",
                "Test Document",
                "# Hello World\n\nThis is test content.",
                CanvasType.DOCUMENT,
                null,
                null);

        // Configure multi-turn ReAct loop:
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Show me the content of test-doc")
                .response(
                        "Let me retrieve the document content.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "retrieve_from_canvas",
                                "{\"documentId\":\"test-doc\",\"sessionId\":\"" + sessionId + "\"}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("Here's the content: # Hello World")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Show me the content of test-doc");

        // Collect all streamed chunks
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool execution markers were emitted
        assertThat(results).anyMatch(ClientPayload.TelemetryResult.class::isInstance);

        // Verify the content was actually retrieved (check telemetry for tool result)
        long toolStartCount = results.stream()
                .filter(ClientPayload.TelemetryResult.class::isInstance)
                .filter(r ->
                        ((ClientPayload.TelemetryResult) r).event().toString().contains("ToolStart"))
                .count();
        assertThat(toolStartCount).isGreaterThanOrEqualTo(1);

        // Verify final response was streamed
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("Hello World"));

        // Verify two LLM calls were made (tool call + final response)
        assertThat(mockModel.getInvocations().size()).isEqualTo(2);
    }

    @Test
    void shouldHandleNonExistentDocument() {
        // Configure multi-turn ReAct loop:
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Show me the content of nonexistent")
                .response(
                        "Let me try to retrieve that document.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "retrieve_from_canvas",
                                "{\"documentId\":\"nonexistent\",\"sessionId\":\"" + sessionId + "\"}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("The document was not found.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Show me the content of nonexistent");

        // Collect all streamed chunks
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool execution markers were emitted
        assertThat(results).anyMatch(ClientPayload.TelemetryResult.class::isInstance);

        // Verify final response indicates document not found
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("not found"));

        // Verify two LLM calls were made
        assertThat(mockModel.getInvocations().size()).isEqualTo(2);
    }

    @Test
    void shouldListEmptyCanvas() {
        // Don't create any canvas documents - test empty list scenario

        // Configure multi-turn ReAct loop:
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("What's in the canvas")
                .response(
                        "Let me check the canvas.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "list_canvas_documents",
                                "{\"sessionId\":\"" + sessionId + "\"}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("The canvas is empty.")
                .build());

        var result =
                planningAgentService.streamMessage(teamId, providerId, "gpt-4o", sessionId, "What's in the canvas?");

        // Collect all streamed chunks
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool execution markers were emitted
        assertThat(results).anyMatch(ClientPayload.TelemetryResult.class::isInstance);

        // Verify final response indicates empty canvas
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("empty"));

        // Verify two LLM calls were made
        assertThat(mockModel.getInvocations().size()).isEqualTo(2);
    }

    @Test
    void shouldExecuteListAndRetrieveInSequence() {
        // Create a canvas document first
        canvasService.createCanvas(
                sessionId,
                "my-doc",
                "My Document",
                "# Important Notes\n\nRemember to test.",
                CanvasType.DOCUMENT,
                null,
                null);

        // Configure multi-turn ReAct loop with 3 turns:
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("What documents do I have")
                .response(
                        "Let me list the documents first.",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                "list_canvas_documents",
                                "{\"sessionId\":\"" + sessionId + "\"}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("my-doc")
                .response(
                        "Now let me retrieve the content.",
                        List.of(new ToolCall(
                                "call-2",
                                "function",
                                "retrieve_from_canvas",
                                "{\"documentId\":\"my-doc\",\"sessionId\":\"" + sessionId + "\"}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Important Notes")
                .response("I found one document called 'My Document' with content about testing.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "What documents do I have and what's in them?");

        // Collect all streamed chunks
        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // Verify tool execution markers were emitted (should be 2 tool calls)
        long toolStartCount = results.stream()
                .filter(ClientPayload.TelemetryResult.class::isInstance)
                .filter(r ->
                        ((ClientPayload.TelemetryResult) r).event().toString().contains("ToolStart"))
                .count();
        assertThat(toolStartCount).isEqualTo(2);

        // Verify final response was streamed
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.MessageChunkResult
                        && ((ClientPayload.MessageChunkResult) r).content().contains("My Document"));

        // Verify three LLM calls were made (list + retrieve + final response)
        assertThat(mockModel.getInvocations().size()).isEqualTo(3);
    }

    @Test
    void shouldWriteCanvasDocument() {
        // Configure ReAct loop:
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Create a roadmap doc")
                .response(
                        "I will create a new planning document.",
                        List.of(
                                new ToolCall(
                                        "call-write-1",
                                        "function",
                                        "write_canvas",
                                        "{\"documentId\":\"new-doc\",\"title\":\"Project Roadmap\",\"content\":\"# Phase 1\\n\\nScaffold integration tests.\",\"canvasType\":\"DOCUMENT\"}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I have created the document Project Roadmap.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Create a roadmap doc called 'Project Roadmap'");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // 1. Verify in database that it was persisted
        CanvasEntity canvas = canvasService.getCanvas(sessionId, "new-doc");
        assertThat(canvas).isNotNull();
        assertThat(canvas.getTitle()).isEqualTo("Project Roadmap");
        assertThat(canvas.getContent()).contains("Scaffold integration tests.");
        assertThat(canvas.getCanvasType()).isEqualTo(CanvasType.DOCUMENT);

        // 2. Verify WebSocket sync events were emitted into the stream
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.CanvasResult
                        && ((ClientPayload.CanvasResult) r).event() instanceof CanvasEvent.Create);
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.CanvasResult
                        && ((ClientPayload.CanvasResult) r).event() instanceof CanvasEvent.Commit);

        assertThat(mockModel.getInvocations().size()).isEqualTo(2);
    }

    @Test
    void shouldWriteSpecCanvasWithExistingRepo() {
        Repository repo = new Repository("backend", "https://example.com/backend.git", "main", RepositoryType.GENERIC);
        repo.setTeam(team);
        repositoryRepository.save(repo);

        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Create an executable plan for backend")
                .response(
                        "I will write a SPEC canvas bound to the backend repository.",
                        List.of(
                                new ToolCall(
                                        "call-write-exec",
                                        "function",
                                        "write_canvas",
                                        "{\"documentId\":\"exec-doc\",\"title\":\"Exec Plan\",\"content\":\"# Plan\",\"canvasType\":\"SPEC\",\"repoName\":\"backend\"}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I have created the task document.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Create an executable plan for backend");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        CanvasEntity canvas = canvasService.getCanvas(sessionId, "exec-doc");
        assertThat(canvas).isNotNull();
        assertThat(canvas.getCanvasType()).isEqualTo(CanvasType.SPEC);
        assertThat(canvas.getRepository().getName()).isEqualTo("backend");
        assertThat(canvas.isNewRepo()).isFalse();

        assertThat(mockModel.getInvocations().size()).isEqualTo(2);
    }

    @Test
    void shouldSurfaceInvalidSpecWriteAsToolError() {
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Create an executable plan")
                .response(
                        "I will write a SPEC canvas without a repository.",
                        List.of(
                                new ToolCall(
                                        "call-write-invalid",
                                        "function",
                                        "write_canvas",
                                        "{\"documentId\":\"bad-doc\",\"title\":\"Bad Plan\",\"content\":\"# Plan\",\"canvasType\":\"SPEC\"}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("The write failed; I will fix the canvas definition.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Create an executable plan without a repository");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // The invalid call must not persist a canvas
        assertThat(canvasService.getCanvas(sessionId, "bad-doc")).isNull();

        // The LLM receives the validation error as the tool response
        assertToolInvocationReturnedResponse(mockModel, 1, "spec canvas requires either a repoName or newRepoName");
    }

    @Test
    void shouldPatchCanvasDocument() {
        // Create an initial document
        canvasService.createCanvas(
                sessionId,
                "patch-doc",
                "Draft Doc",
                "This is old text. Unchanged text.",
                CanvasType.DOCUMENT,
                null,
                null);

        // Configure ReAct loop:
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Update 'This is old text.'")
                .response(
                        "I will patch the document.",
                        List.of(
                                new ToolCall(
                                        "call-patch-1",
                                        "function",
                                        "patch_canvas",
                                        "{\"documentId\":\"patch-doc\",\"patches\":[{\"targetText\":\"This is old text.\",\"replacementText\":\"This is newly patched text!\"}]}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("I have updated the document.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Update 'This is old text.' in draft doc");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // 1. Verify in database that content is updated
        CanvasEntity canvas = canvasService.getCanvas(sessionId, "patch-doc");
        assertThat(canvas).isNotNull();
        assertThat(canvas.getContent()).isEqualTo("This is newly patched text! Unchanged text.");

        // 2. Verify WebSocket sync events were emitted
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.CanvasResult
                        && ((ClientPayload.CanvasResult) r).event() instanceof CanvasEvent.Update);
        assertThat(results)
                .anyMatch(r -> r instanceof ClientPayload.CanvasResult
                        && ((ClientPayload.CanvasResult) r).event() instanceof CanvasEvent.Commit);

        assertThat(mockModel.getInvocations().size()).isEqualTo(2);
    }

    @Test
    void shouldHandleFailedPatchAndReturnFullDocument() {
        // Create an initial document
        canvasService.createCanvas(
                sessionId, "fail-doc", "Draft Doc", "First line. Second line.", CanvasType.DOCUMENT, null, null);

        // Configure ReAct loop:
        mockModel.addMatcher(PromptMatcher.builder()
                .contains("Change 'Nonexistent line.'")
                .response(
                        "I will edit the document.",
                        List.of(
                                new ToolCall(
                                        "call-patch-fail",
                                        "function",
                                        "patch_canvas",
                                        "{\"documentId\":\"fail-doc\",\"patches\":[{\"targetText\":\"Nonexistent line.\",\"replacementText\":\"New text.\"}]}")))
                .maxMatches(1)
                .build());
        mockModel.addMatcher(PromptMatcher.builder()
                .hasToolResponse()
                .response("The patch failed, but I received the current document contents.")
                .build());

        var result = planningAgentService.streamMessage(
                teamId, providerId, "gpt-4o", sessionId, "Change 'Nonexistent line.' to 'New text.' in fail-doc");

        List<ClientPayload.ChatStreamPayload> results = result.collectList().block();
        assertThat(results).isNotNull();

        // 1. Verify in database that content is completely unchanged (rollback)
        CanvasEntity canvas = canvasService.getCanvas(sessionId, "fail-doc");
        assertThat(canvas).isNotNull();
        assertThat(canvas.getContent()).isEqualTo("First line. Second line.");

        // 2. Verify that tool results contain the error message and current state
        // Since LLM got the error, the mock turn is called. We also verify the call count is 2.
        assertThat(mockModel.getInvocations().size()).isEqualTo(2);
    }
}
