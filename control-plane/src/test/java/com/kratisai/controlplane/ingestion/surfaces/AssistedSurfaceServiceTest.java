package com.kratisai.controlplane.ingestion.surfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.ListRepositoryFilesTool;
import com.kratisai.controlplane.ingestion.ReadFileTool;
import com.kratisai.controlplane.ingestion.SearchRepositoryFilesTool;
import com.kratisai.controlplane.model.AttributeKind;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.CtxSurfaceAttribute;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.CtxSurfaceAttributeRepository;
import com.kratisai.controlplane.repository.CtxSurfaceRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

@SuppressFBWarnings(value = "NP_NULL_PARAM_DEREF_NONVIRTUAL", justification = "Tests required-field validation")
class AssistedSurfaceServiceTest {
    @TempDir
    Path root;

    @Test
    void enrichesKnownSurfacesAndRecoversUnknownIdsWhenEvidenceIsAnchored() throws Exception {
        Files.writeString(root.resolve("source.txt"), "endpoint");
        CtxSurfaceRepository surfaces = mock(CtxSurfaceRepository.class);
        CtxSurfaceAttributeRepository attributes = mock(CtxSurfaceAttributeRepository.class);
        SurfaceExtractionService extraction = mock(SurfaceExtractionService.class);
        var service = testService(surfaces, attributes, extraction);
        IngestionBatch batch = mock(IngestionBatch.class);
        Repository repository = mock(Repository.class);
        Team team = mock(Team.class);
        when(batch.getRepository()).thenReturn(repository);
        when(repository.getTeam()).thenReturn(team);
        when(repository.getId()).thenReturn(UUID.randomUUID());
        when(team.getId()).thenReturn(UUID.randomUUID());
        CtxSurface existing = new CtxSurface(
                batch,
                repository.getId(),
                team.getId(),
                SurfaceRole.PROVIDES,
                "existing interface",
                SurfaceDetection.SYFT);
        UUID id = UUID.randomUUID();
        var response = new AssistedResponse.Discovery(List.of(
                new AssistedResponse.Observation(
                        id,
                        SurfaceRole.UNKNOWN,
                        "existing",
                        List.of(
                                new AssistedResponse.Fact(AttributeKind.ENDPOINT, "endpoint", "source.txt"),
                                new AssistedResponse.Fact(AttributeKind.IDENTIFIER, "message", "source.txt"))),
                new AssistedResponse.Observation(
                        UUID.randomUUID(),
                        SurfaceRole.REQUIRES,
                        "wrong",
                        List.of(new AssistedResponse.Fact(AttributeKind.IDENTIFIER, "wrong", "source.txt"))),
                new AssistedResponse.Observation(
                        null,
                        SurfaceRole.UNKNOWN,
                        "unestablished",
                        List.of(new AssistedResponse.Fact(AttributeKind.IDENTIFIER, "ambiguous", "source.txt"))),
                new AssistedResponse.Observation(
                        null,
                        SurfaceRole.UNKNOWN,
                        "invalid",
                        List.of(new AssistedResponse.Fact(AttributeKind.IDENTIFIER, "invented", "missing.txt")))));
        var logger = mock(IngestionBatchLogService.BatchLogger.class);
        assertThat(service.persist(batch, root, Map.of(id, existing), response, logger, List.of(), Set.of()))
                .isEqualTo(3);
        assertThat(existing.getAttributeRows()).hasSize(3).allSatisfy(row -> assertThat(row.getDetectedBy())
                .isEqualTo(SurfaceDetection.MODEL));
        verify(attributes).saveAll(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CtxSurface>> persistedCaptor = ArgumentCaptor.forClass(List.class);
        verify(extraction, times(2)).persist(persistedCaptor.capture());
        List<CtxSurface> created =
                persistedCaptor.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(created)
                .extracting(CtxSurface::getDescription, CtxSurface::getRole)
                .containsExactlyInAnyOrder(
                        tuple("wrong", SurfaceRole.REQUIRES), tuple("unestablished", SurfaceRole.UNKNOWN));
        verify(logger, never()).info(any(), any());
    }

    @Test
    void resolvesUniquePathSuffixAndSuggestsAmbiguousMatches() {
        assertThat(AssistedSurfaceService.resolveFile("application.yml", List.of("src/main/resources/application.yml")))
                .isEqualTo("src/main/resources/application.yml");
        assertThat(AssistedSurfaceService.resolveFile(
                        "application.yml",
                        List.of("src/main/resources/application.yml", "src/test/resources/application.yml")))
                .isNull();
        assertThat(AssistedSurfaceService.suggestFile(
                        "application.yml",
                        List.of("src/main/resources/application.yml", "src/test/resources/application.yml")))
                .isEqualTo("src/main/resources/application.yml");
    }

    @Test
    void discoveryCreatesGroundedSurfacesAndRejectsOnlyUnsafeAnchors() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("tests"));
        Files.writeString(root.resolve("src/config.yaml"), "queue: orders");
        Files.writeString(root.resolve("tests/config.yaml"), "queue: fake");
        Path outside = Files.createTempFile("surface-outside", ".txt");
        try {
            Files.createSymbolicLink(root.resolve("src/link"), outside);
            CtxSurfaceAttributeRepository attributes = mock(CtxSurfaceAttributeRepository.class);
            SurfaceExtractionService extraction = mock(SurfaceExtractionService.class);
            List<CtxSurface> createdSurfaces = new ArrayList<>();
            doAnswer(invocation -> {
                        createdSurfaces.addAll(invocation.getArgument(0));
                        return null;
                    })
                    .when(extraction)
                    .persist(any());
            var service = testService(mock(CtxSurfaceRepository.class), attributes, extraction);
            IngestionBatch batch = batch();
            var response = new AssistedResponse.Discovery(List.of(
                    observation(
                            "queue",
                            List.of(
                                    fact(AttributeKind.TECHNOLOGY, "NewBroker", "src/config.yaml"),
                                    fact(AttributeKind.IDENTIFIER, "orders", "src/config.yaml"),
                                    fact(AttributeKind.IDENTIFIER, "orders", "src/config.yaml"))),
                    observation("test", List.of(fact(AttributeKind.IDENTIFIER, "fake", "tests/config.yaml"))),
                    observation(
                            "mixed",
                            List.of(
                                    fact(AttributeKind.IDENTIFIER, "real", "src/config.yaml"),
                                    fact(AttributeKind.IDENTIFIER, "fake", "tests/config.yaml"))),
                    observation(
                            "traversal", List.of(fact(AttributeKind.IDENTIFIER, "bad", "../" + outside.getFileName()))),
                    observation("symlink", List.of(fact(AttributeKind.IDENTIFIER, "bad", "src/link"))),
                    observation("empty", List.of(fact(AttributeKind.IDENTIFIER, " ", "src/config.yaml"))),
                    observation("invalid", List.of(fact(AttributeKind.IDENTIFIER, "bad", String.valueOf((char) 0)))),
                    observation(" ", List.of(fact(AttributeKind.IDENTIFIER, "valid", "src/config.yaml")))));
            var logger = mock(IngestionBatchLogService.BatchLogger.class);

            assertThat(service.persist(batch, root, Map.of(), response, logger, List.of(), Set.of()))
                    .isEqualTo(2);
            assertThat(createdSurfaces).hasSize(2);
            assertThat(createdSurfaces).anySatisfy(surface -> {
                assertThat(surface.getRole()).isEqualTo(SurfaceRole.REQUIRES);
                assertThat(surface.getDescription()).isEqualTo("queue");
                assertThat(surface.getDetectedBy()).isEqualTo(SurfaceDetection.MODEL);
                assertThat(surface.getAttributeRows())
                        .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                        .containsExactlyInAnyOrder(
                                tuple(AttributeKind.TECHNOLOGY, "NewBroker"),
                                tuple(AttributeKind.IDENTIFIER, "orders"),
                                tuple(AttributeKind.FILE, "src/config.yaml"));
            });
            assertThat(createdSurfaces).anySatisfy(surface -> {
                assertThat(surface.getDescription()).isEqualTo("mixed");
                assertThat(surface.getAttributeRows())
                        .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                        .containsExactlyInAnyOrder(
                                tuple(AttributeKind.IDENTIFIER, "real"), tuple(AttributeKind.FILE, "src/config.yaml"));
            });
            assertThat(createdSurfaces)
                    .extracting(CtxSurface::getDescription)
                    .containsExactlyInAnyOrder("queue", "mixed");
            verify(attributes, never()).saveAll(any());
            verify(logger).info("ASSIST_SURFACES", "Skipped 1 test-anchored observations.");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void enrichmentDeduplicatesModelFactsButRetainsDistinctProvenance() throws Exception {
        Files.writeString(root.resolve("config.yaml"), "queue: orders");
        CtxSurfaceAttributeRepository attributes = mock(CtxSurfaceAttributeRepository.class);
        SurfaceExtractionService extraction = mock(SurfaceExtractionService.class);
        var service = testService(mock(CtxSurfaceRepository.class), attributes, extraction);
        IngestionBatch batch = batch();
        CtxSurface surface = new CtxSurface(
                batch,
                batch.getRepository().getId(),
                batch.getRepository().getTeam().getId(),
                SurfaceRole.UNKNOWN,
                "package",
                SurfaceDetection.SYFT);
        surface.addAttribute(
                new CtxSurfaceAttribute(surface, AttributeKind.IDENTIFIER, "orders", SurfaceDetection.SYFT));
        UUID id = UUID.randomUUID();
        var response = new AssistedResponse.Discovery(List.of(new AssistedResponse.Observation(
                id,
                SurfaceRole.PROVIDES,
                "ignored",
                List.of(
                        fact(AttributeKind.IDENTIFIER, "orders", "config.yaml"),
                        fact(AttributeKind.IDENTIFIER, "orders", "config.yaml")))));
        var logger = mock(IngestionBatchLogService.BatchLogger.class);

        assertThat(service.persist(batch, root, Map.of(id, surface), response, logger, List.of(), Set.of()))
                .isEqualTo(1);
        assertThat(service.persist(batch, root, Map.of(id, surface), response, logger, List.of(), Set.of()))
                .isEqualTo(1);
        assertThat(surface.getRole()).isEqualTo(SurfaceRole.UNKNOWN);
        assertThat(surface.getAttributeRows())
                .hasSize(3)
                .extracting(
                        CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue, CtxSurfaceAttribute::getDetectedBy)
                .containsExactlyInAnyOrder(
                        tuple(AttributeKind.IDENTIFIER, "orders", SurfaceDetection.SYFT),
                        tuple(AttributeKind.IDENTIFIER, "orders", SurfaceDetection.MODEL),
                        tuple(AttributeKind.FILE, "config.yaml", SurfaceDetection.MODEL));
        verify(extraction, never()).persist(any());
    }

    @Test
    void responseRejectsMissingRequiredFieldsAndCopiesLists() {
        var mutable = new ArrayList<AssistedResponse.Fact>();
        mutable.add(fact(AttributeKind.IDENTIFIER, "orders", "config.yaml"));
        var observation = observation("orders", mutable);
        mutable.clear();
        assertThat(observation.attributes()).hasSize(1);
        assertThatNullPointerException().isThrownBy(() -> new AssistedResponse.Discovery(null));
        assertThat(new AssistedResponse.Observation(null, null, "x", List.of()).role())
                .isEqualTo(SurfaceRole.UNKNOWN);
        assertThatNullPointerException()
                .isThrownBy(() -> new AssistedResponse.Observation(null, SurfaceRole.UNKNOWN, null, List.of()));
        assertThatNullPointerException()
                .isThrownBy(() -> new AssistedResponse.Observation(null, SurfaceRole.UNKNOWN, "x", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new AssistedResponse.Fact(null, "x", "f"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AssistedResponse.Fact(AttributeKind.IDENTIFIER, null, "f"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AssistedResponse.Fact(AttributeKind.IDENTIFIER, "x", null));
        assertThat(new AssistedResponse.Fact(AttributeKind.FILE, "path/a.txt", null).file())
                .isEqualTo("path/a.txt");
        assertThat(new AssistedResponse.Fact(AttributeKind.FILE, null, "path/a.txt").value())
                .isEqualTo("path/a.txt");
    }

    @Test
    void validatesDiscoverySurfaceReferencesAndAnchors() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/client.ts"), "export const client = true;");
        CtxSurface candidate = new CtxSurface(
                batch(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                SurfaceRole.UNKNOWN,
                "Unclassified cross-file calls in src/client",
                SurfaceDetection.CMM);
        UUID candidateId = UUID.randomUUID();
        var unknownId = new AssistedResponse.Discovery(List.of(new AssistedResponse.Observation(
                UUID.randomUUID(),
                SurfaceRole.REQUIRES,
                "websocket client",
                List.of(fact(AttributeKind.ENDPOINT, "runtime URL", "src/client.ts")))));
        assertThatNoException()
                .isThrownBy(() -> AssistedSurfaceService.validateDiscoveryResponse(
                        unknownId, root, Map.of(candidateId, candidate), List.of()));

        var groupedCandidate = new AssistedResponse.Discovery(List.of(new AssistedResponse.Observation(
                candidateId,
                SurfaceRole.REQUIRES,
                "websocket client",
                List.of(fact(AttributeKind.ENDPOINT, "runtime URL", "src/client.ts")))));
        assertThatNoException()
                .isThrownBy(() -> AssistedSurfaceService.validateDiscoveryResponse(
                        groupedCandidate, root, Map.of(candidateId, candidate), List.of()));

        var invalidAnchor = new AssistedResponse.Discovery(List.of(new AssistedResponse.Observation(
                null,
                SurfaceRole.REQUIRES,
                "websocket client",
                List.of(fact(AttributeKind.ENDPOINT, "runtime URL", "missing.ts")))));
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        AssistedSurfaceService.validateDiscoveryResponse(invalidAnchor, root, Map.of(), List.of()))
                .withMessageContaining("Invalid source anchors");

        var valid = new AssistedResponse.Discovery(List.of(new AssistedResponse.Observation(
                null,
                SurfaceRole.REQUIRES,
                "websocket client",
                List.of(fact(AttributeKind.ENDPOINT, "runtime URL", "src/client.ts")))));
        assertThatNoException()
                .isThrownBy(() -> AssistedSurfaceService.validateDiscoveryResponse(valid, root, Map.of(), List.of()));
    }

    @Test
    void rejectsNewSurfaceWithOnlyAnAnchorAndNoInterfaceFact() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/client.ts"), "export const client = true;");
        var response = new AssistedResponse.Discovery(List.of(new AssistedResponse.Observation(
                null,
                SurfaceRole.REQUIRES,
                "Client interface",
                List.of(fact(AttributeKind.FILE, "src/client.ts", "src/client.ts")))));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssistedSurfaceService.validateDiscoveryResponse(response, root, Map.of(), List.of()))
                .withMessageContaining("source fact beyond its file anchor");
    }

    @Test
    void persistsUnknownModelIdsAsNewSurfacesWithoutMergingGroupedCallCandidates() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/client.ts"), "export const client = true;");
        IngestionBatch batch = batch();
        UUID repositoryId = batch.getRepository().getId();
        UUID teamId = batch.getRepository().getTeam().getId();
        UUID candidateId = UUID.randomUUID();
        UUID unknownId = UUID.randomUUID();
        CtxSurface candidate = new CtxSurface(
                batch,
                repositoryId,
                teamId,
                SurfaceRole.UNKNOWN,
                "Unclassified cross-file calls in src/client",
                SurfaceDetection.CMM);
        var response = new AssistedResponse.Discovery(List.of(
                new AssistedResponse.Observation(
                        unknownId,
                        SurfaceRole.REQUIRES,
                        "HTTP client to game service",
                        List.of(
                                fact(AttributeKind.ENDPOINT, "runtime URL", "src/client.ts"),
                                fact(AttributeKind.IDENTIFIER, "game session", "src/client.ts"))),
                new AssistedResponse.Observation(
                        candidateId,
                        SurfaceRole.REQUIRES,
                        "WebSocket client",
                        List.of(
                                fact(AttributeKind.TECHNOLOGY, "WebSocket", "src/client.ts"),
                                fact(AttributeKind.IDENTIFIER, "game events", "src/client.ts")))));
        SurfaceExtractionService extraction = mock(SurfaceExtractionService.class);
        List<CtxSurface> persisted = new ArrayList<>();
        doAnswer(invocation -> {
                    persisted.addAll(invocation.getArgument(0));
                    return null;
                })
                .when(extraction)
                .persist(any());
        CtxSurfaceRepository surfaces = mock(CtxSurfaceRepository.class);
        CtxSurfaceAttributeRepository attributes = mock(CtxSurfaceAttributeRepository.class);
        var service = testService(surfaces, attributes, extraction);
        var logger = mock(IngestionBatchLogService.BatchLogger.class);

        assertThat(service.persist(batch, root, Map.of(candidateId, candidate), response, logger, List.of(), Set.of()))
                .isEqualTo(2);

        verify(extraction, times(2)).persist(any());
        assertThat(persisted).hasSize(2).allSatisfy(surface -> {
            assertThat(surface.getDetectedBy()).isEqualTo(SurfaceDetection.MODEL);
            assertThat(surface.getRole()).isEqualTo(SurfaceRole.REQUIRES);
            assertThat(surface.getAttributeRows())
                    .anySatisfy(attribute -> assertThat(attribute.getKind()).isEqualTo(AttributeKind.FILE));
        });
        assertThat(persisted)
                .extracting(CtxSurface::getDescription)
                .containsExactlyInAnyOrder("HTTP client to game service", "WebSocket client");
        assertThat(candidate.getRole()).isEqualTo(SurfaceRole.UNKNOWN);
        verify(logger)
                .debug(
                        "ASSIST_SURFACES",
                        "Treating unknown model surface ID " + unknownId
                                + " (role=REQUIRES, desc=\"HTTP client to game service\", files=[src/client.ts]) as a new, source-anchored surface.");
        verify(logger)
                .debug(
                        "ASSIST_SURFACES",
                        "Treating grouped CMM candidate " + candidateId
                                + " (role=REQUIRES, desc=\"WebSocket client\", files=[src/client.ts]) as evidence for a separate model surface.");
    }

