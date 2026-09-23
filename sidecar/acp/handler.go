package acp

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strings"
	"sync"
	"time"
)

type EventSink interface {
	SendOutput(line string, stream string)
	SendActivity(activity Activity)
	RequestPermission(req PermissionRequest) (selectedOptionID string, err error)
	CreateElicitation(req ElicitationRequest) (ElicitationResult, error)
	PermissionCancelChan() <-chan struct{}
}

// PermissionRequest is the permission request forwarded to the control plane:
// the extracted command, the correlation actionID (ACP toolCallId), the tool
// metadata, the agent's permission options, and the edit diff when present.
// Options are guaranteed non-empty (the handler synthesises a default allow
// option when the agent sends none).
type PermissionRequest struct {
	Command  string
	ActionID string
	Title    string
	Kind     string
	Options  []PermissionOption
	Diff     *ActivityDiff
}

// ElicitationRequest is a structured question (elicitation/create) forwarded
// to the control plane. Form mode carries a JSON Schema; url mode carries a URL.
type ElicitationRequest struct {
	ElicitationID   string
	Message         string
	Mode            string
	RequestedSchema map[string]any
	URL             string
}

// ElicitationResult is the user's response to an elicitation.
type ElicitationResult struct {
	Action  string         `json:"action"` // accept | decline | cancel
	Content map[string]any `json:"content,omitempty"`
}

// The activity detail carries a bounded head+tail; the live env.output relay
// already streamed the full transcript to the control plane.
const (
	detailHeadBytes = 4 * 1024
	detailTailBytes = 8 * 1024
)

func (info *ToolCallInfo) setOutput(text string) {
	info.OutputText = text
}

func (info *ToolCallInfo) appendOutput(data string) {
	info.OutputText += data
}

// boundDetail clips a transcript to a head+tail window for the activity detail
// and reports the number of bytes omitted. Cuts are rune-aligned (valid UTF-8).
func boundDetail(text string) (string, int64) {
	if len(text) <= detailHeadBytes+detailTailBytes {
		return text, 0
	}
	head := runeFloorBoundary(text, detailHeadBytes)
	tailStart := runeCeilBoundary(text, len(text)-detailTailBytes)
	omitted := int64(len(text) - head - (len(text) - tailStart))
	marker := fmt.Sprintf("\n… [%d bytes omitted] …\n", omitted)
	return text[:head] + marker + text[tailStart:], omitted
}

// runeFloorBoundary returns the largest rune-aligned byte offset <= n. When n
// falls inside a leading multi-byte rune, the next boundary (the rune's end) is
// returned, so callers always make progress on valid UTF-8.
func runeFloorBoundary(s string, n int) int {
	if n <= 0 {
		return 0
	}
	if n >= len(s) {
		return len(s)
	}
	last := 0
	for i := range s {
		if i > n {
			if last == 0 {
				return i
			}
			return last
		}
		last = i
	}
	if last == 0 {
		// n falls inside a single leading multi-byte rune; the first safe cut
		// is the rune's end.
		return len(s)
	}
	return last
}

// runeCeilBoundary returns the smallest rune-aligned byte offset >= n.
func runeCeilBoundary(s string, n int) int {
	if n <= 0 {
		return 0
	}
	for i := range s {
		if i >= n {
			return i
		}
	}
	return len(s)
}

// ToolCallInfo is the accumulated state of one ACP tool call, keyed by
// toolCallId.
type ToolCallInfo struct {
	ToolCallID   string
	Kind         string
	Title        string
	Status       ActivityStatus
	Command      string
	FilePath     string
	Locations    []ToolLocation
	RawInputMap  map[string]any
	Meta         map[string]any
	RawOutputRaw json.RawMessage
	// Todos: TodoWrite list from rawInput.todos; non-empty → relay as PLAN.
	Todos []PlanEntry

	OutputText     string // full accumulated output text (content or rawOutput)
	OutputSource   string // "content", "rawOutput" or "terminal"
	EmittedLen     int    // length of OutputText already relayed to the terminal
	TerminalID     string // Goose correlates tool calls to terminal/* RPCs this way
	TerminalExited bool   // a _meta.terminal_exit finalized this tool call
	ExitSignal     *string
	ExitCode       *int
	Truncated      bool
	Hitl           *ActivityHitl
	// Insertion order; Go map iteration is unordered.
	RecordedSeq uint64
}

// TerminalOutputState buffers one agent-owned terminal's relayed output
// (_meta.terminal_info/_meta.terminal_output/_meta.terminal_exit). Keyed by
// terminal_id, which is a separate id space from toolCallId: output/exit
// chunks may arrive before the tool_call that announces the terminal (Zed
// buffers the same way in pending_terminal_output/pending_terminal_exit).
// Output is appended per chunk (delta semantics, unlike the cumulative
// content/rawOutput path).
type TerminalOutputState struct {
	Output   string
	ExitCode *int
	Signal   *string
	Exited   bool
}

