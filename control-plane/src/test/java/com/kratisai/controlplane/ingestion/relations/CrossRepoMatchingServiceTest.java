package com.kratisai.controlplane.ingestion.relations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.BatchChatClientFactory;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService.BatchLogger;
import com.kratisai.controlplane.model.AttributeKind;
import com.kratisai.controlplane.model.Ceiling;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Nature;
import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.testsupport.llm.FakeChatModel;
import com.kratisai.controlplane.testsupport.llm.PromptMatcher;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CrossRepoMatchingServiceTest {
    private final UUID leftRepo = UUID.randomUUID();
    private final UUID rightRepo = UUID.randomUUID();
    private final UUID leftSurface = UUID.randomUUID();
    private final UUID rightSurface = UUID.randomUUID();
    private final UUID leftAttribute = UUID.randomUUID();
    private final UUID rightAttribute = UUID.randomUUID();
    private final CrossRepoMatchingService.SurfaceView left =
            surface(leftSurface, leftAttribute, "unfamiliar client", SurfaceRole.PROVIDES);
    private final CrossRepoMatchingService.SurfaceView right =
            surface(rightSurface, rightAttribute, "other technology", SurfaceRole.REQUIRES);
    private final CrossRepoMatchingService.RepoView subject = repo(leftRepo, left);
    private final CrossRepoMatchingService.RepoView other = repo(rightRepo, right);

    @Test
    void acceptsDifferentTechnologyLabelsWithValidEvidence() {
        assertThat(validate(match(leftRepo, leftSurface, rightRepo, rightSurface, leftAttribute, rightAttribute, "f1")))
                .isNull();
    }

    @Test
    void forwardFixesMultiSurfaceAndOwnerRescueSkipsUnsatisfiableRetriesAndRetriesOnlyRecoverableErrors() {
        var harness = new Harness();
        UUID s2Id = UUID.randomUUID();
        UUID s2AttrId = UUID.randomUUID();
        UUID o2Id = UUID.randomUUID();
        UUID o2AttrId = UUID.randomUUID();
        var s1 = surface(leftSurface, leftAttribute, "s3://${{ secrets.BUCKET }}", SurfaceRole.REQUIRES, "IDENTIFIER");
        var s2 = surface(s2Id, s2AttrId, "shared-cache", SurfaceRole.REQUIRES, "IDENTIFIER");
        var o1 = surface(rightSurface, rightAttribute, "repo:glark-newco/app:*", SurfaceRole.REQUIRES, "IDENTIFIER");
        var o2 = surface(o2Id, o2AttrId, "shared-cache", SurfaceRole.REQUIRES, "IDENTIFIER");
        var subjectView = new CrossRepoMatchingService.RepoView(
                subject.batchId(), leftRepo, "app", subject.synopsis(), List.of(s1, s2));
        var otherView = new CrossRepoMatchingService.RepoView(
                other.batchId(), rightRepo, "infra", other.synopsis(), List.of(o1, o2));

        // 1) Multi-surface "s1, s2" expands; s1<->o1 succeeds, redundant invalid s2<->o1 HOSTS is suppressed by
        // covered nature.
        // 2) Off-by-one surface "s1" with ["s2.a2"] is forward-rescued to s2 for SHARES_STATE and normalized to
        // BIDIRECTIONAL.
        // 3) Unsatisfiable CALLS (no PROVIDES surface in either repo) is discarded in 0 retries!
        String singleRoundResponse = """
                {"matches":[
                  {"subjectRepositoryId":"A","subjectSurfaceId":"s1, s2","otherRepositoryId":"B","otherSurfaceId":"o1","nature":"HOSTS","direction":"A_TO_B","confidence":"CONFIRMED","rationale":"App deploys to S3/OIDC","subjectAttributeIds":["s1.a1"],"otherAttributeIds":["o1.b1, o2.b2"],"subjectSynopsisIds":["f1, f1"],"otherSynopsisIds":["f1"]},
                  {"subjectRepositoryId":"A","subjectSurfaceId":"s1","otherRepositoryId":"B","otherSurfaceId":"o2","nature":"SHARES_STATE","direction":"A_TO_B","confidence":"CONFIRMED","rationale":"Shared cache","subjectAttributeIds":["s2.a2"],"otherAttributeIds":["o2.b2"],"subjectSynopsisIds":[],"otherSynopsisIds":[]},
                  {"subjectRepositoryId":"A","subjectSurfaceId":"s1","otherRepositoryId":"B","otherSurfaceId":"o1","nature":"CALLS","direction":"A_TO_B","confidence":"SUGGESTED","rationale":"Invalid role pair","subjectAttributeIds":["s1.a1"],"otherAttributeIds":["o1.b1"],"subjectSynopsisIds":[],"otherSynopsisIds":[]}
                ]}
                """;
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response(singleRoundResponse)
                .maxMatches(1)
                .build());

        var result = harness.service.infer(subject.batchId(), subjectView, List.of(otherView), harness.logger);

        assertThat(harness.model.getInvocations()).hasSize(1);
        assertThat(result.discarded()).isEqualTo(1);
        assertThat(result.accepted()).hasSize(2);
        assertThat(result.accepted()).anySatisfy(item -> {
            assertThat(item.proposal().nature()).isEqualTo(Nature.HOSTS);
            assertThat(item.proposal().direction()).isEqualTo(Direction.A_TO_B);
            assertThat(item.proposal().otherAttributeIds()).containsExactly(rightAttribute.toString());
        });
        assertThat(result.accepted()).anySatisfy(item -> {
            assertThat(item.proposal().nature()).isEqualTo(Nature.SHARES_STATE);
            assertThat(item.proposal().subjectSurfaceId()).isEqualTo(s2Id.toString());
            assertThat(item.proposal().direction()).isEqualTo(Direction.BIDIRECTIONAL);
        });

        // Now include a recoverable MESSAGES candidate (empty attribute citation) -> triggers 1 correction turn & keeps
        // Pass-1 matches.
        harness.model.reset();
        String withRecoverable = """
                {"matches":[
                  {"subjectRepositoryId":"A","subjectSurfaceId":"s1","otherRepositoryId":"B","otherSurfaceId":"o1","nature":"HOSTS","direction":"A_TO_B","confidence":"CONFIRMED","rationale":"App deploys","subjectAttributeIds":["s1.a1"],"otherAttributeIds":["o1.b1"],"subjectSynopsisIds":[],"otherSynopsisIds":[]},
                  {"subjectRepositoryId":"A","subjectSurfaceId":"s2","otherRepositoryId":"B","otherSurfaceId":"o2","nature":"MESSAGES","direction":"B_TO_A","confidence":"CONFIRMED","rationale":"Events","subjectAttributeIds":[],"otherAttributeIds":["o2.b2"],"subjectSynopsisIds":[],"otherSynopsisIds":[]}
                ]}
                """;
        String fixedMessagesAndMissed = """
                {"matches":[
                  {"subjectRepositoryId":"A","subjectSurfaceId":"s2","otherRepositoryId":"B","otherSurfaceId":"o2","nature":"MESSAGES","direction":"B_TO_A","confidence":"CONFIRMED","rationale":"Events","subjectAttributeIds":["s2.a2"],"otherAttributeIds":["o2.b2"],"subjectSynopsisIds":[],"otherSynopsisIds":[]},
                  {"subjectRepositoryId":"A","subjectSurfaceId":"s2","otherRepositoryId":"B","otherSurfaceId":"o2","nature":"SHARES_STATE","direction":"BIDIRECTIONAL","confidence":"CONFIRMED","rationale":"Shared cache","subjectAttributeIds":["s2.a2"],"otherAttributeIds":["o2.b2"],"subjectSynopsisIds":[],"otherSynopsisIds":[]}
                ]}
                """;
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response(withRecoverable)
                .maxMatches(1)
                .build());
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Previous matches rejected by validation:")
                .response(fixedMessagesAndMissed)
                .maxMatches(1)
                .build());

        var retriedResult = harness.service.infer(subject.batchId(), subjectView, List.of(otherView), harness.logger);
        assertThat(harness.model.getInvocations()).hasSize(2);
        String correctionPrompt = harness.model.getInvocations().get(1).prompt().getContents();
        assertThat(correctionPrompt)
                .contains("Already accepted from your previous pass")
                .contains("- HOSTS: s1 <-> o1")
                .contains("proposed: subject=s2 attrs=[], other=o2 attrs=[o2.b2]")
                .contains(
                        "Repair and return all rejected matches above, plus any additional valid relationships that were missed on the first pass")
                .doesNotContain("Omit any unsupported match");
        assertThat(retriedResult.discarded()).isZero();
        assertThat(retriedResult.accepted())
                .extracting(item -> item.proposal().nature())
                .containsExactlyInAnyOrder(Nature.HOSTS, Nature.MESSAGES, Nature.SHARES_STATE);
    }

    @Test
    void acceptsMessagesAcrossAnyRolesAndNormalizesDirectionToBidirectional() {
        var clientEvents = surface(leftSurface, leftAttribute, "assignLink", SurfaceRole.BIDIRECTIONAL, "IDENTIFIER");
        var wsServer = surface(rightSurface, rightAttribute, "/events", SurfaceRole.UNKNOWN, "ENDPOINT");
        var sameRoleMessages = detailed(
                Nature.MESSAGES,
                Direction.B_TO_A,
                Ceiling.CONFIRMED,
                "SupplyChain exchanges player events with GameService",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of("f1"),
                List.of("f1"));
        assertThat(validate(sameRoleMessages, clientEvents, wsServer)).isNull();
        assertThat(CrossRepoMatchingService.normalizeDirection(
                                sameRoleMessages, List.of(clientEvents), List.of(wsServer))
                        .direction())
                .isEqualTo(Direction.BIDIRECTIONAL);

        var snsPublisher =
                surface(leftSurface, leftAttribute, "game-service-events", SurfaceRole.REQUIRES, "IDENTIFIER");
        var sqsSubscriber =
                surface(rightSurface, rightAttribute, "game-service-events", SurfaceRole.REQUIRES, "IDENTIFIER");
        var brokerMessages = detailed(
                Nature.MESSAGES,
                null,
                Ceiling.CONFIRMED,
                "Services exchange events over shared topic",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of("f1"),
                List.of("f1"));
        assertThat(CrossRepoMatchingService.normalizeDirection(
                                brokerMessages, List.of(snsPublisher), List.of(sqsSubscriber))
                        .direction())
                .isEqualTo(Direction.BIDIRECTIONAL);

        var methodHookClient =
                surface(leftSurface, leftAttribute, "publishPlayerAction", SurfaceRole.REQUIRES, "METHOD");
        var methodMessages = detailed(
                Nature.MESSAGES,
                Direction.UNKNOWN,
                Ceiling.CONFIRMED,
                "Signlines exchanges player actions with GameService",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of(),
                List.of());
        assertThat(validate(methodMessages, methodHookClient, wsServer)).isNull();
        assertThat(CrossRepoMatchingService.normalizeDirection(
                                methodMessages, List.of(methodHookClient), List.of(wsServer))
                        .direction())
                .isEqualTo(Direction.BIDIRECTIONAL);
    }

    @Test
    void rejectsSurfaceRoleAsNatureWithoutFailingTheBatch() {
        var harness = new Harness();
        String response = """
                {"matches":[{"subjectRepositoryId":"A","subjectSurfaceId":"s1","otherRepositoryId":"B","otherSurfaceId":"o1","nature":"REQUIRES","direction":"A_TO_B","confidence":"SUGGESTED","rationale":"shared interface","subjectAttributeIds":["a1"],"otherAttributeIds":["b1"],"subjectSynopsisIds":["f1"],"otherSynopsisIds":["f1"]}]}
                """;
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response(response)
                .maxMatches(1)
                .build());
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Previous matches rejected by validation:")
                .response(response)
                .maxMatches(1)
                .build());

        var result = harness.service.infer(subject.batchId(), subject, List.of(other), harness.logger);

        assertThat(result.accepted()).isEmpty();
        assertThat(result.discarded()).isEqualTo(1);
    }

    @Test
    void rejectsMalformedSurfaceIdShapesWithoutFailingTheBatch() {
        var harness = new Harness();
        String response = """
                {"matches":[{"subjectRepositoryId":"A","subjectSurfaceId":"s1","otherRepositoryId":"B","otherSurfaceId":["o1"],"nature":"CALLS","direction":"A_TO_B","confidence":"SUGGESTED","rationale":"shared interface","subjectAttributeIds":["a1"],"otherAttributeIds":["b1"],"subjectSynopsisIds":["f1"],"otherSynopsisIds":["f1"]}]}
                """;
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response(response)
                .maxMatches(1)
                .build());
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Previous matches rejected by validation:")
                .response(response)
                .maxMatches(1)
                .build());

        var result = harness.service.infer(subject.batchId(), subject, List.of(other), harness.logger);

        assertThat(result.accepted()).isEmpty();
        assertThat(result.discarded()).isEqualTo(1);
    }

    @Test
    void rejectsMalformedAttributeIdsWithoutFailingTheBatch() {
        var harness = new Harness();
        String response = """
                {"matches":[{"subjectRepositoryId":"A","subjectSurfaceId":"s1","otherRepositoryId":"B","otherSurfaceId":"o1","nature":"CALLS","direction":"A_TO_B","confidence":"SUGGESTED","rationale":"shared interface","subjectAttributeIds":["a99"],"otherAttributeIds":["b1"],"subjectSynopsisIds":["f1"],"otherSynopsisIds":["f1"]}]}
                """;
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response(response)
                .maxMatches(1)
                .build());
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Previous matches rejected by validation:")
                .response(response)
                .maxMatches(1)
                .build());

        var result = harness.service.infer(subject.batchId(), subject, List.of(other), harness.logger);

        assertThat(result.accepted()).isEmpty();
        assertThat(result.discarded()).isEqualTo(1);
    }

    @Test
    void rejectsWrongRepositoriesSurfacesAndEvidence() {
        assertThat(validate(match(
                        UUID.randomUUID(), leftSurface, rightRepo, rightSurface, leftAttribute, rightAttribute, "f1")))
                .contains("repository");
        assertThat(validate(match(
                        leftRepo, UUID.randomUUID(), rightRepo, rightSurface, leftAttribute, rightAttribute, "f1")))
                .contains("surface");
        assertThat(validate(match(leftRepo, leftSurface, rightRepo, rightSurface, rightAttribute, leftAttribute, "f1")))
                .contains("evidence");
        assertThat(validate(match(leftRepo, leftSurface, rightRepo, rightSurface, leftAttribute, rightAttribute, "x1")))
                .contains("evidence");
    }

    @Test
    void partitionsAllSurfacesWithoutTruncationByRenderedLength() {
        List<CrossRepoMatchingService.SurfaceView> all = IntStream.range(0, 53)
                .mapToObj(i -> surface(UUID.randomUUID(), UUID.randomUUID(), "surface " + i))
                .toList();
        var groups = CrossRepoMatchingService.groups(all, 1200);
        assertThat(groups).hasSizeGreaterThan(1);
        assertThat(groups.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(all);
        assertThat(CrossRepoMatchingService.groups(List.of(), 1200)).isEmpty();
    }

    @Test
    void partitionsByRenderedTextSizeAndRejectsOversizedIndividualSurface() {
        var first = surface(UUID.randomUUID(), UUID.randomUUID(), "x".repeat(9000));
        var second = surface(UUID.randomUUID(), UUID.randomUUID(), "y".repeat(9000));
        assertThat(CrossRepoMatchingService.groups(List.of(first, second), 16000))
                .containsExactly(List.of(first), List.of(second));
        var oversized = surface(UUID.randomUUID(), UUID.randomUUID(), "z".repeat(16000));
        assertThatThrownBy(() -> CrossRepoMatchingService.groups(List.of(oversized), 16000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("comparison omitted");
    }

    @Test
    void descriptionAloneIsNotContractEvidence() {
        var withoutAttributes = new CrossRepoMatchingService.SurfaceView(
                leftSurface, SurfaceRole.PROVIDES, "Runtime interface", SurfaceDetection.MODEL, List.of());
        var match = new InferenceResponse.RelationProposal(
                leftRepo.toString(),
                leftSurface.toString(),
                rightRepo.toString(),
                rightSurface.toString(),
                Nature.CALLS,
                Direction.A_TO_B,
                Ceiling.SUGGESTED,
                "Compatible",
                List.of(),
                List.of(rightAttribute.toString()),
                List.of(),
                List.of());
        assertThat(CrossRepoMatchingService.validate(
                        match, repo(leftRepo, withoutAttributes), List.of(withoutAttributes), other, List.of(right)))
                .contains("CALLS requires cited non-FILE evidence");
    }

    @Test
    void rejectsMissingFieldsAndInvalidSymmetricDirections() {
        for (InferenceResponse.RelationProposal invalid : Arrays.asList(
                detailed(
                        null,
                        Direction.A_TO_B,
                        Ceiling.CONFIRMED,
                        "rationale",
                        List.of(leftAttribute),
                        List.of(rightAttribute),
                        List.of(),
                        List.of()),
                detailed(
                        Nature.CALLS,
                        null,
                        Ceiling.CONFIRMED,
                        "rationale",
                        List.of(leftAttribute),
                        List.of(rightAttribute),
                        List.of(),
                        List.of()),
                detailed(
                        Nature.CALLS,
                        Direction.A_TO_B,
                        null,
                        "rationale",
                        List.of(leftAttribute),
                        List.of(rightAttribute),
                        List.of(),
                        List.of()),
                detailed(
                        Nature.CALLS,
                        Direction.A_TO_B,
                        Ceiling.CONFIRMED,
                        null,
                        List.of(leftAttribute),
                        List.of(rightAttribute),
                        List.of(),
                        List.of()),
                detailed(
                        Nature.CALLS,
                        Direction.A_TO_B,
                        Ceiling.CONFIRMED,
                        "  ",
                        List.of(leftAttribute),
                        List.of(rightAttribute),
                        List.of(),
                        List.of()))) {
            assertThat(validate(invalid)).contains("required");
        }
        for (Nature nature : List.of(Nature.MESSAGES, Nature.PEER, Nature.SHARES_STATE)) {
            for (Direction llmDirection :
                    new Direction[] {Direction.A_TO_B, Direction.B_TO_A, Direction.UNKNOWN, null}) {
                var normalized = CrossRepoMatchingService.normalizeDirection(
                        detailed(
                                nature,
                                llmDirection,
                                Ceiling.CONFIRMED,
                                "supported",
                                List.of(leftAttribute),
                                List.of(rightAttribute),
                                List.of("f1"),
                                List.of("f1")),
                        List.of(left),
                        List.of(right));
                assertThat(normalized.direction()).isEqualTo(Direction.BIDIRECTIONAL);
            }
        }
    }

    @Test
    void rejectsCallsWithIncompatibleRolesAndNormalizesDirection() {
        var bothRequire = surface(rightSurface, rightAttribute, "consumer", SurfaceRole.REQUIRES);
        var leftRepoView = repo(leftRepo, bothRequire);
        var proposal = match(leftRepo, rightSurface, rightRepo, rightSurface, rightAttribute, rightAttribute, "f1");
        assertThat(CrossRepoMatchingService.validate(
                        proposal, leftRepoView, List.of(bothRequire), other, List.of(right)))
                .contains("one PROVIDES surface and one REQUIRES surface");

        var wrongDirection = match(leftRepo, leftSurface, rightRepo, rightSurface, leftAttribute, rightAttribute, "f1");
        wrongDirection = new InferenceResponse.RelationProposal(
                wrongDirection.subjectRepositoryId(),
                wrongDirection.subjectSurfaceId(),
                wrongDirection.otherRepositoryId(),
                wrongDirection.otherSurfaceId(),
                wrongDirection.nature(),
                Direction.B_TO_A,
                wrongDirection.confidence(),
                wrongDirection.rationale(),
                wrongDirection.subjectAttributeIds(),
                wrongDirection.otherAttributeIds(),
                wrongDirection.subjectSynopsisIds(),
                wrongDirection.otherSynopsisIds());
        assertThat(validate(wrongDirection)).isNull();
        assertThat(CrossRepoMatchingService.normalizeDirection(wrongDirection, List.of(left), List.of(right))
                        .direction())
                .isEqualTo(Direction.A_TO_B);
    }

    @Test
    void persistsExactDirectPackageMatchesAcrossSourcesAndTrimsUnmatchedSyftPurlsFromPrompt() {
        var provider = new CrossRepoMatchingService.SurfaceView(
                leftSurface,
                SurfaceRole.PROVIDES,
                "Package identity declared by package.json",
                SurfaceDetection.MODEL,
                List.of(new CrossRepoMatchingService.AttributeView(
                        leftAttribute,
                        "IDENTIFIER",
                        "pkg:npm/%40glark-newco/game-library@0.0.0",
                        SurfaceDetection.MODEL)));
        UUID manifestFileId = UUID.randomUUID();
        UUID thirdPartyPurlId = UUID.randomUUID();
        var consumer = new CrossRepoMatchingService.SurfaceView(
                rightSurface,
                SurfaceRole.REQUIRES,
                "npm dependency manifest package-lock.json",
                SurfaceDetection.SYFT,
                List.of(
                        new CrossRepoMatchingService.AttributeView(
                                rightAttribute,
                                "IDENTIFIER",
                                "pkg:npm/%40glark-newco/game-library@0.0.0-snapshot.95-1",
                                SurfaceDetection.SYFT),
                        new CrossRepoMatchingService.AttributeView(
                                thirdPartyPurlId, "IDENTIFIER", "pkg:npm/react@19.0.0", SurfaceDetection.SYFT),
                        new CrossRepoMatchingService.AttributeView(
                                manifestFileId, "FILE", "package-lock.json", SurfaceDetection.SYFT)));
        var subjectView = new CrossRepoMatchingService.RepoView(
                subject.batchId(), leftRepo, "GameLibrary", subject.synopsis(), List.of(provider));
        var otherView = new CrossRepoMatchingService.RepoView(
                other.batchId(), rightRepo, "SupplyChain", other.synopsis(), List.of(consumer));
        String duplicate = """
                {"matches":[{"subjectRepositoryId":"A","subjectSurfaceId":"s1","otherRepositoryId":"B","otherSurfaceId":"o1","nature":"BUILDS_AGAINST","direction":"B_TO_A","confidence":"SUGGESTED","rationale":"same direct package","subjectAttributeIds":["a1"],"otherAttributeIds":["b1"],"subjectSynopsisIds":[],"otherSynopsisIds":[]}]}
                """;
        var harness = new Harness();
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response(duplicate)
                .maxMatches(1)
                .build());

        var result = harness.service.infer(subject.batchId(), subjectView, List.of(otherView), harness.logger);

        assertThat(result.accepted()).singleElement().satisfies(accepted -> {
            assertThat(accepted.proposal().nature()).isEqualTo(Nature.BUILDS_AGAINST);
            assertThat(accepted.proposal().confidence()).isEqualTo(Ceiling.CONFIRMED);
            assertThat(accepted.proposal().subjectSurfaceId()).isEqualTo(leftSurface.toString());
            assertThat(accepted.proposal().otherSurfaceId()).isEqualTo(rightSurface.toString());
        });
        assertThat(harness.model.getInvocations().getFirst().prompt().getContents())
                .contains("package-lock.json")
                .doesNotContain("pkg:npm/react@19.0.0");

        // When a deterministic candidate cannot be validated (e.g. null attribute ID), defer to the LLM instead of
        // aborting.
        var unvalidatableProvider = new CrossRepoMatchingService.SurfaceView(
                leftSurface,
                SurfaceRole.PROVIDES,
                "Package identity",
                SurfaceDetection.SYFT,
                List.of(new CrossRepoMatchingService.AttributeView(
                        null, "IDENTIFIER", "pkg:npm/%40glark-newco/game-library@0.0.0", SurfaceDetection.SYFT)));
        var fallbackSubject = new CrossRepoMatchingService.RepoView(
                subject.batchId(), leftRepo, "GameLibrary", subject.synopsis(), List.of(unvalidatableProvider));
        harness.model.reset();
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response("{\"matches\":[]}")
                .maxMatches(1)
                .build());
        var fallbackResult =
                harness.service.infer(subject.batchId(), fallbackSubject, List.of(otherView), harness.logger);
        assertThat(fallbackResult.discarded()).isEqualTo(1);
        assertThat(harness.model.getInvocations()).hasSize(1);
    }

    @Test
    void acceptsHostsBetweenAnyClassifiedOrUnknownSurfacesAndFallsBackToUnknownDirection() {
        var infrastructure =
                surface(leftSurface, leftAttribute, "resource-provider", SurfaceRole.PROVIDES, "TECHNOLOGY");
        var artifact = surface(rightSurface, rightAttribute, "Dockerfile", SurfaceRole.PROVIDES, "FILE");
        var match = detailed(
                Nature.HOSTS,
                Direction.A_TO_B,
                Ceiling.CONFIRMED,
                "Infrastructure hosts the service artifact",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of(),
                List.of());

        assertThat(validate(match, infrastructure, artifact)).isNull();
        assertThat(CrossRepoMatchingService.normalizeDirection(match, List.of(infrastructure), List.of(artifact))
                        .direction())
                .isEqualTo(Direction.A_TO_B);

        var ambiguousHosts = detailed(
                Nature.HOSTS,
                Direction.BIDIRECTIONAL,
                Ceiling.SUGGESTED,
                "Hosting link without clear upstream side",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of(),
                List.of());
        var normalizedHosts =
                CrossRepoMatchingService.normalizeDirection(ambiguousHosts, List.of(infrastructure), List.of(artifact));
        assertThat(normalizedHosts.direction()).isEqualTo(Direction.UNKNOWN);
        assertThat(validate(normalizedHosts, infrastructure, artifact)).isNull();

        var unknownSubject = surface(leftSurface, leftAttribute, "/orders", SurfaceRole.UNKNOWN, "ENDPOINT");
        var unknownOther = surface(rightSurface, rightAttribute, "/orders", SurfaceRole.UNKNOWN, "ENDPOINT");
        var unknownCalls = detailed(
                Nature.CALLS,
                null,
                Ceiling.SUGGESTED,
                "Synchronous HTTP call with unknown direction",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of(),
                List.of());
        var normalizedCalls = CrossRepoMatchingService.normalizeDirection(
                unknownCalls, List.of(unknownSubject), List.of(unknownOther));
        assertThat(normalizedCalls.direction()).isEqualTo(Direction.UNKNOWN);
        assertThat(validate(normalizedCalls, unknownSubject, unknownOther)).isNull();
    }

    @Test
    void derivesBuildRelationsFromDirectSyftPackageCoordinatesInBothOrientations() {
        UUID providerSurfaceId = UUID.randomUUID();
        UUID providerPackageId = UUID.randomUUID();
        var provider = new CrossRepoMatchingService.SurfaceView(
                providerSurfaceId,
                SurfaceRole.PROVIDES,
                "Package identity declared by package.json",
                SurfaceDetection.SYFT,
                List.of(new CrossRepoMatchingService.AttributeView(
                        providerPackageId,
                        "IDENTIFIER",
                        "pkg:npm/%40glark-newco/game-library@0.0.0",
                        SurfaceDetection.SYFT)));
        UUID consumerSurfaceId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();
        var consumer = new CrossRepoMatchingService.SurfaceView(
                consumerSurfaceId,
                SurfaceRole.REQUIRES,
                "npm dependency manifest package-lock.json",
                SurfaceDetection.SYFT,
                List.of(new CrossRepoMatchingService.AttributeView(
                        dependencyId,
                        "IDENTIFIER",
                        "pkg:npm/%40glark-newco/game-library@0.0.0-snapshot.95-1",
                        SurfaceDetection.SYFT)));

        var forward = CrossRepoMatchingService.directBuildMatches(repo(leftRepo, provider), repo(rightRepo, consumer));
        assertThat(forward).singleElement().satisfies(match -> {
            assertThat(match.nature()).isEqualTo(Nature.BUILDS_AGAINST);
            assertThat(match.direction()).isEqualTo(Direction.A_TO_B);
            assertThat(match.subjectAttributeIds()).containsExactly(providerPackageId.toString());
            assertThat(match.otherAttributeIds()).containsExactly(dependencyId.toString());
        });
        assertThat(CrossRepoMatchingService.validate(
                        forward.getFirst(),
                        repo(leftRepo, provider),
                        List.of(provider),
                        repo(rightRepo, consumer),
                        List.of(consumer)))
                .isNull();

        var reverse = CrossRepoMatchingService.directBuildMatches(repo(rightRepo, consumer), repo(leftRepo, provider));
        assertThat(reverse).singleElement().satisfies(match -> {
            assertThat(match.subjectSurfaceId()).isEqualTo(consumerSurfaceId.toString());
            assertThat(match.otherSurfaceId()).isEqualTo(providerSurfaceId.toString());
            assertThat(match.direction()).isEqualTo(Direction.B_TO_A);
        });
    }

    @Test
    void requiresMatchingProvidedPackageCoordinateForBuildRelations() {
        var provider = surface(
                leftSurface,
                leftAttribute,
                "pkg:npm/%40glark-newco/game-library@0.0.0-snapshot.95-1",
                SurfaceRole.PROVIDES,
                "IDENTIFIER");
        var consumer = surface(
                rightSurface,
                rightAttribute,
                "pkg:npm/%40glark-newco/game-library@0.0.0-snapshot.41-1",
                SurfaceRole.REQUIRES,
                "IDENTIFIER");
        var valid = detailed(
                Nature.BUILDS_AGAINST,
                Direction.A_TO_B,
                Ceiling.CONFIRMED,
                "GameTemplate imports the package",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of(),
                List.of());
        assertThat(validate(valid, provider, consumer)).isNull();

        var otherPackage =
                surface(rightSurface, rightAttribute, "pkg:npm/other@1.0.0", SurfaceRole.REQUIRES, "IDENTIFIER");
        assertThat(validate(valid, provider, otherPackage)).contains("package coordinates to match");
    }

    @Test
    void sharedDependenciesAndEndpointsCannotProvePeerOrSharedState() {
        var libraryUser = surface(
                leftSurface,
                leftAttribute,
                "pkg:npm/%40glark-newco/game-library@1",
                SurfaceRole.REQUIRES,
                "IDENTIFIER");
        var otherLibraryUser = surface(
                rightSurface,
                rightAttribute,
                "pkg:npm/%40glark-newco/game-library@1",
                SurfaceRole.REQUIRES,
                "IDENTIFIER");
        var peer = detailed(
                Nature.PEER,
                Direction.BIDIRECTIONAL,
                Ceiling.CONFIRMED,
                "Both use the same dependency",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of("f1"),
                List.of("f1"));
        assertThat(validate(peer, libraryUser, otherLibraryUser))
                .contains("PEER requires cited non-package domain IDENTIFIER");

        var openAiClient = surface(
                leftSurface,
                leftAttribute,
                "https://api.openai.com/v1/chat/completions",
                SurfaceRole.REQUIRES,
                "ENDPOINT");
        var otherOpenAiClient = surface(
                rightSurface,
                rightAttribute,
                "https://api.openai.com/v1/chat/completions",
                SurfaceRole.REQUIRES,
                "ENDPOINT");
        var sharedState = detailed(
                Nature.SHARES_STATE,
                Direction.BIDIRECTIONAL,
                Ceiling.CONFIRMED,
                "Both use OpenAI",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of(),
                List.of());
        assertThat(validate(sharedState, openAiClient, otherOpenAiClient))
                .contains("shared cited resource name or reference");
    }

    @Test
    void peerAndSharedStateRequireDomainAndResourceEvidence() {
        var peerLeft = surface(leftSurface, leftAttribute, "orders platform", SurfaceRole.PROVIDES, "IDENTIFIER");
        var peerRight = surface(rightSurface, rightAttribute, "commerce platform", SurfaceRole.PROVIDES, "IDENTIFIER");
        var peer = detailed(
                Nature.PEER,
                Direction.BIDIRECTIONAL,
                Ceiling.SUGGESTED,
                "Both serve commerce",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of("f1"),
                List.of("f1"));
        assertThat(validate(peer, peerLeft, peerRight)).isNull();

        UUID leftTechAttr = UUID.randomUUID();
        UUID rightPkgAttr = UUID.randomUUID();
        UUID rightEndpointAttr = UUID.randomUUID();
        var peerLeftCoCited = new CrossRepoMatchingService.SurfaceView(
                leftSurface,
                SurfaceRole.PROVIDES,
                "Orders domain frontend",
                SurfaceDetection.MODEL,
                List.of(
                        new CrossRepoMatchingService.AttributeView(
                                leftAttribute, "IDENTIFIER", "orders-game", SurfaceDetection.MODEL),
                        new CrossRepoMatchingService.AttributeView(
                                leftTechAttr, "TECHNOLOGY", "React", SurfaceDetection.MODEL)));
        var peerRightCoCited = new CrossRepoMatchingService.SurfaceView(
                rightSurface,
                SurfaceRole.PROVIDES,
                "Commerce domain frontend",
                SurfaceDetection.MODEL,
                List.of(
                        new CrossRepoMatchingService.AttributeView(
                                rightAttribute, "IDENTIFIER", "commerce-game", SurfaceDetection.MODEL),
                        new CrossRepoMatchingService.AttributeView(
                                rightPkgAttr, "IDENTIFIER", "pkg:npm/react@19.0.0", SurfaceDetection.SYFT),
                        new CrossRepoMatchingService.AttributeView(
                                rightEndpointAttr, "ENDPOINT", "/play", SurfaceDetection.MODEL)));
        var peerWithCoCitedIgnoredEvidence = detailed(
                Nature.PEER,
                Direction.BIDIRECTIONAL,
                Ceiling.SUGGESTED,
                "Both serve the same game domain",
                List.of(leftAttribute, leftTechAttr),
                List.of(rightAttribute, rightPkgAttr, rightEndpointAttr),
                List.of("f1"),
                List.of("f1"));
        assertThat(validate(peerWithCoCitedIgnoredEvidence, peerLeftCoCited, peerRightCoCited))
                .isNull();

        var stateLeft = surface(leftSurface, leftAttribute, "orders-db", SurfaceRole.BIDIRECTIONAL, "IDENTIFIER");
        var stateRight = surface(rightSurface, rightAttribute, "orders-db", SurfaceRole.UNKNOWN, "IDENTIFIER");
        var state = detailed(
                Nature.SHARES_STATE,
                Direction.BIDIRECTIONAL,
                Ceiling.SUGGESTED,
                "Both use the orders database",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of(),
                List.of());
        assertThat(validate(state, stateLeft, stateRight)).isNull();

        var infraProvisioner = surface(leftSurface, leftAttribute, "orders-db", SurfaceRole.PROVIDES, "IDENTIFIER");
        assertThat(validate(state, infraProvisioner, stateRight)).contains("use HOSTS for infrastructure provisioning");
    }

    @Test
    void rejectsMissingAndUnownedEvidenceOnEitherSide() {
        List<InferenceResponse.RelationProposal> invalid = Arrays.asList(
                detailed(
                        Nature.CALLS,
                        Direction.A_TO_B,
                        Ceiling.CONFIRMED,
                        "supported",
                        null,
                        List.of(rightAttribute),
                        List.of(),
                        List.of()),
                detailed(
                        Nature.CALLS,
                        Direction.A_TO_B,
                        Ceiling.CONFIRMED,
                        "supported",
                        List.of(leftAttribute),
                        List.of(rightAttribute),
                        null,
                        List.of()),
                detailed(
                        Nature.CALLS,
                        Direction.A_TO_B,
                        Ceiling.CONFIRMED,
                        "supported",
                        List.of(),
                        List.of(rightAttribute),
                        List.of("f1"),
                        List.of()),
                detailed(
                        Nature.CALLS,
                        Direction.A_TO_B,
                        Ceiling.CONFIRMED,
                        "supported",
                        Collections.singletonList(null),
                        List.of(rightAttribute),
                        List.of(),
                        List.of()),
                detailed(
                        Nature.CALLS,
                        Direction.A_TO_B,
                        Ceiling.CONFIRMED,
                        "supported",
                        List.of(leftAttribute),
                        List.of(UUID.randomUUID()),
                        List.of(),
                        List.of()),
                detailed(
                        Nature.CALLS,
                        Direction.A_TO_B,
                        Ceiling.CONFIRMED,
                        "supported",
                        List.of(leftAttribute),
                        List.of(rightAttribute),
                        Collections.singletonList(null),
                        List.of()),
                detailed(
                        Nature.CALLS,
                        Direction.A_TO_B,
                        Ceiling.CONFIRMED,
                        "supported",
                        List.of(leftAttribute),
                        List.of(rightAttribute),
                        List.of("f0"),
                        List.of()),
                detailed(
                        Nature.CALLS,
                        Direction.A_TO_B,
                        Ceiling.CONFIRMED,
                        "supported",
                        List.of(leftAttribute),
                        List.of(rightAttribute),
                        List.of("f99"),
                        List.of()));
        assertThat(invalid)
                .allSatisfy(candidate -> assertThat(validate(candidate)).contains("evidence"));
        var noSynopsis =
                new CrossRepoMatchingService.RepoView(subject.batchId(), leftRepo, "subject", null, List.of(left));
        assertThat(CrossRepoMatchingService.validate(
                        detailed(
                                Nature.CALLS,
                                Direction.A_TO_B,
                                Ceiling.CONFIRMED,
                                "supported",
                                List.of(leftAttribute),
                                List.of(rightAttribute),
                                List.of("f1"),
                                List.of()),
                        noSynopsis,
                        List.of(left),
                        other,
                        List.of(right)))
                .contains("evidence");
        for (String description : Arrays.asList(null, "  ")) {
            var empty = new CrossRepoMatchingService.SurfaceView(
                    leftSurface, SurfaceRole.UNKNOWN, description, SurfaceDetection.MODEL, List.of());
            assertThat(CrossRepoMatchingService.validate(
                            detailed(
                                    Nature.CALLS,
                                    Direction.A_TO_B,
                                    Ceiling.CONFIRMED,
                                    "supported",
                                    List.of(),
                                    List.of(rightAttribute),
                                    List.of(),
                                    List.of()),
                            repo(leftRepo, empty),
                            List.of(empty),
                            other,
                            List.of(right)))
                    .contains("evidence");
        }
    }

    @Test
    void rejectsWrongPairAndOutOfGroupSurfaces() {
        assertThat(validate(null)).contains("repository");
        assertThat(validate(match(
                        leftRepo, leftSurface, UUID.randomUUID(), rightSurface, leftAttribute, rightAttribute, "f1")))
                .contains("repository");
        assertThat(validate(match(
                        leftRepo, leftSurface, rightRepo, UUID.randomUUID(), leftAttribute, rightAttribute, "f1")))
                .contains("surface");
        var sameRepo = repo(leftRepo, right);
        assertThat(CrossRepoMatchingService.validate(
                        match(leftRepo, leftSurface, leftRepo, rightSurface, leftAttribute, rightAttribute, "f1"),
                        subject,
                        List.of(left),
                        sameRepo,
                        List.of(right)))
                .contains("repository");
    }

    @Test
    void comparesFullRepoPairInSingleCallAndSplitsGroupsOnlyWhenOverflowingPromptBudget() throws Exception {
        var harness = new Harness();
        List<CrossRepoMatchingService.SurfaceView> leftSurfaces = IntStream.range(0, 21)
                .mapToObj(i -> surface(UUID.randomUUID(), UUID.randomUUID(), "left-" + i))
                .toList();
        List<CrossRepoMatchingService.SurfaceView> rightSurfaces = IntStream.range(0, 21)
                .mapToObj(i -> surface(UUID.randomUUID(), UUID.randomUUID(), "right-" + i, SurfaceRole.REQUIRES))
                .toList();
        var subjectView =
                new CrossRepoMatchingService.RepoView(subject.batchId(), leftRepo, "left", null, leftSurfaces);
        var otherView = new CrossRepoMatchingService.RepoView(other.batchId(), rightRepo, "right", null, rightSurfaces);
        var empty =
                new CrossRepoMatchingService.RepoView(UUID.randomUUID(), UUID.randomUUID(), "empty", null, List.of());

        var expectedCanonical = new InferenceResponse.RelationProposal(
                leftRepo.toString(),
                leftSurfaces.getFirst().id().toString(),
                rightRepo.toString(),
                rightSurfaces.getFirst().id().toString(),
                Nature.CALLS,
                Direction.A_TO_B,
                Ceiling.SUGGESTED,
                "Compatible interfaces",
                List.of(leftSurfaces.getFirst().attributes().getFirst().id().toString()),
                List.of(rightSurfaces.getFirst().attributes().getFirst().id().toString()),
                List.of(),
                List.of());
        var aliasValid = new InferenceResponse.RelationProposal(
                "A",
                "s1",
                "B",
                "o1",
                Nature.CALLS,
                Direction.A_TO_B,
                Ceiling.SUGGESTED,
                "Compatible interfaces",
                List.of("a1"),
                List.of("b1"),
                List.of(),
                List.of());
        var aliasInvalid = new InferenceResponse.RelationProposal(
                "C",
                "s1",
                "B",
                "o1",
                Nature.CALLS,
                Direction.A_TO_B,
                Ceiling.SUGGESTED,
                "Unknown repo alias",
                List.of("a1"),
                List.of("b1"),
                List.of(),
                List.of());

        var singleCall = PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response(
                        new ObjectMapper().writeValueAsString(new InferenceResponse(List.of(aliasValid, aliasInvalid))))
                .maxMatches(1)
                .build();
        harness.model.addMatcher(singleCall);

        var result = harness.service.infer(subject.batchId(), subjectView, List.of(empty, otherView), harness.logger);
        assertThat(result.accepted())
                .containsExactly(new CrossRepoMatchingService.AcceptedProposal(other.batchId(), expectedCanonical));
        assertThat(result.discarded()).isEqualTo(1);
        assertThat(harness.model.getInvocations()).hasSize(1);
        String singlePrompt = harness.model.getInvocations().getFirst().prompt().getContents();
        assertThat(singlePrompt)
                .contains("s21 PROVIDES", "o21 REQUIRES", "synopsis=unavailable")
                .doesNotContain(leftSurfaces.getFirst().id().toString());
        verify(harness.logs)
                .log(
                        eq(subject.batchId()),
                        eq(harness.teamId),
                        eq("WARN"),
                        eq("ACTIVATE_BATCH"),
                        contains("repository IDs"));
        verify(harness.logs)
                .log(
                        eq(subject.batchId()),
                        eq(harness.teamId),
                        eq("INFO"),
                        eq("ACTIVATE_BATCH"),
                        contains("0 eligible surfaces: [empty]"));
        verify(harness.logs)
                .log(
                        eq(subject.batchId()),
                        eq(harness.teamId),
                        eq("INFO"),
                        eq("ACTIVATE_BATCH"),
                        contains(
                                "Compared left (21 surfaces) vs right (21 surfaces): direct=0, accepted=1, rejected=1, retries=0."));

        // Now force overflow group splitting by deriving a tight model prompt budget
        harness.model.reset();
        int tightBudget = singlePrompt.length() - 900;
        when(harness.clients.maxPromptCharsFor(any())).thenReturn(tightBudget);
        var overflowMatcher = PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response("{\"matches\":[]}")
                .maxMatches(10)
                .build();
        harness.model.addMatcher(overflowMatcher);
        harness.service.infer(subject.batchId(), subjectView, List.of(otherView), harness.logger);
        assertThat(harness.model.getInvocations()).hasSizeGreaterThan(1);
        for (var leftItem : leftSurfaces) {
            for (var rightItem : rightSurfaces) {
                String leftVal = leftItem.attributes().getFirst().value();
                String rightVal = rightItem.attributes().getFirst().value();
                assertThat(harness.model.getInvocations().stream()
                                .filter(invocation -> {
                                    String prompt = invocation.prompt().getContents();
                                    return prompt.contains(" " + leftVal + "\n")
                                            && prompt.contains(" " + rightVal + "\n");
                                })
                                .count())
                        .isEqualTo(1);
            }
        }
    }

    @Test
    void executesPeerComparisonsInParallelWithIsolatedRetriesAndDeterministicOrdering() {
        var harness = new Harness();
        harness.properties.getMatching().setMaxRetries(1);
        harness.properties.getMatching().setMaxConcurrency(4);

        UUID peerRepo1 = UUID.randomUUID();
        UUID peerRepo2 = UUID.randomUUID();
        UUID peerSurface1 = UUID.randomUUID();
        UUID peerSurface2 = UUID.randomUUID();
        UUID peerAttr1 = UUID.randomUUID();
        UUID peerAttr2 = UUID.randomUUID();
        var peer1 = new CrossRepoMatchingService.RepoView(
                UUID.randomUUID(),
                peerRepo1,
                "slow-retrying-peer",
                null,
                List.of(surface(peerSurface1, peerAttr1, "/peer-one", SurfaceRole.REQUIRES)));
        var peer2 = new CrossRepoMatchingService.RepoView(
                UUID.randomUUID(),
                peerRepo2,
                "fast-peer",
                null,
                List.of(surface(peerSurface2, peerAttr2, "/peer-two", SurfaceRole.REQUIRES)));

        // Peer 1 fails with malformed JSON on attempt 1, then sleeps and returns a swapped-alias match on attempt 2.
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("name=slow-retrying-peer")
                .response("{malformed-json")
                .maxMatches(1)
                .build());
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("name=slow-retrying-peer")
                .response(prompt -> {
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    String json = """
                            {"matches":[{"subjectRepositoryId":"slow-retrying-peer","subjectSurfaceId":"o1","otherRepositoryId":"repo","otherSurfaceId":"s1","nature":"CALLS","direction":"B_TO_A","confidence":"CONFIRMED","rationale":"peer one calls subject","subjectAttributeIds":["b1"],"otherAttributeIds":["a1"],"subjectSynopsisIds":[],"otherSynopsisIds":[]}]}
                            """;
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
                })
                .maxMatches(1)
                .build());
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("name=fast-peer")
                .response("""
                        {"matches":[{"subjectRepositoryId":"A","subjectSurfaceId":"s1","otherRepositoryId":"B","otherSurfaceId":"o1","nature":"CALLS","direction":"A_TO_B","confidence":"SUGGESTED","rationale":"peer two calls subject","subjectAttributeIds":["a1"],"otherAttributeIds":["b1"],"subjectSynopsisIds":[],"otherSynopsisIds":[]}]}
                        """)
                .maxMatches(1)
                .build());

        var result = harness.service.infer(subject.batchId(), subject, List.of(peer1, peer2), harness.logger);

        assertThat(harness.model.getInvocations()).hasSize(3);
        assertThat(result.accepted()).hasSize(2);
        assertThat(result.accepted().getFirst().otherBatchId()).isEqualTo(peer1.batchId());
        assertThat(result.accepted().getFirst().proposal().subjectRepositoryId())
                .isEqualTo(leftRepo.toString());
        assertThat(result.accepted().getFirst().proposal().otherRepositoryId()).isEqualTo(peerRepo1.toString());
        assertThat(result.accepted().getFirst().proposal().otherSurfaceId()).isEqualTo(peerSurface1.toString());
        assertThat(result.accepted().getFirst().proposal().direction()).isEqualTo(Direction.A_TO_B);
        assertThat(result.accepted().get(1).otherBatchId()).isEqualTo(peer2.batchId());
    }

    @Test
    void rendersRoleAliasesFragmentsAndCompactLocalAliasesForBothRepositories() {
        var harness = new Harness();
        var subjectSynopsis = new CrossRepoMatchingService.SynopsisView(
                subject.batchId(),
                "SERVICE",
                List.of("consumer-client", "orders-app"),
                new SynopsisFragments("Client interface."));
        var otherSynopsis = new CrossRepoMatchingService.SynopsisView(
                other.batchId(), "LIBRARY", List.of("orders-sdk"), new SynopsisFragments("Provider interface."));
        var subjectView = new CrossRepoMatchingService.RepoView(
                subject.batchId(), leftRepo, "client", subjectSynopsis, List.of(left));
        var otherView = new CrossRepoMatchingService.RepoView(
                other.batchId(), rightRepo, "provider", otherSynopsis, List.of(right));
        var response = PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response("{\"matches\":[]}")
                .maxMatches(1)
                .build();
        harness.model.addMatcher(response);
        harness.service.infer(subject.batchId(), subjectView, List.of(otherView), harness.logger);
        assertThat(response.getMatchCount()).isEqualTo(1);
        String prompt = harness.model.getInvocations().getFirst().prompt().getContents();
        assertThat(prompt)
                .contains(
                        "subjectRepositoryId=A name=client synopsis=repoRole=SERVICE knownAs=[consumer-client, orders-app] fragments=f1: Client interface.",
                        "otherRepositoryId=B name=provider synopsis=repoRole=LIBRARY knownAs=[orders-sdk] fragments=f1: Provider interface.",
                        "- s1 PROVIDES: Runtime interface",
                        "s1.a1 ENDPOINT unfamiliar client",
                        "- o1 REQUIRES: Runtime interface",
                        "o1.b1 ENDPOINT other technology",
                        "current, implemented relationships that exist between the two repositories as they stand today",
                        "CALLS (directional: A_TO_B or B_TO_A):",
                        "MESSAGES (non-directional: BIDIRECTIONAL):",
                        "HOSTS (directional: A_TO_B or B_TO_A):",
                        "Surface roles are single-repository estimates",
                        "Shared use of the same external third-party\nservice",
                        "sharing a CI tool",
                        "namespace, and name",
                        "versions may differ")
                .doesNotContain("AttributeView[", leftAttribute.toString(), leftSurface.toString(), "detectedBy=");
    }

    @Test
    void rejectsOversizedAliasesBeforeCallingModel() {
        var harness = new Harness();
        int maxPrompt = 200_000;
        var synopsis = new CrossRepoMatchingService.SynopsisView(
                subject.batchId(),
                "SERVICE",
                List.of("x".repeat(maxPrompt)),
                new SynopsisFragments("Client interface."));
        var oversized =
                new CrossRepoMatchingService.RepoView(subject.batchId(), leftRepo, "client", synopsis, List.of(left));
        assertThatThrownBy(() -> harness.service.infer(subject.batchId(), oversized, List.of(other), harness.logger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("character limit");
        assertThat(harness.model.getInvocations()).isEmpty();
    }

    @Test
    void rejectsEmptyMalformedAndOversizedModelInputs() {
        var harness = new Harness();
        harness.properties.getMatching().setMaxRetries(0);
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response(" ")
                .maxMatches(1)
                .build());
        assertThatThrownBy(() -> harness.service.infer(subject.batchId(), subject, List.of(other), harness.logger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no content");
        harness.model.reset();
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response("null")
                .maxMatches(1)
                .build());
        assertThatThrownBy(() -> harness.service.infer(subject.batchId(), subject, List.of(other), harness.logger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid response");
        harness.model.reset();
        int maxPrompt = 200_000;
        var oversizedSynopsis = new CrossRepoMatchingService.SynopsisView(
                subject.batchId(), "SERVICE", List.of(), new SynopsisFragments("x".repeat(maxPrompt)));
        var oversized = new CrossRepoMatchingService.RepoView(
                subject.batchId(), leftRepo, "left", oversizedSynopsis, List.of(left));
        assertThatThrownBy(() -> harness.service.infer(subject.batchId(), oversized, List.of(other), harness.logger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("character limit");
        assertThat(harness.model.getInvocations()).isEmpty();
        verify(harness.logs)
                .log(
                        eq(subject.batchId()),
                        eq(harness.teamId),
                        eq("ERROR"),
                        eq("ACTIVATE_BATCH"),
                        contains("comparison omitted"));
    }

    @Test
    void noPairsAvoidsClientAndMissingBatchFailsBeforeModelCall() {
        var harness = new Harness();
        var empty = new CrossRepoMatchingService.RepoView(subject.batchId(), leftRepo, "empty", null, List.of());
        assertThat(harness.service
                        .infer(subject.batchId(), empty, List.of(other), harness.logger)
                        .accepted())
                .isEmpty();
        assertThat(harness.service
                        .infer(subject.batchId(), subject, List.of(empty), harness.logger)
                        .accepted())
                .isEmpty();
        verify(harness.clients, never()).chatClientFor(any());
        when(harness.batches.findByIdWithRepositoryAndTeam(subject.batchId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> harness.service.infer(subject.batchId(), subject, List.of(other), harness.logger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ingestion batch not found");
    }

    @Test
    void logsDebugNoticeWhenPeerSurfacesTrimToEmptyAndNoDirectMatchesExist() {
        Harness harness = new Harness();
        UUID unmatchedOnlyId = UUID.randomUUID();
        CrossRepoMatchingService.RepoView syftOnlySubject = new CrossRepoMatchingService.RepoView(
                subject.batchId(),
                leftRepo,
                "sbom-consumer",
                new CrossRepoMatchingService.SynopsisView(
                        subject.batchId(), "SERVICE", List.of(), new SynopsisFragments("Consumer.")),
                List.of(new CrossRepoMatchingService.SurfaceView(
                        unmatchedOnlyId,
                        SurfaceRole.REQUIRES,
                        "Dependencies",
                        SurfaceDetection.SYFT,
                        List.of(new CrossRepoMatchingService.AttributeView(
                                UUID.randomUUID(), "IDENTIFIER", "pkg:npm/lodash@4.17.21", SurfaceDetection.SYFT)))));
        CrossRepoMatchingService.InferenceResult result =
                harness.service.infer(subject.batchId(), syftOnlySubject, List.of(other), harness.logger);

        assertThat(result.accepted()).isEmpty();
        verify(harness.logs)
                .log(
                        eq(subject.batchId()),
                        eq(harness.teamId),
                        eq("DEBUG"),
                        eq("ACTIVATE_BATCH"),
                        contains(
                                "Skipped LLM comparison for sbom-consumer vs repo: all surfaces trimmed as unmatched SBOM dependencies."));
        verify(harness.logs)
                .log(
                        eq(subject.batchId()),
                        eq(harness.teamId),
                        eq("INFO"),
                        eq("ACTIVATE_BATCH"),
                        contains(
                                "Compared sbom-consumer (1 surfaces) vs repo (1 surfaces): direct=0, accepted=0, rejected=0, retries=0."));
    }

    @Test
    void logsRecoverableRejectionReasonsWhenRequestingValidationCorrectionPass() {
        Harness harness = new Harness();
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Previous matches rejected by validation:")
                .response("""
                        {"matches":[]}
                        """)
                .maxMatches(1)
                .build());
        harness.model.addMatcher(PromptMatcher.builder()
                .contains("Compare the supplied surface pairs")
                .response("""
                        {"matches":[
                          {"subjectRepositoryId":"A","subjectSurfaceId":"s1",
                           "otherRepositoryId":"B","otherSurfaceId":"o1",
                           "nature":"CALLS","direction":"A_TO_B","confidence":"CONFIRMED",
                           "rationale":"Endpoint match","subjectAttributeIds":[],
                           "otherAttributeIds":[],"subjectSynopsisIds":[],"otherSynopsisIds":[]}
                        ]}
                        """)
                .maxMatches(1)
                .build());

        CrossRepoMatchingService.InferenceResult result =
                harness.service.infer(subject.batchId(), subject, List.of(other), harness.logger);

        assertThat(result.accepted()).isEmpty();
        verify(harness.logs)
                .log(
                        eq(subject.batchId()),
                        eq(harness.teamId),
                        eq("DEBUG"),
                        eq("ACTIVATE_BATCH"),
                        contains(
                                "Requesting 1 validation-correction pass for repo vs repo (1 recoverable candidate(s)): Rejected CALLS candidate"));
    }

    @Test
    void hostsAlignsExplicitOppositeRolesAndPreservesLlmDirectionWhenRolesMatch() {
        var infraDb = surface(leftSurface, leftAttribute, "orders-db", SurfaceRole.PROVIDES, "IDENTIFIER");
        var appDb = surface(rightSurface, rightAttribute, "orders-db", SurfaceRole.REQUIRES, "IDENTIFIER");
        var invertedByLlm = detailed(
                Nature.HOSTS,
                Direction.B_TO_A,
                Ceiling.CONFIRMED,
                "App connects to DB provisioned by infra",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of(),
                List.of());
        assertThat(CrossRepoMatchingService.normalizeDirection(invertedByLlm, List.of(infraDb), List.of(appDb))
                        .direction())
                .isEqualTo(Direction.A_TO_B);
    }

    @Test
    void attributeKindIsCollapsedToFiveKindsAndValidationDecouplesKindsForHostsAndMessages() {
        assertThat(AttributeKind.values())
                .containsExactly(
                        AttributeKind.ENDPOINT,
                        AttributeKind.IDENTIFIER,
                        AttributeKind.METHOD,
                        AttributeKind.TECHNOLOGY,
                        AttributeKind.FILE);
        assertThat(AttributeKind.from("NAME")).isNull();
        assertThat(AttributeKind.from("REF")).isNull();

        var s3Host = surface(leftSurface, leftAttribute, "s3://app-bucket", SurfaceRole.PROVIDES, "ENDPOINT");
        var deployTarget =
                surface(rightSurface, rightAttribute, "https://app.example", SurfaceRole.REQUIRES, "ENDPOINT");
        var hostsWithEndpoints = detailed(
                Nature.HOSTS,
                Direction.A_TO_B,
                Ceiling.CONFIRMED,
                "Static hosting bucket serves app",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of(),
                List.of());
        assertThat(validate(hostsWithEndpoints, s3Host, deployTarget)).isNull();

        var techLeft = surface(leftSurface, leftAttribute, "WebSocket", SurfaceRole.BIDIRECTIONAL, "TECHNOLOGY");
        var techRight = surface(rightSurface, rightAttribute, "WebSocket", SurfaceRole.BIDIRECTIONAL, "TECHNOLOGY");
        var messagesWithTech = detailed(
                Nature.MESSAGES,
                Direction.BIDIRECTIONAL,
                Ceiling.SUGGESTED,
                "Exchanges messages over WebSocket",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of(),
                List.of());
        assertThat(validate(messagesWithTech, techLeft, techRight)).isNull();

        var callsProvideTech = surface(leftSurface, leftAttribute, "gRPC", SurfaceRole.PROVIDES, "TECHNOLOGY");
        var callsRequireMethod = surface(rightSurface, rightAttribute, "POST", SurfaceRole.REQUIRES, "METHOD");
        var callsWithNonFile = detailed(
                Nature.CALLS,
                Direction.A_TO_B,
                Ceiling.SUGGESTED,
                "Synchronous RPC call",
                List.of(leftAttribute),
                List.of(rightAttribute),
                List.of(),
                List.of());
        assertThat(validate(callsWithNonFile, callsProvideTech, callsRequireMethod))
                .isNull();
    }

    @Test
    void systemPromptDefinesHolisticNaturesAndOmitsUnknownDirectionFromSchema() {
        assertThat(CrossRepoMatchingService.systemPrompt())
                .contains("synchronous REST, RPC, GraphQL, or HTTP request/response interface")
                .contains("BUILDS_AGAINST (directional: A_TO_B or B_TO_A)")
                .contains("HOSTS (directional: A_TO_B or B_TO_A)")
                .contains("Never point workload ->")
                .contains("MESSAGES (non-directional: BIDIRECTIONAL)")
                .contains("Prefer MESSAGES for persistent bidirectional")
                .contains("Multiple client repositories may each independently hold MESSAGES relationships")
                .contains("independent synchronous HTTP/REST CALLS to the same third service")
                .contains("SHARES_STATE (non-directional: BIDIRECTIONAL)")
                .contains("save or load data directly from the same shared persistent storage resource")
                .contains("share an API base-URL configuration key")
                .contains("non-package domain IDENTIFIER attributes on both sides")
                .contains("overall repository synopses")
                .contains("including if DTO/payload matches are inexact")
                .contains("payload names are helpful supporting evidence")
                .contains("rather than mandatory");
        String format = CrossRepoMatchingService.createConverter().getFormat();
        assertThat(format)
                .contains("A_TO_B")
                .contains("B_TO_A")
                .contains("BIDIRECTIONAL")
                .doesNotContain("UNKNOWN");
    }

    private InferenceResponse.RelationProposal detailed(
            Nature nature,
            Direction direction,
            Ceiling confidence,
            String rationale,
            List<UUID> subjectAttributes,
            List<UUID> otherAttributes,
            List<String> subjectFragments,
            List<String> otherFragments) {
        return new InferenceResponse.RelationProposal(
                leftRepo.toString(),
                leftSurface.toString(),
                rightRepo.toString(),
                rightSurface.toString(),
                nature,
                direction,
                confidence,
                rationale,
                subjectAttributes == null
                        ? null
                        : subjectAttributes.stream()
                                .map(id -> id == null ? null : id.toString())
                                .toList(),
                otherAttributes == null
                        ? null
                        : otherAttributes.stream()
                                .map(id -> id == null ? null : id.toString())
                                .toList(),
                subjectFragments,
                otherFragments);
    }

    private class Harness {
        final UUID teamId = UUID.randomUUID();
        final IngestionBatchLogService logs = mock(IngestionBatchLogService.class);
        final BatchLogger logger = new BatchLogger(subject.batchId(), teamId, logs);
        final BatchChatClientFactory clients = mock(BatchChatClientFactory.class);
        final IngestionBatchRepository batches = mock(IngestionBatchRepository.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final FakeChatModel model = new FakeChatModel();
        final IngestionProperties properties = new IngestionProperties();
        final CrossRepoMatchingService service;

        Harness() {
            properties.getMatching().setInitialBackoffMs(0);
            service = new CrossRepoMatchingService(clients, batches, transactions, properties);
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(batches.findByIdWithRepositoryAndTeam(subject.batchId()))
                    .thenReturn(Optional.of(mock(IngestionBatch.class)));
            when(clients.chatClientFor(any()))
                    .thenReturn(ChatClient.builder(model).build());
            when(clients.maxPromptCharsFor(any())).thenReturn(200_000);
        }
    }

    private String validate(InferenceResponse.RelationProposal match) {
        return CrossRepoMatchingService.validate(match, subject, List.of(left), other, List.of(right));
    }

    private String validate(
            InferenceResponse.RelationProposal match,
            CrossRepoMatchingService.SurfaceView subjectSurface,
            CrossRepoMatchingService.SurfaceView otherSurface) {
        return CrossRepoMatchingService.validate(
                match,
                repo(leftRepo, subjectSurface),
                List.of(subjectSurface),
                repo(rightRepo, otherSurface),
                List.of(otherSurface));
    }

    private static InferenceResponse.RelationProposal match(
            UUID repoA, UUID surfaceA, UUID repoB, UUID surfaceB, UUID attributeA, UUID attributeB, String fragment) {
        return new InferenceResponse.RelationProposal(
                repoA.toString(),
                surfaceA.toString(),
                repoB.toString(),
                surfaceB.toString(),
                Nature.CALLS,
                Direction.A_TO_B,
                Ceiling.SUGGESTED,
                "Compatible interfaces",
                List.of(attributeA.toString()),
                List.of(attributeB.toString()),
                List.of(fragment),
                List.of());
    }

    private static CrossRepoMatchingService.SurfaceView surface(UUID id, UUID attributeId, String value) {
        return surface(id, attributeId, value, SurfaceRole.PROVIDES, "ENDPOINT");
    }

    private static CrossRepoMatchingService.SurfaceView surface(
            UUID id, UUID attributeId, String value, SurfaceRole role) {
        return surface(id, attributeId, value, role, "ENDPOINT");
    }

    private static CrossRepoMatchingService.SurfaceView surface(
            UUID id, UUID attributeId, String value, SurfaceRole role, String kind) {
        return new CrossRepoMatchingService.SurfaceView(
                id,
                role,
                "Runtime interface",
                SurfaceDetection.MODEL,
                List.of(new CrossRepoMatchingService.AttributeView(attributeId, kind, value, SurfaceDetection.MODEL)));
    }

    private static CrossRepoMatchingService.RepoView repo(UUID id, CrossRepoMatchingService.SurfaceView surface) {
        UUID batchId = UUID.randomUUID();
        return new CrossRepoMatchingService.RepoView(
                batchId,
                id,
                "repo",
                new CrossRepoMatchingService.SynopsisView(
                        batchId, "SERVICE", List.of(), new SynopsisFragments("An interface exists.")),
                List.of(surface));
    }
}
