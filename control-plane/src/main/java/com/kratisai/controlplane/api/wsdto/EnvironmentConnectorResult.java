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
    record Terminate(
            @JsonProperty("status") TerminateStatus status,
            @JsonProperty("exitCode") int exitCode,
            @JsonProperty("error") String error)
            implements EnvironmentConnectorResult {
        public Terminate {
            Objects.requireNonNull(status, "status is required");
        }

        public Terminate(TerminateStatus status, int exitCode) {
            this(status, exitCode, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RegisterGitAuth(@JsonProperty("status") RegisterGitAuthStatus status) implements EnvironmentConnectorResult {
        public RegisterGitAuth {
            Objects.requireNonNull(status, "status is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GitDiffSummaryFile(
            @JsonProperty("path") String path,
            @JsonProperty("status") GitDiffStatus status,
            @JsonProperty("additions") int additions,
            @JsonProperty("deletions") int deletions,
            @JsonProperty("isCollapsedByDefault") boolean isCollapsedByDefault) {
        public GitDiffSummaryFile {
            Objects.requireNonNull(path, "path is required");
            Objects.requireNonNull(status, "status is required");
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
    record GitDiffSummary(
            @JsonProperty("baseCommit") String baseCommit,
            @JsonProperty("headCommit") String headCommit,
            @JsonProperty("totalAdditions") int totalAdditions,
            @JsonProperty("totalDeletions") int totalDeletions,
            @JsonProperty("commitsAhead") Integer commitsAhead,
            @JsonProperty("stagedFiles") Integer stagedFiles,
            @JsonProperty("unstagedFiles") Integer unstagedFiles,
            @JsonProperty("hasChanges") Boolean hasChanges,
            @JsonProperty("commitMessages") List<GitCommitMessage> commitMessages,
            @JsonProperty("files") List<GitDiffSummaryFile> files)
            implements EnvironmentConnectorResult {
        public GitDiffSummary {
            Objects.requireNonNull(baseCommit, "baseCommit is required");
            Objects.requireNonNull(headCommit, "headCommit is required");
            Objects.requireNonNull(files, "files is required");
            files = List.copyOf(files);
            commitMessages = commitMessages != null ? List.copyOf(commitMessages) : List.of();
        }

        public GitDiffSummary(
                String baseCommit,
                String headCommit,
                int totalAdditions,
                int totalDeletions,
                List<GitDiffSummaryFile> files) {
            this(baseCommit, headCommit, totalAdditions, totalDeletions, 0, 0, 0, !files.isEmpty(), List.of(), files);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GitFileDiff(
            @JsonProperty("path") String path,
            @JsonProperty("patch") String patch,
            @JsonProperty("additions") int additions,
            @JsonProperty("deletions") int deletions,
            @JsonProperty("totalLines") int totalLines)
            implements EnvironmentConnectorResult {
        public GitFileDiff {
            Objects.requireNonNull(path, "path is required");
            Objects.requireNonNull(patch, "patch is required");
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
    record GitPush(
            @JsonProperty("commitSha") String commitSha,
            @JsonProperty("branchName") String branchName,
            @JsonProperty("remoteRef") String remoteRef,
            @JsonProperty("status") String status)
            implements EnvironmentConnectorResult {
        public GitPush {
            Objects.requireNonNull(commitSha, "commitSha is required");
            Objects.requireNonNull(branchName, "branchName is required");
            Objects.requireNonNull(status, "status is required");
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
}
