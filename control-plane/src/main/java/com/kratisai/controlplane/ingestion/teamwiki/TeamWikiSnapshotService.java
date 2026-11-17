package com.kratisai.controlplane.ingestion.teamwiki;

import com.kratisai.controlplane.model.CtxRepoRelation;
import com.kratisai.controlplane.model.CtxRepoSynopsis;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Nature;
import com.kratisai.controlplane.model.RepoRole;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot.RelationEntry;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot.RepoEntry;
import com.kratisai.controlplane.repository.CtxRepoRelationRepository;
import com.kratisai.controlplane.repository.CtxRepoSynopsisRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamWikiSnapshotService {

    private final IngestionBatchRepository ingestionBatchRepository;
    private final CtxRepoSynopsisRepository synopsisRepository;
    private final CtxRepoRelationRepository relationRepository;

    public TeamWikiSnapshotService(
            IngestionBatchRepository ingestionBatchRepository,
            CtxRepoSynopsisRepository synopsisRepository,
            CtxRepoRelationRepository relationRepository) {
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.synopsisRepository = synopsisRepository;
        this.relationRepository = relationRepository;
    }

    @Transactional(readOnly = true)
    public SnapshotState captureCurrentState(UUID teamId) {
        List<IngestionBatch> activeBatches = ingestionBatchRepository.findActiveByTeamId(teamId);
        List<UUID> batchIds = activeBatches.stream().map(IngestionBatch::getId).toList();
        Map<UUID, CtxRepoSynopsis> synopsesByBatch = new HashMap<>();
        if (!batchIds.isEmpty()) {
            for (CtxRepoSynopsis synopsis : synopsisRepository.findByBatchIdIn(batchIds)) {
                synopsesByBatch.put(synopsis.getBatch().getId(), synopsis);
            }
        }

        List<RepoEntry> repos = new ArrayList<>();
        Map<UUID, Instant> batchCompletedAtByRepo = new HashMap<>();
        for (IngestionBatch batch : activeBatches) {
            CtxRepoSynopsis synopsis = synopsesByBatch.get(batch.getId());
            RepoRole role = synopsis != null ? synopsis.getRepoRole() : null;
            UUID repoId = batch.getRepository().getId();
            repos.add(
                    new RepoEntry(repoId, batch.getRepository().getName(), batch.getId(), batch.getCommitHash(), role));
            batchCompletedAtByRepo.put(
                    repoId, batch.getCompletedAt() != null ? batch.getCompletedAt() : batch.getStartedAt());
        }
        repos.sort(Comparator.comparing(RepoEntry::repoId));

        List<CtxRepoRelation> currentRelations = relationRepository.findCurrentByTeamId(teamId);
        List<RelationEntry> relations = new ArrayList<>();
        Map<RelationEntry, Instant> relationCreatedAt = new HashMap<>();
        for (CtxRepoRelation rel : currentRelations) {
            RelationEntry entry = new RelationEntry(
                    rel.getRepositoryAId(),
                    rel.getBatchA().getRepository().getName(),
                    rel.getRepositoryBId(),
                    rel.getBatchB().getRepository().getName(),
                    rel.getNature(),
                    rel.getDirection(),
                    rel.getConfidence());
            relations.add(entry);
            relationCreatedAt.put(entry, rel.getCreatedAt());
        }
        relations.sort(Comparator.comparing(RelationEntry::repoAId)
                .thenComparing(RelationEntry::repoBId)
                .thenComparing(r -> r.nature().name())
                .thenComparing(r -> r.direction().name()));

        return new SnapshotState(
                new TeamWikiSourceSnapshot(repos, relations), batchCompletedAtByRepo, relationCreatedAt);
    }

    public SnapshotDiff diff(TeamWikiSourceSnapshot previous, SnapshotState currentState) {
        TeamWikiSourceSnapshot current = currentState.snapshot();
        if (previous == null) {
            Instant earliest = currentState.batchCompletedAtByRepo().values().stream()
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            return new SnapshotDiff(
                    true, current.repos(), List.of(), List.of(), current.relations(), List.of(), List.of(), earliest);
        }

        Map<UUID, RepoEntry> prevRepos = new LinkedHashMap<>();
        for (RepoEntry r : previous.repos()) {
            prevRepos.put(r.repoId(), r);
        }
        Map<UUID, RepoEntry> currRepos = new LinkedHashMap<>();
        for (RepoEntry r : current.repos()) {
            currRepos.put(r.repoId(), r);
        }

        List<RepoEntry> addedRepos = new ArrayList<>();
        List<RepoEntry> removedRepos = new ArrayList<>();
        List<RoleChange> roleChanges = new ArrayList<>();
        List<RepoEntry> reingestedRepos = new ArrayList<>();
        List<Instant> structuralTimestamps = new ArrayList<>();

        for (RepoEntry curr : current.repos()) {
            RepoEntry prev = prevRepos.get(curr.repoId());
            if (prev == null) {
                addedRepos.add(curr);
                Instant ts = currentState.batchCompletedAtByRepo().get(curr.repoId());
                if (ts != null) {
                    structuralTimestamps.add(ts);
                }
            } else {
                if (prev.repoRole() != curr.repoRole()) {
                    roleChanges.add(new RoleChange(curr.repoName(), prev.repoRole(), curr.repoRole()));
                    Instant ts = currentState.batchCompletedAtByRepo().get(curr.repoId());
                    if (ts != null) {
                        structuralTimestamps.add(ts);
                    }
                }
                if (!Objects.equals(prev.batchId(), curr.batchId())
                        || !Objects.equals(prev.commitHash(), curr.commitHash())) {
                    reingestedRepos.add(curr);
                }
            }
        }

        for (RepoEntry prev : previous.repos()) {
            if (!currRepos.containsKey(prev.repoId())) {
                removedRepos.add(prev);
            }
        }

        Set<RelationEntry> prevRelSet = new HashSet<>(previous.relations());
        Set<RelationEntry> currRelSet = new HashSet<>(current.relations());

        List<RelationEntry> addedRelations = new ArrayList<>();
        for (RelationEntry rel : current.relations()) {
            if (!prevRelSet.contains(rel)) {
                addedRelations.add(rel);
                Instant ts = currentState.relationCreatedAt().get(rel);
                if (ts != null) {
                    structuralTimestamps.add(ts);
                }
            }
        }

        List<RelationEntry> removedRelations = new ArrayList<>();
        for (RelationEntry rel : previous.relations()) {
            if (!currRelSet.contains(rel)) {
                removedRelations.add(rel);
            }
        }

        boolean hasStructuralChange = !addedRepos.isEmpty()
                || !removedRepos.isEmpty()
                || !roleChanges.isEmpty()
                || !addedRelations.isEmpty()
                || !removedRelations.isEmpty();

        Instant earliestStructural =
                structuralTimestamps.stream().min(Comparator.naturalOrder()).orElse(null);

        return new SnapshotDiff(
                hasStructuralChange,
                List.copyOf(addedRepos),
                List.copyOf(removedRepos),
                List.copyOf(roleChanges),
                List.copyOf(addedRelations),
                List.copyOf(removedRelations),
                List.copyOf(reingestedRepos),
                earliestStructural);
    }

    static String renderRelation(String nameA, String nameB, Nature nature, Direction direction) {
        return switch (nature) {
            case CALLS ->
                switch (direction) {
                    case A_TO_B -> nameB + " calls " + nameA;
                    case B_TO_A -> nameA + " calls " + nameB;
                    case BIDIRECTIONAL, UNKNOWN -> nameA + " calls " + nameB + " (direction unconfirmed)";
                };
            case BUILDS_AGAINST ->
                switch (direction) {
                    case A_TO_B -> nameB + " builds against " + nameA;
                    case B_TO_A -> nameA + " builds against " + nameB;
                    case BIDIRECTIONAL, UNKNOWN -> nameA + " builds against " + nameB + " (direction unconfirmed)";
                };
            case HOSTS ->
                switch (direction) {
                    case A_TO_B -> nameA + " hosts " + nameB;
                    case B_TO_A -> nameB + " hosts " + nameA;
                    case BIDIRECTIONAL, UNKNOWN -> nameA + " hosts " + nameB + " (direction unconfirmed)";
                };
            case MESSAGES -> nameA + " exchanges messages with " + nameB;
            case SHARES_STATE -> nameA + " shares persistent state with " + nameB;
            case PEER -> nameA + " collaborates as peer with " + nameB;
        };
    }

    public record SnapshotState(
            TeamWikiSourceSnapshot snapshot,
            Map<UUID, Instant> batchCompletedAtByRepo,
            Map<RelationEntry, Instant> relationCreatedAt) {}

    public record RoleChange(String repoName, RepoRole previousRole, RepoRole currentRole) {}

    public record SnapshotDiff(
            boolean hasStructuralChange,
            List<RepoEntry> addedRepos,
            List<RepoEntry> removedRepos,
            List<RoleChange> roleChanges,
            List<RelationEntry> addedRelations,
            List<RelationEntry> removedRelations,
            List<RepoEntry> reingestedRepos,
            Instant earliestStructuralChangeAt) {}
}
