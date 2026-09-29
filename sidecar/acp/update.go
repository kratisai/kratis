package acp

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

// handleSessionUpdate relays one session/update notification: accumulate state,
// emit terminal lines, and emit lifecycle activities. Every ACP discriminator
// has an explicit path here (P1: no data loss); unknown future discriminators
// fall back to a generic relay plus raw capture.
func (h *Handler) handleSessionUpdate(params map[string]interface{}) {
	u, ok := parseToolCallUpdate(params)
	if !ok {
		return
	}

	switch u.SessionUpdate {
	case "tool_call", "tool_call_update":
		h.resetChunkStream("tool activity: " + u.SessionUpdate)
		h.handleToolCallUpdate(u, updateAsMap(params))
	case "agent_message_chunk":
		h.handleMessageChunk(u, "agent", updateAsMap(params))
	case "user_message_chunk":
		h.handleMessageChunk(u, "user", updateAsMap(params))
	case "agent_thought_chunk":
		h.handleThoughtChunk(u, updateAsMap(params))
	case "plan":
		h.resetChunkStream("plan update")
		h.handlePlanUpdate(params)
	case "current_mode_update":
		h.resetChunkStream("current_mode_update")
		h.sink.SendOutput("[Mode] Changed to: "+u.CurrentModeID, "stdout")
		h.sendActivity(ActivityTypeThinking, "Agent mode changed to: "+u.CurrentModeID, "", ActivityInProgress, ActivityDetail{})
	case "usage_update", "session_info_update", "available_commands_update", "config_option_update":
		if u.SessionUpdate == "config_option_update" {
			h.sink.SendOutput("[Update] config_option_update", "stdout")
		}
		h.captureSessionMetadata(u.SessionUpdate, params)
	default:
		h.resetChunkStream("unknown update: " + u.SessionUpdate)
		desc := "[Update] " + u.SessionUpdate
		h.sink.SendOutput(desc, "stdout")
		h.sendActivity(ActivityTypeThinking, desc, "", ActivityInProgress, ActivityDetail{RawUpdate: updateAsMap(params)})
	}
}

// handleToolCallUpdate merges one tool_call update into the per-toolCallId
// state, relays output to the terminal, and emits one activity per lifecycle
// transition. Identical re-sends (OpenCode) collapse: only status changes emit.
func (h *Handler) handleToolCallUpdate(u ToolCallUpdateMsg, _ map[string]any) {
	h.mu.Lock()
	defer h.mu.Unlock()

	info := h.accumulateToolCallLocked(u)
	if info == nil {
		return
	}

	prevStatus := info.Status
	newStatus := sanitizeActivityStatus(u.Status)
	if newStatus == "" {
		newStatus = prevStatus
	}
	if newStatus == "" {
		newStatus = ActivityPending
	}
	if newStatus == ActivityCompleted {
		exit := extractExitCode(u.RawOutput)
		if exit == nil {
			exit = extractExitCode(info.RawOutputRaw)
		}
		if exit != nil && *exit != 0 {
			info.ExitCode = exit
			newStatus = ActivityFailed
		}
	}

	// A _meta.terminal_exit finalizes the command even when the agent omits a
	// wire status transition (agent-owned terminal relay). A non-zero exit
	// code or a termination signal derives failed.
	if info.TerminalExited && newStatus != ActivityCompleted && newStatus != ActivityFailed {
		newStatus = ActivityCompleted
		if (info.ExitCode != nil && *info.ExitCode != 0) || info.ExitSignal != nil {
			newStatus = ActivityFailed
		}
		h.debugf("[ACP][TERM] toolCallId=%q finalized as %s from terminal_exit (wire status=%q exit=%v signal=%v)",
			u.ToolCallID, newStatus, u.Status, info.ExitCode, info.ExitSignal)
	}

	h.relayToolCallOutputLocked(info, u, newStatus)

	if (newStatus == ActivityCompleted || newStatus == ActivityFailed) && info.TerminalID != "" {
		delete(h.terminalOutputs, info.TerminalID)
	}

	if info.Status == newStatus {
		return
	}
	if info.Status == ActivityCompleted || info.Status == ActivityFailed {
		// Terminal is absorbing: an explicit earlier status after a recorded
		// terminal cannot be a lifecycle step (the ACP lifecycle is
		// monotonic), so suppress the regression instead of reopening.
		h.debugf("[ACP][TERM] toolCallId=%q suppressed status regression %s → %s (terminal is absorbing)",
			u.ToolCallID, info.Status, newStatus)
		return
	}
	info.Status = newStatus
	h.sink.SendActivity(h.buildToolActivity(info, newStatus))
}

