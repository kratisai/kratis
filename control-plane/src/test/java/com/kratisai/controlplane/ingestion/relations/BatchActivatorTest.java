package com.kratisai.controlplane.ingestion.relations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService.BatchLogger;
import com.kratisai.controlplane.model.Ceiling;
import com.kratisai.controlplane.model.CtxRepoRelation;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.Nature;
import com.kratisai.controlplane.model.RelationState;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxRepoRelationRepository;
import com.kratisai.controlplane.repository.CtxRepoSynopsisRepository;
import com.kratisai.controlplane.repository.CtxSurfaceRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class BatchActivatorTest {
    private final UUID teamId = UUID.randomUUID();
    private final UUID subjectRepoId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private final UUID otherRepoId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID subjectSurfaceId = UUID.randomUUID();
    private final UUID otherSurfaceId = UUID.randomUUID();
    private final UUID subjectAttributeId = UUID.randomUUID();
    private final UUID otherAttributeId = UUID.randomUUID();
    private final Team team = new Team();
    private final IngestionBatch subject = batch(subjectRepoId, "subject");
    private final IngestionBatch other = batch(otherRepoId, "other");
    private final IngestionBatchRepository batches = mock(IngestionBatchRepository.class);
    private final CtxSurfaceRepository surfaces = mock(CtxSurfaceRepository.class);
    private final CtxRepoRelationRepository relations = mock(CtxRepoRelationRepository.class);
    private final CtxRepoSynopsisRepository synopses = mock(CtxRepoSynopsisRepository.class);
    private final CrossRepoMatchingService inference = mock(CrossRepoMatchingService.class);
    private final TeamActivationLock lock = mock(TeamActivationLock.class);
    private final TeamActivationLock.TeamLock heldLock = mock(TeamActivationLock.TeamLock.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final IngestionBatchLogService logs = mock(IngestionBatchLogService.class);
    private final BatchLogger logger = new BatchLogger(subject.getId(), teamId, logs);
    private final ObjectMapper mapper = new ObjectMapper();

    private BatchActivatorTest() {
        team.setId(teamId);
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(lock.acquire(teamId)).thenReturn(heldLock);
        when(batches.findByIdWithRepositoryAndTeam(subject.getId())).thenReturn(Optional.of(subject));
        when(batches.findByIdWithRepositoryAndTeam(other.getId())).thenReturn(Optional.of(other));
        when(batches.findActiveByTeamId(teamId)).thenReturn(List.of(other));
        when(relations.supersedeCurrentForRepository(teamId, subjectRepoId)).thenReturn(2);
        when(relations.save(any(CtxRepoRelation.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void combinesMatchesAndPersistsOriginalEvidenceWithCanonicalDirection() throws Exception {
        var forward = match(Direction.A_TO_B, Ceiling.CONFIRMED);
        var reverse = match(Direction.B_TO_A, Ceiling.SUGGESTED);
        UUID secondSubjectSurfaceId = UUID.randomUUID();
        UUID secondOtherSurfaceId = UUID.randomUUID();
        var alsoReverse = new InferenceResponse.RelationProposal(
                subjectRepoId.toString(),
                secondSubjectSurfaceId.toString(),
                otherRepoId.toString(),
                secondOtherSurfaceId.toString(),
                Nature.CALLS,
                Direction.B_TO_A,
                Ceiling.CONFIRMED,
                "another interface",
                List.of(subjectAttributeId.toString()),
                List.of(otherAttributeId.toString()),
                List.of("f1"),
                List.of("f2"));
        stubInference(List.of(forward, reverse, alsoReverse));
        BatchActivator.Activation result = activator(mapper).activate(subject.getId(), logger, "abc123");
        assertThat(result).isEqualTo(new BatchActivator.Activation(1, 2));
        var relationCaptor = ArgumentCaptor.forClass(CtxRepoRelation.class);
        verify(relations).save(relationCaptor.capture());
        CtxRepoRelation relation = relationCaptor.getValue();
        assertThat(relation.getRepositoryAId()).isEqualTo(otherRepoId);
        assertThat(relation.getRepositoryBId()).isEqualTo(subjectRepoId);
        assertThat(relation.getDirection()).isEqualTo(Direction.BIDIRECTIONAL);
        assertThat(relation.getConfidence()).isEqualTo(Ceiling.SUGGESTED);
        JsonNode matches = mapper.readTree(relation.getCitations()).path("matches");
        assertThat(matches.size()).isEqualTo(3);
        assertThat(matches.get(0).path("subjectSurfaceId").asText()).isEqualTo(subjectSurfaceId.toString());
        assertThat(matches.get(0).path("otherSurfaceId").asText()).isEqualTo(otherSurfaceId.toString());
        assertThat(matches.get(0).path("confidence").asText()).isEqualTo("CONFIRMED");
        assertThat(matches.get(0).path("rationale").asText()).isEqualTo("compatible interfaces");
        assertThat(matches.get(1).path("confidence").asText()).isEqualTo("SUGGESTED");
        assertThat(matches.get(1).path("evidence").path("direction").asText()).isEqualTo("B_TO_A");
        assertThat(matches.get(2).path("subjectSurfaceId").asText()).isEqualTo(secondSubjectSurfaceId.toString());
        assertThat(matches.get(2).path("otherSurfaceId").asText()).isEqualTo(secondOtherSurfaceId.toString());
        assertThat(matches.get(2).path("rationale").asText()).isEqualTo("another interface");
        JsonNode evidence = matches.get(0).path("evidence");
        assertThat(evidence.path("subjectAttributeIds").get(0).asText()).isEqualTo(subjectAttributeId.toString());
        assertThat(evidence.path("otherAttributeIds").get(0).asText()).isEqualTo(otherAttributeId.toString());
        assertThat(evidence.path("subjectSynopsisIds").get(0).asText()).isEqualTo("f1");
        assertThat(evidence.path("otherSynopsisIds").get(0).asText()).isEqualTo("f2");
        assertThat(evidence.path("direction").asText()).isEqualTo("A_TO_B");
        assertThat(subject.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
        assertThat(subject.isActive()).isTrue();
        assertThat(subject.getCommitHash()).isEqualTo("abc123");
        assertThat(subject.getCompletedAt()).isNotNull();
        verify(batches).deactivateOtherBatches(subjectRepoId, subject.getId());
        verify(heldLock).close();
    }

    @Test
    void reactivatesExistingRelationAndReplacesOldEvidence() throws Exception {
        CtxRepoRelation existing =
                new CtxRepoRelation(teamId, other, subject, Nature.CALLS, Direction.A_TO_B, Ceiling.SUGGESTED);
        existing.setState(RelationState.SUPERSEDED);
        existing.setRationale("obsolete");
        existing.setCitations("{\"sources\":[\"old\"]}");
        when(relations.findByBatchAIdAndBatchBIdAndNature(other.getId(), subject.getId(), Nature.CALLS))
                .thenReturn(Optional.of(existing));
        stubInference(List.of(match(Direction.A_TO_B, Ceiling.CONFIRMED)));
        activator(mapper).activate(subject.getId(), logger, "commit");
        assertThat(existing.getDirection()).isEqualTo(Direction.B_TO_A);
        assertThat(existing.getConfidence()).isEqualTo(Ceiling.CONFIRMED);
        assertThat(existing.getState()).isEqualTo(RelationState.CURRENT);
        assertThat(existing.getRationale()).isNull();
        JsonNode citations = mapper.readTree(existing.getCitations());
        assertThat(citations.path("matches").size()).isEqualTo(1);
        assertThat(citations.path("legacy").path("rationale").asText()).isEqualTo("obsolete");
        assertThat(citations
                        .path("legacy")
                        .path("citations")
                        .path("sources")
                        .get(0)
                        .asText())
                .isEqualTo("old");
        activator(mapper).activate(subject.getId(), logger, "commit");
        JsonNode repeated = mapper.readTree(existing.getCitations());
        assertThat(repeated.path("legacy")).isEqualTo(citations.path("legacy"));
        assertThat(repeated.path("matches").size()).isEqualTo(1);
        assertThat(repeated.has("previous")).isFalse();
        assertThat(existing.getRationale()).isNull();
        verify(relations, times(2)).save(existing);
    }

    @Test
    void preservesRationaleWithoutLegacyCitations() throws Exception {
        CtxRepoRelation existing =
                new CtxRepoRelation(teamId, other, subject, Nature.CALLS, Direction.A_TO_B, Ceiling.SUGGESTED);
        existing.setRationale("old rationale");
        when(relations.findByBatchAIdAndBatchBIdAndNature(other.getId(), subject.getId(), Nature.CALLS))
                .thenReturn(Optional.of(existing));
        stubInference(List.of(match(Direction.A_TO_B, Ceiling.CONFIRMED)));
        activator(mapper).activate(subject.getId(), logger, "commit");
        JsonNode citations = mapper.readTree(existing.getCitations());
        assertThat(citations.path("legacy").path("rationale").asText()).isEqualTo("old rationale");
        assertThat(citations.path("legacy").has("citations")).isFalse();
        assertThat(existing.getRationale()).isNull();
    }

    @Test
    void activatingSubjectLeavesUnrelatedPeerToPeerRelationsUntouched() throws Exception {
        UUID peerCRepoId = UUID.randomUUID();
        IngestionBatch peerC = batch(peerCRepoId, "peer-c");
        peerC.setActive(true);

        when(batches.findActiveByTeamId(teamId)).thenReturn(List.of(other, peerC));
        stubInference(List.of());

        activator(mapper).activate(subject.getId(), logger, "commit-a");

        verify(relations).supersedeCurrentForRepository(teamId, subjectRepoId);
        verify(relations, never()).supersedeCurrentForRepository(teamId, otherRepoId);
        verify(relations, never()).supersedeCurrentForRepository(teamId, peerCRepoId);
    }

    @Test
    void addsRationaleToExistingLegacyWithoutNesting() throws Exception {
        CtxRepoRelation existing =
                new CtxRepoRelation(teamId, other, subject, Nature.CALLS, Direction.A_TO_B, Ceiling.SUGGESTED);
        existing.setRationale("old rationale");
        existing.setCitations(
                "{\"matches\":[{\"obsolete\":true}],\"legacy\":{\"citations\":{\"source\":\"old\"},\"extra\":1}}");
        when(relations.findByBatchAIdAndBatchBIdAndNature(other.getId(), subject.getId(), Nature.CALLS))
                .thenReturn(Optional.of(existing));
        stubInference(List.of(match(Direction.A_TO_B, Ceiling.CONFIRMED)));
        activator(mapper).activate(subject.getId(), logger, "commit");
        JsonNode citations = mapper.readTree(existing.getCitations());
        JsonNode legacy = citations.path("legacy");
        assertThat(legacy.path("rationale").asText()).isEqualTo("old rationale");
        assertThat(legacy.path("citations").path("source").asText()).isEqualTo("old");
        assertThat(legacy.path("extra").asInt()).isEqualTo(1);
        assertThat(citations.path("matches").get(0).path("rationale").asText()).isEqualTo("compatible interfaces");
        assertThat(existing.getRationale()).isNull();
        activator(mapper).activate(subject.getId(), logger, "commit");
        assertThat(mapper.readTree(existing.getCitations()).path("legacy")).isEqualTo(legacy);
    }

    @Test
    void conflictingLegacyRationalesAbortWithoutDiscardingEvidence() {
        CtxRepoRelation existing =
                new CtxRepoRelation(teamId, other, subject, Nature.CALLS, Direction.A_TO_B, Ceiling.SUGGESTED);
        existing.setRationale("first");
        String previous = "{\"matches\":[],\"legacy\":{\"rationale\":\"second\"}}";
        existing.setCitations(previous);
        when(relations.findByBatchAIdAndBatchBIdAndNature(other.getId(), subject.getId(), Nature.CALLS))
                .thenReturn(Optional.of(existing));
        stubInference(List.of(match(Direction.A_TO_B, Ceiling.CONFIRMED)));
        assertThatThrownBy(() -> activator(mapper).activate(subject.getId(), logger, "commit"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting legacy relation rationales");
        assertThat(existing.getCitations()).isEqualTo(previous);
        assertThat(existing.getRationale()).isEqualTo("first");
        verify(relations, never()).save(any());
        verify(transactions).rollback(any());
    }

    @Test
    void excludesLegacyAndUnclassifiedSurfacesFromMatchingAndReportsCounts() throws Exception {
        CtxSurface legacy = mock(CtxSurface.class);
        CtxSurface unclassified = mock(CtxSurface.class);
        CtxSurface current = mock(CtxSurface.class);
        when(legacy.getDetectedBy()).thenReturn(SurfaceDetection.UNKNOWN);
        when(unclassified.getDetectedBy()).thenReturn(SurfaceDetection.CMM);
        when(unclassified.getRole()).thenReturn(SurfaceRole.UNKNOWN);
        when(current.getDetectedBy()).thenReturn(SurfaceDetection.CMM);
        when(current.getRole()).thenReturn(SurfaceRole.PROVIDES);
        when(current.getBatch()).thenReturn(subject);
        when(current.getId()).thenReturn(subjectSurfaceId);
        when(surfaces.findByBatchIdIn(any())).thenReturn(List.of(legacy, unclassified, current));
        stubInference(List.of());
        activator(mapper).activate(subject.getId(), logger, "commit");
        var subjectView = ArgumentCaptor.forClass(CrossRepoMatchingService.RepoView.class);
        verify(inference).infer(eq(subject.getId()), subjectView.capture(), any(), eq(logger));
        assertThat(subjectView.getValue().surfaces())
                .extracting(CrossRepoMatchingService.SurfaceView::id)
                .containsExactly(subjectSurfaceId);
        verify(logs)
                .log(
                        eq(subject.getId()),
                        eq(teamId),
                        eq("INFO"),
                        eq("ACTIVATE_BATCH"),
                        eq("Excluded 1 legacy UNKNOWN-provenance surfaces and 1 unclassified surfaces from matching."));
    }

    @Test
    void abortsBeforeWritesIfActiveSnapshotChanges() {
        when(batches.findActiveByTeamId(teamId)).thenReturn(List.of(other), List.of());
        stubInference(List.of(match(Direction.A_TO_B, Ceiling.CONFIRMED)));
        assertThatThrownBy(() -> activator(mapper).activate(subject.getId(), logger, "commit"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot changed");
        verify(relations, never()).supersedeCurrentForRepository(any(), any());
        verify(batches, never()).saveAndFlush(any());
        verify(transactions).rollback(any());
        verify(heldLock).close();
        assertThat(subject.isActive()).isFalse();
    }

    @Test
    void inferenceFailureDoesNotSupersedeOrActivateAndReleasesLock() {
        when(inference.infer(eq(subject.getId()), any(), any(), eq(logger)))
                .thenThrow(new IllegalStateException("model unavailable"));
        assertThatThrownBy(() -> activator(mapper).activate(subject.getId(), logger, "commit"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model unavailable");
        verify(relations, never()).supersedeCurrentForRepository(any(), any());
        verify(batches, never()).saveAndFlush(any());
        verify(heldLock).close();
        assertThat(subject.isActive()).isFalse();
    }

    @Test
    void evidenceSerializationFailureRollsBackActivationAndReleasesLock() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("unserializable") {});
        stubInference(List.of(match(Direction.BIDIRECTIONAL, Ceiling.CONFIRMED)));
        assertThatThrownBy(() -> activator(failingMapper).activate(subject.getId(), logger, "commit"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot serialize surface match evidence")
                .hasCauseInstanceOf(JsonProcessingException.class);
        verify(transactions).rollback(any());
        verify(relations, never()).save(any());
        verify(batches, never()).saveAndFlush(any());
        verify(heldLock).close();
        assertThat(subject.isActive()).isFalse();
    }

    @Test
    void missingBatchDoesNotAcquireLock() {
        when(batches.findByIdWithRepositoryAndTeam(subject.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> activator(mapper).activate(subject.getId(), logger, "commit"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ingestion batch not found");
        verify(lock, never()).acquire(any());
    }

    private BatchActivator activator(ObjectMapper objectMapper) {
        return new BatchActivator(batches, surfaces, relations, synopses, inference, lock, objectMapper, transactions);
    }

    private void stubInference(List<InferenceResponse.RelationProposal> accepted) {
        when(inference.infer(eq(subject.getId()), any(), any(), eq(logger)))
                .thenReturn(new CrossRepoMatchingService.InferenceResult(
                        accepted.stream()
                                .map(match -> new CrossRepoMatchingService.AcceptedProposal(other.getId(), match))
                                .toList(),
                        0));
    }

    private InferenceResponse.RelationProposal match(Direction direction, Ceiling confidence) {
        return new InferenceResponse.RelationProposal(
                subjectRepoId.toString(),
                subjectSurfaceId.toString(),
                otherRepoId.toString(),
                otherSurfaceId.toString(),
                Nature.CALLS,
                direction,
                confidence,
                "compatible interfaces",
                List.of(subjectAttributeId.toString()),
                List.of(otherAttributeId.toString()),
                List.of("f1"),
                List.of("f2"));
    }

    private IngestionBatch batch(UUID repositoryId, String name) {
        Repository repository = new Repository();
        repository.setId(repositoryId);
        repository.setName(name);
        repository.setTeam(team);
        IngestionBatch batch = new IngestionBatch(repository);
        batch.setId(UUID.randomUUID());
        return batch;
    }
}