// Handler relays ACP agent traffic to the Kratis event sink. It is safe for
// concurrent use: the transport dispatches each message in its own goroutine.
type Handler struct {
	sink      EventSink
	terminals *TerminalManager
	workspace string
	debug     bool

	mu        sync.Mutex
	toolCalls map[string]*ToolCallInfo
	// terminalOutputs buffers agent-owned terminal relay state keyed by
	// terminal_id until the correlated tool call arrives (see
	// TerminalOutputState). Guarded by mu.
	terminalOutputs map[string]*TerminalOutputState
	// sessionMetadata captures metadata-only updates (usage, session info,
	// available commands, config options); captured, never displayed.
	sessionMetadata map[string][]map[string]any

	// inferredStreamType: update type of the current sidecar-generated key
	// ("" = none active). inferredSeq: single monotonic counter for all such
	// keys (inferred-<n>, plan-<n>), never reset so keys stay unique. Both
	// guarded by mu.
	inferredStreamType string
	inferredSeq        int
	toolCallSeq        uint64
	openChunkRun       *chunkRun
	chunkRunCounts     map[string]int
}

func NewHandler(sink EventSink, terminals *TerminalManager, workspace string) *Handler {
	if terminals != nil {
		terminals.SetOutputSink(sink.SendOutput)
	}
	return &Handler{
		sink:            sink,
		terminals:       terminals,
		workspace:       workspace,
		toolCalls:       make(map[string]*ToolCallInfo),
		terminalOutputs: make(map[string]*TerminalOutputState),
		sessionMetadata: make(map[string][]map[string]any),
		chunkRunCounts:  make(map[string]int),
	}
}

// SetDebug gates whether sidecar diagnostics are sent to the control-plane
func (h *Handler) SetDebug(debug bool) {
	h.debug = debug
}

func (h *Handler) debugf(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	log.Printf("%s", msg)
	if !h.debug {
		for _, prefix := range []string{
			"[NOTIF]", "[REQ]", "[PERM]", "[ACP][TERM]", "[ACP][PERM]", "[ACP][FS]", "[ACP][TIMING]", "[ACP][MSGID]",
		} {
			if strings.HasPrefix(msg, prefix) {
				return
			}
		}
	}
	h.sink.SendOutput(msg, "stdout")
}

// warnf reports an ACP protocol violation: always logged, always relayed to
// the control-plane terminal on stderr, never gated by the debug flag. Silent
// drops hide harness non-conformance and leave activity-log gaps that cannot
// be explained after the fact.
func (h *Handler) warnf(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	log.Printf("%s", msg)
	h.sink.SendOutput(msg, "stderr")
}

func (h *Handler) CloseChunkRun(reason string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.closeChunkRunLocked(reason)
}

func (h *Handler) NotificationHandler(transport *AcpTransport) func(string, map[string]interface{}, interface{}, bool) {
	return func(method string, params map[string]interface{}, id interface{}, hasID bool) {
		if hasID {
			h.debugf("[REQ] Agent request method=%s id=%v", method, id)
		} else {
			h.debugf("[NOTIF] Agent notification method=%s", method)
		}

		if method == "session/update" {
			h.handleSessionUpdate(params)
		}

		if hasID {
			switch method {
			case "session/request_permission":
				h.HandlePermissionRequest(transport, params, id)
			case "elicitation/create":
				h.HandleElicitationRequest(transport, params, id)
			case "terminal/create":
				h.HandleTerminalCreate(transport, params, id)
			case "terminal/output":
				h.HandleTerminalOutput(transport, params, id)
			case "terminal/wait_for_exit":
				h.HandleTerminalWaitForExit(transport, params, id)
			case "terminal/release":
				h.HandleTerminalRelease(transport, params, id)
			case "terminal/kill":
				h.HandleTerminalKill(transport, params, id)
			case "fs/write_text_file":
				h.HandleFsWriteTextFile(transport, params, id)
			case "fs/read_text_file":
				h.HandleFsReadTextFile(transport, params, id)
			default:
				h.debugf("[WARN] Unhandled agent request method=%s id=%v — agent may timeout", method, id)
			}
		}
	}
}

func (h *Handler) OutputHandler() func(string, string) {
	return func(line string, stream string) {
		h.sink.SendOutput(line, stream)
	}
}