    @Test
    void discoveryInputUsesCompleteFileIndexWithoutPriorSurfaceSelection() {
        IngestionBatch batch = batch();
        UUID teamId = batch.getRepository().getTeam().getId();
        CtxNode config = node(batch, teamId, NodeType.FILE, "infra/main.tf");
        config.setMetadata("{\"language\":\"hcl\",\"cmmLabel\":\"File\"}");
        CtxNode resource = node(batch, teamId, NodeType.CLASS, "infra/main.tf");
        resource.setSymbolName("resource.cloud_service.worker");
        resource.setMetadata("{\"cmmLabel\":\"Class\",\"importance\":12}");
        CtxNode provider = node(batch, teamId, NodeType.FUNCTION, "lib/state/ServerProvider.ts");
        provider.setSymbolName("GameServerProvider");
        provider.setMetadata("{\"cmmLabel\":\"Function\",\"is_entry_point\":true,\"param_names\":[\"gameType\"]}");
        CtxNode client = node(batch, teamId, NodeType.FUNCTION, "lib/net/WebSocketService.ts");
        client.setSymbolName("getConnection");
        client.setMetadata(
                "{\"cmmLabel\":\"Function\",\"is_entry_point\":true,\"return_type\":\"Connection\",\"param_names\":[\"url\"]}");
        CtxNode test = node(batch, teamId, NodeType.MODULE, "test/ServerProvider.test.ts");
        test.setMetadata("{\"language\":\"typescript\",\"is_test\":true}");
        var logger = mock(IngestionBatchLogService.BatchLogger.class);

        String input = AssistedSurfaceService.discoveryInput(
                List.of(config, resource, provider, client, test),
                List.of(
                        "infra/main.tf",
                        "lib/state/ServerProvider.ts",
                        "lib/net/WebSocketService.ts",
                        "test/ServerProvider.test.ts",
                        "cypress/support/mock-server.ts"),
                Set.of("test/ServerProvider.test.ts"),
                logger);

        assertThat(input)
                .contains(
                        "Surface index (no prior surface inventory)",
                        "infra/main.tf [hcl]",
                        "symbol=Class:resource.cloud_service.worker",
                        "entry=Function:GameServerProvider params=gameType",
                        "entry=Function:getConnection returns=Connection params=url")
                .doesNotContain(
                        "test/ServerProvider.test.ts",
                        "cypress/support/mock-server.ts",
                        "Previously extracted surfaces");
    }

