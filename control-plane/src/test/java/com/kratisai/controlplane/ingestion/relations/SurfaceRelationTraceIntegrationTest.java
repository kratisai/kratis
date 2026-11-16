package com.kratisai.controlplane.ingestion.relations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService.BatchLogger;
import com.kratisai.controlplane.ingestion.IngestionUsageTracker;
import com.kratisai.controlplane.ingestion.surfaces.AssistedSurfaceService;
import com.kratisai.controlplane.model.AttributeKind;
import com.kratisai.controlplane.model.Ceiling;
import com.kratisai.controlplane.model.CtxRepoRelation;
import com.kratisai.controlplane.model.CtxRepoSynopsis;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.CtxSurfaceAttribute;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionBatchLog;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.Nature;
import com.kratisai.controlplane.model.RelationState;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
import com.kratisai.controlplane.planningagent.RepositoryTool;
import com.kratisai.controlplane.repository.CtxRepoRelationRepository;
import com.kratisai.controlplane.repository.CtxRepoSynopsisRepository;
import com.kratisai.controlplane.repository.CtxSurfaceAttributeRepository;
import com.kratisai.controlplane.repository.CtxSurfaceRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import com.kratisai.controlplane.testsupport.llm.FakeChatModel;
import com.kratisai.controlplane.testsupport.llm.PromptMatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@SpringIntegrationTest
class SurfaceRelationTraceIntegrationTest {
    @Autowired
    private AssistedSurfaceService assistedSurfaces;

    @Autowired
    private SynopsisService synopses;

    @Autowired
    private BatchActivator activator;

    @Autowired
    private RepositoryTool repositoryTool;

    @Autowired
    private IngestionBatchLogService logs;

    @Autowired
    private IngestionUsageTracker usage;

    @Autowired
    private IngestionBatchRepository batches;

    @Autowired
    private CtxSurfaceRepository surfaces;

    @Autowired
    private CtxSurfaceAttributeRepository attributes;

    @Autowired
    private CtxWikiPageRepository wikiPages;

    @Autowired
    private CtxRepoSynopsisRepository synopsisRows;

    @Autowired
    private CtxRepoRelationRepository relations;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner cleaner;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private IngestionProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactions;

