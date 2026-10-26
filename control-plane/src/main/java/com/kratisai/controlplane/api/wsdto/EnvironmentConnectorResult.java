package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/**
 * Connector → control-plane result payloads. Control-plane → connector responses live in
 * {@link EnvironmentResponsePayload}.
 */
public sealed interface EnvironmentConnectorResult {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Exec(
            @JsonProperty("status") ExecStatus status,
            @JsonProperty("exitCode") int exitCode,
            @JsonProperty("error") String error)
            implements EnvironmentConnectorResult {
        public Exec {
            Objects.requireNonNull(status, "status is required");
        }

        public Exec(ExecStatus status, int exitCode) {
            this(status, exitCode, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LaunchAcpAgent(
            @JsonProperty("status") LaunchStatus status,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("agentName") String agentName,
            @JsonProperty("agentVersion") String agentVersion,
            @JsonProperty("error") String error)
            implements EnvironmentConnectorResult {
        public LaunchAcpAgent {
            Objects.requireNonNull(status, "status is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AcpPrompt(
            @JsonProperty("status") PromptStatus status,
            @JsonProperty("stopReason") StopReason stopReason,
            @JsonProperty("error") String error)
            implements EnvironmentConnectorResult {
        public AcpPrompt {
            Objects.requireNonNull(status, "status is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CancelAgent(
            @JsonProperty("status") CancelAgentStatus status,
            @JsonProperty("exitCode") int exitCode,
            @JsonProperty("error") String error)
            implements EnvironmentConnectorResult {
        public CancelAgent {
            Objects.requireNonNull(status, "status is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RegisterGitAuth(@JsonProperty("status") GitRegistrationStatus status) implements EnvironmentConnectorResult {
        public RegisterGitAuth {
            Objects.requireNonNull(status, "status is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RegisterGitIdentity(@JsonProperty("status") GitRegistrationStatus status)
            implements EnvironmentConnectorResult {
        public RegisterGitIdentity {
            Objects.requireNonNull(status, "status is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GitDiffManifestFile(
            @JsonProperty("path") String path,
            @JsonProperty("status") GitDiffStatus status,
            @JsonProperty("additions") int additions,
            @JsonProperty("deletions") int deletions,
            @JsonProperty("isCollapsedByDefault") boolean isCollapsedByDefault,
            @JsonProperty("sha") String sha,
            @JsonProperty("size") int size) {
        public GitDiffManifestFile {
            Objects.requireNonNull(path, "path is required");
            Objects.requireNonNull(status, "status is required");
            Objects.requireNonNull(sha, "sha is required");
        }
    }

    record DiffSectionPart(
            @JsonProperty("sha") String sha,
            @JsonProperty("partIndex") int partIndex,
            @JsonProperty("partCount") int partCount,
            @JsonProperty("data") String data) {
        public DiffSectionPart {
            Objects.requireNonNull(sha, "sha is required");
            Objects.requireNonNull(data, "data is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GitCommitMessage(
            @JsonProperty("sha") String sha,
            @JsonProperty("subject") String subject,
            @JsonProperty("body") String body) {
        public GitCommitMessage {
            Objects.requireNonNull(sha, "sha is required");
            Objects.requireNonNull(subject, "subject is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReadFileSlice(
            @JsonProperty("path") String path,
            @JsonProperty("startLine") int startLine,
            @JsonProperty("lines") List<String> lines)
            implements EnvironmentConnectorResult {
        public ReadFileSlice {
            Objects.requireNonNull(path, "path is required");
            Objects.requireNonNull(lines, "lines is required");
            lines = List.copyOf(lines);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GetLogs(@JsonProperty("lines") List<String> lines) implements EnvironmentConnectorResult {
        public GetLogs {
            Objects.requireNonNull(lines, "lines is required");
            lines = List.copyOf(lines);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GitPush(
            @JsonProperty("commitSha") String commitSha,
            @JsonProperty("branchName") String branchName,
            @JsonProperty("remoteRef") String remoteRef,
            @JsonProperty("status") String status,
            @JsonProperty("requestedBranch") String requestedBranch,
            @JsonProperty("branchCreated") Boolean branchCreated,
            @JsonProperty("previousBranchMerged") Boolean previousBranchMerged)
            implements EnvironmentConnectorResult {
        public GitPush {
            Objects.requireNonNull(commitSha, "commitSha is required");
            Objects.requireNonNull(branchName, "branchName is required");
            Objects.requireNonNull(status, "status is required");
        }

        public GitPush(String commitSha, String branchName, String remoteRef, String status) {
            this(commitSha, branchName, remoteRef, status, null, null, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GitSetRemote(
            @JsonProperty("status") String status,
            @JsonProperty("defaultBranch") String defaultBranch,
            @JsonProperty("seedCommit") String seedCommit)
            implements EnvironmentConnectorResult {
        public GitSetRemote {
            Objects.requireNonNull(status, "status is required");
            Objects.requireNonNull(defaultBranch, "defaultBranch is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GitChangeSetFile(
            @JsonProperty("path") String path,
            @JsonProperty("status") String status,
            @JsonProperty("additions") int additions,
            @JsonProperty("deletions") int deletions) {
        public GitChangeSetFile {
            Objects.requireNonNull(path, "path is required");
            Objects.requireNonNull(status, "status is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GitChangeSetStats(
            @JsonProperty("additions") int additions,
            @JsonProperty("deletions") int deletions,
            @JsonProperty("files") List<GitChangeSetFile> files,
            @JsonProperty("digest") String digest) {
        public GitChangeSetStats {
            files = files != null ? List.copyOf(files) : List.of();
            Objects.requireNonNull(digest, "digest is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GitObservePublish(
            @JsonProperty("defaultBranch") String defaultBranch,
            @JsonProperty("defaultSha") String defaultSha,
            @JsonProperty("executionBaseSha") String executionBaseSha,
            @JsonProperty("defaultIsAncestorOfHead") Boolean defaultIsAncestorOfHead,
            @JsonProperty("incomingCommitCount") int incomingCommitCount,
            @JsonProperty("commitCount") int commitCount,
            @JsonProperty("hasUncommittedChanges") Boolean hasUncommittedChanges,
            @JsonProperty("headSha") String headSha,
            @JsonProperty("changeSet") GitChangeSetStats changeSet)
            implements EnvironmentConnectorResult {
        public GitObservePublish {
            Objects.requireNonNull(defaultBranch, "defaultBranch is required");
            Objects.requireNonNull(defaultSha, "defaultSha is required");
            Objects.requireNonNull(executionBaseSha, "executionBaseSha is required");
            Objects.requireNonNull(defaultIsAncestorOfHead, "defaultIsAncestorOfHead is required");
            Objects.requireNonNull(hasUncommittedChanges, "hasUncommittedChanges is required");
            Objects.requireNonNull(changeSet, "changeSet is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GitRebase(
            @JsonProperty("newBaseSha") String newBaseSha,
            @JsonProperty("changeSet") GitChangeSetStats changeSet) implements EnvironmentConnectorResult {
        public GitRebase {
            Objects.requireNonNull(newBaseSha, "newBaseSha is required");
            Objects.requireNonNull(changeSet, "changeSet is required");
        }
    }
}