// sanitizeActivityStatus accepts only the four ACP ToolCallStatus values;
// non-standard statuses (e.g. OpenCode "running") are treated as absent so the
// wire format stays within the closed enum.
func sanitizeActivityStatus(status string) ActivityStatus {
	switch ActivityStatus(status) {
	case ActivityPending, ActivityInProgress, ActivityCompleted, ActivityFailed:
		return ActivityStatus(status)
	}
	return ""
}

// relayToolCallOutputLocked prints the terminal relay: [Tool] for tool_call,
// delta output for cumulative updates, [Tool] status line for status-only
// updates. TodoWrite calls print a [Plan] line instead, so the raw todo JSON
// never leaks.
func (h *Handler) relayToolCallOutputLocked(info *ToolCallInfo, u ToolCallUpdateMsg, newStatus ActivityStatus) {
	if isTodoWriteTool(info) {
		h.sink.SendOutput("[Plan] Agent plan updated", "stdout")
		return
	}
	if u.SessionUpdate == "tool_call" {
		h.sink.SendOutput("[Tool] "+h.buildToolCallDescription(info, string(newStatus)), "stdout")
		return
	}

	fullText := info.OutputText
	if len(fullText) > info.EmittedLen {
		stream := "stdout"
		if newStatus == ActivityFailed {
			stream = "stderr"
		}
		h.sink.SendOutput(fullText[info.EmittedLen:], stream)
		info.EmittedLen = len(fullText)
		return
	}
	if u.Status != "" && ActivityStatus(u.Status) != info.Status {
		h.sink.SendOutput("[Tool] "+h.buildToolCallDescription(info, u.Status), "stdout")
	}
}

// accumulateToolCallLocked merges one tool_call message into the accumulated
// state; nil when the message carries no toolCallId. ACP requires toolCallId
// on both tool_call and tool_call_update, so a missing one is a harness
// protocol violation: it is reported loudly rather than dropped silently.
func (h *Handler) accumulateToolCallLocked(u ToolCallUpdateMsg) *ToolCallInfo {
	if u.ToolCallID == "" {
		h.warnf("[WARN] ACP violation: %s without toolCallId (title=%q kind=%q status=%q) — activity dropped",
			u.SessionUpdate, u.Title, u.Kind, u.Status)
		return nil
	}

	info, exists := h.toolCalls[u.ToolCallID]
	if !exists {
		h.toolCallSeq++
		info = &ToolCallInfo{ToolCallID: u.ToolCallID, RecordedSeq: h.toolCallSeq}
		h.toolCalls[u.ToolCallID] = info
	}

	if u.Kind != "" {
		info.Kind = u.Kind
	}
	if u.Title != "" {
		info.Title = u.Title
	}
	if len(u.Locations) > 0 && u.Locations[0].Path != "" {
		info.Locations = u.Locations
		info.FilePath = u.Locations[0].Path
	}
	ri := parseRawInput(u.RawInput)
	if ri.Command != "" {
		info.Command = ri.Command
	}
	if ri.FilePath != "" {
		info.FilePath = ri.FilePath
	} else if ri.Path != "" {
		info.FilePath = ri.Path
	}
	if m := rawInputAsMap(u.RawInput); m != nil {
		info.RawInputMap = m
		if todos := parseTodos(m["todos"]); len(todos) > 0 {
			info.Todos = todos
		}
	}
	// Fallback: some harnesses put the TodoWrite list in the tool result
	// (rawOutput as a JSON array, string, or {todos: [...]} object) and only
	// signal the tool through its title.
	if len(info.Todos) == 0 && isTodoWriteTitle(info) {
		if todos := parseTodosFromRaw(u.RawOutput); len(todos) > 0 {
			info.Todos = todos
		}
	}
	if len(u.Meta) > 0 {
		info.Meta = u.Meta
	}
	if len(u.RawOutput) > 0 {
		info.RawOutputRaw = u.RawOutput
	}

	contentText, terminalID := parseContentBlocks(u.Content)
	if terminalID != "" {
		info.TerminalID = terminalID
	}
	if contentText != "" {
		info.setOutput(contentText)
		source := "content"
		if info.OutputSource != source {
			info.OutputSource = source
			info.EmittedLen = 0
		}
	} else if raw := extractRawOutputTextRaw(u.RawOutput); raw != "" {
		info.setOutput(raw)
		source := "rawOutput"
		if info.OutputSource != source {
			info.OutputSource = source
			info.EmittedLen = 0
		}
	}

	h.applyTerminalMetaLocked(info, u.Meta)

	return info
}

