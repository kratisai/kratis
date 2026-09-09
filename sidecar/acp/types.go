package acp

import "encoding/json"

// RequestPermissionParams represents the params of a session/request_permission
// ACP request from the agent to the sidecar client.
// Schema: acp-schema.json#/$defs/RequestPermissionRequest
// Required fields: sessionId, toolCall, options
type RequestPermissionParams struct {
	SessionID string             `json:"sessionId"`
	ToolCall  PermissionToolCall `json:"toolCall"`
	Options   []PermissionOption `json:"options"`
}

// PermissionToolCall is the toolCall object carried inside a
// session/request_permission request. It mirrors ToolCallUpdate from the ACP
// schema and is used only for the data fields we actually need.
// rawInput is schema-less (its shape varies per tool), so we decode it lazily.
type PermissionToolCall struct {
	ToolCallID string          `json:"toolCallId"`
	Title      string          `json:"title"`
	Kind       string          `json:"kind"`
	RawInput   json.RawMessage `json:"rawInput"` // deferred — agent-defined shape
	Locations  []ToolLocation  `json:"locations"`
	Meta       map[string]any  `json:"_meta"` // opaque extension bag, preserved as-is
}

// PermissionOption is one entry in the options array of a
// session/request_permission request.
type PermissionOption struct {
	OptionID string `json:"optionId"`
	Name     string `json:"name"`
	Kind     string `json:"kind"`
}

// CreateElicitationParams represents the params of an elicitation/create
// ACP request from the agent to the sidecar client. Form mode carries a
// JSON Schema; url mode carries a URL.
type CreateElicitationParams struct {
	SessionID       string         `json:"sessionId"`
	ToolCallID      string         `json:"toolCallId"`
	ElicitationID   string         `json:"elicitationId"`
	Message         string         `json:"message"`
	Mode            string         `json:"mode"`
	RequestedSchema map[string]any `json:"requestedSchema"`
	URL             string         `json:"url"`
}

// SessionUpdateParams represents the params of a session/update notification.
// Schema: acp-schema.json#/$defs/SessionUpdate (discriminated by sessionUpdate field)
type SessionUpdateParams struct {
	SessionID string            `json:"sessionId"`
	Update    ToolCallUpdateMsg `json:"update"`
}

// ToolCallUpdateMsg is the update payload for session/update variants. Only the
// fields we read are declared here; the rest are ignored via json.Unmarshal
// default behaviour.
type ToolCallUpdateMsg struct {
	SessionUpdate string          `json:"sessionUpdate"`
	ToolCallID    string          `json:"toolCallId"`
	MessageID     string          `json:"messageId"`
	CurrentModeID string          `json:"currentModeId"`
	Kind          string          `json:"kind"`
	Title         string          `json:"title"`
	Status        string          `json:"status"`
	RawInput      json.RawMessage `json:"rawInput"`  // deferred — agent-defined shape
	RawOutput     json.RawMessage `json:"rawOutput"` // deferred — agent-defined shape
	Content       json.RawMessage `json:"content"`   // deferred — block array or single block
	Locations     []ToolLocation  `json:"locations"`
	Meta          map[string]any  `json:"_meta"` // opaque extension bag, preserved as-is
}

// ToolLocation is a single location entry inside a ToolCallUpdateMsg.
type ToolLocation struct {
	Path string `json:"path"`
	Line int    `json:"line"`
}

// _meta is an opaque extension bag per the ACP spec. Some agents use _meta
// stanzas for capabilities outside the core spec-set.
// e.g. the meta.terminal_output capability exposes the terminal_output in
// _meta

// parseTerminalInfo reads the terminal_info meta that announces an
// agent-owned terminal (codex-acp/claude-agent-acp shape).
func parseTerminalInfo(meta map[string]any) (terminalID, cwd string, ok bool) {
	ti, ok := meta["terminal_info"].(map[string]any)
	if !ok {
		return "", "", false
	}
	id, _ := ti["terminal_id"].(string)
	if id == "" {
		return "", "", false
	}
	cwd, _ = ti["cwd"].(string)
	return id, cwd, true
}