func (h *Handler) HandlePermissionRequest(transport *AcpTransport, params map[string]interface{}, id interface{}) {
	if params == nil {
		h.sendErrorResponse(transport, id, -32602, "Invalid params", "params object is required")
		return
	}
	cmdText := h.extractPermissionCommand(params)
	actionID := h.extractPermissionActionID(params)
	h.debugf("[PERM] Permission request id=%v, command=%q, actionId=%q", id, cmdText, actionID)

	// Authorisation is part of the tool call's lifecycle.
	p, _ := parseRequestPermissionParams(params)
	info := h.beginPermission(actionID, cmdText, p.ToolCall.Title, p.ToolCall.Kind)
	if info != nil {
		h.emitToolActivity(info, ActivityPending)
	}

	// The agent owns the option set; a non-empty one is guaranteed downstream
	// so the UI always has something to present and the control plane always
	// has an option to return.
	options := p.Options
	if len(options) == 0 {
		options = []PermissionOption{{OptionID: "allow", Name: "Allow", Kind: string(ApprovalAllowOnce)}}
	}

	req := PermissionRequest{
		Command:  cmdText,
		ActionID: actionID,
		Title:    p.ToolCall.Title,
		Kind:     p.ToolCall.Kind,
		Options:  options,
		Diff:     permissionDiff(p.ToolCall),
	}

	selectedOptionID, err := h.sink.RequestPermission(req)
	h.debugf("[PERM] Control-plane returned selectedOptionID=%q, err=%v", selectedOptionID, err)

	cancelled := err != nil && strings.Contains(err.Error(), "cancelled")
	selected := err == nil && selectedOptionID != ""
	optionKind := ApprovalOptionKind("")
	if selected {
		optionKind = optionKindForOptions(options, selectedOptionID)
	}
	h.resolvePermission(actionID, selected && isAllowKind(optionKind), selectedOptionID, optionKind, cancelled && !selected)

	if cancelled && !selected {
		h.debugf("[PERM] Permission request was cancelled, sending cancelled outcome to agent")
		h.writePermissionOutcome(transport, id, "cancelled", "")
		return
	}

	responseBody, buildErr := buildPermissionResponse(id, selectedOptionID, options)
	if buildErr != nil {
		h.debugf("[PERM] %v", buildErr)
		responseBody = map[string]interface{}{
			"jsonrpc": "2.0",
			"error": map[string]interface{}{
				"code":    -32602,
				"message": buildErr.Error(),
			},
			"id": id,
		}
	}

	h.debugf("[PERM] Writing response to agent stdin")
	if writeErr := transport.WriteResponse(responseBody); writeErr != nil {
		h.debugf("[PERM] FAILED to write permission response: %v", writeErr)
	}
}

// writePermissionOutcome writes an ACP permission response carrying the given
// outcome (cancelled) or selection (selected + optionId) to the agent.
func (h *Handler) writePermissionOutcome(transport *AcpTransport, id interface{}, outcome string, optionID string) {
	body := map[string]interface{}{
		"jsonrpc": "2.0",
		"result": map[string]interface{}{
			"outcome": map[string]interface{}{"outcome": outcome},
		},
		"id": id,
	}
	if optionID != "" {
		body["result"].(map[string]interface{})["outcome"].(map[string]interface{})["optionId"] = optionID
	}
	if writeErr := transport.WriteResponse(body); writeErr != nil {
		h.debugf("[PERM] FAILED to write permission response: %v", writeErr)
	}
}

// HandleElicitationRequest handles an elicitation/create request from the agent
// (negotiated via clientCapabilities._meta.elicitation.form): forward it to the
// control plane, block for the user's answer, and write the ACP response
// (action accept/decline/cancel + content on accept).
func (h *Handler) HandleElicitationRequest(transport *AcpTransport, params map[string]interface{}, id interface{}) {
	if params == nil {
		h.sendErrorResponse(transport, id, -32602, "Invalid params", "params object is required")
		return
	}
	p, err := parseCreateElicitationParams(params)
	if err != nil {
		h.sendErrorResponse(transport, id, -32602, "Invalid params", err.Error())
		return
	}
	// Kratis only advertises the form capability (_meta.elicitation.form).
	// URL mode is an out-of-band interaction (often OAuth) Kratis deliberately
	// does not support; reject it per the ACP rule that a mode the client has
	// not advertised is invalid params.
	if p.Mode != "form" {
		h.debugf("[ELIC] Rejecting elicitation request id=%v: unsupported mode %q", id, p.Mode)
		h.sendErrorResponse(transport, id, -32602, "Invalid params", fmt.Sprintf("unsupported elicitation mode %q: Kratis supports only form mode", p.Mode))
		return
	}
	req := ElicitationRequest{
		ElicitationID:   coalesce(p.ElicitationID, p.ToolCallID),
		Message:         p.Message,
		Mode:            p.Mode,
		RequestedSchema: p.RequestedSchema,
		URL:             p.URL,
	}
	h.debugf("[ELIC] Elicitation request id=%v, mode=%q, message=%q", id, p.Mode, p.Message)

	result, err := h.sink.CreateElicitation(req)
	h.debugf("[ELIC] Control-plane returned action=%q, err=%v", result.Action, err)

	action := "cancel"
	if err == nil && result.Action != "" {
		action = result.Action
	}
	responseBody := map[string]interface{}{
		"jsonrpc": "2.0",
		"result": map[string]interface{}{
			"action": action,
		},
		"id": id,
	}
	if action == "accept" && len(result.Content) > 0 {
		responseBody["result"].(map[string]interface{})["content"] = result.Content
	}
	if writeErr := transport.WriteResponse(responseBody); writeErr != nil {
		h.debugf("[ELIC] FAILED to write elicitation response: %v", writeErr)
	}
}