// applyTerminalMetaLocked merges the agent-owned terminal relay meta
// (_meta.terminal_info/_meta.terminal_output/_meta.terminal_exit) into the
// tool call. This is the negotiated extension advertised via the
// "terminal_output" capability. terminal_output chunks are deltas and are
// appended — never replace — the transcript, unlike the cumulative
// content/rawOutput path.
func (h *Handler) applyTerminalMetaLocked(info *ToolCallInfo, meta map[string]any) {
	if len(meta) == 0 {
		return
	}
	if tid, _, ok := parseTerminalInfo(meta); ok && tid != "" {
		if info.TerminalID == "" {
			info.TerminalID = tid
		}
		if state, exists := h.terminalOutputs[tid]; exists {
			// Output/exit arrived before the tool_call announced the terminal
			// (terminal_id is a separate id space from toolCallId).
			if info.OutputSource != "terminal" {
				info.setOutput(state.Output)
				info.OutputSource = "terminal"
				info.EmittedLen = 0
			} else {
				info.appendOutput(state.Output)
			}
			if info.ExitCode == nil {
				info.ExitCode = state.ExitCode
			}
			if info.ExitSignal == nil {
				info.ExitSignal = state.Signal
			}
			if state.Exited {
				info.TerminalExited = true
			}
			delete(h.terminalOutputs, tid)
			// The status transition may already have been emitted before the
			// buffered transcript drained; re-emit so the final activity
			// carries the transcript and exit code.
			if info.Status == ActivityCompleted || info.Status == ActivityFailed {
				h.sink.SendActivity(h.buildToolActivity(info, info.Status))
			}
		}
	}
	if tid, data, ok := parseTerminalOutput(meta); ok && data != "" {
		if info.TerminalID == tid {
			// Correlated: append so the delta relay emits it and the detail
			// carries the full transcript. The EmittedLen clamp guards against
			// the content path having replaced OutputText with shorter text.
			if info.OutputSource != "terminal" {
				info.OutputSource = "terminal"
			}
			if info.EmittedLen > len(info.OutputText) {
				info.EmittedLen = len(info.OutputText)
			}
			info.appendOutput(data)
		} else {
			// Uncorrelated: buffer until the tool_call with terminal_info.
			state := h.terminalOutputs[tid]
			if state == nil {
				state = &TerminalOutputState{}
				h.terminalOutputs[tid] = state
			}
			state.Output += data
		}
	}
	if tid, code, signal, ok := parseTerminalExit(meta); ok {
		if info.TerminalID == tid {
			if info.ExitCode == nil {
				info.ExitCode = code
			}
			if info.ExitSignal == nil {
				info.ExitSignal = signal
			}
			info.TerminalExited = true
		} else {
			state := h.terminalOutputs[tid]
			if state == nil {
				state = &TerminalOutputState{}
				h.terminalOutputs[tid] = state
			}
			if state.ExitCode == nil {
				state.ExitCode = code
			}
			if signal != nil {
				state.Signal = signal
			}
			state.Exited = true
		}
	}
}

