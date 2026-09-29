package com.kratisai.controlplane.ingestion.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.*;
import com.kratisai.controlplane.agentloop.LlmErrorCategory;
import com.kratisai.controlplane.agentloop.ReActLoopFatalException;
import com.kratisai.controlplane.ingestion.IngestionUsageTracker;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.DimensionCategory;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.ProcessExecutor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@SpringIntegrationTest
class WikiGenerationServiceTest {

    @Autowired
    private WikiGenerationService wikiGenerationService;

    @Autowired
    private CtxWikiPageRepository ctxWikiPageRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private CtxDimensionRepository ctxDimensionRepository;

    @Autowired
    private ProcessExecutor processExecutor;

    @Autowired
    private IngestionUsageTracker usageTracker;

    @Value("${kratis.parser.scc-binary-path}")
    private String sccBinaryPath;

    private IngestionBatch batch;
    private File cloneDir;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();
        fakeChatModel.reset();
        Mockito.reset(processExecutor);
        Mockito.doReturn(new ProcessExecutor.ProcessResult(0, "[]".getBytes(StandardCharsets.UTF_8)))
                .when(processExecutor)
                .execute(Mockito.anyList(), Mockito.any(File.class), Mockito.any());

        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext();
        batch = ctx.batch();
        batch.setActive(true);
        ingestionBatchRepository.saveAndFlush(batch);

