package acp

// ActivityType is the closed set of env.activity activityType values.
type ActivityType string

const (
	ActivityTypeThinking    ActivityType = "THINKING"
	ActivityTypeResearch    ActivityType = "RESEARCH"
	ActivityTypeEdited      ActivityType = "EDITED"
	ActivityTypeCommand     ActivityType = "COMMAND"
	ActivityTypeMessage     ActivityType = "MESSAGE"
	ActivityTypeElicitation ActivityType = "ELICITATION"
	ActivityTypePlan        ActivityType = "PLAN"
)

// ActivityStatus is the closed set of env.activity status values, mirroring the
// ACP ToolCallStatus lifecycle (pending → in_progress → completed | failed).
type ActivityStatus string

const (
	ActivityPending    ActivityStatus = "pending"
	ActivityInProgress ActivityStatus = "in_progress"
	ActivityCompleted  ActivityStatus = "completed"
	ActivityFailed     ActivityStatus = "failed"
)

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

// PlanEntry is one task in the ACP execution plan. The plan is replace-all per
// update: every session/update plan carries the complete entry list.
type PlanEntry struct {
	Content  string            `json:"content"`
	Priority PlanEntryPriority `json:"priority"`
	Status   PlanEntryStatus   `json:"status"`
}

// Activity is one discrete activity-log event relayed to the control plane.
// Every event carries a status so the UI can drive lifecycle transitions; the
// detail carries the structured, accumulated tool/message state.
type Activity struct {
	ActivityType ActivityType
	Description  string
	ActionID     string
	Status       ActivityStatus
	Detail       ActivityDetail
}

// ActivityDetail is the structured detail of an activity event. Every field is
// optional and omitted when empty. input/meta/rawUpdate are agent-defined
// opaque bags preserved verbatim (never parsed for behavior).
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