// buildToolActivity renders the wire activity from accumulated state. TodoWrite
// tool calls (todo list in rawInput.todos or output) relay as a PLAN keyed by
// their own toolCallId; everything else keeps kind/title/toolCallId.
func (h *Handler) buildToolActivity(info *ToolCallInfo, status ActivityStatus) Activity {
	if todos := todoWriteEntries(info); len(todos) > 0 {
		return Activity{
			ActivityType: ActivityTypePlan,
			Description:  "Agent plan updated",
			ActionID:     info.ToolCallID,
			Status:       ActivityInProgress,
			Detail: ActivityDetail{
				Plan:      todos,
				RawUpdate: info.RawInputMap,
			},
		}
	}
	return Activity{
		ActivityType: mapToolKindToActivity(info.Kind),
		Description:  coalesce(info.Title, info.Kind, "Agent action"),
		ActionID:     info.ToolCallID,
		Status:       status,
		Detail:       h.buildToolDetail(info),
	}
}

// todoWriteEntries returns the TodoWrite list: rawInput.todos, falling back
// to a todo-named tool's output text.
func todoWriteEntries(info *ToolCallInfo) []PlanEntry {
	if len(info.Todos) > 0 {
		return info.Todos
	}
	if isTodoWriteTitle(info) {
		return parseTodosFromText(info.OutputText)
	}
	return nil
}

// isTodoWriteTool reports whether the tool call carries a TodoWrite list.
func isTodoWriteTool(info *ToolCallInfo) bool {
	return len(todoWriteEntries(info)) > 0
}

// isTodoWriteTitle matches harness todo-tool titles (todowrite, N todos, ...).
func isTodoWriteTitle(info *ToolCallInfo) bool {
	title := strings.ToLower(info.Title)
	return strings.Contains(title, "todo")
}

// parseTodosFromText parses a JSON entry array from a tool call's output text.
func parseTodosFromText(text string) []PlanEntry {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	var raw []interface{}
	if err := json.Unmarshal([]byte(text), &raw); err != nil {
		return nil
	}
	return mapPlanEntries(raw)
}

// parseTodosFromRaw reads a TodoWrite list from a tool result: a JSON array, a
// JSON string holding one, or a {todos: [...]} object. nil when unrecognizable.
func parseTodosFromRaw(raw json.RawMessage) []PlanEntry {
	if len(raw) == 0 {
		return nil
	}
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		return nil
	}
	switch t := v.(type) {
	case string:
		return parseTodosFromText(t)
	case []interface{}:
		return mapPlanEntries(t)
	case map[string]interface{}:
		if todos, ok := t["todos"].([]interface{}); ok {
			return mapPlanEntries(todos)
		}
	}
	return nil
}

// buildToolDetail renders the structured, accumulated tool state for the
// expandable activity record. The rawInput/meta bags are preserved verbatim.
func (h *Handler) buildToolDetail(info *ToolCallInfo) ActivityDetail {
	output, omitted := boundDetail(info.OutputText)
	detail := ActivityDetail{
		Kind:      ActivityKind(info.Kind),
		Title:     info.Title,
		Input:     info.RawInputMap,
		Output:    output,
		ExitCode:  info.ExitCode,
		Truncated: info.Truncated || omitted > 0,
		Meta:      info.Meta,
		Hitl:      info.Hitl,
	}
	for _, loc := range info.Locations {
		al := ActivityLocation{Path: loc.Path}
		if loc.Line > 0 {
			line := loc.Line
			al.Line = &line
		}
		detail.Locations = append(detail.Locations, al)
	}
	if oldText, newText, ok := extractDiff(info.RawInputMap); ok {
		detail.Diff = &ActivityDiff{OldText: oldText, NewText: newText, Path: info.FilePath}
	}
	return detail
}