// parseTerminalOutput reads one _meta.terminal_output chunk. data is a delta,
// not cumulative: callers must append it to the terminal's transcript.
func parseTerminalOutput(meta map[string]any) (terminalID, data string, ok bool) {
	to, ok := meta["terminal_output"].(map[string]any)
	if !ok {
		return "", "", false
	}
	id, _ := to["terminal_id"].(string)
	if id == "" {
		return "", "", false
	}
	data, _ = to["data"].(string)
	return id, data, true
}

// parseTerminalExit reads the _meta.terminal_exit that finalizes an
// agent-owned terminal.
func parseTerminalExit(meta map[string]any) (terminalID string, exitCode *int, signal *string, ok bool) {
	te, ok := meta["terminal_exit"].(map[string]any)
	if !ok {
		return "", nil, nil, false
	}
	id, _ := te["terminal_id"].(string)
	if id == "" {
		return "", nil, nil, false
	}
	if ec, ok := numberAsInt(te["exit_code"]); ok {
		exitCode = ec
	}
	if s, ok := te["signal"].(string); ok {
		signal = &s
	}
	return id, exitCode, signal, true
}

// toolRawInput is used only to decode the relevant keys out of the opaque
// rawInput blob. Only fields that Kratis actually needs are declared.
type toolRawInput struct {
	Command  string `json:"command"`
	FilePath string `json:"file_path"`
	Path     string `json:"path"`
}

// parseRawInput decodes a json.RawMessage rawInput into the fields Kratis cares
// about. It handles both JSON objects and JSON strings that themselves contain
// JSON (some agents double-encode rawInput as a string).
func parseRawInput(raw json.RawMessage) toolRawInput {
	if len(raw) == 0 {
		return toolRawInput{}
	}

	var result toolRawInput

	// Happy path: rawInput is a plain JSON object.
	if err := json.Unmarshal(raw, &result); err == nil {
		return result
	}

	// Some agents send rawInput as a JSON string containing JSON (double-encoded).
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		_ = json.Unmarshal([]byte(s), &result)
	}

	return result
}

// rawInputAsMap decodes the opaque rawInput blob into a map for structured
// activity detail. Returns nil when rawInput is absent or unparseable.
func rawInputAsMap(raw json.RawMessage) map[string]any {
	if len(raw) == 0 {
		return nil
	}
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err == nil {
		return m
	}
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		if err := json.Unmarshal([]byte(s), &m); err == nil {
			return m
		}
	}
	return nil
}

// parseRequestPermissionParams decodes the params map of a
// session/request_permission request into a typed struct.
// Returns an error if the required fields are absent.
func parseRequestPermissionParams(params map[string]interface{}) (RequestPermissionParams, error) {
	b, err := json.Marshal(params)
	if err != nil {
		return RequestPermissionParams{}, err
	}
	var p RequestPermissionParams
	if err := json.Unmarshal(b, &p); err != nil {
		return RequestPermissionParams{}, err
	}
	return p, nil
}

// parseCreateElicitationParams decodes the params map of an elicitation/create
// request into a typed struct.
func parseCreateElicitationParams(params map[string]interface{}) (CreateElicitationParams, error) {
	b, err := json.Marshal(params)
	if err != nil {
		return CreateElicitationParams{}, err
	}
	var p CreateElicitationParams
	if err := json.Unmarshal(b, &p); err != nil {
		return CreateElicitationParams{}, err
	}
	return p, nil
}

// parseToolCallUpdate decodes the "update" value of a session/update params map
// into a typed struct.
func parseToolCallUpdate(params map[string]interface{}) (ToolCallUpdateMsg, bool) {
	updateRaw, ok := params["update"]
	if !ok {
		return ToolCallUpdateMsg{}, false
	}
	b, err := json.Marshal(updateRaw)
	if err != nil {
		return ToolCallUpdateMsg{}, false
	}
	var u ToolCallUpdateMsg
	if err := json.Unmarshal(b, &u); err != nil {
		return ToolCallUpdateMsg{}, false
	}
	return u, true
}
