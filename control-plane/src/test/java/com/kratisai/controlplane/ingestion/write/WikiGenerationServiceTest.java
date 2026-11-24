package com.kratisai.controlplane.ingestion.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.agentloop.LlmErrorCategory;
import com.kratisai.controlplane.agentloop.ReActLoopFatalException;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionUsageTracker;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.DimensionCategory;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.ProcessExecutor;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import com.kratisai.controlplane.testsupport.UseRealNativeBinaries;
import com.kratisai.controlplane.testsupport.WikiPageContentFixtures;
import com.kratisai.controlplane.testsupport.llm.FakeChatModel;
import com.kratisai.controlplane.testsupport.llm.PromptMatcher;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@SpringIntegrationTest
class WikiGenerationServiceTest {

    private static final String REPAIR_MARKER = "The wiki is incomplete. Fix these defects";

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

    @Value("${kratis.ingestion.parser.scc-binary-path}")
    private String sccBinaryPath;

    private IngestionBatch batch;
    private File cloneDir;
    private IngestionBatchLogService.BatchLogger batchLogger;

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
        batchLogger = new IngestionBatchLogService.BatchLogger(
                batch.getId(), ctx.team().getId(), Mockito.mock(IngestionBatchLogService.class));

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

    private static String pageCall(String slug, String title, String parentPageSlug) {
        return pageCallWithContent(
                slug, title, parentPageSlug, "# " + title + "\n\n" + WikiPageContentFixtures.WIKI_PAGE_BODY);
    }

    private static String pageCallWithContent(String slug, String title, String parentPageSlug, String content) {
        String parent = parentPageSlug == null ? "" : ",\"parentPageSlug\":\"" + parentPageSlug + "\"";
        try {
            return "{\"pageSlug\":\"" + slug + "\",\"title\":\"" + title + "\",\"content\":"
                    + new ObjectMapper().writeValueAsString(content) + parent + "}";
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode test page call", e);
        }
    }

