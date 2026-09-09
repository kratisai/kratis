package com.kratisai.controlplane.ingestion.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.*;
import com.kratisai.controlplane.SlowTest;
import com.kratisai.controlplane.agentloop.ReActLoopFatalException;
import com.kratisai.controlplane.ingestion.IngestionUsageTracker;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
@SlowTest
class PatternResearchServiceTest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private PatternResearchService patternResearchService;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxDimensionRepository ctxDimensionRepository;

    @Autowired
    private CtxNodeDimensionRepository ctxNodeDimensionRepository;

    @Autowired
    private CtxArchitecturePatternRepository ctxArchitecturePatternRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private IngestionUsageTracker usageTracker;

    private Team team;
    private IngestionBatch batch;
    private CtxNode controllerNode;
    private CtxNode repositoryNode;
    private CtxDimension controllerDimension;

    @BeforeEach
    void setUp() {
        fakeChatModel.reset();
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext context = testDataFactory.createDefaultContext("Pattern Team", "test-repo");
        team = context.team();
        batch = context.batch();
        batch.setActive(true);
        ingestionBatchRepository.saveAndFlush(batch);

        controllerNode = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/com/example/UserController.java");
        repositoryNode = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/com/example/UserRepository.java");

        ctxNodeRepository.saveAll(List.of(controllerNode, repositoryNode));

        controllerDimension = new CtxDimension(
                batch, team.getId(), DimensionCategory.ARCHETYPE, "Controller", null, List.of("*Controller.java"));
        ctxDimensionRepository.save(controllerDimension);
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    private void setupDimensionHub(CtxNode node, CtxDimension dimension, double rankScore) {
        CtxNodeDimension nodeDimension = new CtxNodeDimension(node, dimension, rankScore);
        ctxNodeDimensionRepository.save(nodeDimension);
    }

    // -------------------------------------------------------------------------
    // researchPatterns tests
    // -------------------------------------------------------------------------

    @Test
    void archetypeHubs_savesCtxArchitecturePatterns() {
        setupDimensionHub(controllerNode, controllerDimension, 0.9);

        fakeChatModel.addMatcher(PromptMatcher.builder().response("""
                                [
                                  {
                                    "name": "MVC Architecture",
                                    "description": "This is an MVC architecture with layered controllers and services.",
                                    "exemplarPaths": ["src/main/java/com/example/UserController.java"]
                                  }
                                ]
                                """).build());

        patternResearchService.researchPatterns(batch);

        List<CtxArchitecturePattern> patterns =
                ctxArchitecturePatternRepository.findByBatchIdWithExemplarNodes(batch.getId());
        assertThat(patterns).hasSize(1);
        assertThat(patterns.getFirst().getDescription()).contains("MVC architecture");
        assertThat(patterns.getFirst().getExemplarNodes()).isNotEmpty();
        assertThat(patterns.getFirst().getExemplarNodes().getFirst().getPath())
                .isEqualTo("src/main/java/com/example/UserController.java");
    }

    @Test
    void toolExecutionException_wrapsErrorAsJsonForGoogleGenAi() {
        // GoogleGenAiChatModel.parseJsonToMap rejects plain-text tool errors
        setupDimensionHub(controllerNode, controllerDimension, 0.9);

        AssistantMessage.ToolCall badCall =
                new AssistantMessage.ToolCall("call_1", "function", "nonexistent_tool", "{}");
        AssistantMessage toolCallMessage =
                AssistantMessage.builder().toolCalls(List.of(badCall)).build();

        AtomicReference<String> capturedToolResponse = new AtomicReference<>();

        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(toolCallMessage).maxMatches(1).build());

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    prompt.getInstructions().stream()
                            .filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast)
                            .findFirst()
                            .ifPresent(trm -> capturedToolResponse.set(
                                    trm.getResponses().getFirst().responseData()));
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                                                    [
                                                      {
                                                        "name": "MVC Architecture",
                                                        "description": "Recovered after tool error.",
                                                        "exemplarPaths": []
                                                      }
                                                    ]
                                                    """))));
                })
                .maxMatches(1)
                .build());

        patternResearchService.researchPatterns(batch);

        assertThat(capturedToolResponse.get()).isNotNull();
        assertThatCode(() -> new ObjectMapper().readTree(capturedToolResponse.get()))
                .doesNotThrowAnyException();
        assertThat(capturedToolResponse.get()).contains("Tool not found: nonexistent_tool");
    }

    @Test
    void noArchetypeHubs_skipsPatternDeduction() {
        // No dimension hubs set — Stage 2 should be skipped
        fakeChatModel.addMatcher(PromptMatcher.builder().response("[]").build());

        patternResearchService.researchPatterns(batch);

        List<CtxArchitecturePattern> patterns = ctxArchitecturePatternRepository.findByBatchId(batch.getId());
        assertThat(patterns).isEmpty();
    }

    @Test
    void malformedJson_exceedsRetries_throwsException() {
        setupDimensionHub(controllerNode, controllerDimension, 0.9);

        fakeChatModel.addMatcher(
                PromptMatcher.builder().response("not valid json at all").build());

        // Should throw after exceeding retries
        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> patternResearchService.researchPatterns(batch));
        assertThat(exception.getMessage()).contains("Failed to get valid architecture patterns");
    }

    @Test
    void exemplarPathsNotFound_patternStillSaved() {
        setupDimensionHub(controllerNode, controllerDimension, 0.9);

        fakeChatModel.addMatcher(PromptMatcher.builder().response("""
                                [
                                  {
                                    "name": "Hexagonal Architecture",
                                    "description": "Hexagonal architecture pattern.",
                                    "exemplarPaths": ["non/existent/path/File.java"]
                                  }
                                ]
                                """).build());

        patternResearchService.researchPatterns(batch);

        List<CtxArchitecturePattern> patterns =
                ctxArchitecturePatternRepository.findByBatchIdWithExemplarNodes(batch.getId());
        assertThat(patterns).hasSize(1);
        assertThat(patterns.getFirst().getDescription()).contains("Hexagonal");
        assertThat(patterns.getFirst().getExemplarNodes()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // per-archetype hub selection tests
    // -------------------------------------------------------------------------

    @Test
    void perArchetypeHubs_atMostFivePerArchetype() {
        // Create 3 Controller hubs — only top 2 by rankScore should be passed to the LLM
        CtxNode hub1 = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/com/example/AController.java");
        CtxNode hub2 = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/com/example/BController.java");
        CtxNode hub3 = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/com/example/CController.java");

        ctxNodeRepository.saveAll(List.of(hub1, hub2, hub3));

        setupDimensionHub(hub1, controllerDimension, 0.9);
        setupDimensionHub(hub2, controllerDimension, 0.8);
        setupDimensionHub(hub3, controllerDimension, 0.7);

        AtomicReference<String> capturedSystemPrompt = new AtomicReference<>();

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    capturedSystemPrompt.set(prompt.getContents());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                                                    [
                                                      {
                                                        "name": "MVC Pattern",
                                                        "description": "MVC pattern.",
                                                        "exemplarPaths": []
                                                      }
                                                    ]
                                                    """))));
                })
                .build());

        patternResearchService.researchPatterns(batch);

        String prompt = capturedSystemPrompt.get();
        assertThat(prompt).isNotNull();
        long hubMentions = Stream.of(
                        "Archetype: Controller",
                        "Exemplar Files:",
                        "AController.java",
                        "BController.java",
                        "CController.java")
                .filter(prompt::contains)
                .count();
        assertThat(hubMentions).isEqualTo(5);
    }

    @Test
    void multipleArchetypeGroups_eachArchetypeContributesFiles() {
        CtxDimension repositoryDimension = new CtxDimension(
                batch, team.getId(), DimensionCategory.ARCHETYPE, "Repository", null, List.of("*Repository.java"));
        ctxDimensionRepository.save(repositoryDimension);

        setupDimensionHub(controllerNode, controllerDimension, 0.9);
        setupDimensionHub(repositoryNode, repositoryDimension, 0.8);

        AtomicReference<String> capturedSystemPrompt = new AtomicReference<>();

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    capturedSystemPrompt.set(prompt.getContents());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                                                    [
                                                      {
                                                        "name": "Layered Architecture",
                                                        "description": "Layered architecture.",
                                                        "exemplarPaths": []
                                                      }
                                                    ]
                                                    """))));
                })
                .build());

        patternResearchService.researchPatterns(batch);

        String prompt = capturedSystemPrompt.get();
        assertThat(prompt).isNotNull();
        assertThat(prompt).contains("Archetype: Controller");
        assertThat(prompt).contains("UserController.java");
        assertThat(prompt).contains("Archetype: Repository");
        assertThat(prompt).contains("UserRepository.java");
    }

    // -------------------------------------------------------------------------
    // format-retry loop tests
    // -------------------------------------------------------------------------

    @Test
    void fatalLlmError_abortsImmediatelyWithoutRetrying() {
        setupDimensionHub(controllerNode, controllerDimension, 0.9);

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .throwsException(new NonTransientAiException("401 - invalid api key"))
                .build());

        assertThatThrownBy(() -> patternResearchService.researchPatterns(batch))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM call failed during pattern deduction")
                .hasCauseInstanceOf(ReActLoopFatalException.class);
        assertThat(fakeChatModel.getInvocations()).hasSize(1);
    }

    @Test
    void formatRetry_secondResponseValid_savesPatterns() {
        setupDimensionHub(controllerNode, controllerDimension, 0.9);

        AtomicInteger stage2CallCount = new AtomicInteger(0);

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    int call = stage2CallCount.incrementAndGet();
                    if (call == 1) {
                        return new ChatResponse(
                                List.of(new Generation(new AssistantMessage("Sorry, I cannot help with that."))));
                    }
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                                                    [
                                                      {
                                                        "name": "MVC Architecture",
                                                        "description": "MVC architecture recovered after retry.",
                                                        "exemplarPaths": []
                                                      }
                                                    ]
                                                    """))));
                })
                .build());

        patternResearchService.researchPatterns(batch);

        List<CtxArchitecturePattern> patterns = ctxArchitecturePatternRepository.findByBatchId(batch.getId());
        assertThat(patterns).hasSize(1);
        assertThat(patterns.getFirst().getDescription()).contains("recovered after retry");
    }

    @Test
    void formatRetry_bothResponsesInvalid_throwsException() {
        setupDimensionHub(controllerNode, controllerDimension, 0.9);

        fakeChatModel.addMatcher(
                PromptMatcher.builder().response("not valid json").build());

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> patternResearchService.researchPatterns(batch));
        assertThat(exception.getMessage()).contains("Failed to get valid architecture patterns");
    }

    @Test
    void invalidPatternInArray_triggersRetry() {
        setupDimensionHub(controllerNode, controllerDimension, 0.9);

        AtomicInteger callCount = new AtomicInteger(0);

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    int call = callCount.incrementAndGet();
                    if (call == 1) {
                        // First response has an invalid pattern (empty description)
                        return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                                        [
                                          {
                                            "name": "Empty Pattern",
                                            "description": "",
                                            "exemplarPaths": []
                                          }
                                        ]
                                        """))));
                    }
                    // Second response is fully valid after retry
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                                    [
                                      {
                                        "name": "Valid Pattern",
                                        "description": "This is a valid pattern after retry.",
                                        "exemplarPaths": ["src/main/java/com/example/UserController.java"]
                                      }
                                    ]
                                    """))));
                })
                .build());

        patternResearchService.researchPatterns(batch);

        List<CtxArchitecturePattern> patterns =
                ctxArchitecturePatternRepository.findByBatchIdWithExemplarNodes(batch.getId());
        assertThat(patterns).hasSize(1);
        assertThat(patterns.getFirst().getDescription()).contains("valid pattern after retry");
    }

    @Test
    void nullExemplarPaths_stillSavesPattern() {
        setupDimensionHub(controllerNode, controllerDimension, 0.9);

        fakeChatModel.addMatcher(PromptMatcher.builder().response("""
                [
                  {
                    "name": "No Paths Pattern",
                    "description": "Pattern with null exemplar paths.",
                    "exemplarPaths": null
                  }
                ]
                """).build());

        patternResearchService.researchPatterns(batch);

        List<CtxArchitecturePattern> patterns =
                ctxArchitecturePatternRepository.findByBatchIdWithExemplarNodes(batch.getId());
        assertThat(patterns).hasSize(1);
        assertThat(patterns.getFirst().getDescription()).contains("null exemplar paths");
        assertThat(patterns.getFirst().getExemplarNodes()).isEmpty();
    }

    @Test
    void emptyPatternArray_throwsException() {
        setupDimensionHub(controllerNode, controllerDimension, 0.9);

        fakeChatModel.addMatcher(PromptMatcher.builder().response("[]").build());

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> patternResearchService.researchPatterns(batch));
        assertThat(exception.getMessage()).contains("Failed to get valid architecture patterns");
    }

    @Test
    void tracksTokenUsageAndToolCallsDuringPatternDeduction() {
        setupDimensionHub(controllerNode, controllerDimension, 0.9);

        AssistantMessage.ToolCall toolCall =
                new AssistantMessage.ToolCall("call_1", "function", "read_file", "{\"path\":\"UserController.java\"}");
        AssistantMessage toolCallMessage =
                AssistantMessage.builder().toolCalls(List.of(toolCall)).build();

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCallMessage)
                .usage(new org.springframework.ai.chat.model.MessageAggregator.DefaultUsage(120, 60, 180))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response("""
                        [
                          {
                            "name": "MVC Architecture",
                            "description": "Pattern with tool call tracking.",
                            "exemplarPaths": ["src/main/java/com/example/UserController.java"]
                          }
                        ]
                        """)
                .usage(new org.springframework.ai.chat.model.MessageAggregator.DefaultUsage(80, 40, 120))
                .maxMatches(1)
                .build());

        patternResearchService.researchPatterns(batch);

        var counters = usageTracker.snapshotAndClear(batch.getId());
        assertThat(counters).isPresent();
        assertThat(counters.get().toolCalls()).isEqualTo(1);
    }
}
