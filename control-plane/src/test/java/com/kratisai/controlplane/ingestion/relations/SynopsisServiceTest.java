package com.kratisai.controlplane.ingestion.relations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.BatchChatClientFactory;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionUsageTracker;
import com.kratisai.controlplane.ingestion.ReadFileTool;
import com.kratisai.controlplane.model.AttributeKind;
import com.kratisai.controlplane.model.CtxArchitecturePattern;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxRepoSynopsis;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.CtxSurfaceAttribute;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.RepoRole;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxArchitecturePatternRepository;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import com.kratisai.controlplane.repository.CtxRepoSynopsisRepository;
import com.kratisai.controlplane.repository.CtxSurfaceRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.testsupport.llm.FakeChatModel;
import com.kratisai.controlplane.testsupport.llm.PromptMatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SynopsisServiceTest {
    @TempDir
    Path cloneRoot;

    private SynopsisService service() {
        IngestionProperties properties = new IngestionProperties();
        properties.setCloneDir(cloneRoot.toString());
        return new SynopsisService(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                mock(PlatformTransactionManager.class),
                properties);
    }

    @Test
    void allSurfacesAndTheirAttributesAppearInThePrompt() {
        CtxSurface provided = mock(CtxSurface.class);
        CtxSurface required = mock(CtxSurface.class);
        CtxSurfaceAttribute name = mock(CtxSurfaceAttribute.class);
        CtxSurfaceAttribute file = mock(CtxSurfaceAttribute.class);
        UUID providedId = UUID.randomUUID();
        UUID requiredId = UUID.randomUUID();
        UUID nameId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        when(provided.getId()).thenReturn(providedId);
        when(provided.getRole()).thenReturn(SurfaceRole.PROVIDES);
        when(provided.getDescription()).thenReturn("Event publisher");
        when(provided.getDetectedBy()).thenReturn(SurfaceDetection.CMM);
        when(provided.getAttributeRows()).thenReturn(List.of(name, file));
        when(required.getId()).thenReturn(requiredId);
        when(required.getRole()).thenReturn(SurfaceRole.REQUIRES);
        when(required.getDescription()).thenReturn("Unfamiliar transport consumer");
        when(required.getDetectedBy()).thenReturn(SurfaceDetection.MODEL);
        when(required.getAttributeRows()).thenReturn(List.of());
        when(name.getId()).thenReturn(nameId);
        when(name.getKind()).thenReturn(AttributeKind.NAME);
        when(name.getValue()).thenReturn("orders");
        when(name.getDetectedBy()).thenReturn(SurfaceDetection.MODEL);
        when(file.getId()).thenReturn(fileId);
        when(file.getKind()).thenReturn(AttributeKind.FILE);
        when(file.getValue()).thenReturn("src/events.java");
        when(file.getDetectedBy()).thenReturn(SurfaceDetection.CMM);
        CtxWikiPage wiki = mock(CtxWikiPage.class);
        when(wiki.getPageSlug()).thenReturn("root");
        when(wiki.getTitle()).thenReturn("Root");
        var inputs = new SynopsisService.SynopsisInputs(
                List.of(), List.of(), SynopsisService.evidenceSurfaces(List.of(provided, required)), wiki);

        String prompt = service().buildSystemPrompt(new SynopsisService.Prepared(null, "repo", "model", inputs));
        assertThat(prompt)
                .contains(
                        providedId.toString(),
                        requiredId.toString(),
                        nameId.toString(),
                        fileId.toString(),
                        "Event publisher",
                        "Unfamiliar transport consumer",
                        "name=orders",
                        "file=src/events.java",
                        "source: MODEL",
                        "Seed known_as names: [orders]");
    }

    @Test
    void capsSurfaceEvidenceInPrompt() {
        CtxWikiPage wiki = mock(CtxWikiPage.class);
        when(wiki.getPageSlug()).thenReturn("root");
        when(wiki.getTitle()).thenReturn("Root");
        List<SynopsisService.SurfaceEvidence> surfaces = IntStream.range(0, 2_000)
                .mapToObj(index -> new SynopsisService.SurfaceEvidence(
                        UUID.randomUUID(),
                        SurfaceRole.PROVIDES,
                        "surface " + index,
                        "MODEL",
                        List.of(new SynopsisService.AttributeEvidence(
                                UUID.randomUUID(), "ref", "x".repeat(500), "MODEL"))))
                .toList();
        var inputs = new SynopsisService.SynopsisInputs(List.of(), List.of(), surfaces, wiki);

        String prompt = service().buildSystemPrompt(new SynopsisService.Prepared(null, "repo", "model", inputs));

        assertThat(prompt)
                .contains("omitted for prompt size")
                .hasSizeLessThan(SynopsisService.SURFACE_EVIDENCE_CHAR_BUDGET + 20_000);
    }

    @Test
    void excludesLegacyAndUnclassifiedSurfacesFromSynopsisInputs() {
        CtxSurface legacy = mock(CtxSurface.class);
        CtxSurface unclassified = mock(CtxSurface.class);
        CtxSurface current = mock(CtxSurface.class);
        UUID currentId = UUID.randomUUID();
        when(legacy.getDetectedBy()).thenReturn(SurfaceDetection.UNKNOWN);
        when(unclassified.getDetectedBy()).thenReturn(SurfaceDetection.CMM);
        when(unclassified.getRole()).thenReturn(SurfaceRole.UNKNOWN);
        when(current.getDetectedBy()).thenReturn(SurfaceDetection.CMM);
        when(current.getId()).thenReturn(currentId);
        when(current.getRole()).thenReturn(SurfaceRole.PROVIDES);
        when(current.getDescription()).thenReturn("production interface");
        when(current.getAttributeRows()).thenReturn(List.of());

        assertThat(SynopsisService.evidenceSurfaces(List.of(legacy, unclassified, current)))
                .extracting(SynopsisService.SurfaceEvidence::id)
                .containsExactly(currentId);
    }

    @Test
    void retainsOnlyCitationsFromInputsAndTheCurrentClone() throws Exception {
        UUID batchId = UUID.randomUUID();
        UUID dimensionId = UUID.randomUUID();
        UUID surfaceId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        String unknown = UUID.randomUUID().toString();
        Path clone = Files.createDirectory(cloneRoot.resolve(batchId.toString()));
        Files.writeString(clone.resolve("README.md"), "repo");
        Files.writeString(cloneRoot.resolve("outside.txt"), "outside");
        CtxDimension dimension = mock(CtxDimension.class);
        when(dimension.getId()).thenReturn(dimensionId);
        CtxWikiPage wiki = mock(CtxWikiPage.class);
        when(wiki.getPageSlug()).thenReturn("root");
        var attribute = new SynopsisService.AttributeEvidence(attributeId, "name", "orders", "MODEL");
        var surface = new SynopsisService.SurfaceEvidence(
                surfaceId, SurfaceRole.PROVIDES, "publisher", "MODEL", List.of(attribute));
        var inputs = new SynopsisService.SynopsisInputs(List.of(dimension), List.of(), List.of(surface), wiki);
        var response = new SynopsisResponse(
                RepoRole.SERVICE,
                List.of(),
                "domain",
                "interfaces",
                "deployment",
                new SynopsisResponse.Citations(
                        List.of(dimensionId.toString(), unknown, dimensionId.toString()),
                        "root",
                        List.of("README.md", "../outside.txt", "missing"),
                        List.of(surfaceId.toString(), unknown),
                        List.of(attributeId.toString(), unknown)));
        var logger = new IngestionBatchLogService.BatchLogger(
                batchId, UUID.randomUUID(), mock(IngestionBatchLogService.class));

        assertThat(service().filterCitations(batchId, inputs, response, logger))
                .isEqualTo(Map.of(
                        "dimensionIds",
                        List.of(dimensionId.toString()),
                        "wikiSlug",
                        "root",
                        "filePaths",
                        List.of("README.md"),
                        "surfaceIds",
                        List.of(surfaceId.toString()),
                        "attributeIds",
                        List.of(attributeId.toString())));
    }

    @Test
    void promptPreservesProvenanceAndSeedsOnlyProvidedNames() {
        var duplicate = new SynopsisService.AttributeEvidence(UUID.randomUUID(), "name", "orders", "MODEL");
        var identifier = new SynopsisService.AttributeEvidence(UUID.randomUUID(), "identifier", "pkg/orders", "SYFT");
        var file = new SynopsisService.AttributeEvidence(UUID.randomUUID(), "file", "orders.yaml", "CMM");
        var required = new SynopsisService.AttributeEvidence(UUID.randomUUID(), "name", "external", "CMM");
        var unknown = new SynopsisService.AttributeEvidence(UUID.randomUUID(), "identifier", "unverified", "UNKNOWN");
        var surfaces = List.of(
                new SynopsisService.SurfaceEvidence(
                        UUID.randomUUID(),
                        SurfaceRole.PROVIDES,
                        "publisher",
                        "CMM",
                        List.of(duplicate, identifier, file)),
                new SynopsisService.SurfaceEvidence(
                        UUID.randomUUID(), SurfaceRole.PROVIDES, "second publisher", "MODEL", List.of(duplicate)),
                new SynopsisService.SurfaceEvidence(
                        UUID.randomUUID(), SurfaceRole.REQUIRES, "consumer", "SYFT", List.of(required)),
                new SynopsisService.SurfaceEvidence(
                        UUID.randomUUID(), SurfaceRole.UNKNOWN, "unknown", "UNKNOWN", List.of(unknown)));
        CtxDimension dimension = mock(CtxDimension.class);
        UUID dimensionId = UUID.randomUUID();
        when(dimension.getId()).thenReturn(dimensionId);
        when(dimension.getName()).thenReturn("Product");
        when(dimension.getSynopsis()).thenReturn("d".repeat(501));
        CtxArchitecturePattern pattern = mock(CtxArchitecturePattern.class);
        when(pattern.getName()).thenReturn("Messaging");
        when(pattern.getDescription()).thenReturn("p".repeat(301));
        CtxWikiPage wiki = mock(CtxWikiPage.class);
        when(wiki.getPageSlug()).thenReturn("overview");
        when(wiki.getTitle()).thenReturn("Overview");
        when(wiki.getContent()).thenReturn("w".repeat(4001));
        var inputs = new SynopsisService.SynopsisInputs(List.of(dimension), List.of(pattern), surfaces, wiki);

        String prompt = service().buildSystemPrompt(new SynopsisService.Prepared(null, "orders-repo", "model", inputs));

        assertThat(prompt)
                .contains("Repository: orders-repo", "[" + dimensionId + "] Product", "Messaging: ")
                .contains("w".repeat(4000) + "\n...", "d".repeat(500) + "\n...", "p".repeat(300) + "\n...")
                .doesNotContain("w".repeat(4001), "d".repeat(501), "p".repeat(301))
                .contains("file=orders.yaml (source: CMM)", "identifier=pkg/orders (source: SYFT)")
                .contains("name=external (source: CMM)", "identifier=unverified (source: UNKNOWN)")
                .contains("Seed known_as names: [orders, pkg/orders]");
    }

    @Test
    void rejectsEscapingMissingAndNonFileCitationsAndDeduplicatesValidPaths() throws Exception {
        UUID batchId = UUID.randomUUID();
        Path clone = Files.createDirectory(cloneRoot.resolve(batchId.toString()));
        Path directory = Files.createDirectory(clone.resolve("nested"));
        Files.writeString(directory.resolve("source.txt"), "source");
        Path outside = Files.writeString(cloneRoot.resolve("outside.txt"), "outside");
        Files.createSymbolicLink(clone.resolve("outside-link"), outside);
        Files.createSymbolicLink(clone.resolve("inside-link"), directory.resolve("source.txt"));
        CtxWikiPage wiki = mock(CtxWikiPage.class);
        when(wiki.getPageSlug()).thenReturn("root");
        var inputs = new SynopsisService.SynopsisInputs(List.of(), List.of(), List.of(), wiki);
        var response = new SynopsisResponse(
                RepoRole.DATA,
                List.of(),
                "domain",
                "interfaces",
                "deployment",
                new SynopsisResponse.Citations(
                        List.of("not-a-uuid"),
                        "other",
                        List.of(
                                "",
                                "   ",
                                "nested",
                                "missing",
                                "../outside.txt",
                                outside.toString(),
                                "outside-link",
                                "nested/source.txt",
                                "nested/source.txt",
                                "inside-link",
                                "bad\u0000path"),
                        List.of("not-a-uuid"),
                        List.of("not-a-uuid")));
        IngestionBatchLogService logs = mock(IngestionBatchLogService.class);
        UUID teamId = UUID.randomUUID();
        var logger = new IngestionBatchLogService.BatchLogger(batchId, teamId, logs);

        assertThat(service().filterCitations(batchId, inputs, response, logger))
                .isEqualTo(Map.of(
                        "dimensionIds", List.of(),
                        "filePaths", List.of("nested/source.txt", "inside-link"),
                        "surfaceIds", List.of(),
                        "attributeIds", List.of()));
        verify(logs)
                .log(
                        batchId,
                        teamId,
                        "INFO",
                        "SYNTHESIZE_SYNOPSIS",
                        "Citations kept: 0 dimension ids, no wiki slug, 2 file paths, 0 surfaces, 0 attributes.");
    }

    @Test
    void missingCloneCannotValidateFileCitations() {
        UUID batchId = UUID.randomUUID();
        CtxWikiPage wiki = mock(CtxWikiPage.class);
        when(wiki.getPageSlug()).thenReturn("root");
        var inputs = new SynopsisService.SynopsisInputs(List.of(), List.of(), List.of(), wiki);
        var response = new SynopsisResponse(
                RepoRole.SERVICE,
                null,
                "domain",
                "interfaces",
                "deployment",
                new SynopsisResponse.Citations(null, null, List.of("README.md"), null, null));
        var logger = new IngestionBatchLogService.BatchLogger(
                batchId, UUID.randomUUID(), mock(IngestionBatchLogService.class));

        assertThat(service().filterCitations(batchId, inputs, response, logger))
                .containsEntry("filePaths", List.of())
                .doesNotContainKey("wikiSlug");
    }

    @Test
    void responseRequiresRoleAndNormalizesMissingOptionalValues() {
        assertThatThrownBy(() -> new SynopsisResponse(null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repoRole is required");
        for (RepoRole role : RepoRole.values()) {
            var response = new SynopsisResponse(role, null, null, null, null, null);
            assertThat(response.repoRole()).isEqualTo(role);
            assertThat(response.knownAs()).isEmpty();
            assertThat(response.sectionsBlank()).isTrue();
            assertThat(response.citations()).isEqualTo(new SynopsisResponse.Citations(null, null, null, null, null));
            assertThat(response.citations().dimensionIds()).isEmpty();
            assertThat(response.citations().filePaths()).isEmpty();
            assertThat(response.citations().surfaceIds()).isEmpty();
            assertThat(response.citations().attributeIds()).isEmpty();
        }
    }

    @Test
    void responseLimitsAliasesAndDefensivelyCopiesEvidence() {
        var aliases = new ArrayList<>(
                IntStream.range(0, 10).mapToObj(i -> "name-" + i).toList());
        var dimensionIds = new ArrayList<>(List.of("dimension"));
        var citations = new SynopsisResponse.Citations(dimensionIds, "root", null, null, null);
        var response = new SynopsisResponse(RepoRole.LIBRARY, aliases, "domain", "interfaces", "deployment", citations);
        var attributes = new ArrayList<>(
                List.of(new SynopsisService.AttributeEvidence(UUID.randomUUID(), "name", "library", "MODEL")));
        var surface = new SynopsisService.SurfaceEvidence(
                UUID.randomUUID(), SurfaceRole.PROVIDES, "library", "MODEL", attributes);
        aliases.clear();
        dimensionIds.clear();
        attributes.clear();

        assertThat(response.knownAs())
                .containsExactlyElementsOf(
                        IntStream.range(0, 8).mapToObj(i -> "name-" + i).toList());
        assertThat(response.citations().dimensionIds()).containsExactly("dimension");
        assertThat(surface.attributes()).singleElement().satisfies(attribute -> assertThat(attribute.value())
                .isEqualTo("library"));
        assertThat(response.sectionsBlank()).isFalse();
        assertThat(new SynopsisResponse(RepoRole.SERVICE, List.of(), " ", "interfaces", "deployment", null)
                        .sectionsBlank())
                .isTrue();
        assertThat(new SynopsisResponse(RepoRole.SERVICE, List.of(), "domain", " ", "deployment", null).sectionsBlank())
                .isTrue();
        assertThat(new SynopsisResponse(RepoRole.SERVICE, List.of(), "domain", "interfaces", " ", null).sectionsBlank())
                .isTrue();
    }

    @Test
    void synthesizePersistsOnlySuppliedProvenance() throws Exception {
        var fixture = new SynthesisFixture();
        Path clone = Files.createDirectory(cloneRoot.resolve(fixture.batchId.toString()));
        Files.writeString(clone.resolve("README.md"), "repo");
        UUID surfaceId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        CtxSurface surface = mock(CtxSurface.class);
        CtxSurfaceAttribute attribute = mock(CtxSurfaceAttribute.class);
        when(surface.getId()).thenReturn(surfaceId);
        when(surface.getRole()).thenReturn(SurfaceRole.PROVIDES);
        when(surface.getDescription()).thenReturn("orders publisher");
        when(surface.getDetectedBy()).thenReturn(SurfaceDetection.CMM);
        when(surface.getAttributeRows()).thenReturn(List.of(attribute));
        when(attribute.getId()).thenReturn(attributeId);
        when(attribute.getKind()).thenReturn(AttributeKind.NAME);
        when(attribute.getValue()).thenReturn("orders");
        when(attribute.getDetectedBy()).thenReturn(SurfaceDetection.MODEL);
        when(fixture.surfaces.findByBatchId(fixture.batchId)).thenReturn(List.of(surface));
        PromptMatcher matcher = fixture.reply("""
                {"repoRole":"SERVICE","knownAs":["orders"],"domain":"  Order processing  ",
                "interfaces":" Events ","deployment":" Container ","citations":{
                "wikiSlug":"root","filePaths":["README.md","../outside"],
                "surfaceIds":["%s","%s"],"attributeIds":["%s"]}}
                """.formatted(surfaceId, UUID.randomUUID(), attributeId), 1);

        fixture.service.synthesize(fixture.batchId, fixture.logger);

        assertThat(matcher.getMatchCount()).isEqualTo(1);
        var saved = ArgumentCaptor.forClass(CtxRepoSynopsis.class);
        verify(fixture.synopses).save(saved.capture());
        CtxRepoSynopsis row = saved.getValue();
        assertThat(row.getRepoRole()).isEqualTo(RepoRole.SERVICE);
        assertThat(row.getKnownAs()).containsExactly("orders");
        assertThat(row.getSynopsis())
                .isEqualTo("Domain: Order processing\n\nInterfaces: Events\n\nDeployment: Container");
        assertThat(row.getModelRef()).isEqualTo("test-model");
        assertThat(row.getCitations())
                .isEqualTo(Map.of(
                        "dimensionIds", List.of(),
                        "wikiSlug", "root",
                        "filePaths", List.of("README.md"),
                        "surfaceIds", List.of(surfaceId.toString()),
                        "attributeIds", List.of(attributeId.toString())));
        assertThat(fixture.model.getInvocations()).singleElement().satisfies(invocation -> assertThat(
                        invocation.prompt().getContents())
                .contains(
                        surfaceId.toString(),
                        attributeId.toString(),
                        "orders publisher",
                        "name=orders (source: MODEL)"));
    }

    @Test
    void missingBatchOrWikiRootFailsBeforeCallingModel() {
        var missingBatch = new SynthesisFixture();
        when(missingBatch.batches.findByIdWithRepositoryAndTeam(missingBatch.batchId))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> missingBatch.service.synthesize(missingBatch.batchId, missingBatch.logger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ingestion batch not found");
        assertThat(missingBatch.model.getInvocations()).isEmpty();
        verify(missingBatch.synopses, never()).save(any());

        var missingWiki = new SynthesisFixture();
        when(missingWiki.wikis.findByBatchIdAndParentPageIsNullOrderByOrderIndexAsc(missingWiki.batchId))
                .thenReturn(List.of());
        assertThatThrownBy(() -> missingWiki.service.synthesize(missingWiki.batchId, missingWiki.logger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no wiki root page");
        assertThat(missingWiki.model.getInvocations()).isEmpty();
        verify(missingWiki.synopses, never()).save(any());
    }

    @Test
    void retriesIncompleteAndOverlongSectionsBeforeSaving() {
        var fixture = new SynthesisFixture();
        PromptMatcher blank = fixture.reply("""
                {"repoRole":"SERVICE","domain":" ","interfaces":"interfaces","deployment":"deployment"}
                """, 1);
        PromptMatcher tooLong = fixture.reply("""
                {"repoRole":"SERVICE","domain":"%s","interfaces":"interfaces","deployment":"deployment"}
                """.formatted("word ".repeat(249)), 1);
        PromptMatcher valid = fixture.reply("""
                {"repoRole":"SERVICE","domain":"domain","interfaces":"interfaces","deployment":"deployment"}
                """, 1);

        fixture.service.synthesize(fixture.batchId, fixture.logger);

        assertThat(blank.getMatchCount()).isEqualTo(1);
        assertThat(tooLong.getMatchCount()).isEqualTo(1);
        assertThat(valid.getMatchCount()).isEqualTo(1);
        assertThat(fixture.model.getInvocations()).hasSize(3);
        assertThat(fixture.model.getInvocations().get(1).prompt().getContents())
                .contains("All three description sections");
        assertThat(fixture.model.getInvocations().get(2).prompt().getContents())
                .contains("The description is 251 words");
        verify(fixture.synopses).save(any(CtxRepoSynopsis.class));
    }

    @Test
    void exhaustedInvalidResponsesFailWithoutPersistingAndAreLogged() {
        var fixture = new SynthesisFixture();
        PromptMatcher invalid = fixture.reply("""
                {"repoRole":"SERVICE","domain":"domain","interfaces":"","deployment":"deployment"}
                """, 9);

        assertThatThrownBy(() -> fixture.service.synthesize(fixture.batchId, fixture.logger))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SYNTHESIZE_SYNOPSIS failed for " + fixture.batchId)
                .hasMessageContaining("Agent exceeded maximum iterations");
        assertThat(invalid.getMatchCount()).isEqualTo(9);
        verify(fixture.synopses, never()).save(any());
        verify(fixture.logs)
                .log(
                        eq(fixture.batchId),
                        eq(fixture.teamId),
                        eq("ERROR"),
                        eq("SYNTHESIZE_SYNOPSIS"),
                        contains("Agent exceeded maximum iterations"));
    }

    @Test
    void acceptsCaseInsensitiveRepoRoleFromModelJson() {
        var fixture = new SynthesisFixture();
        PromptMatcher lowercaseRole = fixture.reply("""
                {"repoRole":" service ","knownAs":["orders"],"domain":"Orders domain","interfaces":"REST API","deployment":"Docker"}
                """, 1);

        fixture.service.synthesize(fixture.batchId, fixture.logger);

        assertThat(lowercaseRole.getMatchCount()).isEqualTo(1);
        verify(fixture.synopses).save(argThat(saved -> saved.getRepoRole() == RepoRole.SERVICE));
        assertThat(RepoRole.from("web_app")).isEqualTo(RepoRole.WEB_APP);
        assertThat(RepoRole.from("unknown")).isNull();
        assertThat(RepoRole.from(null)).isNull();
    }

    private final class SynthesisFixture {
        private final UUID batchId = UUID.randomUUID();
        private final UUID teamId = UUID.randomUUID();
        private final FakeChatModel model = new FakeChatModel();
        private final IngestionBatchRepository batches = mock(IngestionBatchRepository.class);
        private final CtxWikiPageRepository wikis = mock(CtxWikiPageRepository.class);
        private final CtxSurfaceRepository surfaces = mock(CtxSurfaceRepository.class);
        private final CtxRepoSynopsisRepository synopses = mock(CtxRepoSynopsisRepository.class);
        private final IngestionBatchLogService logs = mock(IngestionBatchLogService.class);
        private final IngestionBatchLogService.BatchLogger logger =
                new IngestionBatchLogService.BatchLogger(batchId, teamId, logs);
        private final SynopsisService service;

        private SynthesisFixture() {
            IngestionProperties properties = new IngestionProperties();
            properties.setCloneDir(cloneRoot.toString());
            PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
            BatchChatClientFactory clients = mock(BatchChatClientFactory.class);
            IngestionBatch batch = mock(IngestionBatch.class);
            Repository repository = mock(Repository.class);
            Team team = mock(Team.class);
            CtxWikiPage wiki = mock(CtxWikiPage.class);
            when(batch.getRepository()).thenReturn(repository);
            when(repository.getName()).thenReturn("repo");
            when(repository.getId()).thenReturn(UUID.randomUUID());
            when(repository.getTeam()).thenReturn(team);
            when(team.getId()).thenReturn(teamId);
            when(wiki.getPageSlug()).thenReturn("root");
            when(wiki.getTitle()).thenReturn("Root");
            when(batches.findByIdWithRepositoryAndTeam(batchId)).thenReturn(Optional.of(batch));
            when(wikis.findByBatchIdAndParentPageIsNullOrderByOrderIndexAsc(batchId))
                    .thenReturn(List.of(wiki));
            when(clients.chatClientFor(batch))
                    .thenReturn(ChatClient.builder(model).build());
            when(clients.modelRefFor(batch)).thenReturn("test-model");
            CtxDimensionRepository dimensions = mock(CtxDimensionRepository.class);
            CtxArchitecturePatternRepository patterns = mock(CtxArchitecturePatternRepository.class);
            service = new SynopsisService(
                    clients,
                    new ReadFileTool(properties),
                    batches,
                    dimensions,
                    wikis,
                    patterns,
                    surfaces,
                    synopses,
                    mock(IngestionUsageTracker.class),
                    Runnable::run,
                    transactions,
                    properties);
        }

        private PromptMatcher reply(String response, int maxMatches) {
            PromptMatcher matcher = PromptMatcher.builder()
                    .contains("You synthesize the synopsis of a repository")
                    .response(response)
                    .maxMatches(maxMatches)
                    .build();
            model.addMatcher(matcher);
            return matcher;
        }
    }
}