// beginPermission attaches the HITL permission to a current tool call
func (h *Handler) beginPermission(toolCallID string, command string, title string, kind string) *ToolCallInfo {
	if toolCallID == "" {
		return nil
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	info := h.toolCalls[toolCallID]
	if info == nil {
		h.toolCallSeq++
		info = &ToolCallInfo{ToolCallID: toolCallID, RecordedSeq: h.toolCallSeq}
		h.toolCalls[toolCallID] = info
	}
	if info.Command == "" {
		info.Command = command
	}
	if info.Title == "" {
		info.Title = title
	}
	if info.Kind == "" {
		info.Kind = kind
	}
	info.Hitl = &ActivityHitl{HitlID: toolCallID, Kind: "approval"}
	return info
}

// resolvePermission records the HITL decision on the tool call and emits the
// resolution transition: in_progress on approval, failed on rejection.
func (h *Handler) resolvePermission(toolCallID string, approved bool, optionID string, _ ApprovalOptionKind, cancelled bool) {
	if toolCallID == "" {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	info := h.toolCalls[toolCallID]
	if info == nil {
		return
	}
	if info.Hitl == nil {
		info.Hitl = &ActivityHitl{HitlID: toolCallID, Kind: "approval"}
	}
	info.Hitl.Approved = approved
	info.Hitl.OptionID = optionID
	info.Hitl.Cancelled = cancelled
	info.Hitl.Response = hitlResponse(approved, cancelled)

	status := ActivityInProgress
	if !approved {
		status = ActivityFailed
	}
	if info.Status == status || info.Status == ActivityCompleted || info.Status == ActivityFailed {
		return
	}
	info.Status = status
	h.sink.SendActivity(h.buildToolActivity(info, status))
}

// hitlResponse maps the resolved permission outcome to the wire HITL response
// discriminator.
func hitlResponse(approved bool, cancelled bool) string {
	if cancelled {
		return "cancelled"
	}
	if approved {
		return "approved"
	}
	return "declined"
}

// emitToolActivity emits an activity from the accumulated state for the given
// status transition, updating the recorded status.
func (h *Handler) emitToolActivity(info *ToolCallInfo, status ActivityStatus) {
	h.mu.Lock()
	if info.Status == status || info.Status == ActivityCompleted || info.Status == ActivityFailed {
		h.mu.Unlock()
		return
	}
	info.Status = status
	activity := h.buildToolActivity(info, status)
	h.mu.Unlock()
	h.sink.SendActivity(activity)
}

func (h *Handler) HandleTerminalCreate(transport *AcpTransport, params map[string]interface{}, id interface{}) {
	h.debugf("[ACP][TERM] Received terminal/create request id=%v", id)

	command, _ := params["command"].(string)
	if command == "" {
		h.debugf("[ACP][TERM] ERROR: no command provided")
		h.sendErrorResponse(transport, id, -32602, "Invalid params", "command is required")
		return
	}

	cwd, _ := params["cwd"].(string)
	h.debugf("[ACP][TERM] Requesting HITL permission for command=%q cwd=%q", command, cwd)

	// Some ACP RPCs carry no toolCallId (e.g. Goose); correlate the permission
	// with the accumulated tool call whose command matches so the approval
	// lands on the tool activity (Slice 6).
	toolCallID := h.findToolCallByCommand(command)
	info := h.beginPermission(toolCallID, command, "", "")
	if info != nil {
		h.emitToolActivity(info, ActivityPending)
	}

	// terminal/create carries no agent option set; synthesise the default pair
	// so the UI and control plane see a uniform shape.
	req := PermissionRequest{
		Command:  command,
		ActionID: toolCallID,
		Options: []PermissionOption{
			{OptionID: "allow", Name: "Allow", Kind: string(ApprovalAllowOnce)},
			{OptionID: "reject", Name: "Reject", Kind: string(ApprovalRejectOnce)},
		},
	}
	selectedOptionID, err := h.sink.RequestPermission(req)
	h.debugf("[ACP][TERM] HITL permission result: selectedOptionID=%q, err=%v", selectedOptionID, err)

	if err != nil && strings.Contains(err.Error(), "cancelled") {
		h.debugf("[ACP][TERM] Permission cancelled, returning cancelled outcome")
		h.resolvePermission(toolCallID, false, "", "", true)
		responseBody := map[string]interface{}{
			"jsonrpc": "2.0",
			"result": map[string]interface{}{
				"outcome": map[string]interface{}{
					"outcome": "cancelled",
				},
			},
			"id": id,
		}
		_ = transport.WriteResponse(responseBody)
		return
	}

	if selectedOptionID == "" || err != nil {
		h.debugf("[ACP][TERM] Permission denied: selectedOptionID=%q, err=%v", selectedOptionID, err)
		h.resolvePermission(toolCallID, false, "", "", false)
		responseBody := map[string]interface{}{
			"jsonrpc": "2.0",
			"result": map[string]interface{}{
				"outcome": map[string]interface{}{
					"outcome": "rejected",
				},
			},
			"id": id,
		}
		_ = transport.WriteResponse(responseBody)
		return
	}
	h.resolvePermission(toolCallID, true, selectedOptionID, ApprovalAllowOnce, false)

	h.debugf("[ACP][TERM] Permission granted, creating terminal for command=%q cwd=%q", command, cwd)

	term, err := h.terminals.CreateTerminal(command, cwd)
	if err != nil {
		h.debugf("[ACP][TERM] ERROR: failed to create terminal: %v", err)
		h.sendErrorResponse(transport, id, -32000, "Failed to create terminal", err.Error())
		return
	}

	h.mu.Lock()
	if info := h.toolCalls[toolCallID]; info != nil {
		info.TerminalID = term.ID
	}
	h.mu.Unlock()

	h.debugf("[ACP][TERM] Terminal created with id=%s", term.ID)

	response := map[string]interface{}{
		"jsonrpc": "2.0",
		"result": map[string]interface{}{
			"terminalId": term.ID,
		},
		"id": id,
	}
	_ = transport.WriteResponse(response)
}

// findToolCallByCommand correlates a terminal/create command with the
// accumulated tool call that triggered it. terminal/create carries no
// toolCallId per spec, so on ambiguity the most recently recorded match wins.
func (h *Handler) findToolCallByCommand(command string) string {
	if command == "" {
		return ""
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	var best string
	var bestSeq uint64
	matches := 0
	for id, info := range h.toolCalls {
		if info.Command != command || info.Status == ActivityCompleted || info.Status == ActivityFailed {
			continue
		}
		matches++
		if best == "" || info.RecordedSeq > bestSeq {
			best, bestSeq = id, info.RecordedSeq
		}
	}
	if matches > 1 {
		h.warnf("[WARN] Ambiguous terminal/create correlation: command=%q matches %d live tool calls — selected %q (most recently recorded)",
			command, matches, best)
	}
	return best
}

// findToolCallByPath correlates an fs/write_text_file path with the live tool
// call that triggered it. fs/write_text_file carries no toolCallId per spec
// (Goose bridges developer edit/write through the client), so the most
// recently recorded live match wins.
func (h *Handler) findToolCallByPath(path string) string {
	if path == "" {
		return ""
	}
	target := normalizeFsPath(h.workspace, path)
	h.mu.Lock()
	defer h.mu.Unlock()
	var best string
	var bestSeq uint64
	matches := 0
	for id, info := range h.toolCalls {
		if info.Status == ActivityCompleted || info.Status == ActivityFailed {
			continue
		}
		matched := target != "" && normalizeFsPath(h.workspace, info.FilePath) == target
		if !matched {
			for _, loc := range info.Locations {
				if target != "" && normalizeFsPath(h.workspace, loc.Path) == target {
					matched = true
					break
				}
			}
		}
		if !matched {
			if cmd := info.Command; cmd != "" && target != "" && (cmd == target || strings.Contains(cmd, target)) {
				matched = true
			}
		}
		if !matched && matchesRawPath(info, target, path) {
			matched = true
		}
		if !matched {
			continue
		}
		matches++
		if best == "" || info.RecordedSeq > bestSeq {
			best, bestSeq = id, info.RecordedSeq
		}
	}
	if matches > 1 {
		h.warnf("[WARN] Ambiguous fs/write correlation: path=%q matches %d live tool calls — selected %q (most recently recorded)",
			path, matches, best)
	}
	return best
}

// normalizeFsPath resolves agent and fs paths to a comparable absolute form so
// relative tool-call paths match absolute fs/write_text_file paths.
func normalizeFsPath(workspace string, path string) string {
	if path == "" {
		return ""
	}
	cleaned := path
	if workspace != "" && !isAbsPath(cleaned) {
		cleaned = workspace + "/" + cleaned
	}
	parts := strings.Split(cleaned, "/")
	var out []string
	for _, part := range parts {
		switch part {
		case "", ".":
			continue
		case "..":
			if len(out) > 0 {
				out = out[:len(out)-1]
			}
		default:
			out = append(out, part)
		}
	}
	return "/" + strings.Join(out, "/")
}

func isAbsPath(path string) bool {
	return strings.HasPrefix(path, "/")
}

// matchesRawPath falls back to suffix matching for tool calls that never
// announced a path (e.g. Goose content-only updates before fs/write).
func matchesRawPath(info *ToolCallInfo, target string, rawPath string) bool {
	if target == "" || info == nil {
		return false
	}
	raw := info.RawInputMap
	if len(raw) == 0 {
		return false
	}
	for _, key := range []string{"path", "file_path", "filePath", "file", "filename"} {
		if value, ok := raw[key].(string); ok {
			if candidate := normalizeFsPath("", value); candidate != "" && candidate == target {
				return true
			}
		}
	}
	for _, value := range []string{info.Title, info.Command} {
		if value != "" && rawPath != "" && strings.Contains(value, rawPath) {
			return true
		}
	}
	return false
}

func (h *Handler) HandleTerminalOutput(transport *AcpTransport, params map[string]interface{}, id interface{}) {
	terminalID, _ := params["terminalId"].(string)
	if terminalID == "" {
		h.sendErrorResponse(transport, id, -32602, "Invalid params", "terminalId is required")
		return
	}

	h.debugf("[ACP][TERM] Getting output for terminal=%s", terminalID)

	stdout, stderr, exited := h.terminals.GetOutput(terminalID)
	combinedOutput := stdout + stderr

	if !exited {
		response := map[string]interface{}{
			"jsonrpc": "2.0",
			"result": map[string]interface{}{
				"output":    combinedOutput,
				"truncated": false,
			},
			"id": id,
		}
		_ = transport.WriteResponse(response)
		return
	}

	term, _ := h.terminals.GetTerminal(terminalID)
	term.mu.RLock()
	exitStatus := term.ExitStatus
	term.mu.RUnlock()

	result := map[string]interface{}{
		"output":    combinedOutput,
		"truncated": false,
	}
	if exitStatus != nil {
		result["exitStatus"] = exitStatus
	}

	h.forwardTerminalOutputOnce(terminalID, exitStatus)

	response := map[string]interface{}{
		"jsonrpc": "2.0",
		"result":  result,
		"id":      id,
	}
	_ = transport.WriteResponse(response)
}

func (h *Handler) HandleTerminalWaitForExit(transport *AcpTransport, params map[string]interface{}, id interface{}) {
	terminalID, _ := params["terminalId"].(string)
	if terminalID == "" {
		h.sendErrorResponse(transport, id, -32602, "Invalid params", "terminalId is required")
		return
	}

	h.debugf("[ACP][TERM] Waiting for terminal=%s to exit", terminalID)

	exitStatus, err := h.terminals.WaitForExit(terminalID, 5*time.Minute)
	if err != nil {
		h.debugf("[ACP][TERM] ERROR: %v", err)
		h.sendErrorResponse(transport, id, -32000, "Failed to wait for exit", err.Error())
		return
	}

	h.forwardTerminalOutputOnce(terminalID, exitStatus)

	result := map[string]interface{}{}
	if exitStatus != nil {
		if exitStatus.ExitCode != nil {
			result["exitCode"] = *exitStatus.ExitCode
		}
		if exitStatus.Signal != nil {
			result["signal"] = *exitStatus.Signal
		}
	}

	response := map[string]interface{}{
		"jsonrpc": "2.0",
		"result":  result,
		"id":      id,
	}
	_ = transport.WriteResponse(response)
}

// forwardTerminalOutputOnce relays a terminal's captured output to env.output.
// Live streaming (via the manager's output sink) relays lines as they are
// produced; this forwards only the un-streamed remainder, at most once, at
// exit.
func (h *Handler) forwardTerminalOutputOnce(terminalID string, exitStatus *TerminalExitStatus) {
	term, ok := h.terminals.GetTerminal(terminalID)
	if !ok {
		return
	}
	// After exit the pipes EOF and the copiers finish quickly; wait so the
	// buffer is complete before computing the remainder.
	<-term.relayDone

	term.mu.Lock()
	if term.OutputForwarded {
		term.mu.Unlock()
		return
	}
	term.OutputForwarded = true
	stdout := term.Stdout.String()
	stderr := term.Stderr.String()
	remainder := stdout[term.stdoutEmitted:] + stderr[term.stderrEmitted:]
	term.mu.Unlock()

	exitCode := 0
	if exitStatus != nil && exitStatus.ExitCode != nil {
		exitCode = *exitStatus.ExitCode
	}
	// The transcript may be huge; setOutput retains it in full for the relay
	// while the activity detail is bounded.
	output := stdout + stderr
	stream := "stdout"
	if exitCode != 0 {
		stream = "stderr"
	}
	if remainder != "" {
		h.sink.SendOutput(remainder, stream)
	}

	// Correlate the exit with the accumulated tool call and derive failure.
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, info := range h.toolCalls {
		if info.TerminalID != terminalID {
			continue
		}
		code := exitCode
		info.ExitCode = &code
		info.setOutput(output)
		info.OutputSource = "terminal"
		status := ActivityCompleted
		if code != 0 {
			status = ActivityFailed
		}
		if info.Status == status || info.Status == ActivityCompleted || info.Status == ActivityFailed {
			continue
		}
		info.Status = status
		h.sink.SendActivity(h.buildToolActivity(info, status))
	}
}

func (h *Handler) HandleTerminalRelease(transport *AcpTransport, params map[string]interface{}, id interface{}) {
	terminalID, _ := params["terminalId"].(string)
	if terminalID == "" {
		h.sendErrorResponse(transport, id, -32602, "Invalid params", "terminalId is required")
		return
	}

	h.debugf("[ACP][TERM] Releasing terminal=%s", terminalID)
	h.terminals.ReleaseTerminal(terminalID)

	response := map[string]interface{}{
		"jsonrpc": "2.0",
		"result":  map[string]interface{}{},
		"id":      id,
	}
	_ = transport.WriteResponse(response)
}

func (h *Handler) HandleTerminalKill(transport *AcpTransport, params map[string]interface{}, id interface{}) {
	terminalID, _ := params["terminalId"].(string)
	if terminalID == "" {
		h.sendErrorResponse(transport, id, -32602, "Invalid params", "terminalId is required")
		return
	}

	h.debugf("[ACP][TERM] Killing terminal=%s", terminalID)
	if err := h.terminals.KillTerminal(terminalID); err != nil {
		h.debugf("[ACP][TERM] ERROR: %v", err)
		h.sendErrorResponse(transport, id, -32000, "Failed to kill terminal", err.Error())
		return
	}

	response := map[string]interface{}{
		"jsonrpc": "2.0",
		"result":  map[string]interface{}{},
		"id":      id,
	}
	_ = transport.WriteResponse(response)
}

// HandleFsWriteTextFile gates file writes through HITL approval, aliased to the generic "write" tool kind.
func (h *Handler) HandleFsWriteTextFile(transport *AcpTransport, params map[string]interface{}, id interface{}) {
	path, _ := params["path"].(string)
	if path == "" {
		path, _ = params["file_path"].(string)
	}
	content, _ := params["content"].(string)

	if path == "" {
		h.sendErrorResponse(transport, id, -32602, "Invalid params", "path (or file_path) is required")
		return
	}

	h.debugf("[ACP][FS] Requesting HITL permission for write: %s (%d bytes)", path, len(content))

	diff := &ActivityDiff{Path: path, NewText: content}
	if old, err := os.ReadFile(path); err == nil { //nolint:gosec // G304: diff preview reads the agent-requested path by design; the write itself is HITL-gated below
		diff.OldText = string(old)
	}

	// fs/write_text_file carries no toolCallId per ACP spec (Goose bridges
	// developer edit/write through the client). Correlate with the live tool
	// call for this path so the HITL approval attaches to the tool activity
	// instead of synthesising an orphan hitlId downstream.
	toolCallID := h.findToolCallByPath(path)
	info := h.beginPermission(toolCallID, path, path, "write")
	if info != nil {
		h.emitToolActivity(info, ActivityPending)
	}

	// No agent option set; synthesise the default pair for a uniform shape.
	req := PermissionRequest{
		Command:  path,
		ActionID: toolCallID,
		Title:    path,
		Kind:     "write",
		Options: []PermissionOption{
			{OptionID: "allow", Name: "Allow", Kind: string(ApprovalAllowOnce)},
			{OptionID: "reject", Name: "Reject", Kind: string(ApprovalRejectOnce)},
		},
		Diff: diff,
	}
	selectedOptionID, err := h.sink.RequestPermission(req)
	h.debugf("[ACP][FS] HITL permission result: selectedOptionID=%q, err=%v", selectedOptionID, err)

	cancelled := err != nil && strings.Contains(err.Error(), "cancelled")
	selected := err == nil && selectedOptionID != ""
	optionKind := ApprovalOptionKind("")
	if selected {
		optionKind = optionKindForOptions(req.Options, selectedOptionID)
	}
	h.resolvePermission(toolCallID, selected && isAllowKind(optionKind), selectedOptionID, optionKind, cancelled && !selected)

	if cancelled && !selected {
		h.sendErrorResponse(transport, id, -32800, "Cancelled", "Write cancelled before a decision")
		return
	}
	if !selected {
		h.sendErrorResponse(transport, id, -32000, "Write denied", "The user denied permission to write "+path)
		return
	}

	if err := os.WriteFile(path, []byte(content), 0600); err != nil {
		h.debugf("[ACP][FS] ERROR: failed to write file: %v", err)
		h.sendErrorResponse(transport, id, -32000, "Failed to write file", err.Error())
		return
	}

	h.debugf("[ACP][FS] File written successfully: %s", path)

	response := map[string]interface{}{
		"jsonrpc": "2.0",
		"result":  map[string]interface{}{},
		"id":      id,
	}
	_ = transport.WriteResponse(response)
}

func (h *Handler) HandleFsReadTextFile(transport *AcpTransport, params map[string]interface{}, id interface{}) {
	path, _ := params["path"].(string)

	if path == "" {
		h.sendErrorResponse(transport, id, -32602, "Invalid params", "path is required")
		return
	}

	h.debugf("[ACP][FS] Reading file: %s", path)

	content, err := os.ReadFile(path) //nolint:gosec // G304: agent file-read tool reads the requested path by design
	if err != nil {
		h.debugf("[ACP][FS] ERROR: failed to read file: %v", err)
		h.sendErrorResponse(transport, id, -32000, "Failed to read file", err.Error())
		return
	}

	h.debugf("[ACP][FS] File read successfully: %s (%d bytes)", path, len(content))

	response := map[string]interface{}{
		"jsonrpc": "2.0",
		"result": map[string]interface{}{
			"content": string(content),
		},
		"id": id,
	}
	_ = transport.WriteResponse(response)
}

func (h *Handler) sendErrorResponse(transport *AcpTransport, id interface{}, code int, message string, data interface{}) {
	if id == nil {
		return
	}
	resp := map[string]interface{}{
		"jsonrpc": "2.0",
		"error": map[string]interface{}{
			"code":    code,
			"message": message,
			"data":    data,
		},
		"id": id,
	}
	_ = transport.WriteResponse(resp)
}

func (h *Handler) extractPermissionActionID(params map[string]interface{}) string {
	p, err := parseRequestPermissionParams(params)
	if err != nil {
		return ""
	}
	return p.ToolCall.ToolCallID
}

func mapToolKindToActivity(kind string) ActivityType {
	switch kind {
	case "read", "search", "fetch":
		return ActivityTypeResearch
	case "edit", "write", "delete", "move":
		return ActivityTypeEdited
	case "execute":
		return ActivityTypeCommand
	case "think":
		return ActivityTypeThinking
	default:
		return ActivityTypeCommand
	}
}

func extractOutputText(output map[string]interface{}) string {
	for _, key := range []string{"output", "formatted_output"} {
		if text, ok := output[key].(string); ok {
			return text
		}
	}

	if contentArr, ok := output["content"].([]interface{}); ok {
		var texts []string
		for _, item := range contentArr {
			if contentObj, ok := item.(map[string]interface{}); ok {
				if text, ok := contentObj["text"].(string); ok && text != "" {
					texts = append(texts, text)
				}
			}
		}
		if len(texts) > 0 {
			return strings.Join(texts, "\n")
		}
	}

	return ""
}

func extractToolOutputFromSessionUpdate(params map[string]interface{}) (string, string, bool) {
	if params == nil {
		return "", "", false
	}

	update, hasUpdate := params["update"].(map[string]interface{})
	if !hasUpdate {
		return "", "", false
	}

	sessionUpdateType, _ := update["sessionUpdate"].(string)
	if sessionUpdateType != "tool_call_update" {
		return "", "", false
	}

	status, _ := update["status"].(string)
	if status != "completed" && status != "failed" {
		return "", "", false
	}

	_, hasRawOutput := update["rawOutput"]
	if !hasRawOutput {
		return "", "", false
	}

	output := strings.TrimSpace(extractRawOutputText(update))
	if output == "" {
		return "", "", false
	}

	stream := "stdout"
	if status == "failed" {
		stream = "stderr"
	}

	return output, stream, true
}

func (h *Handler) extractPermissionCommand(params map[string]interface{}) string {
	if params == nil {
		return ""
	}

	p, err := parseRequestPermissionParams(params)
	if err != nil {
		return ""
	}

	tc := p.ToolCall
	ri := parseRawInput(tc.RawInput)

	var filePath string
	if len(tc.Locations) > 0 {
		filePath = tc.Locations[0].Path
	}

	// Merge with any state tracked from preceding session/update notifications.
	if tc.ToolCallID != "" {
		h.mu.Lock()
		if cached, ok := h.toolCalls[tc.ToolCallID]; ok {
			if ri.Command == "" {
				ri.Command = cached.Command
			}
			if filePath == "" {
				filePath = cached.FilePath
			}
			if tc.Title == "" {
				tc.Title = cached.Title
			}
		}
		h.mu.Unlock()
	}

	path := coalesce(ri.FilePath, ri.Path, filePath)
	name := tc.Title

	if ri.Command != "" {
		return ri.Command
	}
	if name != "" && path != "" {
		return name + " " + path
	}
	return coalesce(name, path, "(unknown operation)")
}

// coalesce returns the first non-empty string from the provided values.
func coalesce(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// optionKindForOptions looks up the ACP kind of a selected option within the
// effective (possibly synthesised) option set.
func optionKindForOptions(options []PermissionOption, optionID string) ApprovalOptionKind {
	for _, opt := range options {
		if opt.OptionID == optionID {
			return ApprovalOptionKind(opt.Kind)
		}
	}
	return ""
}

// isAllowKind reports whether an ACP option kind grants permission.
func isAllowKind(kind ApprovalOptionKind) bool {
	return kind == ApprovalAllowOnce || kind == ApprovalAllowAlways
}

// permissionDiff extracts the edit diff of an edit-kind permission tool call
// from its rawInput bag, mirroring the tool-call activity path (OpenCode:
// oldString/newString; Goose: before/after).
func permissionDiff(tc PermissionToolCall) *ActivityDiff {
	raw := rawInputAsMap(tc.RawInput)
	if oldText, newText, ok := extractDiff(raw); ok {
		path := ""
		if len(tc.Locations) > 0 {
			path = tc.Locations[0].Path
		}
		return &ActivityDiff{OldText: oldText, NewText: newText, Path: path}
	}
	return nil
}

// buildPermissionResponse renders the ACP permission response for the user's
// selection. Any non-empty selectedOptionID (allow or reject kind) becomes
// outcome.selected{optionId}; an empty selection becomes outcome.cancelled.
func buildPermissionResponse(id interface{}, selectedOptionID string, options []PermissionOption) (map[string]interface{}, error) {
	if selectedOptionID == "" {
		log.Printf("[ACP] buildPermissionResponse: no selection, outcome.cancelled")
		return map[string]interface{}{
			"jsonrpc": "2.0",
			"result": map[string]interface{}{
				"outcome": map[string]interface{}{
					"outcome": "cancelled",
				},
			},
			"id": id,
		}, nil
	}

	if optionKindForOptions(options, selectedOptionID) == "" {
		log.Printf("[ACP] buildPermissionResponse: selectedOptionID=%q not in offered options — sending cancelled", selectedOptionID)
		return map[string]interface{}{
			"jsonrpc": "2.0",
			"result": map[string]interface{}{
				"outcome": map[string]interface{}{
					"outcome": "cancelled",
				},
			},
			"id": id,
		}, nil
	}

	log.Printf("[ACP] buildPermissionResponse: selection, outcome.selected, optionId=%q", selectedOptionID)
	return map[string]interface{}{
		"jsonrpc": "2.0",
		"result": map[string]interface{}{
			"outcome": map[string]interface{}{
				"outcome":  "selected",
				"optionId": selectedOptionID,
			},
		},
		"id": id,
	}, nil
}

// extractRawOutputText extracts the human-readable text from the rawOutput
// value of a session/update map (legacy map-based variant used by tests).
func extractRawOutputText(update map[string]interface{}) string {
	rawOutput, ok := update["rawOutput"]
	if !ok {
		return ""
	}

	switch v := rawOutput.(type) {
	case string:
		trimmed := strings.TrimSpace(v)
		if strings.HasPrefix(trimmed, "{") {
			var parsed map[string]interface{}
			if err := json.Unmarshal([]byte(trimmed), &parsed); err == nil {
				return extractOutputText(parsed)
			}
		}
		return trimmed

	case map[string]interface{}:
		return extractOutputText(v)
	}

	return ""
}
