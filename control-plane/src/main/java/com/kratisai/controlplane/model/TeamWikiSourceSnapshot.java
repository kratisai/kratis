package com.kratisai.controlplane.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable manifest of active repository batches and current relations captured at TeamWikiBatch start. */
public record TeamWikiSourceSnapshot(List<RepoEntry> repos, List<RelationEntry> relations) {

    public TeamWikiSourceSnapshot {
        repos = repos == null ? List.of() : List.copyOf(repos);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    public record RepoEntry(UUID repoId, String repoName, UUID batchId, String commitHash, RepoRole repoRole) {
        public RepoEntry {
            Objects.requireNonNull(repoId, "repoId");
            Objects.requireNonNull(repoName, "repoName");
            Objects.requireNonNull(batchId, "batchId");
        }
    }

    public record RelationEntry(
            UUID repoAId,
            String repoAName,
            UUID repoBId,
            String repoBName,
            Nature nature,
            Direction direction,
            Ceiling confidence) {
        public RelationEntry {
            Objects.requireNonNull(repoAId, "repoAId");
            Objects.requireNonNull(repoAName, "repoAName");
            Objects.requireNonNull(repoBId, "repoBId");
            Objects.requireNonNull(repoBName, "repoBName");
            Objects.requireNonNull(nature, "nature");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(confidence, "confidence");
        }
    }
}