// handleMessageChunk appends one agent/user message chunk to the current
// chunk run (see appendChunkRun).
func (h *Handler) handleMessageChunk(u ToolCallUpdateMsg, role string, rawUpdate map[string]any) {
	text := extractTextFromContentChunk(u)
	if text == "" {
		return
	}
	prefix := "[Agent] "
	if role == "user" {
		prefix = "[User] "
	}
	h.sink.SendOutput(prefix+text, "stdout")

	streamKind := "agent_message"
	if role == "user" {
		streamKind = "user_message"
	}
	h.appendChunkRun(streamKind, ActivityTypeMessage, role, u.MessageID, text, chunkMeta(u), rawUpdate)
}

func (h *Handler) handleThoughtChunk(u ToolCallUpdateMsg, rawUpdate map[string]any) {
	text := extractTextFromContentChunk(u)
	if text == "" {
		return
	}
	h.sink.SendOutput("[Thought] "+text, "stdout")

	h.appendChunkRun("thought", ActivityTypeThinking, "agent", u.MessageID, text, chunkMeta(u), rawUpdate)
}

// Runs emit cumulative text, throttled to bound the wire payload; the
// terminal relay stays on every delta.
const (
	chunkEmitInterval = 250 * time.Millisecond
	chunkEmitBytes    = 512
)

// chunkRun is one contiguous run of same-kind message/thought chunks.
type chunkRun struct {
	actionID     string
	activityType ActivityType
	streamKind   string
	role         string
	messageID    string
	meta         map[string]any
	rawUpdate    map[string]any
	text         string
	lastEmit     time.Time
	emittedBytes int
}

func (h *Handler) appendChunkRun(
	streamKind string,
	activityType ActivityType,
	role string,
	messageID string,
	text string,
	meta map[string]any,
	rawUpdate map[string]any,
) {
	h.mu.Lock()
	defer h.mu.Unlock()

	run := h.openChunkRun
	if run != nil && (run.streamKind != streamKind || run.messageID != messageID) {
		h.closeChunkRunLocked(fmt.Sprintf("%s chunk (messageId=%q) starts a new run", streamKind, messageID))
		run = nil
	}
	if run == nil {
		run = h.openChunkRunLocked(streamKind, activityType, role, messageID)
	}
	run.activityType = activityType
	run.role = role
	run.meta = meta
	run.rawUpdate = rawUpdate
	run.text += text

	if time.Since(run.lastEmit) >= chunkEmitInterval || len(run.text)-run.emittedBytes >= chunkEmitBytes {
		h.emitChunkRunLocked(run, ActivityInProgress)
	}
}

// Run 1 of a messageId uses it as its key; run n >= 2 uses <messageId>#<n>;
// a missing messageId mints inferred-<n>.
func (h *Handler) openChunkRunLocked(streamKind string, activityType ActivityType, role string, messageID string) *chunkRun {
	var actionID string
	if messageID != "" {
		h.chunkRunCounts[messageID]++
		actionID = messageID
		if n := h.chunkRunCounts[messageID]; n >= 2 {
			actionID = fmt.Sprintf("%s#%d", messageID, n)
		}
		h.debugf("[ACP][MSGID] %s: opened run messageId=%q actionId=%q", streamKind, messageID, actionID)
	} else {
		if streamKind != h.inferredStreamType {
			h.inferredSeq++
			h.inferredStreamType = streamKind
		}
		actionID = fmt.Sprintf("inferred-%d", h.inferredSeq)
		h.debugf("[ACP][MSGID] %s: no messageId, opened run with inferred actionId=%q", streamKind, actionID)
	}
	run := &chunkRun{
		actionID:     actionID,
		activityType: activityType,
		streamKind:   streamKind,
		role:         role,
		messageID:    messageID,
	}
	h.openChunkRun = run
	return run
}