    @Test
    void isReadableTextFileRejectsBinaryAndEmptyFilesWithoutExtensionRules() throws Exception {
        Path dir = Files.createTempDirectory("text-sniff");
        Path png = dir.resolve("sprite.png");
        Files.write(png, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x00, 0x0A});
        Path empty = dir.resolve("empty.gitkeep");
        Files.write(empty, new byte[0]);
        Path terraform = dir.resolve("main.tf");
        Files.writeString(terraform, "resource \"aws_s3_bucket\" \"app\" {}\n");

        assertThat(AssistedSurfaceService.isReadableTextFile(png)).isFalse();
        assertThat(AssistedSurfaceService.isReadableTextFile(empty)).isFalse();
        assertThat(AssistedSurfaceService.isReadableTextFile(terraform)).isTrue();
    }

    @Test
    void deserializesCaseInsensitiveEnumsAllowsFileEnrichmentAndCapsExistingSurfaceUpdates() throws Exception {
        Files.writeString(root.resolve("api.yaml"), "openapi: 3.0.0\n");
        ObjectMapper mapper = new ObjectMapper();
        AssistedResponse.Discovery parsed = mapper.readValue("""
                {"surfaces":[{"surfaceId":null,"role":"requires","description":"API","attributes":[{"kind":"endpoint","value":"/orders","file":"api.yaml"}]}]}
                """, AssistedResponse.Discovery.class);
        assertThat(parsed.surfaces().getFirst().role()).isEqualTo(SurfaceRole.REQUIRES);
        assertThat(parsed.surfaces().getFirst().attributes().getFirst().kind()).isEqualTo(AttributeKind.ENDPOINT);
        assertThat(AttributeKind.from("unknown_kind")).isNull();
        assertThat(SurfaceRole.from("unknown_role")).isNull();

        // Enriching an existing non-SYFT surface accepts a FILE-only observation without counting as test-anchored.
        CtxSurfaceAttributeRepository attributes = mock(CtxSurfaceAttributeRepository.class);
        SurfaceExtractionService extraction = mock(SurfaceExtractionService.class);
        var service = testService(mock(CtxSurfaceRepository.class), attributes, extraction);
        IngestionBatch batch = batch();
        CtxSurface fileTarget = new CtxSurface(
                batch,
                batch.getRepository().getId(),
                batch.getRepository().getTeam().getId(),
                SurfaceRole.PROVIDES,
                "existing",
                SurfaceDetection.CMM);
        fileTarget.addAttribute(
                new CtxSurfaceAttribute(fileTarget, AttributeKind.ENDPOINT, "/orders", SurfaceDetection.CMM));
        UUID fileTargetId = UUID.randomUUID();
        var fileOnlyLogger = mock(IngestionBatchLogService.BatchLogger.class);
        var fileOnlyEnrichment = new AssistedResponse.Discovery(List.of(new AssistedResponse.Observation(
                fileTargetId,
                SurfaceRole.PROVIDES,
                "existing",
                List.of(fact(AttributeKind.FILE, "api.yaml", "api.yaml")))));

        assertThat(service.persist(
                        batch,
                        root,
                        Map.of(fileTargetId, fileTarget),
                        fileOnlyEnrichment,
                        fileOnlyLogger,
                        List.of(),
                        Set.of()))
                .isEqualTo(1);
        assertThat(fileTarget.getAttributeRows())
                .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                .containsExactlyInAnyOrder(
                        tuple(AttributeKind.ENDPOINT, "/orders"), tuple(AttributeKind.FILE, "api.yaml"));
        verify(fileOnlyLogger, never()).info("ASSIST_SURFACES", "Skipped 1 test-anchored observations.");

        // Updating an existing non-SYFT surface caps total attributes at MAX_NON_SYFT_ATTRIBUTES (30).
        CtxSurface cappedTarget = new CtxSurface(
                batch,
                batch.getRepository().getId(),
                batch.getRepository().getTeam().getId(),
                SurfaceRole.PROVIDES,
                "capped",
                SurfaceDetection.CMM);
        for (int i = 0; i < 29; i++) {
            cappedTarget.addAttribute(
                    new CtxSurfaceAttribute(cappedTarget, AttributeKind.ENDPOINT, "/ep-" + i, SurfaceDetection.CMM));
        }
        UUID cappedTargetId = UUID.randomUUID();
        var capEnrichment = new AssistedResponse.Discovery(List.of(new AssistedResponse.Observation(
                cappedTargetId,
                SurfaceRole.PROVIDES,
                "capped",
                List.of(
                        fact(AttributeKind.IDENTIFIER, "extra-1", "api.yaml"),
                        fact(AttributeKind.IDENTIFIER, "extra-2", "api.yaml")))));

        assertThat(service.persist(
                        batch,
                        root,
                        Map.of(cappedTargetId, cappedTarget),
                        capEnrichment,
                        mock(IngestionBatchLogService.BatchLogger.class),
                        List.of(),
                        Set.of()))
                .isEqualTo(1);
        assertThat(cappedTarget.getAttributeRows()).hasSize(GraphSurfaceTranslator.MAX_NON_SYFT_ATTRIBUTES);
    }

    @Test
    void promptDocumentsPayloadIdentifiersAndWorkloadVsInfraRoles() {
        assertThat(AssistedSurfaceService.prompt())
                .contains("when visible, request/response payload or schema names")
                .contains("always emit TWO separate observations")
                .contains("payload/schema alongside superclass, event-family")
                .contains("BIDIRECTIONAL observation for the runtime communication contract")
                .contains("Repo-local internals that do not cross repository boundaries")
                .contains("defines deployable application build/workload manifests")
                .contains("Focus on external communication, dependency, and deployment interfaces")
                .contains("Batch multiple read_file, search_files, and list_files calls")
                .contains("event/message/payload/schema/channel names");
    }

    private static AssistedSurfaceService testService(
            CtxSurfaceRepository surfaces,
            CtxSurfaceAttributeRepository attributes,
            SurfaceExtractionService extraction) {
        return new AssistedSurfaceService(
                mock(IngestionBatchRepository.class),
                surfaces,
                attributes,
                mock(CtxNodeRepository.class),
                mock(CtxEdgeRepository.class),
                extraction,
                null,
                null,
                null,
                null,
                mock(ReadFileTool.class),
                mock(ListRepositoryFilesTool.class),
                mock(SearchRepositoryFilesTool.class),
                mock(PlatformTransactionManager.class),
                Runnable::run);
    }

    private static AssistedResponse.Fact fact(AttributeKind kind, String value, String file) {
        return new AssistedResponse.Fact(kind, value, file);
    }

    private static AssistedResponse.Observation observation(String description, List<AssistedResponse.Fact> facts) {
        return new AssistedResponse.Observation(null, SurfaceRole.REQUIRES, description, facts);
    }

    private static CtxNode node(IngestionBatch batch, UUID teamId, NodeType type, String path) {
        return new CtxNode(batch, teamId, "repo", type, path);
    }

    private static IngestionBatch batch() {
        IngestionBatch batch = mock(IngestionBatch.class);
        Repository repository = mock(Repository.class);
        Team team = mock(Team.class);
        when(batch.getRepository()).thenReturn(repository);
        when(repository.getTeam()).thenReturn(team);
        when(repository.getId()).thenReturn(UUID.randomUUID());
        when(team.getId()).thenReturn(UUID.randomUUID());
        return batch;
    }
}
