package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kratisai.controlplane.api.wsprotocol.Direction;
import com.kratisai.controlplane.api.wsprotocol.MessageKind;
import com.kratisai.controlplane.model.CredentialType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sealed set of every connector ({@code /ws/env}) request/notification payload. The sealed
 * sub-interfaces encode the protocol metadata (message kind and direction) as defaults, so each
 * record declares only its fields and wire method name.
 */
public sealed interface EnvironmentRpcPayload extends RpcPayload
        permits EnvironmentRpcPayload.InboundRequestPayload,
                EnvironmentRpcPayload.InboundNotificationPayload,
                EnvironmentRpcPayload.OutboundRequestPayload,
                EnvironmentRpcPayload.OutboundNotificationPayload {

    /** Sidecar → control-plane requests that expect a response. */
    sealed interface InboundRequestPayload extends EnvironmentRpcPayload {

        @Override
        default MessageKind messageKind() {
            return MessageKind.REQUEST;
        }

        @Override
        default Direction direction() {
            return Direction.CONNECTOR_TO_CONTROL_PLANE;
        }
    }

    /** Sidecar → control-plane notifications (no response expected). */
    sealed interface InboundNotificationPayload extends EnvironmentRpcPayload {

        @Override
        default MessageKind messageKind() {
            return MessageKind.NOTIFICATION;
        }

        @Override
        default Direction direction() {
            return Direction.CONNECTOR_TO_CONTROL_PLANE;
        }
    }

    /** Control-plane → sidecar requests that expect a response. */
    sealed interface OutboundRequestPayload<R extends EnvironmentConnectorResult> extends EnvironmentRpcPayload {

        @Override
        default MessageKind messageKind() {
            return MessageKind.REQUEST;
        }

        @Override
        default Direction direction() {
            return Direction.CONTROL_PLANE_TO_CONNECTOR;
        }

        @JsonIgnore
        @SuppressWarnings("unchecked")
        default Class<R> resultType() {
            return (Class<R>)
                    switch (this) {
                        case Exec ignored -> EnvironmentConnectorResult.Exec.class;
                        case LaunchAcpAgent ignored -> EnvironmentConnectorResult.LaunchAcpAgent.class;
                        case AcpPrompt ignored -> EnvironmentConnectorResult.AcpPrompt.class;
                        case Terminate ignored -> EnvironmentConnectorResult.Terminate.class;
                        case RegisterGitAuth ignored -> EnvironmentConnectorResult.RegisterGitAuth.class;
                        case GitDiffSummary ignored -> EnvironmentConnectorResult.GitDiffSummary.class;
                        case GitFileDiff ignored -> EnvironmentConnectorResult.GitFileDiff.class;
                        case ReadFileSlice ignored -> EnvironmentConnectorResult.ReadFileSlice.class;
                        case GitPush ignored -> EnvironmentConnectorResult.GitPush.class;
                    };
        }
    }

    /** Completion is asynchronous. */
    sealed interface OutboundNotificationPayload extends EnvironmentRpcPayload {

        @Override
        default MessageKind messageKind() {
            return MessageKind.NOTIFICATION;
        }

        @Override
        default Direction direction() {
            return Direction.CONTROL_PLANE_TO_CONNECTOR;
        }
    }

    /** Authenticate and connect a sidecar. */
    record Register(
            @JsonProperty("token") String token,
            @JsonProperty("containerId") String containerId,
            @JsonProperty("isReconnect") Boolean isReconnect,
            @JsonProperty("activeAcpSessionId") String activeAcpSessionId,
            @JsonProperty("hasActiveAgent") Boolean hasActiveAgent,
            @JsonProperty("lastEventSequence") Long lastEventSequence,
            @JsonProperty("pendingHitlIds") List<String> pendingHitlIds,
            @JsonProperty("protocolVersion") Integer protocolVersion,
            @JsonProperty("connectorVersion") String connectorVersion)
            implements InboundRequestPayload {
        public static final String METHOD = "env.register";

        @Override
        public String method() {
            return METHOD;
        }

        public Register {
            Objects.requireNonNull(token, "token is required");
        }
    }

    /** Sidecar keepalive. */
    record Heartbeat() implements InboundRequestPayload, NoParamsPayload {
        public static final String METHOD = "env.heartbeat";

        @Override
        public String method() {
            return METHOD;
        }
    }

    /** Ask the control plane for a fresh git credential token for this environment. */
    record GitToken() implements InboundRequestPayload, NoParamsPayload {
        public static final String METHOD = "env.git_token";

        @Override
        public String method() {
            return METHOD;
        }
    }

    /** Ask the control plane for a human decision: authorize a command or answer a structured question. */
    record HitlRequest(
            @JsonProperty("hitlId") String hitlId,
            @JsonProperty("message") String message,
            @JsonProperty("kind") HitlKind kind,
            @JsonProperty("executionId") String executionId,
            @JsonProperty("command") String command,
            @JsonProperty("title") String title,
            @JsonProperty("toolKind") String toolKind,
            @JsonProperty("options") List<PermissionOption> options,
            @JsonProperty("diff") ActivityDiff diff,
            @JsonProperty("form") Map<String, Object> form)
            implements InboundRequestPayload {
        public static final String METHOD = "env.hitl_request";

        @Override
        public String method() {
            return METHOD;
        }

        public HitlRequest(
                String hitlId,
                String message,
                HitlKind kind,
                String executionId,
                String command,
                String title,
                String toolKind,
                List<PermissionOption> options,
                ActivityDiff diff,
                Map<String, Object> form) {
            this.hitlId = Objects.requireNonNull(hitlId, "hitlId is required");
            this.message = Objects.requireNonNull(message, "message is required");
            this.kind = Objects.requireNonNull(kind, "kind is required");
            this.executionId = Objects.requireNonNull(executionId, "executionId is required");
            this.command = command;
            this.title = title;
            this.toolKind = toolKind;
            this.options = options;
            this.diff = diff;
            this.form = form;
        }
    }

    /** Stream a sandbox output line. */
    record Output(
            @JsonProperty("line") String line,
            @JsonProperty("stream") OutputStream stream,
            @JsonProperty("executionId") String executionId)
            implements InboundNotificationPayload {
        public static final String METHOD = "env.output";

        @Override
        public String method() {
            return METHOD;
        }

        public Output(String line, OutputStream stream, String executionId) {
            this.line = Objects.requireNonNull(line, "line is required");
            this.stream = Objects.requireNonNull(stream, "stream is required");
            this.executionId = Objects.requireNonNull(executionId, "executionId is required");
        }
    }

    /** Report a connector-side failure (unexpected close, dropped frame) to the control plane. */
    record SidecarError(
            @JsonProperty("kind") SidecarErrorKind kind,
            @JsonProperty("message") String message,
            @JsonProperty("executionId") String executionId,
            @JsonProperty("closeCode") Integer closeCode,
            @JsonProperty("closeReason") String closeReason)
            implements InboundNotificationPayload {
        public static final String METHOD = "env.sidecar_error";

        @Override
        public String method() {
            return METHOD;
        }

        public SidecarError {
            Objects.requireNonNull(kind, "kind is required");
            Objects.requireNonNull(message, "message is required");
        }
    }

    /** Report agent activity on an execution. */
    record Activity(
            @JsonProperty("activityType") ActivityType activityType,
            @JsonProperty("description") String description,
            @JsonProperty("executionId") String executionId,
            @JsonProperty("actionId") String actionId,
            @JsonProperty("status") ActivityStatus status,
            @JsonProperty("detail") ActivityDetail detail)
            implements InboundNotificationPayload {
        public static final String METHOD = "env.activity";

        @Override
        public String method() {
            return METHOD;
        }

        public Activity(
                ActivityType activityType,
                String description,
                String executionId,
                String actionId,
                ActivityStatus status,
                ActivityDetail detail) {
            this.activityType = Objects.requireNonNull(activityType, "activityType is required");
            this.description = Objects.requireNonNull(description, "description is required");
            this.executionId = Objects.requireNonNull(executionId, "executionId is required");
            this.actionId = actionId;
            this.status = Objects.requireNonNull(status, "status is required");
            this.detail = detail;
        }
    }

    /** Report sandbox command completion. */
    record Complete(
            @JsonProperty("exitCode") Integer exitCode,
            @JsonProperty("executionId") String executionId) implements InboundNotificationPayload {
        public static final String METHOD = "env.complete";

        @Override
        public String method() {
            return METHOD;
        }

        public Complete(Integer exitCode, String executionId) {
            this.exitCode = Objects.requireNonNull(exitCode, "exitCode is required");
            this.executionId = Objects.requireNonNull(executionId, "executionId is required");
        }
    }

    /** Report repository checkout completion. */
    record CheckoutComplete(
            @JsonProperty("status") CheckoutStatus status,
            @JsonProperty("error") String error,
            @JsonProperty("commitHash") String commitHash,
            @JsonProperty("executionId") String executionId)
            implements InboundNotificationPayload {
        public static final String METHOD = "env.checkout_complete";

        @Override
        public String method() {
            return METHOD;
        }

        public CheckoutComplete(CheckoutStatus status, String error, String commitHash, String executionId) {
            this.status = Objects.requireNonNull(status, "status is required");
            this.error = error;
            this.commitHash = commitHash;
            this.executionId = Objects.requireNonNull(executionId, "executionId is required");
        }
    }

    /** Report that the ACP agent session was initialized. */
    record AcpInitialized(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("agentName") String agentName,
            @JsonProperty("agentVersion") String agentVersion,
            @JsonProperty("executionId") String executionId)
            implements InboundNotificationPayload {
        public static final String METHOD = "env.acp_initialized";

        @Override
        public String method() {
            return METHOD;
        }

        public AcpInitialized(String sessionId, String agentName, String agentVersion, String executionId) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId is required");
            this.agentName = Objects.requireNonNull(agentName, "agentName is required");
            this.agentVersion = Objects.requireNonNull(agentVersion, "agentVersion is required");
            this.executionId = Objects.requireNonNull(executionId, "executionId is required");
        }
    }

    /** Report that the ACP agent finished its turn. */
    record AcpPromptComplete(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("stopReason") StopReason stopReason,
            @JsonProperty("executionId") String executionId)
            implements InboundNotificationPayload {
        public static final String METHOD = "env.acp_prompt_complete";

        @Override
        public String method() {
            return METHOD;
        }

        public AcpPromptComplete(String sessionId, StopReason stopReason, String executionId) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId is required");
            this.stopReason = Objects.requireNonNull(stopReason, "stopReason is required");
            this.executionId = Objects.requireNonNull(executionId, "executionId is required");
        }
    }

    /** Run a shell command in the sandbox. */
    record Exec(
            @JsonProperty("command") String command,
            @JsonProperty("persistEnv") Boolean persistEnv,
            @JsonProperty("executionId") String executionId)
            implements OutboundRequestPayload<EnvironmentConnectorResult.Exec> {
        public static final String METHOD = "env.exec";

        @Override
        public String method() {
            return METHOD;
        }

        public Exec {
            Objects.requireNonNull(command, "command is required");
            Objects.requireNonNull(executionId, "executionId is required");
        }
    }

    /** Register git credentials with the sidecar. Tokens are resolved on-demand by the control plane. */
    record RegisterGitAuth(
            @JsonProperty("credentialType") String credentialType,
            @JsonProperty("privateKey") String privateKey,
            @JsonProperty("userName") String userName,
            @JsonProperty("userEmail") String userEmail)
            implements OutboundRequestPayload<EnvironmentConnectorResult.RegisterGitAuth> {
        public static final String METHOD = "env.registerGitAuth";

        private static final Set<String> SUPPORTED_TYPES =
                Set.of(CredentialType.GITHUB_APP.name(), CredentialType.PAT.name(), CredentialType.SSH_KEY.name());

        @Override
        public String method() {
            return METHOD;
        }

        public RegisterGitAuth {
            Objects.requireNonNull(credentialType, "credentialType is required");
            Objects.requireNonNull(userName, "userName is required");
            Objects.requireNonNull(userEmail, "userEmail is required");
            if (!SUPPORTED_TYPES.contains(credentialType)) {
                throw new IllegalArgumentException(
                        "Unsupported credentialType: " + credentialType + ". Expected one of " + SUPPORTED_TYPES);
            }
        }
    }

    /** Completion arrives via {@code env.checkout_complete} — never via a response envelope. */
    record Checkout(
            @JsonProperty("url") String url,
            @JsonProperty("branch") String branch,
            @JsonProperty("env") Map<String, String> env,
            @JsonProperty("executionId") String executionId)
            implements OutboundNotificationPayload {
        public static final String METHOD = "env.checkout";

        @Override
        public String method() {
            return METHOD;
        }

        public Checkout(String url, String branch, Map<String, String> env, String executionId) {
            this.url = Objects.requireNonNull(url, "url is required");
            this.branch = branch;
            this.env = env;
            this.executionId = Objects.requireNonNull(executionId, "executionId is required");
        }
    }

    /** Launch an ACP agent session. */
    record LaunchAcpAgent(
            @JsonProperty("agentCommand") String agentCommand,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("executionId") String executionId)
            implements OutboundRequestPayload<EnvironmentConnectorResult.LaunchAcpAgent> {
        public static final String METHOD = "env.launch_acp_agent";

        @Override
        public String method() {
            return METHOD;
        }

        public LaunchAcpAgent(String agentCommand, String modelName, String executionId) {
            this.agentCommand = Objects.requireNonNull(agentCommand, "agentCommand is required");
            this.modelName = modelName;
            this.executionId = Objects.requireNonNull(executionId, "executionId is required");
        }
    }

    /** Send the task prompt to an ACP agent session. */
    record AcpPrompt(
            @JsonProperty("taskPrompt") String taskPrompt,
            @JsonProperty("executionId") String executionId,
            @JsonProperty("isSteering") Boolean isSteering)
            implements OutboundRequestPayload<EnvironmentConnectorResult.AcpPrompt> {
        public static final String METHOD = "env.acp_prompt";

        @Override
        public String method() {
            return METHOD;
        }

        public AcpPrompt(String taskPrompt, String executionId) {
            this(taskPrompt, executionId, null);
        }

        public AcpPrompt(String taskPrompt, String executionId, Boolean isSteering) {
            this.taskPrompt = Objects.requireNonNull(taskPrompt, "taskPrompt is required");
            this.executionId = Objects.requireNonNull(executionId, "executionId is required");
            this.isSteering = isSteering;
        }
    }

    /** Terminate the sandbox execution. */
    record Terminate() implements OutboundRequestPayload<EnvironmentConnectorResult.Terminate>, NoParamsPayload {
        public static final String METHOD = "env.terminate";

        @Override
        public String method() {
            return METHOD;
        }
    }

    /** Retrieve git diff summary manifest for working tree / commits. */
    record GitDiffSummary(
            @JsonProperty("baseBranch") String baseBranch,
            @JsonProperty("executionId") String executionId)
            implements OutboundRequestPayload<EnvironmentConnectorResult.GitDiffSummary> {
        public static final String METHOD = "env.git_diff_summary";

        @Override
        public String method() {
            return METHOD;
        }
    }

    /** Retrieve git unified diff patch for a single file. */
    record GitFileDiff(
            @JsonProperty("path") String path,
            @JsonProperty("baseBranch") String baseBranch,
            @JsonProperty("executionId") String executionId)
            implements OutboundRequestPayload<EnvironmentConnectorResult.GitFileDiff> {
        public static final String METHOD = "env.git_file_diff";

        @Override
        public String method() {
            return METHOD;
        }

        public GitFileDiff {
            Objects.requireNonNull(path, "path is required");
        }
    }

    /** Read slice of lines from a workspace file for hunk expansion. */
    record ReadFileSlice(
            @JsonProperty("path") String path,
            @JsonProperty("startLine") int startLine,
            @JsonProperty("endLine") int endLine,
            @JsonProperty("executionId") String executionId)
            implements OutboundRequestPayload<EnvironmentConnectorResult.ReadFileSlice> {
        public static final String METHOD = "env.read_file_slice";

        @Override
        public String method() {
            return METHOD;
        }

        public ReadFileSlice {
            Objects.requireNonNull(path, "path is required");
        }
    }

    /** Stage, commit, rebase, and push branch to remote origin. */
    record GitPush(
            @JsonProperty("branchName") String branchName,
            @JsonProperty("commitMessage") String commitMessage,
            @JsonProperty("force") Boolean force,
            @JsonProperty("squash") Boolean squash,
            @JsonProperty("targetBranch") String targetBranch,
            @JsonProperty("executionId") String executionId)
            implements OutboundRequestPayload<EnvironmentConnectorResult.GitPush> {
        public static final String METHOD = "env.git_push";

        @Override
        public String method() {
            return METHOD;
        }

        public GitPush {
            Objects.requireNonNull(branchName, "branchName is required");
        }
    }
}