func (h *Handler) closeChunkRunLocked(reason string) {
	run := h.openChunkRun
	if run == nil {
		return
	}
	h.openChunkRun = nil
	h.inferredStreamType = ""
	h.debugf("[ACP][MSGID] closing run actionId=%q messageId=%q kind=%s: %s",
		run.actionID, run.messageID, run.streamKind, reason)
	if run.text == "" {
		return
	}
	h.emitChunkRunLocked(run, ActivityCompleted)
}

func (h *Handler) emitChunkRunLocked(run *chunkRun, status ActivityStatus) {
	run.lastEmit = time.Now()
	run.emittedBytes = len(run.text)
	h.sink.SendActivity(Activity{
		ActivityType: run.activityType,
		Description:  run.text,
		ActionID:     run.actionID,
		Status:       status,
		Detail: ActivityDetail{
			MessageID: run.messageID,
			Role:      run.role,
			Meta:      run.meta,
			RawUpdate: run.rawUpdate,
		},
	})
}

// resetChunkStream closes the open chunk run at a non-chunk boundary (tool
// call, plan, mode change, unknown update); metadata-only updates don't reset.
func (h *Handler) resetChunkStream(reason string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.closeChunkRunLocked(reason)
}

// chunkMeta merges the chunk-level _meta bag with the content-block _meta bag
// (block wins) for message/thought activities. Both are ACP extension bags
// preserved verbatim for debugging; never parsed for behavior. The full chunk
// is also preserved in detail.rawUpdate.
func chunkMeta(u ToolCallUpdateMsg) map[string]any {
	meta := map[string]any{}
	for k, v := range u.Meta {
		meta[k] = v
	}
	if len(u.Content) > 0 {
		var block map[string]any
		if err := json.Unmarshal(u.Content, &block); err == nil {
			if bm, ok := block["_meta"].(map[string]any); ok {
				for k, v := range bm {
					meta[k] = v
				}
			}
		}
	}
	if len(meta) == 0 {
		return nil
	}
	return meta
}

// handlePlanUpdate relays one ACP plan snapshot as a PLAN activity with a
// fresh plan-<n> key, so every update is its own activity in the log.
func (h *Handler) handlePlanUpdate(params map[string]interface{}) {
	raw := updateAsMap(params)
	h.sink.SendOutput("[Plan] Agent plan updated", "stdout")
	detail := ActivityDetail{
		Plan:      parsePlanEntries(raw),
		RawUpdate: raw,
	}
	h.sendActivity(ActivityTypePlan, "Agent plan updated", h.nextPlanActionID(), ActivityInProgress, detail)
}

// nextPlanActionID mints a fresh plan-<n> key; every plan snapshot is its own
// activity, so the key is never reused. Guarded by mu.
func (h *Handler) nextPlanActionID() string {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.inferredSeq++
	h.inferredStreamType = "plan"
	return fmt.Sprintf("plan-%d", h.inferredSeq)
}

// parsePlanEntries reads the entries of a session/update plan payload.
func parsePlanEntries(raw map[string]any) []PlanEntry {
	if raw == nil {
		return nil
	}
	rawEntries, ok := raw["entries"].([]interface{})
	if !ok {
		return nil
	}
	return mapPlanEntries(rawEntries)
}

// parseTodos reads a TodoWrite list from a tool call's rawInput "todos" bag.
func parseTodos(v any) []PlanEntry {
	raw, ok := v.([]interface{})
	if !ok {
		return nil
	}
	return mapPlanEntries(raw)
}