        cloneDir = Path.of(
                        System.getProperty("java.io.tmpdir"),
                        "kratis-ingest",
                        batch.getId().toString())
                .toFile();
        assertThat(cloneDir.mkdirs()).isTrue();
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(processExecutor);
        if (cloneDir != null) {
            deleteDirectoryRecursively(cloneDir);
        }
    }

    @Test
    void happyPath1_dynamicWikiGenerationWithTools() {
        // Turn 1: LLM returns a ToolCall to write a wiki page
        AssistantMessage.ToolCall toolCall1 = new AssistantMessage.ToolCall(
                "call_1",
                "function",
                "write_wiki_page",
                "{\"pageSlug\":\"overview\",\"title\":\"Overview\",\"content\":\"# Overview\\nThis is a test repo.\"}");
        AssistantMessage turn1Message =
                AssistantMessage.builder().toolCalls(List.of(toolCall1)).build();

        // Turn 2: LLM returns a final message indicating completion
        AssistantMessage turn2Message = new AssistantMessage("Wiki generation complete.");

        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(turn1Message).maxMatches(1).build());
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(turn2Message).maxMatches(1).build());

        wikiGenerationService.generateWiki(batch);

        List<CtxWikiPage> pages = ctxWikiPageRepository.findByBatchId(batch.getId());
        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().getPageSlug()).isEqualTo("overview");
        assertThat(pages.getFirst().getTitle()).isEqualTo("Overview");
        assertThat(pages.getFirst().getContent()).contains("# Overview");
    }

    @Test
    void happyPath2_multiplePagesGenerated() {
        // Turn 1: LLM writes first page
        AssistantMessage.ToolCall toolCall1 = new AssistantMessage.ToolCall(
                "call_1",
                "function",
                "write_wiki_page",
                "{\"pageSlug\":\"architecture\",\"title\":\"Architecture\",\"content\":\"# Arch\"}");
        AssistantMessage turn1Message =
                AssistantMessage.builder().toolCalls(List.of(toolCall1)).build();

        // Turn 2: LLM writes second page
        AssistantMessage.ToolCall toolCall2 = new AssistantMessage.ToolCall(
                "call_2",
                "function",
                "write_wiki_page",
                "{\"pageSlug\":\"api\",\"title\":\"API\",\"content\":\"# API\"}");
        AssistantMessage turn2Message =
                AssistantMessage.builder().toolCalls(List.of(toolCall2)).build();

        // Turn 3: LLM finishes
        AssistantMessage turn3Message = new AssistantMessage("Done.");

        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(turn1Message).maxMatches(1).build());
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(turn2Message).maxMatches(1).build());
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(turn3Message).maxMatches(1).build());

        wikiGenerationService.generateWiki(batch);

        List<CtxWikiPage> pages = ctxWikiPageRepository.findByBatchId(batch.getId());
        assertThat(pages).hasSize(2);
        assertThat(pages).extracting(CtxWikiPage::getPageSlug).containsExactlyInAnyOrder("architecture", "api");
    }

    @Test
    void failureCase_infiniteToolLoop() {
        // Configure the mock LLM to continuously return ToolCalls without ever yielding
        // the final message.
        AssistantMessage.ToolCall toolCall =
                new AssistantMessage.ToolCall("call_1", "function", "get_architectural_patterns", "{}");
        AssistantMessage infiniteMessage =
                AssistantMessage.builder().toolCalls(List.of(toolCall)).build();

        // Mock it to always return the tool call
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(infiniteMessage).build());

        var message = assertThrows(RuntimeException.class, () -> wikiGenerationService.generateWiki(batch));

        assertThat(message)
                .hasMessageContaining("Exceeded maximum iterations (20) without yielding final completion message.");
    }

    @Test
    void fatalLlmError_abortsImmediatelyWithoutRetrying() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .throwsException(new NonTransientAiException("401 - invalid api key"))
                .build());

        assertThatThrownBy(() -> wikiGenerationService.generateWiki(batch))
                .isInstanceOf(ReActLoopFatalException.class)
                .satisfies(e -> assertThat(((ReActLoopFatalException) e).category())
                        .isEqualTo(LlmErrorCategory.AUTHENTICATION));
        assertThat(fakeChatModel.getInvocations()).hasSize(1);
    }

    @Test
    void toolExecutionException_wrapsErrorAsJsonForGoogleGenAi() {
        // GoogleGenAiChatModel.parseJsonToMap rejects plain-text tool errors
        AssistantMessage.ToolCall badCall =
                new AssistantMessage.ToolCall("call_1", "function", "nonexistent_tool", "{}");
        AssistantMessage assistantMessage =
                AssistantMessage.builder().toolCalls(List.of(badCall)).build();

        AtomicReference<String> capturedToolResponse = new AtomicReference<>();

        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(assistantMessage).maxMatches(1).build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    prompt.getInstructions().stream()
                            .filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast)
                            .findFirst()
                            .ifPresent(trm -> capturedToolResponse.set(
                                    trm.getResponses().getFirst().responseData()));
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("Wiki generation complete."))));
                })
                .maxMatches(1)
                .build());

        wikiGenerationService.generateWiki(batch);

        assertThat(capturedToolResponse.get()).isNotNull();
        assertThatCode(() -> new ObjectMapper().readTree(capturedToolResponse.get()))
                .doesNotThrowAnyException();
        assertThat(capturedToolResponse.get()).contains("Tool not found: nonexistent_tool");
    }

    @Test
    void happyPath3_wikiGenerationConsumesDimensionData() {
        // Create a dimension to verify it's consumed in the system prompt
        CtxDimension dimension = new CtxDimension(
                batch,
                batch.getRepository().getTeam().getId(),
                DimensionCategory.DOMAIN,
                "Authentication",
                "Handles auth and security",
                List.of());
        ctxDimensionRepository.saveAndFlush(dimension);

        // Turn 1: LLM returns a ToolCall to write a wiki page
        // We verify the system prompt contains the dimension name "Authentication"
        AssistantMessage.ToolCall toolCall1 = new AssistantMessage.ToolCall(
                "call_1",
                "function",
                "write_wiki_page",
                "{\"pageSlug\":\"auth-overview\",\"title\":\"Auth Overview\",\"content\":\"# Auth Overview\"}");
        AssistantMessage turn1Message =
                AssistantMessage.builder().toolCalls(List.of(toolCall1)).build();

        // Turn 2: LLM returns a final message indicating completion
        AssistantMessage turn2Message = new AssistantMessage("Wiki generation complete.");

        // First matcher checks for "Authentication" in the prompt (verifying dimension
        // data is consumed)
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Authentication")
                .response(turn1Message)
                .maxMatches(1)
                .build());

        // Second matcher handles the follow-up prompt after tool execution
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(turn2Message).maxMatches(1).build());

        wikiGenerationService.generateWiki(batch);

        List<CtxWikiPage> pages = ctxWikiPageRepository.findByBatchId(batch.getId());
        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().getPageSlug()).isEqualTo("auth-overview");
        assertThat(pages.getFirst().getTitle()).isEqualTo("Auth Overview");
    }

    @Test
    void testHeuristics_and_complexityClassification() {
        // Test Low LOC and Low Files -> Simple
        assertThat(wikiGenerationService.getComplexityClassification(1000, 10)).isEqualTo("Simple");
        assertThat(wikiGenerationService.calculateTargetPageCount(1000, 10)).isEqualTo(2);

        // Test Low LOC and High Files -> Medium due to structural breadth
        assertThat(wikiGenerationService.getComplexityClassification(1000, 150)).isEqualTo("Medium");
        assertThat(wikiGenerationService.calculateTargetPageCount(1000, 150)).isEqualTo(9);

        // Test High LOC and Low Files -> Medium due to low structural breadth
        assertThat(wikiGenerationService.getComplexityClassification(20000, 15)).isEqualTo("Medium");
        assertThat(wikiGenerationService.calculateTargetPageCount(20000, 15)).isEqualTo(13);

        // Test High LOC and High Files -> Complex
        assertThat(wikiGenerationService.getComplexityClassification(20000, 150))
                .isEqualTo("Complex");
        assertThat(wikiGenerationService.calculateTargetPageCount(20000, 150)).isEqualTo(20);

        // Test Medium classification
        assertThat(wikiGenerationService.getComplexityClassification(8500, 85)).isEqualTo("Medium");
        int mediumPages = wikiGenerationService.calculateTargetPageCount(8500, 85);
        assertThat(mediumPages).isEqualTo(11);
    }

    @Test
    void testParseToolOutput_sccFormat() {
        String sccJsonOutput = """
                                [
                                  {
                                    "Name": "Java",
                                    "Code": 4500,
                                    "Count": 35
                                  },
                                  {
                                    "Name": "Markdown",
                                    "Code": 100,
                                    "Count": 3
                                  }
                                ]
                                """;
        WikiGenerationService.RepoStats stats = wikiGenerationService.parseSccOutput(sccJsonOutput);
        assertThat(stats).isNotNull();
        assertThat(stats.linesOfCode()).isEqualTo(4600);
        assertThat(stats.filesCount()).isEqualTo(38);
    }

    @Test
    void testWikiGeneration_withComplexityStatsInPrompt() throws Exception {
        // Stub scc to return 4560 LOC and 23 files
        String mockSccJson = """
                                [
                                  {
                                    "Name": "Java",
                                    "Code": 4560,
                                    "Count": 23
                                  }
                                ]
                                """;
        Mockito.doReturn(new ProcessExecutor.ProcessResult(0, mockSccJson.getBytes(StandardCharsets.UTF_8)))
                .when(processExecutor)
                .execute(Mockito.anyList(), Mockito.any(File.class), Mockito.any());

        // Turn 1: LLM returns a ToolCall to write a wiki page
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_1",
                "function",
                "write_wiki_page",
                "{\"pageSlug\":\"overview\",\"title\":\"Overview\",\"content\":\"# Overview\"}");
        AssistantMessage turn1Message =
                AssistantMessage.builder().toolCalls(List.of(toolCall)).build();
        AssistantMessage turn2Message = new AssistantMessage("Wiki generation complete.");

        // Verify system prompt contains the complexity metrics
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Approximate Lines of Code: 4560")
                .contains("Approximate Number of Files: 23")
                .contains("Classification: Medium")
                .contains("Suggested Target Number of Wiki Pages:")
                .response(turn1Message)
                .maxMatches(1)
                .build());

        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(turn2Message).maxMatches(1).build());

        wikiGenerationService.generateWiki(batch);

        List<CtxWikiPage> pages = ctxWikiPageRepository.findByBatchId(batch.getId());
        assertThat(pages).hasSize(1);
    }

    @Test
    void testWikiGeneration_withSccFailure() throws Exception {
        Mockito.doThrow(new IOException("Simulated CLI error"))
                .when(processExecutor)
                .execute(Mockito.anyList(), Mockito.any(File.class), Mockito.any());

        assertThatThrownBy(() -> wikiGenerationService.generateWiki(batch))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Repository metrics analysis failed because scc is missing or failed");
    }

    @Test
    void testWikiGeneration_withNonExistentCloneDir() {
        // Ensure it doesn't exist
        deleteDirectoryRecursively(cloneDir);

        assertThatThrownBy(() -> wikiGenerationService.generateWiki(batch))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Repository metrics analysis failed because scc is missing or failed");
    }

    @Test
    void testWikiGeneration_withRealSccExecution() throws Exception {
        String sccPath = Path.of(sccBinaryPath).toAbsolutePath().toString();
        assertThat(Path.of(sccPath))
                .as("scc binary must exist at %s (CI installs to ../build/bin/scc; see pr-control-plane.yml)", sccPath)
                .exists()
                .isExecutable();

        Mockito.doCallRealMethod()
                .when(processExecutor)
                .execute(Mockito.anyList(), Mockito.any(File.class), Mockito.anyMap());

        File tempDir = Path.of(System.getProperty("java.io.tmpdir"), "kratis-real-scc-test-" + batch.getId())
                .toFile();
        assertThat(tempDir.mkdirs()).isTrue();
        File sourceFile = new File(tempDir, "Hello.java");
        Files.writeString(sourceFile.toPath(), "public class Hello {\n    // 1\n    // 2\n}");

        try {
            ProcessExecutor.ProcessResult result =
                    processExecutor.execute(List.of(sccPath, "-f", "json"), tempDir, new HashMap<>());
            assertThat(result.exitCode()).isEqualTo(0);

            String output = new String(result.output(), StandardCharsets.UTF_8);
            WikiGenerationService.RepoStats stats = wikiGenerationService.parseSccOutput(output);

            assertThat(stats).isNotNull();
            assertThat(stats.filesCount()).isEqualTo(1);
            assertThat(stats.linesOfCode()).isEqualTo(2);
        } finally {
            deleteDirectoryRecursively(tempDir);
        }
    }

    @Test
    void testParseToolOutput_invalidJson() {
        assertThatThrownBy(() -> wikiGenerationService.parseSccOutput("{corrupt-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse scc output");
    }

    @Test
    void tracksTokenUsageAndToolCallsAcrossTurns() {
        AssistantMessage.ToolCall toolCall1 = new AssistantMessage.ToolCall(
                "call_1",
                "function",
                "write_wiki_page",
                "{\"pageSlug\":\"overview\",\"title\":\"Overview\",\"content\":\"# Overview\"}");
        AssistantMessage turn1Message =
                AssistantMessage.builder().toolCalls(List.of(toolCall1)).build();
        AssistantMessage turn2Message = new AssistantMessage("Wiki generation complete.");

        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(turn1Message).maxMatches(1).build());
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(turn2Message).maxMatches(1).build());

        wikiGenerationService.generateWiki(batch);

        var counters = usageTracker.snapshotAndClear(batch.getId());
        assertThat(counters).isPresent();
        assertThat(counters.get().toolCalls()).isEqualTo(1);
    }

    @Test
    void tracksZeroTokensAndToolCallsWhenResponsesCarryNoUsage() {
        AssistantMessage turn1Message = new AssistantMessage("Wiki generation complete.");

        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(turn1Message).maxMatches(1).build());

        wikiGenerationService.generateWiki(batch);

        assertThat(usageTracker.snapshotAndClear(batch.getId())).isEmpty();
    }

    // Best-effort cleanup in test teardown.
    @SuppressWarnings("ResultOfMethodCallIgnored")
    @SuppressFBWarnings({
        "RV_RETURN_VALUE_IGNORED",
        "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE",
        "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT"
    })
    private void deleteDirectoryRecursively(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectoryRecursively(file);
                }
            }
        }
        dir.delete();
    }
}
