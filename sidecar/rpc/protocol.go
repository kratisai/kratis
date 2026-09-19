package rpc

import (
	"encoding/json"

	"kratis-connector/acp"
)

// JsonRpcRequest represents a JSON-RPC 2.0 request or notification.
type JsonRpcRequest struct {
	JsonRPC string      `json:"jsonrpc"`
	Method  string      `json:"method"`
	Params  interface{} `json:"params,omitempty"`
	ID      interface{} `json:"id,omitempty"`
}

// JsonRpcResponse represents a JSON-RPC 2.0 response.
type JsonRpcResponse struct {
	JsonRPC string          `json:"jsonrpc"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   *JsonRpcError   `json:"error,omitempty"`
	ID      interface{}     `json:"id"`
}

// JsonRpcError represents a JSON-RPC 2.0 error object.
type JsonRpcError struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

// ActivityType aliases the acp package's closed set of env.activity activityType values.
type ActivityType = acp.ActivityType

const (
	ActivityThinking    ActivityType = "THINKING"
	ActivityResearch    ActivityType = "RESEARCH"
	ActivityEdited      ActivityType = "EDITED"
	ActivityCommand     ActivityType = "COMMAND"
	ActivityMessage     ActivityType = "MESSAGE"
	ActivityElicitation ActivityType = "ELICITATION"
	ActivityPlan        ActivityType = "PLAN"
)

// ActivityStatus is the closed set of env.activity status values, mirroring
// the ACP ToolCallStatus lifecycle (pending → in_progress → completed | failed).
type ActivityStatus string

const (
	ActivityPending    ActivityStatus = "pending"
	ActivityInProgress ActivityStatus = "in_progress"
	ActivityCompleted  ActivityStatus = "completed"
	ActivityFailed     ActivityStatus = "failed"
)

// StopReason is the closed set of ACP session/prompt stop reasons.
type StopReason string

const (
	StopReasonEndTurn         StopReason = "end_turn"
	StopReasonMaxTokens       StopReason = "max_tokens"
	StopReasonMaxTurnRequests StopReason = "max_turn_requests"
	StopReasonRefusal         StopReason = "refusal"
	StopReasonCancelled       StopReason = "cancelled"
)

// ValidStopReasons is the set of accepted ACP stop reasons.
var ValidStopReasons = map[StopReason]bool{
	StopReasonEndTurn:         true,
	StopReasonMaxTokens:       true,
	StopReasonMaxTurnRequests: true,
	StopReasonRefusal:         true,
	StopReasonCancelled:       true,
}

// OutputStream is stdout or stderr for env.output.
type OutputStream string

const (
	StreamStdout OutputStream = "stdout"
	StreamStderr OutputStream = "stderr"
)

// CheckoutStatus is the closed set of env.checkout_complete status values.
type CheckoutStatus string

const (
	CheckoutSuccess CheckoutStatus = "success"
	CheckoutFailed  CheckoutStatus = "failed"
)

// ExecStatus is the closed set of env.exec result status values.
type ExecStatus string

const (
	ExecCompleted ExecStatus = "completed"
	ExecFailed    ExecStatus = "failed"
)

// LaunchStatus is the closed set of env.launch_acp_agent result status values.
type LaunchStatus string

const (
	LaunchLaunched LaunchStatus = "launched"
	LaunchFailed   LaunchStatus = "failed"
)

// PromptStatus is the closed set of env.acp_prompt result status values.
type PromptStatus string

const (
	PromptCompleted PromptStatus = "completed"
	PromptFailed    PromptStatus = "failed"
)

// TerminateStatus is the closed set of env.terminate result status values.
type TerminateStatus string

const (
	TerminateCompleted  TerminateStatus = "completed"
	TerminateTerminated TerminateStatus = "terminated"
)

// RegisterResultType is the discriminator for the env.register result payload.
type RegisterResultType string

const RegisterResultTypeEnvRegister RegisterResultType = "env_register"

// RegisterStatus is the closed set of env.register result status values.
type RegisterStatus string

const (
	RegisterStatusRegistered  RegisterStatus = "registered"
	RegisterStatusReconnected RegisterStatus = "reconnected"
)

// HeartbeatResultType is the discriminator for the env.heartbeat result payload.
type HeartbeatResultType string

const HeartbeatResultTypeEnvHeartbeat HeartbeatResultType = "env_heartbeat"

// HeartbeatStatus is the only valid env.heartbeat result status.
type HeartbeatStatus string

const HeartbeatStatusOK HeartbeatStatus = "ok"

// GitAuthStatus is the only valid env.registerGitAuth result status.
type GitAuthStatus string

const GitAuthStatusSuccess GitAuthStatus = "success"

// CredentialType is the closed set of credential types for env.registerGitAuth.
type CredentialType string

const (
	CredentialTypeGitHubApp CredentialType = "GITHUB_APP" //nolint:gosec // G101 false positive: protocol type discriminator, not a credential
	CredentialTypePAT       CredentialType = "PAT"
	CredentialTypeSSHKey    CredentialType = "SSH_KEY"
)

type RegisterParams struct {
	Token              string   `json:"token"`
	ContainerID        string   `json:"containerId,omitempty"`
	IsReconnect        bool     `json:"isReconnect,omitempty"`
	ActiveAcpSessionID string   `json:"activeAcpSessionId,omitempty"`
	HasActiveAgent     bool     `json:"hasActiveAgent,omitempty"`
	LastEventSequence  int64    `json:"lastEventSequence,omitempty"`
	PendingHitlIDs     []string `json:"pendingHitlIds,omitempty"`
	ProtocolVersion    int      `json:"protocolVersion,omitempty"`
	ConnectorVersion   string   `json:"connectorVersion,omitempty"`
}

// RegisterResult is the success result payload for the env.register method.
type RegisterResult struct {
	Type          RegisterResultType `json:"type"`
	Status        RegisterStatus     `json:"status"`
	EnvironmentID string             `json:"environmentId"`
}

// HeartbeatParams is the parameters payload for the env.heartbeat method.
type HeartbeatParams struct{}

// HeartbeatResult is the success result payload for the env.heartbeat method.
type HeartbeatResult struct {
	Type   HeartbeatResultType `json:"type"`
	Status HeartbeatStatus     `json:"status"`
}

// GitTokenParams is the parameters payload for the env.git_token method.
type GitTokenParams struct{}

// GitTokenResult is the success result payload for the env.git_token method.
type GitTokenResult struct {
	Token string `json:"token"`
}

// ExecParams is the payload sent from server to execute a shell command.
type ExecParams struct {
	Command     string `json:"command"`
	PersistEnv  bool   `json:"persistEnv,omitempty"`
	ExecutionID string `json:"executionId"`
}

// ExecResult is the success response payload for env.exec.
type ExecResult struct {
	Status   ExecStatus `json:"status"`
	ExitCode int        `json:"exitCode"`
	Error    string     `json:"error,omitempty"`
}

// PermissionOption is one selectable option offered to the user for a HITL
// approval request.
type PermissionOption struct {
	OptionID string `json:"optionId"`
	Name     string `json:"name"`
	Kind     string `json:"kind"`
}

// HitlKind discriminates a HITL request: a command/tool approval or a
// structured question (form).
type HitlKind string

const (
	HitlApproval HitlKind = "approval"
	HitlQuestion HitlKind = "question"
)

// HitlRequest is the unified human-in-the-loop request from the sidecar
// (was env.request_permission and env.create_elicitation). kind=approval asks
// the user to authorize a command; kind=question asks a structured question
// with a form schema.
type HitlRequest struct {
	HitlID      string   `json:"hitlId"`
	Message     string   `json:"message"`
	Kind        HitlKind `json:"kind"`
	ExecutionID string   `json:"executionId"`

	// kind=approval
	Command  string             `json:"command,omitempty"`
	Title    string             `json:"title,omitempty"`
	ToolKind string             `json:"toolKind,omitempty"`
	Options  []PermissionOption `json:"options,omitempty"`
	Diff     *ActivityDiff      `json:"diff,omitempty"`

	// kind=question
	Form map[string]any `json:"form,omitempty"`
}

// HitlResponse is the user's answer to a HITL request.
type HitlResponse string

const (
	HitlApproved  HitlResponse = "approved"
	HitlAnswered  HitlResponse = "answered"
	HitlDeclined  HitlResponse = "declined"
	HitlCancelled HitlResponse = "cancelled"
)

// HitlResult is the user's response to a HITL request: response=approved
// carries the selected optionId; response=answered carries the submitted form
// content.
type HitlResult struct {
	Response HitlResponse   `json:"response"`
	OptionID string         `json:"optionId,omitempty"`
	Content  map[string]any `json:"content,omitempty"`
}

// OutputParams is sent by the sidecar to stream stdout/stderr lines in real-time.
type OutputParams struct {
	Line        string       `json:"line"`
	Stream      OutputStream `json:"stream"`
	ExecutionID string       `json:"executionId"`
}

// SidecarErrorKind is the closed set of connector-reported error kinds.
type SidecarErrorKind string

const (
	// SidecarErrorConnectionClosed reports an unexpected control-plane close
	// (message carries the close code/reason).
	SidecarErrorConnectionClosed SidecarErrorKind = "connection_closed"
	// SidecarErrorFrameDropped reports an outbound frame dropped by the
	// transport size guard (message carries method and byte size).
	SidecarErrorFrameDropped SidecarErrorKind = "frame_dropped"
)

// SidecarErrorParams reports a connector-side failure to the control plane.
type SidecarErrorParams struct {
	Kind        SidecarErrorKind `json:"kind"`
	Message     string           `json:"message"`
	ExecutionID string           `json:"executionId,omitempty"`
	CloseCode   *int             `json:"closeCode,omitempty"`
	CloseReason string           `json:"closeReason,omitempty"`
}

// CompleteParams is sent by the sidecar upon execution completion.
type CompleteParams struct {
	ExitCode    int    `json:"exitCode"`
	ExecutionID string `json:"executionId"`
}

// RegisterGitAuthParams is the parameters payload for the env.registerGitAuth method.
type RegisterGitAuthParams struct {
	CredentialType CredentialType `json:"credentialType"`
	PrivateKey     string         `json:"privateKey,omitempty"`
	UserName       string         `json:"userName"`
	UserEmail      string         `json:"userEmail"`
}

type RegisterGitAuthResult struct {
	Status GitAuthStatus `json:"status"`
}

// CheckoutParams is the parameters payload for the env.checkout method.
type CheckoutParams struct {
	URL         string            `json:"url"`
	Branch      string            `json:"branch,omitempty"`
	Env         map[string]string `json:"env,omitempty"`
	ExecutionID string            `json:"executionId"`
}

// CheckoutCompleteParams is sent by the sidecar upon checkout completion.
type CheckoutCompleteParams struct {
	Status      CheckoutStatus `json:"status"`
	Error       string         `json:"error,omitempty"`
	CommitHash  string         `json:"commitHash,omitempty"`
	ExecutionID string         `json:"executionId"`
}

// LaunchAcpAgentParams is the parameters payload for the env.launch_acp_agent method.
type LaunchAcpAgentParams struct {
	AgentCommand string `json:"agentCommand"`
	ModelName    string `json:"modelName,omitempty"`
	ExecutionID  string `json:"executionId"`
}

// LaunchAcpAgentResult is the success response payload for env.launch_acp_agent.
type LaunchAcpAgentResult struct {
	Status       LaunchStatus `json:"status"`
	SessionID    string       `json:"sessionId,omitempty"`
	AgentName    string       `json:"agentName,omitempty"`
	AgentVersion string       `json:"agentVersion,omitempty"`
	Error        string       `json:"error,omitempty"`
}

// AcpPromptParams is the parameters payload for the env.acp_prompt method.
type AcpPromptParams struct {
	TaskPrompt  string `json:"taskPrompt"`
	ExecutionID string `json:"executionId"`
	IsSteering  bool   `json:"isSteering,omitempty"`
}

// AcpPromptResult is the success response payload for env.acp_prompt.
type AcpPromptResult struct {
	Status     PromptStatus `json:"status"`
	StopReason StopReason   `json:"stopReason,omitempty"`
	Error      string       `json:"error,omitempty"`
}

// TerminateParams is the parameters payload for the env.terminate method.
type TerminateParams struct {
	// Empty - session is identified by the WebSocket connection
}

// TerminateResult is the success response payload for env.terminate.
type TerminateResult struct {
	Status   TerminateStatus `json:"status"`
	ExitCode int             `json:"exitCode"`
	Error    string          `json:"error,omitempty"`
}

// GitDiffStatus represents file change status in diff summary.
type GitDiffStatus string

const (
	GitDiffModified GitDiffStatus = "MODIFIED"
	GitDiffAdded    GitDiffStatus = "ADDED"
	GitDiffDeleted  GitDiffStatus = "DELETED"
	GitDiffRenamed  GitDiffStatus = "RENAMED"
)

// GitDiffSummaryFile represents one changed file in env.git_diff_summary result.
type GitDiffSummaryFile struct {
	Path                 string        `json:"path"`
	Status               GitDiffStatus `json:"status"`
	Additions            int           `json:"additions"`
	Deletions            int           `json:"deletions"`
	IsCollapsedByDefault bool          `json:"isCollapsedByDefault"`
}

// GitDiffSummaryParams is the parameters payload for env.git_diff_summary.
type GitDiffSummaryParams struct {
	BaseBranch  string `json:"baseBranch,omitempty"`
	ExecutionID string `json:"executionId,omitempty"`
}

// GitCommitMessage represents one commit in the unpushed log.
type GitCommitMessage struct {
	Sha     string `json:"sha"`
	Subject string `json:"subject"`
	Body    string `json:"body"`
}

// GitDiffSummaryResult is the success response payload for env.git_diff_summary.
type GitDiffSummaryResult struct {
	BaseCommit     string               `json:"baseCommit"`
	HeadCommit     string               `json:"headCommit"`
	TotalAdditions int                  `json:"totalAdditions"`
	TotalDeletions int                  `json:"totalDeletions"`
	CommitsAhead   int                  `json:"commitsAhead"`
	StagedFiles    int                  `json:"stagedFiles"`
	UnstagedFiles  int                  `json:"unstagedFiles"`
	HasChanges     bool                 `json:"hasChanges"`
	CommitMessages []GitCommitMessage   `json:"commitMessages,omitempty"`
	Files          []GitDiffSummaryFile `json:"files"`
}

// GitFileDiffParams is the parameters payload for env.git_file_diff.
type GitFileDiffParams struct {
	Path        string `json:"path"`
	BaseBranch  string `json:"baseBranch,omitempty"`
	ExecutionID string `json:"executionId,omitempty"`
}

// GitFileDiffResult is the success response payload for env.git_file_diff.
type GitFileDiffResult struct {
	Path       string `json:"path"`
	Patch      string `json:"patch"`
	Additions  int    `json:"additions"`
	Deletions  int    `json:"deletions"`
	TotalLines int    `json:"totalLines"`
}

// ReadFileSliceParams is the parameters payload for env.read_file_slice.
type ReadFileSliceParams struct {
	Path        string `json:"path"`
	StartLine   int    `json:"startLine"`
	EndLine     int    `json:"endLine"`
	ExecutionID string `json:"executionId,omitempty"`
}

// ReadFileSliceResult is the success response payload for env.read_file_slice.
type ReadFileSliceResult struct {
	Path      string   `json:"path"`
	StartLine int      `json:"startLine"`
	Lines     []string `json:"lines"`
}

// GitPushParams is the parameters payload for env.git_push.
type GitPushParams struct {
	BranchName    string `json:"branchName"`
	CommitMessage string `json:"commitMessage,omitempty"`
	Force         bool   `json:"force,omitempty"`
	Squash        bool   `json:"squash,omitempty"`
	TargetBranch  string `json:"targetBranch,omitempty"`
	ExecutionID   string `json:"executionId,omitempty"`
}

// GitPushResult is the success response payload for env.git_push.
type GitPushResult struct {
	CommitSha  string `json:"commitSha"`
	BranchName string `json:"branchName"`
	RemoteRef  string `json:"remoteRef,omitempty"`
	Status     string `json:"status"`
}

// GitSetRemoteParams is the parameters payload for env.git_set_remote.
type GitSetRemoteParams struct {
	RemoteURL     string `json:"remoteUrl"`
	DefaultBranch string `json:"defaultBranch"`
	ExecutionID   string `json:"executionId,omitempty"`
}

// GitSetRemoteResult is the success response payload for env.git_set_remote.
type GitSetRemoteResult struct {
	Status        string `json:"status"`
	DefaultBranch string `json:"defaultBranch"`
	SeedCommit    string `json:"seedCommit,omitempty"`
}

// AcpInitializedParams is sent by the sidecar when ACP handshake completes.
type AcpInitializedParams struct {
	SessionID    string `json:"sessionId"`
	AgentName    string `json:"agentName"`
	AgentVersion string `json:"agentVersion"`
	ExecutionID  string `json:"executionId"`
}

// AcpPromptCompleteParams is sent by the sidecar when session/prompt completes.
type AcpPromptCompleteParams struct {
	SessionID   string     `json:"sessionId"`
	StopReason  StopReason `json:"stopReason"`
	ExecutionID string     `json:"executionId"`
}

// ActivityParams is sent by the sidecar to stream execution activity events.
// The status drives the lifecycle transition; detail carries the structured,
// accumulated tool/message state for the expandable activity record.
type ActivityParams struct {
	ActivityType ActivityType   `json:"activityType"`
	Description  string         `json:"description"`
	ExecutionID  string         `json:"executionId"`
	ActionID     string         `json:"actionId,omitempty"`
	Status       ActivityStatus `json:"status"`
	Detail       ActivityDetail `json:"detail"`
}

// ActivityKind is the closed set of ACP ToolKind values.
type ActivityKind string

const (
	KindRead       ActivityKind = "read"
	KindEdit       ActivityKind = "edit"
	KindDelete     ActivityKind = "delete"
	KindMove       ActivityKind = "move"
	KindSearch     ActivityKind = "search"
	KindExecute    ActivityKind = "execute"
	KindThink      ActivityKind = "think"
	KindFetch      ActivityKind = "fetch"
	KindSwitchMode ActivityKind = "switch_mode"
	KindOther      ActivityKind = "other"
)

// ApprovalOptionKind is the closed set of ACP PermissionOptionKind values.
type ApprovalOptionKind string

const (
	ApprovalAllowOnce    ApprovalOptionKind = "allow_once"
	ApprovalAllowAlways  ApprovalOptionKind = "allow_always"
	ApprovalRejectOnce   ApprovalOptionKind = "reject_once"
	ApprovalRejectAlways ApprovalOptionKind = "reject_always"
)

// ActivityDetail mirrors acp.ActivityDetail: structured, optional fields
// derived from the accumulated ACP tool/message state. input/meta/rawUpdate
// are agent-defined opaque bags preserved verbatim.
type ActivityDetail struct {
	Kind      ActivityKind       `json:"kind,omitempty"`
	Title     string             `json:"title,omitempty"`
	Locations []ActivityLocation `json:"locations,omitempty"`
	Input     map[string]any     `json:"input,omitempty"`
	Output    string             `json:"output,omitempty"`
	Diff      *ActivityDiff      `json:"diff,omitempty"`
	ExitCode  *int               `json:"exitCode,omitempty"`
	Truncated bool               `json:"truncated,omitempty"`
	Meta      map[string]any     `json:"meta,omitempty"`
	Hitl      *ActivityHitl      `json:"hitl,omitempty"`
	MessageID string             `json:"messageId,omitempty"`
	Role      string             `json:"role,omitempty"`
	Plan      []PlanEntry        `json:"plan,omitempty"`
	RawUpdate map[string]any     `json:"rawUpdate,omitempty"`
}

// PlanEntry mirrors acp.PlanEntry for the env.activity wire format.
type PlanEntry struct {
	Content  string            `json:"content"`
	Priority PlanEntryPriority `json:"priority"`
	Status   PlanEntryStatus   `json:"status"`
}

// PlanEntryPriority is the closed set of ACP PlanEntryPriority values.
type PlanEntryPriority string

const (
	PlanPriorityHigh   PlanEntryPriority = "high"
	PlanPriorityMedium PlanEntryPriority = "medium"
	PlanPriorityLow    PlanEntryPriority = "low"
)

// PlanEntryStatus is the closed set of ACP PlanEntryStatus values.
type PlanEntryStatus string

const (
	PlanStatusPending    PlanEntryStatus = "pending"
	PlanStatusInProgress PlanEntryStatus = "in_progress"
	PlanStatusCompleted  PlanEntryStatus = "completed"
)

// ActivityLocation is one file location referenced by a tool call.
type ActivityLocation struct {
	Path string `json:"path,omitempty"`
	Line *int   `json:"line,omitempty"`
}

// ActivityDiff is the before/after text of an edit tool.
type ActivityDiff struct {
	OldText string `json:"oldText,omitempty"`
	NewText string `json:"newText,omitempty"`
	Path    string `json:"path,omitempty"`
}

// ActivityHitl is the HITL request/response state attached to a tool activity,
// mirroring the control plane's ActivityHitl record.
type ActivityHitl struct {
	HitlID    string             `json:"hitlId,omitempty"`
	Kind      string             `json:"kind,omitempty"`
	Message   string             `json:"message,omitempty"`
	Command   string             `json:"command,omitempty"`
	Title     string             `json:"title,omitempty"`
	ToolKind  string             `json:"toolKind,omitempty"`
	Options   []PermissionOption `json:"options,omitempty"`
	Diff      *ActivityDiff      `json:"diff,omitempty"`
	Form      map[string]any     `json:"form,omitempty"`
	Response  string             `json:"response,omitempty"`
	OptionID  string             `json:"optionId,omitempty"`
	Content   map[string]any     `json:"content,omitempty"`
	Approved  bool               `json:"approved,omitempty"`
	Cancelled bool               `json:"cancelled,omitempty"`
}