// mapPlanEntries converts {content, priority, status} objects (or bare
// strings) into PlanEntry; skips invalid entries, defaults unknown
// priority/status.
func mapPlanEntries(raw []interface{}) []PlanEntry {
	entries := make([]PlanEntry, 0, len(raw))
	for _, rawEntry := range raw {
		switch entry := rawEntry.(type) {
		case string:
			if entry == "" {
				continue
			}
			entries = append(entries, PlanEntry{Content: entry, Priority: PlanPriorityMedium, Status: PlanStatusPending})
		case map[string]interface{}:
			content, _ := entry["content"].(string)
			if content == "" {
				continue
			}
			priority := normalizePlanPriority(entry["priority"])
			status := normalizePlanStatus(entry["status"])
			entries = append(entries, PlanEntry{Content: content, Priority: priority, Status: status})
		}
	}
	return entries
}

// normalizePlanPriority maps a wire priority to the enum, defaulting to medium.
func normalizePlanPriority(v any) PlanEntryPriority {
	switch s, ok := v.(string); {
	case ok && PlanEntryPriority(s) == PlanPriorityHigh:
		return PlanPriorityHigh
	case ok && PlanEntryPriority(s) == PlanPriorityLow:
		return PlanPriorityLow
	default:
		return PlanPriorityMedium
	}
}

// normalizePlanStatus maps a wire status to the enum, defaulting to pending.
func normalizePlanStatus(v any) PlanEntryStatus {
	switch s, ok := v.(string); {
	case ok && PlanEntryStatus(s) == PlanStatusInProgress:
		return PlanStatusInProgress
	case ok && PlanEntryStatus(s) == PlanStatusCompleted:
		return PlanStatusCompleted
	default:
		return PlanStatusPending
	}
}

// captureSessionMetadata accumulates metadata-only discriminators (usage,
// session info, available commands, config options) into session-level state.
// They are captured, never displayed in the terminal (P4).
func (h *Handler) captureSessionMetadata(discriminator string, params map[string]interface{}) {
	update := updateAsMap(params)
	if update == nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	h.sessionMetadata[discriminator] = append(h.sessionMetadata[discriminator], update)
}

// SessionMetadata returns a snapshot of the accumulated metadata-only session
// updates, keyed by discriminator.
func (h *Handler) SessionMetadata() map[string][]map[string]any {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := make(map[string][]map[string]any, len(h.sessionMetadata))
	for k, v := range h.sessionMetadata {
		out[k] = append([]map[string]any(nil), v...)
	}
	return out
}

func (h *Handler) sendActivity(
	activityType ActivityType, description string, actionID string, status ActivityStatus, detail ActivityDetail) {
	h.sink.SendActivity(Activity{
		ActivityType: activityType,
		Description:  description,
		ActionID:     actionID,
		Status:       status,
		Detail:       detail,
	})
}

// buildToolCallDescription renders the [Tool] terminal line from accumulated
// state: title (status), followed by the command or file path.
func (h *Handler) buildToolCallDescription(info *ToolCallInfo, status string) string {
	parts := []string{}
	if info.Title != "" {
		parts = append(parts, info.Title)
	}
	if status != "" {
		parts = append(parts, "("+status+")")
	}
	if info.Command != "" {
		parts = append(parts, info.Command)
	} else if info.FilePath != "" {
		parts = append(parts, info.FilePath)
	}
	if len(parts) == 0 {
		return "Agent action"
	}
	return strings.Join(parts, " ")
}

// extractTextFromContentChunk decodes the single content block of a message or
// thought chunk: {"type":"text","text":...}.
func extractTextFromContentChunk(u ToolCallUpdateMsg) string {
	if len(u.Content) == 0 {
		return ""
	}
	var block map[string]any
	if err := json.Unmarshal(u.Content, &block); err != nil {
		return ""
	}
	text, _ := block["text"].(string)
	return text
}