    private final List<Path> cloneDirectories = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cleaner.cleanAll();
        fakeChatModel.reset();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Path directory : cloneDirectories) {
            if (Files.exists(directory)) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
            }
        }
        cleaner.cleanAll();
    }

    @Test
    void tracesRepositoryEvidenceThroughMatchingAndActivation() throws Exception {
        TestDataFactory.TestContext context = testDataFactory.createDefaultContext("Trace team", "provider");
        Repository consumer = testDataFactory.createRepository(
                context.team(), "consumer", TestDataFactory.getOrCreateDummyRepo(), "main", null);
        IngestionBatch providerBatch = context.batch();
        IngestionBatch consumerBatch = testDataFactory.createBatchForRepository(consumer);
        Evidence provider = discover(
                providerBatch,
                context.team().getId(),
                "server.conf",
                "route=/v1/orders\n",
                SurfaceRole.PROVIDES,
                "Provides orders",
                "provider-protocol");
        Evidence subject = discover(
                consumerBatch,
                context.team().getId(),
                "client.conf",
                "endpoint=/v1/orders\n",
                SurfaceRole.REQUIRES,
                "Calls orders",
                "consumer-protocol");

        synthesize(providerBatch, context.team().getId(), provider);
        synthesize(consumerBatch, context.team().getId(), subject);
        activator.activate(
                providerBatch.getId(), logger(providerBatch, context.team().getId()), "provider-commit");

        PromptMatcher matching = PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response(prompt -> {
                    String contents = prompt.getContents();
                    String subjectAttrAlias = extractAlias(contents, "(s1\\.a\\d+) IDENTIFIER orders-interface");
                    String otherAttrAlias = extractAlias(contents, "(o1\\.b\\d+) IDENTIFIER orders-interface");
                    try {
                        String json = objectMapper.writeValueAsString(
                                new InferenceResponse(List.of(new InferenceResponse.RelationProposal(
                                        "A",
                                        "s1",
                                        "B",
                                        "o1",
                                        Nature.CALLS,
                                        Direction.B_TO_A,
                                        Ceiling.SUGGESTED,
                                        "The client calls the orders endpoint",
                                        List.of(subjectAttrAlias),
                                        List.of(otherAttrAlias),
                                        List.of("f1"),
                                        List.of("f1")))));
                        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .maxMatches(1)
                .build();
        fakeChatModel.addMatcher(matching);
        BatchActivator.Activation activation = activator.activate(
                consumerBatch.getId(), logger(consumerBatch, context.team().getId()), "consumer-commit");

        assertThat(matching.getMatchCount()).isEqualTo(1);
        assertThat(activation.relationsWritten()).isEqualTo(1);
        assertThat(batches.findById(providerBatch.getId()).orElseThrow().isActive())
                .isTrue();
        IngestionBatch activated = batches.findById(consumerBatch.getId()).orElseThrow();
        assertThat(activated.isActive()).isTrue();
        assertThat(activated.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
        assertThat(activated.getCommitHash()).isEqualTo("consumer-commit");

        transactions.executeWithoutResult(
                status -> assertThat(relations.findAll()).singleElement().satisfies(relation -> {
                    assertThat(relation.getNature()).isEqualTo(Nature.CALLS);
                    assertThat(relation.getConfidence()).isEqualTo(Ceiling.SUGGESTED);
                    assertThat(relation.getState()).isEqualTo(RelationState.CURRENT);
                    assertThat(List.of(relation.getRepositoryAId(), relation.getRepositoryBId()))
                            .containsExactlyInAnyOrder(
                                    consumer.getId(), context.repository().getId());
                    assertThat(List.of(
                                    relation.getBatchA().getId(),
                                    relation.getBatchB().getId()))
                            .containsExactlyInAnyOrder(consumerBatch.getId(), providerBatch.getId());
                    Direction expectedDirection =
                            relation.getRepositoryAId().equals(consumer.getId()) ? Direction.B_TO_A : Direction.A_TO_B;
                    assertThat(relation.getDirection()).isEqualTo(expectedDirection);
                    assertMatch(relation, subject, provider, consumerBatch.getId(), providerBatch.getId());
                }));
        assertThat(repositoryTool.getRelatedRepos(
                        consumer.getName(),
                        new ToolContext(Map.of("teamId", context.team().getId()))))
                .singleElement()
                .satisfies(related -> {
                    assertThat(related.otherRepoName())
                            .isEqualTo(context.repository().getName());
                    assertThat(related.synopsis()).contains("f1");
                    assertThat(related.relations()).singleElement().satisfies(rel -> {
                        assertThat(rel.relation())
                                .isEqualTo(consumer.getName() + " CALLS "
                                        + context.repository().getName());
                        assertThat(rel.currentRepoFiles()).containsExactly("client.conf");
                    });
                });
        assertThat(logs.getLogsForBatch(consumerBatch.getId()))
                .extracting(IngestionBatchLog::getMessage)
                .anySatisfy(
                        message -> assertThat(message)
                                .contains(
                                        "Compared consumer (1 surfaces) vs provider (1 surfaces): direct=0, accepted=1, rejected=0, retries=0."));
        assertThat(fakeChatModel.getInvocations())
                .anySatisfy(invocation -> assertThat(invocation.prompt().getContents())
                        .contains(
                                "subjectRepositoryId=A",
                                "otherRepositoryId=B",
                                "- s1 REQUIRES:",
                                "- o1 PROVIDES:",
                                "IDENTIFIER orders-interface")
                        .doesNotContain(
                                subject.surfaceId().toString(),
                                provider.surfaceId().toString(),
                                subject.attributeId().toString(),
                                provider.attributeId().toString(),
                                consumerBatch.getId().toString(),
                                providerBatch.getId().toString()));

        // Activating a third unrelated repository C must leave the provider <-> consumer relation CURRENT.
        Repository thirdRepo = testDataFactory.createRepository(
                context.team(), "unrelated-repo", "https://example.invalid/unrelated.git", "main", null);
        IngestionBatch thirdBatch = batches.saveAndFlush(new IngestionBatch(thirdRepo));
        Evidence thirdEvidence = discover(
                thirdBatch,
                context.team().getId(),
                "worker.conf",
                "worker = standalone\n",
                SurfaceRole.PROVIDES,
                "Standalone worker",
                "worker-runtime");
        synthesize(thirdBatch, context.team().getId(), thirdEvidence);
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response("{\"matches\":[]}")
                .maxMatches(2)
                .build());
        activator.activate(thirdBatch.getId(), logger(thirdBatch, context.team().getId()), "third-commit");
        transactions.executeWithoutResult(
                status -> assertThat(relations.findAll()).singleElement().satisfies(relation -> {
                    assertThat(relation.getState()).isEqualTo(RelationState.CURRENT);
                    assertThat(List.of(relation.getRepositoryAId(), relation.getRepositoryBId()))
                            .containsExactlyInAnyOrder(
                                    consumer.getId(), context.repository().getId());
                }));
    }

    private static String extractAlias(String prompt, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(prompt);
        assertThat(matcher.find()).as("Expected pattern %s in prompt", regex).isTrue();
        return matcher.group(1);
    }

    private Evidence discover(
            IngestionBatch batch,
            UUID teamId,
            String file,
            String contents,
            SurfaceRole role,
            String description,
            String technology)
            throws Exception {
        Path root = Path.of(properties.getCloneDir(), batch.getId().toString());
        Files.createDirectories(root);
        cloneDirectories.add(root);
        Files.writeString(root.resolve(file), contents);
        PromptMatcher read = PromptMatcher.builder()
                .contains("Discover contracts between this codebase and external code or infrastructure")
                .response(
                        "",
                        List.of(new AssistantMessage.ToolCall(
                                "read-" + batch.getId(),
                                "function",
                                "read_file",
                                "{\"relativePath\":\"" + file + "\"}")))
                .maxMatches(1)
                .build();
        PromptMatcher discovered = PromptMatcher.builder()
                .hasToolResponse()
                .response("""
                        {"surfaces":[{"surfaceId":null,"role":"%s","description":"%s","attributes":[
                        {"kind":"TECHNOLOGY","value":"%s","file":"%s"},
                        {"kind":"IDENTIFIER","value":"orders-interface","file":"%s"}]}]}
                        """.formatted(role, description, technology, file, file))
                .maxMatches(1)
                .build();
        fakeChatModel.addMatcher(read);
        fakeChatModel.addMatcher(discovered);
        assistedSurfaces.assist(batch.getId(), logger(batch, teamId));
        assertThat(read.getMatchCount()).isEqualTo(1);
        assertThat(discovered.getMatchCount()).isEqualTo(1);
        assertThat(usage.snapshotAndClear(batch.getId()).orElseThrow().toolCalls())
                .isEqualTo(1);
        assertThat(fakeChatModel.getInvocations())
                .anySatisfy(invocation -> assertThat(invocation.prompt().getInstructions().stream()
                                .filter(ToolResponseMessage.class::isInstance)
                                .map(ToolResponseMessage.class::cast)
                                .flatMap(message -> message.getResponses().stream())
                                .map(ToolResponseMessage.ToolResponse::responseData))
                        .anySatisfy(response -> assertThat(response).contains(contents.trim())));
        return transactions.execute(status -> {
            CtxSurface surface = surfaces.findByBatchId(batch.getId()).getFirst();
            assertThat(surface.getBatch().getId()).isEqualTo(batch.getId());
            assertThat(surface.getRole()).isEqualTo(role);
            assertThat(surface.getDetectedBy()).isEqualTo(SurfaceDetection.MODEL);
            assertThat(surface.getDescription()).isEqualTo(description);
            List<CtxSurfaceAttribute> facts = attributes.findBySurfaceId(surface.getId());
            assertThat(facts).anySatisfy(fact -> {
                assertThat(fact.getKind()).isEqualTo(AttributeKind.FILE);
                assertThat(fact.getValue()).isEqualTo(file);
            });
            CtxSurfaceAttribute technologyFact = facts.stream()
                    .filter(fact -> fact.getKind() == AttributeKind.TECHNOLOGY)
                    .findFirst()
                    .orElseThrow();
            assertThat(technologyFact.getValue()).isEqualTo(technology);
            CtxSurfaceAttribute identityFact = facts.stream()
                    .filter(fact -> fact.getKind() == AttributeKind.IDENTIFIER)
                    .findFirst()
                    .orElseThrow();
            assertThat(identityFact.getValue()).isEqualTo("orders-interface");
            return new Evidence(surface.getId(), technologyFact.getId(), identityFact.getId());
        });
    }

    private void synthesize(IngestionBatch batch, UUID teamId, Evidence evidence) {
        wikiPages.saveAndFlush(
                new CtxWikiPage(batch, teamId, "trace", null, "overview", "Overview", 0, "Order interface research"));
        PromptMatcher synopsis = PromptMatcher.builder()
                .contains("You synthesize the synopsis of a repository")
                .response("""
                        {"repoRole":"SERVICE","knownAs":[],"domain":"Order processing.",
                        "interfaces":"Orders interface.","deployment":"Runs as a service.",
                        "citations":{"dimensionIds":[],"wikiSlug":"overview","filePaths":[],
                        "surfaceIds":["%s"],"attributeIds":["%s"]}}
                        """.formatted(evidence.surfaceId(), evidence.attributeId()))
                .maxMatches(1)
                .build();
        fakeChatModel.addMatcher(synopsis);
        synopses.synthesize(batch.getId(), logger(batch, teamId));
        assertThat(synopsis.getMatchCount()).isEqualTo(1);
        CtxRepoSynopsis row = synopsisRows.findByBatchId(batch.getId()).orElseThrow();
        assertThat(row.getCitations())
                .containsEntry("surfaceIds", List.of(evidence.surfaceId().toString()))
                .containsEntry("attributeIds", List.of(evidence.attributeId().toString()));
    }

    private void assertMatch(
            CtxRepoRelation relation, Evidence subject, Evidence provider, UUID subjectBatchId, UUID providerBatchId) {
        JsonNode citations;
        try {
            citations = objectMapper.readTree(relation.getCitations());
        } catch (Exception exception) {
            throw new AssertionError("Invalid persisted relation citations", exception);
        }
        assertThat(citations.path("matches").size()).isEqualTo(1);
        JsonNode match = citations.path("matches").get(0);
        assertThat(match.path("subjectSurfaceId").asText())
                .isEqualTo(subject.surfaceId().toString());
        assertThat(match.path("otherSurfaceId").asText())
                .isEqualTo(provider.surfaceId().toString());
        assertThat(surfaces.findById(subject.surfaceId())
                        .orElseThrow()
                        .getBatch()
                        .getId())
                .isEqualTo(subjectBatchId);
        assertThat(surfaces.findById(provider.surfaceId())
                        .orElseThrow()
                        .getBatch()
                        .getId())
                .isEqualTo(providerBatchId);
        assertThat(match.path("confidence").asText()).isEqualTo("SUGGESTED");
        assertThat(match.path("rationale").asText()).isEqualTo("The client calls the orders endpoint");
        JsonNode evidence = match.path("evidence");
        assertThat(evidence.path("subjectAttributeIds").size()).isEqualTo(1);
        assertThat(evidence.path("otherAttributeIds").size()).isEqualTo(1);
        assertThat(evidence.path("subjectAttributeIds").get(0).asText())
                .isEqualTo(subject.identityAttributeId().toString());
        assertThat(evidence.path("otherAttributeIds").get(0).asText())
                .isEqualTo(provider.identityAttributeId().toString());
        assertThat(evidence.path("subjectSynopsisIds").get(0).asText()).isEqualTo("f1");
        assertThat(evidence.path("otherSynopsisIds").get(0).asText()).isEqualTo("f1");
        assertThat(evidence.path("direction").asText()).isEqualTo("B_TO_A");
    }

    private BatchLogger logger(IngestionBatch batch, UUID teamId) {
        return new BatchLogger(batch.getId(), teamId, logs);
    }

    private record Evidence(UUID surfaceId, UUID attributeId, UUID identityAttributeId) {}
}
