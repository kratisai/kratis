package com.kratisai.controlplane.ingestion.relations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService.BatchLogger;
import com.kratisai.controlplane.model.Ceiling;
import com.kratisai.controlplane.model.CtxRepoRelation;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.Nature;
import com.kratisai.controlplane.model.RelationState;
import com.kratisai.controlplane.repository.CtxRepoRelationRepository;
import com.kratisai.controlplane.repository.CtxRepoSynopsisRepository;
import com.kratisai.controlplane.repository.CtxSurfaceRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class BatchActivator {

    private final IngestionBatchRepository ingestionBatchRepository;
    private final CtxSurfaceRepository ctxSurfaceRepository;
    private final CtxRepoRelationRepository ctxRepoRelationRepository;
    private final CtxRepoSynopsisRepository ctxRepoSynopsisRepository;
    private final CrossRepoMatchingService crossRepoMatchingService;
    private final TeamActivationLock teamActivationLock;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public BatchActivator(
            IngestionBatchRepository ingestionBatchRepository,
            CtxSurfaceRepository ctxSurfaceRepository,
            CtxRepoRelationRepository ctxRepoRelationRepository,
            CtxRepoSynopsisRepository ctxRepoSynopsisRepository,
            CrossRepoMatchingService crossRepoMatchingService,
            TeamActivationLock teamActivationLock,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.ctxSurfaceRepository = ctxSurfaceRepository;
        this.ctxRepoRelationRepository = ctxRepoRelationRepository;
        this.ctxRepoSynopsisRepository = ctxRepoSynopsisRepository;
        this.crossRepoMatchingService = crossRepoMatchingService;
        this.teamActivationLock = teamActivationLock;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Activation(int relationsWritten, int superseded) {}

    record Prepared(CrossRepoMatchingService.RepoView subject, List<CrossRepoMatchingService.RepoView> others) {}

    record RelationKey(UUID otherBatchId, Nature nature) {}

    public Activation activate(UUID batchId, BatchLogger batchLogger, String commitHash) throws SQLException {
        UUID teamId = loadBatch(batchId).getRepository().getTeam().getId();
        try (TeamActivationLock.TeamLock ignored = teamActivationLock.acquire(teamId)) {
            Prepared prepared = transactionTemplate.execute(status -> prepare(batchId, teamId));
            CrossRepoMatchingService.InferenceResult result =
                    crossRepoMatchingService.infer(batchId, prepared.subject(), prepared.others(), batchLogger);
            Activation activation =
                    transactionTemplate.execute(status -> activateLocked(batchId, prepared, result, commitHash));
            batchLogger.info(
                    "ACTIVATE_BATCH",
                    "Batch activated; " + result.accepted().size() + " surface matches accepted and "
                            + result.discarded() + " rejected.");
            return activation;
        }
    }

    private Prepared prepare(UUID batchId, UUID teamId) {
        IngestionBatch subject = loadBatch(batchId);
        List<IngestionBatch> active = ingestionBatchRepository.findActiveByTeamId(teamId).stream()
                .filter(batch -> !batch.getRepository()
                        .getId()
                        .equals(subject.getRepository().getId()))
                .toList();
        List<UUID> ids = new ArrayList<>();
        ids.add(batchId);
        active.forEach(batch -> ids.add(batch.getId()));
        Map<UUID, CrossRepoMatchingService.SynopsisView> synopsisByBatch = new HashMap<>();
        ctxRepoSynopsisRepository
                .findByBatchIdIn(ids)
                .forEach(row ->
                        synopsisByBatch.put(row.getBatch().getId(), CrossRepoMatchingService.SynopsisView.of(row)));
        Map<UUID, List<CrossRepoMatchingService.SurfaceView>> surfaceByBatch = new HashMap<>();
        List<CtxSurface> observedSurfaces = ctxSurfaceRepository.findByBatchIdIn(ids);
        observedSurfaces.forEach(surface -> surfaceByBatch
                .computeIfAbsent(surface.getBatch().getId(), unused -> new ArrayList<>())
                .add(new CrossRepoMatchingService.SurfaceView(
                        surface.getId(),
                        surface.getRole(),
                        surface.getDescription(),
                        surface.getDetectedBy(),
                        surface.getAttributeRows().stream()
                                .map(attribute -> new CrossRepoMatchingService.AttributeView(
                                        attribute.getId(),
                                        attribute.getKind().name(),
                                        attribute.getValue(),
                                        attribute.getDetectedBy()))
                                .toList())));
        return new Prepared(
                view(subject, synopsisByBatch, surfaceByBatch),
                active.stream()
                        .map(batch -> view(batch, synopsisByBatch, surfaceByBatch))
                        .toList());
    }

    private static CrossRepoMatchingService.RepoView view(
            IngestionBatch batch,
            Map<UUID, CrossRepoMatchingService.SynopsisView> synopses,
            Map<UUID, List<CrossRepoMatchingService.SurfaceView>> surfaces) {
        return new CrossRepoMatchingService.RepoView(
                batch.getId(),
                batch.getRepository().getId(),
                batch.getRepository().getName(),
                synopses.get(batch.getId()),
                List.copyOf(surfaces.getOrDefault(batch.getId(), List.of())));
    }

    private Activation activateLocked(
            UUID batchId, Prepared prepared, CrossRepoMatchingService.InferenceResult result, String commitHash) {
        IngestionBatch subject = loadBatch(batchId);
        UUID teamId = subject.getRepository().getTeam().getId();
        List<UUID> expected = prepared.others().stream()
                .map(CrossRepoMatchingService.RepoView::batchId)
                .sorted()
                .toList();
        List<UUID> actual = ingestionBatchRepository.findActiveByTeamId(teamId).stream()
                .filter(batch -> !batch.getRepository()
                        .getId()
                        .equals(subject.getRepository().getId()))
                .map(IngestionBatch::getId)
                .sorted()
                .toList();
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Active batch snapshot changed during surface matching");
        }
        int superseded = ctxRepoRelationRepository.supersedeCurrentForRepository(
                teamId, subject.getRepository().getId());
        Map<RelationKey, List<CrossRepoMatchingService.AcceptedProposal>> grouped = new HashMap<>();
        result.accepted().forEach(match -> grouped.computeIfAbsent(
                        new RelationKey(match.otherBatchId(), match.proposal().nature()), unused -> new ArrayList<>())
                .add(match));
        for (var entry : grouped.entrySet()) {
            IngestionBatch other = loadBatch(entry.getKey().otherBatchId());
            boolean subjectFirst = CtxRepoRelation.compareBySqlOrder(
                            subject.getRepository().getId(),
                            other.getRepository().getId())
                    < 0;
            IngestionBatch first = subjectFirst ? subject : other;
            IngestionBatch second = subjectFirst ? other : subject;
            Direction direction = null;
            Ceiling confidence = Ceiling.CONFIRMED;
            for (CrossRepoMatchingService.AcceptedProposal item : entry.getValue()) {
                Direction candidate = canonicalDirection(item.proposal().direction(), subjectFirst);
                if (direction == null) {
                    direction = candidate;
                } else if (candidate != Direction.UNKNOWN && direction != candidate) {
                    direction = direction == Direction.UNKNOWN ? candidate : Direction.BIDIRECTIONAL;
                }
                if (item.proposal().confidence() == Ceiling.SUGGESTED) confidence = Ceiling.SUGGESTED;
            }
            CtxRepoRelation relation = ctxRepoRelationRepository
                    .findByBatchAIdAndBatchBIdAndNature(
                            first.getId(), second.getId(), entry.getKey().nature())
                    .orElse(null);
            if (relation == null) {
                relation = new CtxRepoRelation(
                        teamId, first, second, entry.getKey().nature(), direction, confidence);
            } else {
                relation.setDirection(direction);
                relation.setConfidence(confidence);
                relation.setState(RelationState.CURRENT);
            }
            String citations = citations(entry.getValue());
            relation.setCitations(citations);
            ctxRepoRelationRepository.save(relation);
        }
        subject.setStatus(IngestionStatus.SUCCESS);
        subject.setCommitHash(commitHash);
        subject.setCompletedAt(Instant.now());
        subject.setActive(true);
        ingestionBatchRepository.saveAndFlush(subject);
        ingestionBatchRepository.deactivateOtherBatches(subject.getRepository().getId(), subject.getId());
        return new Activation(grouped.size(), superseded);
    }

    private String citations(List<CrossRepoMatchingService.AcceptedProposal> proposals) {
        List<Map<String, Object>> evidence = proposals.stream()
                .map(item -> {
                    InferenceResponse.RelationProposal proposal = item.proposal();
                    return Map.of(
                            "subjectSurfaceId", proposal.subjectSurfaceId(),
                            "otherSurfaceId", proposal.otherSurfaceId(),
                            "confidence", proposal.confidence(),
                            "rationale", proposal.rationale(),
                            "evidence",
                                    Map.of(
                                            "subjectAttributeIds", proposal.subjectAttributeIds(),
                                            "otherAttributeIds", proposal.otherAttributeIds(),
                                            "subjectSynopsisIds", proposal.subjectSynopsisIds(),
                                            "otherSynopsisIds", proposal.otherSynopsisIds(),
                                            "direction", proposal.direction()));
                })
                .toList();
        try {
            return objectMapper.writeValueAsString(Map.of("matches", evidence));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize surface match evidence", e);
        }
    }

    private static Direction canonicalDirection(Direction direction, boolean subjectFirst) {
        if (subjectFirst
                || direction == null
                || direction == Direction.BIDIRECTIONAL
                || direction == Direction.UNKNOWN) {
            return direction;
        }
        return direction == Direction.A_TO_B ? Direction.B_TO_A : Direction.A_TO_B;
    }

    private IngestionBatch loadBatch(UUID batchId) {
        return ingestionBatchRepository
                .findByIdWithRepositoryAndTeam(batchId)
                .orElseThrow(() -> new IllegalStateException("Ingestion batch not found: " + batchId));
    }
}