    private static AssistantMessage toolCall(String id, String arguments) {
        return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "write_wiki_page", arguments)))
                .build();
    }

    private static AssistantMessage listPagesCall(String id) {
        return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "list_wiki_pages", "{}")))
                .build();
    }

    private static boolean hasWrittenPage(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .anyMatch(response -> response.responseData() != null
                        && response.responseData().contains("SUCCESS"));
    }

    private void addRepairMatcher(AssistantMessage response) {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains(REPAIR_MARKER)
                .response(response)
                .maxMatches(1)
                .build());
    }

    @Test
    void happyPath1_dynamicWikiGenerationWithTools() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("call_1", pageCall("overview", "Overview", null)))
                .maxMatches(1)
                .build());
        addRepairMatcher(toolCall("repair_1", pageCall("overview", "Overview", null)));
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .build());

        String result = wikiGenerationService.generateWiki(batch, batchLogger);

        assertThat(result).isEqualTo("generated 1 page(s)");
        List<CtxWikiPage> pages = ctxWikiPageRepository.findByBatchId(batch.getId());
        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().getPageSlug()).isEqualTo("overview");
        assertThat(pages.getFirst().getTitle()).isEqualTo("Overview");
        assertThat(pages.getFirst().getContent()).contains("# Overview");
    }

    @Test
    void happyPath2_multiplePagesGenerated() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("call_1", pageCall("architecture", "Architecture", null)))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("call_2", pageCall("api", "API", "architecture")))
                .maxMatches(1)
                .build());
        addRepairMatcher(toolCall("repair_1", pageCall("api", "API", "architecture")));
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(new AssistantMessage("Done.")).build());

        wikiGenerationService.generateWiki(batch, batchLogger);

        List<CtxWikiPage> pages = ctxWikiPageRepository.findByBatchId(batch.getId());
        assertThat(pages).hasSize(2);
        assertThat(pages).extracting(CtxWikiPage::getPageSlug).containsExactlyInAnyOrder("architecture", "api");
    }

    @Test
    void failureCase_infiniteToolLoop_exhaustsBothPassesAndFails() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_1", "function", "get_architectural_patterns", "{}")))
                        .build())
                .build());

        assertThatThrownBy(() -> wikiGenerationService.generateWiki(batch, batchLogger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete wiki")
                .hasMessageContaining("no wiki pages were written");

        assertThat(ctxWikiPageRepository.findByBatchId(batch.getId())).isEmpty();
    }

    @Test
    void turnExhaustion_succeedsWhenSavedWikiIsCoherent() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("page_1", pageCall("overview", "Overview", null)))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(listPagesCall("list_1")).build());

        String result = wikiGenerationService.generateWiki(batch, batchLogger);

        assertThat(result).isEqualTo("generated 1 page(s) after turn exhaustion");
        assertThat(ctxWikiPageRepository.findByBatchId(batch.getId()))
                .extracting(CtxWikiPage::getPageSlug)
                .containsExactly("overview");
    }

    @Test
    void turnExhaustion_runsBoundedCompletionForMissingLinkedPages() {
        String overviewContent =
                "# Overview\n\nSee [Architecture](architecture).\n\n" + WikiPageContentFixtures.WIKI_PAGE_BODY;
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("root_1", pageCallWithContent("overview", "Overview", null, overviewContent)))
                .maxMatches(1)
                .build());
        addRepairMatcher(toolCall("child_1", pageCall("architecture", "Architecture", "overview")));
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(prompt -> prompt.getContents().contains(REPAIR_MARKER) && hasWrittenPage(prompt))
                .response(new AssistantMessage("Wiki generation complete."))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(listPagesCall("list_1")).build());

        String result = wikiGenerationService.generateWiki(batch, batchLogger);

        assertThat(result).isEqualTo("generated 2 page(s) after turn exhaustion");
        assertThat(ctxWikiPageRepository.findByBatchId(batch.getId()))
                .extracting(CtxWikiPage::getPageSlug)
                .containsExactlyInAnyOrder("overview", "architecture");
    }

    @Test
    void fatalLlmError_abortsImmediatelyWithoutRetrying() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .throwsException(new NonTransientAiException("401 - invalid api key"))
                .build());

        assertThatThrownBy(() -> wikiGenerationService.generateWiki(batch, batchLogger))
                .isInstanceOf(ReActLoopFatalException.class)
                .satisfies(e -> assertThat(((ReActLoopFatalException) e).category())
                        .isEqualTo(LlmErrorCategory.AUTHENTICATION));
        assertThat(fakeChatModel.getInvocations()).hasSize(1);
    }

    @Test
    void toolExecutionException_wrapsErrorAsJsonForGoogleGenAi() {
        AssistantMessage unknownToolCall = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "nonexistent_tool", "{}")))
                .build();
        AtomicReference<String> capturedToolResponse = new AtomicReference<>();

        fakeChatModel.addMatcher(
                PromptMatcher.builder().response(unknownToolCall).maxMatches(1).build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    latestToolResponse(prompt).ifPresent(response -> capturedToolResponse.set(response.responseData()));
                    return new ChatResponse(
                            List.of(new Generation(toolCall("call_2", pageCall("overview", "Overview", null)))));
                })
                .maxMatches(1)
                .build());
        addRepairMatcher(toolCall("repair_1", pageCall("overview", "Overview", null)));
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .build());

        wikiGenerationService.generateWiki(batch, batchLogger);

        assertThat(capturedToolResponse.get()).isNotNull();
        assertThatCode(() -> new ObjectMapper().readTree(capturedToolResponse.get()))
                .doesNotThrowAnyException();
        assertThat(capturedToolResponse.get()).contains("Tool not found: nonexistent_tool");
    }

    private static Optional<ToolResponse> latestToolResponse(Prompt prompt) {
        List<ToolResponseMessage> responses = prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .toList();
        return responses.isEmpty()
                ? Optional.empty()
                : responses.getLast().getResponses().stream().reduce((a, b) -> b);
    }

    @Test
    void happyPath3_wikiGenerationConsumesDimensionData() {
        CtxDimension dimension = new CtxDimension(
                batch,
                batch.getRepository().getTeam().getId(),
                DimensionCategory.DOMAIN,
                "Authentication",
                "Handles auth and security",
                List.of());
        ctxDimensionRepository.saveAndFlush(dimension);

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Authentication")
                .response(toolCall("call_1", pageCall("auth-overview", "Auth Overview", null)))
                .maxMatches(1)
                .build());
        addRepairMatcher(toolCall("repair_1", pageCall("auth-overview", "Auth Overview", null)));
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .build());

        wikiGenerationService.generateWiki(batch, batchLogger);

        List<CtxWikiPage> pages = ctxWikiPageRepository.findByBatchId(batch.getId());
        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().getPageSlug()).isEqualTo("auth-overview");
    }

    @Test
    void testHeuristics_and_complexityClassification() {
        assertThat(wikiGenerationService.getComplexityClassification(1000, 10)).isEqualTo("Simple");
        assertThat(wikiGenerationService.calculateTargetPageCount(1000, 10)).isEqualTo(2);

        assertThat(wikiGenerationService.getComplexityClassification(1000, 150)).isEqualTo("Medium");
        assertThat(wikiGenerationService.calculateTargetPageCount(1000, 150)).isEqualTo(9);

        assertThat(wikiGenerationService.getComplexityClassification(20000, 15)).isEqualTo("Medium");
        assertThat(wikiGenerationService.calculateTargetPageCount(20000, 15)).isEqualTo(13);

        assertThat(wikiGenerationService.getComplexityClassification(20000, 150))
                .isEqualTo("Complex");
        assertThat(wikiGenerationService.calculateTargetPageCount(20000, 150)).isEqualTo(20);

        assertThat(wikiGenerationService.getComplexityClassification(8500, 85)).isEqualTo("Medium");
        assertThat(wikiGenerationService.calculateTargetPageCount(8500, 85)).isEqualTo(11);
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

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Approximate Lines of Code: 4560")
                .contains("Approximate Number of Files: 23")
                .contains("Classification: Medium")
                .contains("Suggested Target Number of Wiki Pages:")
                .response(toolCall("call_1", pageCall("overview", "Overview", null)))
                .maxMatches(1)
                .build());
        addRepairMatcher(toolCall("repair_1", pageCall("overview", "Overview", null)));
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .build());

        wikiGenerationService.generateWiki(batch, batchLogger);

        assertThat(ctxWikiPageRepository.findByBatchId(batch.getId())).hasSize(1);
    }

    @Test
    void testWikiGeneration_withSccFailure() throws Exception {
        Mockito.doThrow(new IOException("Simulated CLI error"))
                .when(processExecutor)
                .execute(Mockito.anyList(), Mockito.any(File.class), Mockito.any());

        assertThatThrownBy(() -> wikiGenerationService.generateWiki(batch, batchLogger))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Repository metrics analysis failed because scc is missing or failed");
    }

    @Test
    void testWikiGeneration_withNonExistentCloneDir() {
        deleteDirectoryRecursively(cloneDir);

        assertThatThrownBy(() -> wikiGenerationService.generateWiki(batch, batchLogger))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Repository metrics analysis failed because scc is missing or failed");
    }

    @Test
    @UseRealNativeBinaries
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
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("call_1", pageCall("overview", "Overview", null)))
                .maxMatches(1)
                .build());
        addRepairMatcher(toolCall("repair_1", pageCall("overview", "Overview", null)));
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .build());

        wikiGenerationService.generateWiki(batch, batchLogger);

        var counters = usageTracker.snapshotAndClear(batch.getId());
        assertThat(counters).isPresent();
        assertThat(counters.get().toolCalls()).isEqualTo(1);
    }

    @Test
    void generateWiki_withNoPages_failsCompletionGateWithoutRecordingUsage() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .build());

        assertThatThrownBy(() -> wikiGenerationService.generateWiki(batch, batchLogger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no wiki pages were written");

        assertThat(usageTracker.snapshotAndClear(batch.getId())).isEmpty();
    }

    @Test
    void unknownParentSlug_isReportedToModelAndRecovered() {
        AtomicReference<String> capturedError = new AtomicReference<>();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("call_1", pageCall("architecture", "Architecture", "home")))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    latestToolResponse(prompt).ifPresent(response -> capturedError.set(response.responseData()));
                    return new ChatResponse(
                            List.of(new Generation(toolCall("call_2", pageCall("overview", "Overview", null)))));
                })
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("call_3", pageCall("architecture", "Architecture", "overview")))
                .maxMatches(1)
                .build());
        addRepairMatcher(toolCall("repair_1", pageCall("architecture", "Architecture", "overview")));
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .build());

        wikiGenerationService.generateWiki(batch, batchLogger);

        assertThat(capturedError.get()).contains("does not exist").contains("Fix and retry");
        assertThat(ctxWikiPageRepository.findByBatchId(batch.getId()))
                .extracting(CtxWikiPage::getPageSlug)
                .containsExactlyInAnyOrder("overview", "architecture");
    }

    @Test
    void systemPrompt_encouragesWikiMapParallelismAndProseMargin() {
        AtomicReference<String> systemText = new AtomicReference<>();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    if (systemText.get() == null) {
                        prompt.getInstructions().stream()
                                .filter(Message.class::isInstance)
                                .map(Message::getText)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .ifPresent(systemText::set);
                    }
                    return new ChatResponse(
                            List.of(new Generation(toolCall("call_1", pageCall("overview", "Overview", null)))));
                })
                .maxMatches(1)
                .build());
        addRepairMatcher(toolCall("repair_1", pageCall("overview", "Overview", null)));
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .build());

        wikiGenerationService.generateWiki(batch, batchLogger);

        assertThat(systemText.get())
                .contains("Wiki Map")
                .contains("in the same turn so they run in parallel")
                .contains("at least 120 prose words")
                .contains("Current wiki status")
                .contains("advisory, not a minimum");
    }

    @Test
    void turnFeedback_reportsMissingLinksOnLaterTurns() {
        String overviewContent = "# Overview\n\nSee [Testing](testing).\n\n" + WikiPageContentFixtures.WIKI_PAGE_BODY;
        AtomicReference<String> secondPrompt = new AtomicReference<>();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("call_1", pageCallWithContent("overview", "Overview", null, overviewContent)))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    if (secondPrompt.get() == null) {
                        secondPrompt.set(prompt.getContents());
                    }
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("Thinking."))));
                })
                .maxMatches(1)
                .build());
        addRepairMatcher(toolCall("repair_1", pageCall("testing", "Testing", "overview")));
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(prompt -> prompt.getContents().contains(REPAIR_MARKER) && hasWrittenPage(prompt))
                .response(new AssistantMessage("Wiki generation complete."))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .build());

        wikiGenerationService.generateWiki(batch, batchLogger);

        assertThat(secondPrompt.get()).contains("Current wiki status").contains("testing (referenced by: overview)");
    }

    @Test
    void fileLinks_doNotTriggerTheCompletionPass() {
        String overviewContent = "# Overview\n\nRead [the README](README.md) and see [App](Application.java).\n\n"
                + WikiPageContentFixtures.WIKI_PAGE_BODY;
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("call_1", pageCallWithContent("overview", "Overview", null, overviewContent)))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .build());

        String result = wikiGenerationService.generateWiki(batch, batchLogger);

        assertThat(result).isEqualTo("generated 1 page(s)");
        assertThat(fakeChatModel.getInvocations()).hasSize(2);
    }

    @Test
    void multipleRoots_areReparentedDuringTheCompletionPass() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("call_1", pageCall("overview", "Overview", null)))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("call_2", pageCall("architecture", "Architecture", null)))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .maxMatches(1)
                .build());
        addRepairMatcher(toolCall("repair_1", pageCall("architecture", "Architecture", "overview")));
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(prompt -> prompt.getContents().contains(REPAIR_MARKER) && hasWrittenPage(prompt))
                .response(new AssistantMessage("Wiki generation complete."))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .build());

        String result = wikiGenerationService.generateWiki(batch, batchLogger);

        assertThat(result).isEqualTo("generated 2 page(s)");
        CtxWikiPage child = ctxWikiPageRepository.findByBatchIdWithParent(batch.getId()).stream()
                .filter(candidate -> "architecture".equals(candidate.getPageSlug()))
                .findFirst()
                .orElseThrow();
        assertThat(child.getParentPage()).isNotNull();
        assertThat(child.getParentPage().getPageSlug()).isEqualTo("overview");
    }

    @Test
    void multipleRoots_failTheBatchWhenTheCompletionPassLeavesThem() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("call_1", pageCall("overview", "Overview", null)))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("call_2", pageCall("architecture", "Architecture", null)))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .build());

        assertThatThrownBy(() -> wikiGenerationService.generateWiki(batch, batchLogger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple root pages")
                .hasMessageContaining("architecture");
    }

    @Test
    void missingLinksFromNonRootPages_areReportedWithTheirReferrer() {
        String overviewContent = "# Overview\n\n" + WikiPageContentFixtures.WIKI_PAGE_BODY;
        String architectureContent =
                "# Architecture\n\nSee [Testing](testing).\n\n" + WikiPageContentFixtures.WIKI_PAGE_BODY;
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall("call_1", pageCallWithContent("overview", "Overview", null, overviewContent)))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(toolCall(
                        "call_2", pageCallWithContent("architecture", "Architecture", "overview", architectureContent)))
                .maxMatches(1)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(new AssistantMessage("Wiki generation complete."))
                .build());

        assertThatThrownBy(() -> wikiGenerationService.generateWiki(batch, batchLogger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("testing (referenced by: architecture)");
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