// parseContentBlocks decodes a tool_call_update content array. Returns the
// concatenated text of every block (handling both {"type":"text","text":...}
// and nested {"type":"content","content":{"type":"text","text":...}} blocks)
// and the terminalId of a {"type":"terminal","terminalId":...} block when
// present (Goose correlates tool calls to terminals this way).
func parseContentBlocks(content json.RawMessage) (text string, terminalID string) {
	if len(content) == 0 {
		return "", ""
	}
	var blocks []json.RawMessage
	if err := json.Unmarshal(content, &blocks); err != nil {
		return "", ""
	}
	var texts []string
	for _, raw := range blocks {
		var block map[string]any
		if err := json.Unmarshal(raw, &block); err != nil {
			continue
		}
		if tid, _ := block["terminalId"].(string); tid != "" {
			terminalID = tid
		}
		if t, _ := block["text"].(string); t != "" {
			texts = append(texts, t)
			continue
		}
		if nested, ok := block["content"].(map[string]any); ok {
			if t, ok := nested["text"].(string); ok && t != "" {
				texts = append(texts, t)
			}
		}
	}
	return strings.Join(texts, "\n"), terminalID
}

// extractRawOutputTextRaw extracts the human-readable output text from an
// opaque rawOutput value (string, JSON string, or object with output /
// formatted_output / content[]).
func extractRawOutputTextRaw(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		return ""
	}
	switch t := v.(type) {
	case string:
		trimmed := strings.TrimSpace(t)
		if strings.HasPrefix(trimmed, "{") {
			var parsed map[string]interface{}
			if err := json.Unmarshal([]byte(trimmed), &parsed); err == nil {
				return extractOutputText(parsed)
			}
		}
		return trimmed
	case map[string]interface{}:
		return extractOutputText(t)
	}
	return ""
}

// extractExitCode reads the command exit code from a rawOutput value: the
// metadata.exit of OpenCode-style output, or the exit_code/exit of
// Codex-style output. Returns nil when no exit code is present.
func extractExitCode(raw json.RawMessage) *int {
	if len(raw) == 0 {
		return nil
	}
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		return nil
	}
	var root map[string]any
	switch t := v.(type) {
	case string:
		if !strings.HasPrefix(t, "{") {
			return nil
		}
		_ = json.Unmarshal([]byte(t), &root)
	case map[string]any:
		root = t
	default:
		return nil
	}
	if root == nil {
		return nil
	}
	for _, key := range []string{"exit_code", "exit"} {
		if num, ok := numberAsInt(root[key]); ok {
			return num
		}
	}
	if meta, ok := root["metadata"].(map[string]any); ok {
		if num, ok := numberAsInt(meta["exit"]); ok {
			return num
		}
	}
	return nil
}

func numberAsInt(v any) (*int, bool) {
	switch n := v.(type) {
	case float64:
		i := int(n)
		return &i, true
	case int:
		return &n, true
	case json.Number:
		if i, err := n.Int64(); err == nil {
			v := int(i)
			return &v, true
		}
	}
	return nil, false
}

// extractDiff pulls the before/after text of an edit tool out of the rawInput
// bag (OpenCode: oldString/newString; Goose: before/after).
func extractDiff(input map[string]any) (oldText string, newText string, ok bool) {
	if input == nil {
		return "", "", false
	}
	oldText, _ = input["oldString"].(string)
	newText, _ = input["newString"].(string)
	if oldText == "" {
		oldText, _ = input["before"].(string)
	}
	if newText == "" {
		newText, _ = input["after"].(string)
	}
	return oldText, newText, oldText != "" || newText != ""
}

// updateAsMap returns the raw update payload of a session/update notification
// as a map, or nil when absent.
func updateAsMap(params map[string]interface{}) map[string]any {
	if params == nil {
		return nil
	}
	update, ok := params["update"].(map[string]interface{})
	if !ok {
		return nil
	}
	b, err := json.Marshal(update)
	if err != nil {
		return nil
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		return nil
	}
	return m
}
