package acp

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestExtractPermissionCommand_ClaudeCodeFormat(t *testing.T) {
	h := NewHandler(nil, nil, "")
	params := map[string]interface{}{
		"options": []interface{}{
			map[string]interface{}{"kind": "allow_once", "optionId": "allow"},
			map[string]interface{}{"kind": "reject_once", "optionId": "reject"},
		},
		"sessionId": "test-session",
		"toolCall": map[string]interface{}{
			"toolCallId": "call_123",
			"rawInput":   map[string]interface{}{"command": "echo hello"},
			"title":      "echo hello",
			"kind":       "execute",
		},
	}

	cmd := h.extractPermissionCommand(params)
	if cmd != "echo hello" {
		t.Errorf("expected command 'echo hello', got %q", cmd)
	}
}

func TestExtractPermissionCommand_ClaudeCodeTitleFallback(t *testing.T) {
	h := NewHandler(nil, nil, "")
	params := map[string]interface{}{
		"options": []interface{}{
			map[string]interface{}{"kind": "allow_once", "optionId": "allow"},
		},
		"toolCall": map[string]interface{}{
			"toolCallId": "call_456",
			"title":      "Bash(ls -la)",
			"kind":       "execute",
		},
	}

	cmd := h.extractPermissionCommand(params)
	if cmd != "Bash(ls -la)" {
		t.Errorf("expected command 'Bash(ls -la)', got %q", cmd)
	}
}

func TestExtractPermissionCommand_NilParams(t *testing.T) {
	h := NewHandler(nil, nil, "")
	cmd := h.extractPermissionCommand(nil)
	if cmd != "" {
		t.Errorf("expected empty command, got %q", cmd)
	}
}

func TestExtractPermissionCommand_NoToolCall(t *testing.T) {
	h := NewHandler(nil, nil, "")
	params := map[string]interface{}{
		"options": []interface{}{},
	}
	cmd := h.extractPermissionCommand(params)
	if cmd != "(unknown operation)" {
		t.Errorf("expected '(unknown operation)', got %q", cmd)
	}
}

func TestExtractPermissionCommand_StateTrackedToolCall(t *testing.T) {
	h := NewHandler(&mockEventSink{}, nil, "")
	// 1. Record tool call from session/update notification
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "call_789",
			"title":         "write_file",
			"locations":     []interface{}{map[string]interface{}{"path": "/kratis/workspace/test.sh"}},
		},
	})

	// 2. session/request_permission arrives with toolCallId only
	params := map[string]interface{}{
		"sessionId": "test-session",
		"toolCall": map[string]interface{}{
			"toolCallId": "call_789",
		},
	}

	cmd := h.extractPermissionCommand(params)
	expected := "write_file /kratis/workspace/test.sh"
	if cmd != expected {
		t.Errorf("expected command %q, got %q", expected, cmd)
	}
}

func TestBuildPermissionResponse_AllowOnceSelection(t *testing.T) {
	options := []PermissionOption{{OptionID: "allow-once", Name: "Allow once", Kind: "allow_once"}}
	resp, err := buildPermissionResponse(float64(0), "allow-once", options)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	result := resp["result"].(map[string]interface{})
	outcome := result["outcome"].(map[string]interface{})
	if outcome["outcome"] != "selected" {
		t.Errorf("expected outcome 'selected', got %v", outcome["outcome"])
	}
	if outcome["optionId"] != "allow-once" {
		t.Errorf("expected optionId 'allow-once', got %v", outcome["optionId"])
	}
	if resp["id"] != float64(0) {
		t.Errorf("expected id 0, got %v", resp["id"])
	}
	if resp["jsonrpc"] != "2.0" {
		t.Errorf("expected jsonrpc '2.0', got %v", resp["jsonrpc"])
	}
}

func TestBuildPermissionResponse_RejectSelectionIsSelectedNotCancelled(t *testing.T) {
	options := []PermissionOption{
		{OptionID: "allow", Name: "Allow once", Kind: "allow_once"},
		{OptionID: "reject", Name: "Reject", Kind: "reject_once"},
	}
	resp, err := buildPermissionResponse(float64(1), "reject", options)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	result := resp["result"].(map[string]interface{})
	outcome := result["outcome"].(map[string]interface{})
	if outcome["outcome"] != "selected" {
		t.Errorf("expected outcome 'selected' for clicked reject option, got %v", outcome["outcome"])
	}
	if outcome["optionId"] != "reject" {
		t.Errorf("expected optionId 'reject', got %v", outcome["optionId"])
	}
}

func TestBuildPermissionResponse_NoSelectionIsCancelled(t *testing.T) {
	options := []PermissionOption{
		{OptionID: "allow", Name: "Allow once", Kind: "allow_once"},
	}
	resp, err := buildPermissionResponse(float64(1), "", options)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	result := resp["result"].(map[string]interface{})
	outcome := result["outcome"].(map[string]interface{})
	if outcome["outcome"] != "cancelled" {
		t.Errorf("expected outcome 'cancelled' for empty selection, got %v", outcome["outcome"])
	}
	if _, exists := outcome["optionId"]; exists {
		t.Errorf("expected no optionId for cancellation, got %v", outcome["optionId"])
	}
}

func TestBuildPermissionResponse_UnknownOptionIsCancelled(t *testing.T) {
	options := []PermissionOption{{OptionID: "allow", Name: "Allow once", Kind: "allow_once"}}
	resp, err := buildPermissionResponse(float64(2), "not-offered", options)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	result := resp["result"].(map[string]interface{})
	outcome := result["outcome"].(map[string]interface{})
	if outcome["outcome"] != "cancelled" {
		t.Errorf("expected outcome 'cancelled' for unknown option, got %v", outcome["outcome"])
	}
}

func TestBuildPermissionResponse_IdCorrelation(t *testing.T) {
	options := []PermissionOption{{OptionID: "allow", Name: "Allow once", Kind: "allow_once"}}
	for _, id := range []interface{}{float64(0), float64(42), "string-id-123"} {
		resp, err := buildPermissionResponse(id, "allow", options)
		if err != nil {
			t.Fatalf("unexpected error for id %v: %v", id, err)
		}
		if resp["id"] != id {
			t.Errorf("expected id %v, got %v", id, resp["id"])
		}
	}
}

func TestOptionKindForOptions(t *testing.T) {
	options := []PermissionOption{
		{OptionID: "a1", Name: "A1", Kind: "allow_once"},
		{OptionID: "r1", Name: "R1", Kind: "reject_always"},
	}
	if got := optionKindForOptions(options, "a1"); got != ApprovalAllowOnce {
		t.Errorf("expected allow_once, got %q", got)
	}
	if got := optionKindForOptions(options, "r1"); got != ApprovalRejectAlways {
		t.Errorf("expected reject_always, got %q", got)
	}
	if got := optionKindForOptions(options, "missing"); got != "" {
		t.Errorf("expected empty kind for missing option, got %q", got)
	}
}

func TestIsAllowKind(t *testing.T) {
	for kind, want := range map[ApprovalOptionKind]bool{
		ApprovalAllowOnce:    true,
		ApprovalAllowAlways:  true,
		ApprovalRejectOnce:   false,
		ApprovalRejectAlways: false,
		"":                   false,
	} {
		if got := isAllowKind(kind); got != want {
			t.Errorf("isAllowKind(%q) = %v, want %v", kind, got, want)
		}
	}
}

func TestPermissionDiff(t *testing.T) {
	tc := PermissionToolCall{
		ToolCallID: "call-1",
		Kind:       "edit",
		Locations:  []ToolLocation{{Path: "/kratis/workspace/a.ts"}},
		RawInput:   json.RawMessage(`{"command":"edit","oldString":"old","newString":"new"}`),
	}
	diff := permissionDiff(tc)
	if diff == nil {
		t.Fatal("expected diff to be extracted")
	}
	if diff.OldText != "old" || diff.NewText != "new" || diff.Path != "/kratis/workspace/a.ts" {
		t.Errorf("unexpected diff: %+v", diff)
	}

	if got := permissionDiff(PermissionToolCall{ToolCallID: "call-2"}); got != nil {
		t.Errorf("expected nil diff for non-edit rawInput, got %+v", got)
	}
}

func TestHandleSessionUpdate_TableDriven(t *testing.T) {
	tests := []struct {
		name               string
		params             map[string]interface{}
		expectedActivities []capturedActivity
		expectedOutputs    []capturedOutput
	}{
		{
			name: "agent_message_chunk maps to MESSAGE",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "agent_message_chunk",
					"messageId":     "msg-1",
					"content":       map[string]interface{}{"type": "text", "text": "chunk text"},
				},
			},
			expectedActivities: []capturedActivity{
				{activityType: ActivityTypeMessage, description: "chunk text", actionID: "msg-1", status: "in_progress"},
			},
			expectedOutputs: []capturedOutput{
				{line: "[Agent] chunk text", stream: "stdout"},
			},
		},
		{
			name: "user_message_chunk maps to MESSAGE with user role",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "user_message_chunk",
					"messageId":     "msg-2",
					"content":       map[string]interface{}{"type": "text", "text": "user text"},
				},
			},
			expectedActivities: []capturedActivity{
				{activityType: ActivityTypeMessage, description: "user text", actionID: "msg-2", status: "in_progress"},
			},
			expectedOutputs: []capturedOutput{
				{line: "[User] user text", stream: "stdout"},
			},
		},
		{
			name: "agent_thought_chunk maps to THINKING",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "agent_thought_chunk",
					"messageId":     "msg-3",
					"content":       map[string]interface{}{"type": "text", "text": "reasoning..."},
				},
			},
			expectedActivities: []capturedActivity{
				{activityType: ActivityTypeThinking, description: "reasoning...", actionID: "msg-3", status: "in_progress"},
			},
			expectedOutputs: []capturedOutput{
				{line: "[Thought] reasoning...", stream: "stdout"},
			},
		},
		{
			name: "tool_call (read kind) emits pending RESEARCH activity",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "tool_call",
					"toolCallId":    "tc-1",
					"kind":          "read",
					"title":         "Reading main.go",
					"status":        "pending",
				},
			},
			expectedActivities: []capturedActivity{
				{activityType: ActivityTypeResearch, description: "Reading main.go", actionID: "tc-1", status: "pending"},
			},
			expectedOutputs: []capturedOutput{
				{line: "[Tool] Reading main.go (pending)", stream: "stdout"},
			},
		},
		{
			name: "tool_call (edit kind) emits pending EDITED activity",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "tool_call",
					"toolCallId":    "tc-2",
					"kind":          "edit",
					"title":         "Editing config.yaml",
					"status":        "pending",
				},
			},
			expectedActivities: []capturedActivity{
				{activityType: ActivityTypeEdited, description: "Editing config.yaml", actionID: "tc-2", status: "pending"},
			},
			expectedOutputs: []capturedOutput{
				{line: "[Tool] Editing config.yaml (pending)", stream: "stdout"},
			},
		},
		{
			name: "tool_call (execute kind) emits pending COMMAND activity",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "tool_call",
					"toolCallId":    "tc-3",
					"kind":          "execute",
					"title":         "Running tests",
					"status":        "pending",
				},
			},
			expectedActivities: []capturedActivity{
				{activityType: ActivityTypeCommand, description: "Running tests", actionID: "tc-3", status: "pending"},
			},
			expectedOutputs: []capturedOutput{
				{line: "[Tool] Running tests (pending)", stream: "stdout"},
			},
		},
		{
			name: "tool_call without status defaults to pending",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "tool_call",
					"toolCallId":    "tc-4",
					"kind":          "execute",
					"title":         "shell · git status",
				},
			},
			expectedActivities: []capturedActivity{
				{activityType: ActivityTypeCommand, description: "shell · git status", actionID: "tc-4", status: "pending"},
			},
			expectedOutputs: []capturedOutput{
				{line: "[Tool] shell · git status (pending)", stream: "stdout"},
			},
		},
		{
			name: "plan relays line and detail",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "plan",
				},
			},
			expectedActivities: []capturedActivity{
				{activityType: ActivityTypePlan, description: "Agent plan updated", actionID: "plan-1", status: "in_progress"},
			},
			expectedOutputs: []capturedOutput{
				{line: "[Plan] Agent plan updated", stream: "stdout"},
			},
		},
		{
			name: "current_mode_update relays line",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "current_mode_update",
					"currentModeId": "architect",
				},
			},
			expectedActivities: []capturedActivity{
				{activityType: ActivityTypeThinking, description: "Agent mode changed to: architect", status: "in_progress"},
			},
			expectedOutputs: []capturedOutput{
				{line: "[Mode] Changed to: architect", stream: "stdout"},
			},
		},
		{
			name: "usage_update is captured but silent",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "usage_update",
					"used":          float64(0),
					"size":          float64(128000),
				},
			},
			expectedActivities: nil,
			expectedOutputs:    nil,
		},
		{
			name: "session_info_update is captured but silent",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "session_info_update",
				},
			},
		},
		{
			name: "available_commands_update is captured but silent",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "available_commands_update",
				},
			},
		},
		{
			name: "config_option_update keeps the fallback line",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "config_option_update",
				},
			},
			expectedOutputs: []capturedOutput{
				{line: "[Update] config_option_update", stream: "stdout"},
			},
		},
		{
			name: "unknown discriminator falls back to generic relay",
			params: map[string]interface{}{
				"update": map[string]interface{}{
					"sessionUpdate": "future_update_type",
				},
			},
			expectedActivities: []capturedActivity{
				{activityType: ActivityTypeThinking, description: "[Update] future_update_type", status: "in_progress"},
			},
			expectedOutputs: []capturedOutput{
				{line: "[Update] future_update_type", stream: "stdout"},
			},
		},
		{
			name: "session/update with no update field is silent",
			params: map[string]interface{}{
				"sessionId": "test-session",
			},
		},
		{
			name:   "nil params is silent",
			params: nil,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			sink := &mockEventSink{}
			h := NewHandler(sink, nil, "")
			h.handleSessionUpdate(tt.params)
			assertActivities(t, sink.activities, tt.expectedActivities)
			assertOutputs(t, sink.outputs, tt.expectedOutputs)
		})
	}
}

func TestHandleSessionUpdate_AccumulatesMetadata(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "usage_update",
			"used":          float64(42),
		},
	})
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "session_info_update",
			"cwd":           "/kratis/workspace",
		},
	})

	metadata := h.SessionMetadata()
	if len(metadata["usage_update"]) != 1 {
		t.Errorf("expected 1 usage_update capture, got %d", len(metadata["usage_update"]))
	}
	if len(metadata["session_info_update"]) != 1 {
		t.Errorf("expected 1 session_info_update capture, got %d", len(metadata["session_info_update"]))
	}
	if used, ok := metadata["usage_update"][0]["used"].(float64); !ok || used != 42 {
		t.Errorf("expected usage captured with used=42, got %v", metadata["usage_update"][0])
	}
}

func planUpdateParams(entries ...map[string]interface{}) map[string]interface{} {
	update := map[string]interface{}{
		"sessionUpdate": "plan",
		"entries":       entries,
	}
	return map[string]interface{}{"update": update}
}

func TestHandleSessionUpdate_PlanStructuredEntries(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(planUpdateParams(
		map[string]interface{}{"content": "Setup repo", "priority": "high", "status": "in_progress"},
		map[string]interface{}{"content": "Implement feature", "priority": "medium", "status": "pending"},
		map[string]interface{}{"content": "Run tests", "priority": "low", "status": "completed"},
	))

	if len(sink.activities) != 1 {
		t.Fatalf("expected 1 activity, got %d: %+v", len(sink.activities), sink.activities)
	}
	act := sink.activities[0]
	if act.activityType != "PLAN" || !strings.HasPrefix(act.actionID, "plan-") || act.actionID == "" {
		t.Errorf("expected PLAN activity keyed by plan-<n> in_progress, got %+v", act)
	}
	want := []PlanEntry{
		{Content: "Setup repo", Priority: PlanPriorityHigh, Status: PlanStatusInProgress},
		{Content: "Implement feature", Priority: PlanPriorityMedium, Status: PlanStatusPending},
		{Content: "Run tests", Priority: PlanPriorityLow, Status: PlanStatusCompleted},
	}
	if !reflect.DeepEqual(act.detail.Plan, want) {
		t.Errorf("expected structured plan entries, got %+v", act.detail.Plan)
	}
	if act.detail.RawUpdate == nil || act.detail.RawUpdate["sessionUpdate"] != "plan" {
		t.Errorf("expected raw update preserved verbatim, got %+v", act.detail.RawUpdate)
	}
}

func TestHandleSessionUpdate_PlanReplaceAllAndOrdering(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(planUpdateParams(
		map[string]interface{}{"content": "A", "priority": "high", "status": "in_progress"},
		map[string]interface{}{"content": "B", "priority": "medium", "status": "pending"},
		map[string]interface{}{"content": "C", "priority": "medium", "status": "pending"},
	))
	h.handleSessionUpdate(planUpdateParams(
		map[string]interface{}{"content": "A", "priority": "high", "status": "completed"},
		map[string]interface{}{"content": "D", "priority": "medium", "status": "in_progress"},
	))

	if len(sink.activities) != 2 {
		t.Fatalf("expected 2 plan activities, got %d: %+v", len(sink.activities), sink.activities)
	}
	want := []PlanEntry{
		{Content: "A", Priority: PlanPriorityHigh, Status: PlanStatusCompleted},
		{Content: "D", Priority: PlanPriorityMedium, Status: PlanStatusInProgress},
	}
	if !reflect.DeepEqual(sink.activities[1].detail.Plan, want) {
		t.Errorf("expected replace-all with ordering preserved (entry B/C dropped), got %+v", sink.activities[1].detail.Plan)
	}
	// Each update gets its own actionId so it shows as a fresh activity at its
	// point in history instead of merging into the first plan entry.
	if sink.activities[0].actionID == sink.activities[1].actionID {
		t.Errorf("expected distinct actionIDs per plan update, got %q twice", sink.activities[0].actionID)
	}
	for i, act := range sink.activities {
		if act.actionID == "" || !strings.HasPrefix(act.actionID, "plan-") {
			t.Errorf("activity %d: expected plan-<n> actionID, got %q", i, act.actionID)
		}
	}
}

func TestHandleSessionUpdate_PlanTodoWriteMapping(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	// claude-agent-acp bridges TodoWrite into a session/update plan: a think
	// tool card plus the entry list (priority medium, statuses passed through).
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "todo-1",
			"kind":          "think",
			"title":         "Update TODOs: Write tests",
			"status":        "completed",
		},
	})
	h.handleSessionUpdate(planUpdateParams(
		map[string]interface{}{"content": "Write tests", "status": "in_progress"},
		map[string]interface{}{"content": "Refactor", "status": "pending"},
	))

	if len(sink.activities) != 2 {
		t.Fatalf("expected tool card + plan activity, got %d: %+v", len(sink.activities), sink.activities)
	}
	if sink.activities[0].activityType != "THINKING" {
		t.Errorf("expected TodoWrite tool card as THINKING, got %+v", sink.activities[0])
	}
	want := []PlanEntry{
		{Content: "Write tests", Priority: PlanPriorityMedium, Status: PlanStatusInProgress},
		{Content: "Refactor", Priority: PlanPriorityMedium, Status: PlanStatusPending},
	}
	if !reflect.DeepEqual(sink.activities[1].detail.Plan, want) {
		t.Errorf("expected TodoWrite-derived plan entries (default priority medium), got %+v", sink.activities[1].detail.Plan)
	}
}

func TestHandleSessionUpdate_TodoWriteToolCallRelaysAsPlan(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	// OpenCode emits TodoWrite as a tool call: todos arrive in the in_progress
	// rawInput.todos and again in the completed output as JSON. Both relay as
	// PLAN and never leak the raw JSON to the terminal.
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "todo-1",
			"kind":          "other",
			"title":         "todowrite",
			"status":        "pending",
		},
	})
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "todo-1",
			"title":         "todowrite",
			"status":        "in_progress",
			"rawInput": map[string]interface{}{
				"todos": []interface{}{
					map[string]interface{}{"content": "Step 1: Setup", "priority": "high", "status": "in_progress"},
					map[string]interface{}{"content": "Step 2: Build", "priority": "high", "status": "pending"},
					map[string]interface{}{"content": "Step 3: Test", "priority": "high", "status": "pending"},
				},
			},
		},
	})
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "todo-1",
			"title":         "3 todos",
			"status":        "completed",
			"rawOutput": `[
  {
    "content": "Step 1: Setup",
    "status": "in_progress",
    "priority": "high"
  },
  {
    "content": "Step 2: Build",
    "status": "pending",
    "priority": "high"
  }
]`,
		},
	})

	// The pending event (no todos known yet) is a plain tool card; the
	// in_progress and completed events relay as PLAN activities.
	if len(sink.activities) != 3 {
		t.Fatalf("expected 3 activities, got %d: %+v", len(sink.activities), sink.activities)
	}
	if sink.activities[0].activityType != "COMMAND" || sink.activities[0].actionID != "todo-1" {
		t.Errorf("expected pending event as COMMAND tool card, got %+v", sink.activities[0])
	}
	want := []PlanEntry{
		{Content: "Step 1: Setup", Priority: PlanPriorityHigh, Status: PlanStatusInProgress},
		{Content: "Step 2: Build", Priority: PlanPriorityHigh, Status: PlanStatusPending},
		{Content: "Step 3: Test", Priority: PlanPriorityHigh, Status: PlanStatusPending},
	}
	for i := 1; i < 3; i++ {
		act := sink.activities[i]
		// TodoWrite PLAN activities key by their own toolCallId: each TodoWrite
		// call is one plan snapshot, and lifecycle events merge into that entry.
		if act.activityType != "PLAN" || act.actionID != "todo-1" || act.status != "in_progress" {
			t.Errorf("activity %d: expected PLAN keyed by %q in_progress, got %+v", i, "todo-1", act)
		}
		if !reflect.DeepEqual(act.detail.Plan, want) {
			t.Errorf("activity %d: expected structured todos, got %+v", i, act.detail.Plan)
		}
	}
	// The raw todo JSON must never leak to the terminal; the todo events relay
	// as [Plan] lines instead of the tool's JSON output.
	for _, out := range sink.outputs {
		if strings.Contains(out.line, `"content"`) {
			t.Errorf("expected no raw todo JSON relay, got %q", out.line)
		}
	}
	planLines := 0
	for _, out := range sink.outputs {
		if out.line == "[Plan] Agent plan updated" {
			planLines++
		}
	}
	if planLines < 2 {
		t.Errorf("expected [Plan] relay for todo events, got %v", sink.outputs)
	}
}

func TestHandleSessionUpdate_TodoWriteToolCallOutputFallback(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	// Variant: no rawInput.todos; a todo-named tool carries the list only in
	// its result as a JSON array.
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "todo-2",
			"kind":          "think",
			"title":         "Update TODOs",
			"status":        "pending",
		},
	})
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "todo-2",
			"status":        "completed",
			"rawOutput":     `[{"content":"Write tests","priority":"medium","status":"in_progress"},{"content":"Refactor","priority":"medium","status":"pending"}]`,
		},
	})

	if len(sink.activities) != 2 {
		t.Fatalf("expected 2 activities, got %d: %+v", len(sink.activities), sink.activities)
	}
	last := sink.activities[1]
	if last.activityType != "PLAN" || last.actionID != "todo-2" {
		t.Errorf("expected completed event as PLAN keyed by its toolCallId, got %+v", last)
	}
	want := []PlanEntry{
		{Content: "Write tests", Priority: PlanPriorityMedium, Status: PlanStatusInProgress},
		{Content: "Refactor", Priority: PlanPriorityMedium, Status: PlanStatusPending},
	}
	if !reflect.DeepEqual(last.detail.Plan, want) {
		t.Errorf("expected output-derived todos, got %+v", last.detail.Plan)
	}
}

func TestParsePlanEntries(t *testing.T) {
	raw := map[string]interface{}{
		"sessionUpdate": "plan",
		"entries": []interface{}{
			map[string]interface{}{"content": "valid", "priority": "high", "status": "completed"},
			map[string]interface{}{"content": "missing status and priority"},
			map[string]interface{}{"content": ""}, // empty content skipped
			42,                                    // non-string/non-object entry skipped
			"bare string todo",                    // string-form todo maps to defaults
			map[string]interface{}{"content": "unknown enums fall back", "priority": "urgent", "status": "done"},
		},
	}
	want := []PlanEntry{
		{Content: "valid", Priority: PlanPriorityHigh, Status: PlanStatusCompleted},
		{Content: "missing status and priority", Priority: PlanPriorityMedium, Status: PlanStatusPending},
		{Content: "bare string todo", Priority: PlanPriorityMedium, Status: PlanStatusPending},
		{Content: "unknown enums fall back", Priority: PlanPriorityMedium, Status: PlanStatusPending},
	}
	got := parsePlanEntries(raw)
	if !reflect.DeepEqual(got, want) {
		t.Errorf("expected %+v, got %+v", want, got)
	}
	if got := parsePlanEntries(nil); got != nil {
		t.Errorf("expected nil for nil raw, got %+v", got)
	}
	if got := parsePlanEntries(map[string]interface{}{"sessionUpdate": "plan"}); len(got) != 0 {
		t.Errorf("expected no entries when entries key absent, got %+v", got)
	}
}

// sessionUpdateParams builds a session/update params map for one message/thought chunk.
func sessionUpdateParams(sessionUpdate, messageId, text string) map[string]interface{} {
	update := map[string]interface{}{
		"sessionUpdate": sessionUpdate,
		"content":       map[string]interface{}{"type": "text", "text": text},
	}
	if messageId != "" {
		update["messageId"] = messageId
	}
	return map[string]interface{}{"update": update}
}

func TestHandleSessionUpdate_InferredMessageID_ConsecutiveChunksShareID(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(sessionUpdateParams("agent_message_chunk", "", "Hello "))
	h.handleSessionUpdate(sessionUpdateParams("agent_message_chunk", "", "world"))
	h.CloseChunkRun("prompt turn ended")

	if len(sink.activities) != 2 {
		t.Fatalf("expected one in_progress plus one completed emission for the run, got %d: %+v", len(sink.activities), sink.activities)
	}
	first, closed := sink.activities[0], sink.activities[1]
	if first.activityType != "MESSAGE" || closed.activityType != "MESSAGE" {
		t.Fatalf("expected MESSAGE activities, got %q and %q", first.activityType, closed.activityType)
	}
	if first.actionID == "" || first.actionID != closed.actionID {
		t.Errorf("one run must keep one inferred actionID, got %q then %q", first.actionID, closed.actionID)
	}
	if !strings.HasPrefix(first.actionID, "inferred-") {
		t.Errorf("expected inferred-* actionID, got %q", first.actionID)
	}
	if first.status != "in_progress" || closed.status != "completed" {
		t.Errorf("expected in_progress then completed, got %q then %q", first.status, closed.status)
	}
	if first.description != "Hello " || closed.description != "Hello world" {
		t.Errorf("expected cumulative run text, got %q then %q", first.description, closed.description)
	}
	if first.detail.MessageID != "" || closed.detail.MessageID != "" {
		t.Errorf("expected empty detail.messageId for an inferred run, got %q / %q", first.detail.MessageID, closed.detail.MessageID)
	}
}

func TestHandleSessionUpdate_InferredMessageID_ThoughtMessageThought_ThreeStreams(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	for _, tc := range []struct{ kind, text string }{
		{"agent_thought_chunk", "thought one"},
		{"agent_message_chunk", "message"},
		{"agent_thought_chunk", "thought two"},
	} {
		h.handleSessionUpdate(sessionUpdateParams(tc.kind, "", tc.text))
	}

	if len(sink.activities) != 5 {
		t.Fatalf("expected 5 activities, got %d: %+v", len(sink.activities), sink.activities)
	}
	a := sink.activities
	if a[0].actionID == a[2].actionID || a[2].actionID == a[4].actionID || a[0].actionID == a[4].actionID {
		t.Errorf("thought→message→thought must yield three distinct run IDs, got %q, %q, %q", a[0].actionID, a[2].actionID, a[4].actionID)
	}
	if a[0].activityType != "THINKING" || a[2].activityType != "MESSAGE" || a[4].activityType != "THINKING" {
		t.Errorf("unexpected activity types: %q, %q, %q", a[0].activityType, a[2].activityType, a[4].activityType)
	}
	if a[0].status != "in_progress" || a[1].status != "completed" || a[2].status != "in_progress" || a[3].status != "completed" || a[4].status != "in_progress" {
		t.Errorf("expected closed runs to emit completed, got statuses %q %q %q %q %q", a[0].status, a[1].status, a[2].status, a[3].status, a[4].status)
	}
	if a[1].description != "thought one" || a[3].description != "message" || a[4].description != "thought two" {
		t.Errorf("expected cumulative text per run, got %q, %q, %q", a[1].description, a[3].description, a[4].description)
	}
}

func TestHandleSessionUpdate_InferredMessageID_RealMessageIdResetsStream(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(sessionUpdateParams("agent_message_chunk", "", "inferred "))
	h.handleSessionUpdate(sessionUpdateParams("agent_message_chunk", "real-1", "real"))
	h.handleSessionUpdate(sessionUpdateParams("agent_message_chunk", "", " again"))

	if len(sink.activities) != 5 {
		t.Fatalf("expected 5 activities, got %d: %+v", len(sink.activities), sink.activities)
	}
	a := sink.activities
	if a[2].actionID != "real-1" || a[3].actionID != "real-1" {
		t.Errorf("expected the agent messageId as actionID, got %q / %q", a[2].actionID, a[3].actionID)
	}
	if a[2].detail.MessageID != "real-1" {
		t.Errorf("expected detail.messageId to carry the raw ACP messageId, got %q", a[2].detail.MessageID)
	}
	if a[4].actionID == a[0].actionID {
		t.Errorf("chunk after a real messageId must not reuse the previous inferred ID")
	}
	if a[4].actionID == "real-1" || !strings.HasPrefix(a[4].actionID, "inferred-") {
		t.Errorf("chunk without messageId after a real messageId must start a fresh inferred run, got %q", a[4].actionID)
	}
}

func TestHandleSessionUpdate_InferredMessageID_ToolCallResetsStream(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(sessionUpdateParams("agent_message_chunk", "", "first message"))
	h.handleSessionUpdate(map[string]interface{}{"update": map[string]interface{}{
		"sessionUpdate": "tool_call",
		"toolCallId":    "tc-1",
		"kind":          "read",
		"title":         "Reading",
	}})
	h.handleSessionUpdate(sessionUpdateParams("agent_message_chunk", "", "second message"))

	if len(sink.activities) != 4 {
		t.Fatalf("expected 4 activities, got %d: %+v", len(sink.activities), sink.activities)
	}
	a := sink.activities
	if a[1].status != "completed" || a[1].description != "first message" {
		t.Errorf("expected the tool call to close the run with completed, got %+v", a[1])
	}
	if a[0].actionID == a[3].actionID {
		t.Errorf("message chunks separated by a tool call must not share an inferred ID")
	}
}

func TestHandleSessionUpdate_PlanSharesInferredCounter(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	// Chunk stream + plan snapshot share one counter: the plan mints plan-2
	// and resets the chunk stream.
	h.handleSessionUpdate(sessionUpdateParams("agent_message_chunk", "", "first "))
	h.handleSessionUpdate(sessionUpdateParams("agent_message_chunk", "", "second"))
	h.handleSessionUpdate(planUpdateParams(
		map[string]interface{}{"content": "A", "priority": "medium", "status": "pending"},
	))
	h.handleSessionUpdate(sessionUpdateParams("agent_message_chunk", "", " third"))

	if len(sink.activities) != 4 {
		t.Fatalf("expected 4 activities, got %d: %+v", len(sink.activities), sink.activities)
	}
	a := sink.activities
	if a[0].actionID != a[1].actionID {
		t.Errorf("consecutive message chunks must share one inferred ID, got %q then %q", a[0].actionID, a[1].actionID)
	}
	if !strings.HasPrefix(a[0].actionID, "inferred-") {
		t.Errorf("expected inferred-* chunk actionID, got %q", a[0].actionID)
	}
	if a[2].activityType != "PLAN" || a[2].actionID != "plan-2" {
		t.Errorf("expected plan to share the counter as plan-2, got %+v", a[2])
	}
	if a[3].actionID == a[0].actionID || !strings.HasPrefix(a[3].actionID, "inferred-") {
		t.Errorf("chunk after a plan update must start a fresh inferred stream, got %q", a[3].actionID)
	}
}

func TestHandleSessionUpdate_ForwardsChunkMetaAndRawUpdate(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(map[string]interface{}{"update": map[string]interface{}{
		"sessionUpdate": "agent_message_chunk",
		"messageId":     "msg-1",
		"content": map[string]interface{}{
			"type":  "text",
			"text":  "hi",
			"_meta": map[string]interface{}{"blockKey": "blockVal"},
		},
		"_meta": map[string]interface{}{"chunkKey": "chunkVal"},
	}})

	if len(sink.activities) != 1 {
		t.Fatalf("expected 1 activity, got %d", len(sink.activities))
	}
	detail := sink.activities[0].detail
	if detail.Meta == nil {
		t.Fatal("expected _meta forwarded into detail.meta")
	}
	if detail.Meta["chunkKey"] != "chunkVal" {
		t.Errorf("expected chunk-level _meta preserved, got %+v", detail.Meta)
	}
	if detail.Meta["blockKey"] != "blockVal" {
		t.Errorf("expected content-block _meta merged in, got %+v", detail.Meta)
	}
	if detail.RawUpdate == nil {
		t.Fatal("expected rawUpdate captured")
	}
	if detail.RawUpdate["sessionUpdate"] != "agent_message_chunk" {
		t.Errorf("expected rawUpdate to preserve the update payload, got %+v", detail.RawUpdate)
	}
	if detail.MessageID != "msg-1" {
		t.Errorf("expected messageId, got %q", detail.MessageID)
	}
}

func TestHandleSessionUpdate_UserMessageChunkSeparatesAgentMessageStream(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(sessionUpdateParams("agent_message_chunk", "", "agent reply "))
	h.handleSessionUpdate(sessionUpdateParams("user_message_chunk", "", "user follow-up"))
	h.handleSessionUpdate(sessionUpdateParams("agent_message_chunk", "", "agent reply two"))

	if len(sink.activities) != 5 {
		t.Fatalf("expected 5 activities, got %d: %+v", len(sink.activities), sink.activities)
	}
	a := sink.activities
	if a[0].actionID == a[4].actionID {
		t.Errorf("agent message chunks separated by a user message must not share an inferred ID")
	}
	if a[2].detail.Role != "user" {
		t.Errorf("expected user role on the user message chunk, got %q", a[2].detail.Role)
	}
	if a[1].status != "completed" || a[3].status != "completed" {
		t.Errorf("expected both closed runs to emit completed, got %q and %q", a[1].status, a[3].status)
	}
}

func assertActivities(t *testing.T, got []capturedActivity, want []capturedActivity) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("expected %d activities, got %d: %+v", len(want), len(got), got)
	}
	for i := range want {
		if got[i].activityType != want[i].activityType ||
			got[i].description != want[i].description ||
			got[i].actionID != want[i].actionID ||
			got[i].status != want[i].status {
			t.Errorf("activity %d: expected %+v, got %+v", i, want[i], got[i])
		}
		if !reflect.DeepEqual(want[i].detail, ActivityDetail{}) && !reflect.DeepEqual(got[i].detail, want[i].detail) {
			t.Errorf("activity %d detail: expected %+v, got %+v", i, want[i].detail, got[i].detail)
		}
	}
}

func assertOutputs(t *testing.T, got []capturedOutput, want []capturedOutput) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("expected %d outputs, got %d: %+v", len(want), len(got), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("output %d: expected %+v, got %+v", i, want[i], got[i])
		}
	}
}

func TestParseInitializeResponse_FullResponse(t *testing.T) {
	resp := map[string]interface{}{
		"jsonrpc": "2.0",
		"result": map[string]interface{}{
			"protocolVersion": float64(1),
			"agentCapabilities": map[string]interface{}{
				"createTerminal": true,
			},
			"authMethods": []interface{}{},
			"agentInfo": map[string]interface{}{
				"name":    "test-agent",
				"version": "1.0.0",
				"title":   "Test Agent",
			},
		},
	}

	result, name, version, err := ParseInitializeResponse(resp)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result.ProtocolVersion != 1 {
		t.Errorf("expected protocol version 1, got %d", result.ProtocolVersion)
	}
	if name != "test-agent" {
		t.Errorf("expected name 'test-agent', got %q", name)
	}
	if version != "1.0.0" {
		t.Errorf("expected version '1.0.0', got %q", version)
	}
	if result.AgentInfo.Title != "Test Agent" {
		t.Errorf("expected title 'Test Agent', got %q", result.AgentInfo.Title)
	}
}

func TestParseInitializeResponse_ServerInfoFallback(t *testing.T) {
	resp := map[string]interface{}{
		"jsonrpc": "2.0",
		"result": map[string]interface{}{
			"protocolVersion": float64(1),
			"serverInfo": map[string]interface{}{
				"name":    "fallback-agent",
				"version": "2.0.0",
			},
		},
	}

	_, name, version, err := ParseInitializeResponse(resp)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if name != "fallback-agent" {
		t.Errorf("expected name 'fallback-agent', got %q", name)
	}
	if version != "2.0.0" {
		t.Errorf("expected version '2.0.0', got %q", version)
	}
}

func TestParseInitializeResponse_NoResult(t *testing.T) {
	resp := map[string]interface{}{
		"jsonrpc": "2.0",
	}

	_, _, _, err := ParseInitializeResponse(resp)
	if err == nil {
		t.Error("expected error for missing result")
	}
}

func TestExtractToolOutputFromSessionUpdate_ClaudeCodePlainText(t *testing.T) {
	// Claude Code sends rawOutput as a plain text string inside params.update
	params := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"status":        "completed",
			"rawOutput":     "Hello Kratis - what a lovely day\n",
		},
	}

	output, stream, ok := extractToolOutputFromSessionUpdate(params)
	if !ok {
		t.Fatal("expected output to be extracted")
	}
	expected := "Hello Kratis - what a lovely day"
	if output != expected {
		t.Errorf("expected %q, got %q", expected, output)
	}
	if stream != "stdout" {
		t.Errorf("expected stdout, got %q", stream)
	}
}

func TestExtractToolOutputFromSessionUpdate_OpenCodeJSONString(t *testing.T) {
	// OpenCode sends rawOutput as a JSON string with output and metadata fields inside params.update
	params := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"status":        "completed",
			"rawOutput":     `{"output":"Hello Kratis - what a lovely day\n","metadata":{"exit":0}}`,
		},
	}

	output, stream, ok := extractToolOutputFromSessionUpdate(params)
	if !ok {
		t.Fatal("expected output to be extracted")
	}
	expected := "Hello Kratis - what a lovely day"
	if output != expected {
		t.Errorf("expected %q, got %q", expected, output)
	}
	if stream != "stdout" {
		t.Errorf("expected stdout, got %q", stream)
	}
}

func TestExtractToolOutputFromSessionUpdate_MapFormat(t *testing.T) {
	// Some agents may send rawOutput as a direct map object inside params.update
	params := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"status":        "completed",
			"rawOutput": map[string]interface{}{
				"output":   "command output here",
				"metadata": map[string]interface{}{"exit": float64(0)},
			},
		},
	}

	output, stream, ok := extractToolOutputFromSessionUpdate(params)
	if !ok {
		t.Fatal("expected output to be extracted")
	}
	expected := "command output here"
	if output != expected {
		t.Errorf("expected %q, got %q", expected, output)
	}
	if stream != "stdout" {
		t.Errorf("expected stdout, got %q", stream)
	}
}

func TestExtractToolOutputFromSessionUpdate_ToolCallUpdateCompleted(t *testing.T) {
	// Tool output inside params.update with sessionUpdate=tool_call_update and status=completed
	params := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"status":        "completed",
			"rawOutput":     "tool execution result",
		},
	}

	output, stream, ok := extractToolOutputFromSessionUpdate(params)
	if !ok {
		t.Fatal("expected output to be extracted")
	}
	expected := "tool execution result"
	if output != expected {
		t.Errorf("expected %q, got %q", expected, output)
	}
	if stream != "stdout" {
		t.Errorf("expected stdout, got %q", stream)
	}
}

func TestExtractToolOutputFromSessionUpdate_ToolCallUpdateNotCompleted(t *testing.T) {
	// Tool call update with status != completed should not extract output
	params := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"status":        "in_progress",
			"rawOutput":     "partial output",
		},
	}

	_, _, ok := extractToolOutputFromSessionUpdate(params)
	if ok {
		t.Error("expected no output extraction for in_progress status")
	}
}

func TestExtractToolOutputFromSessionUpdate_NilParams(t *testing.T) {
	_, _, ok := extractToolOutputFromSessionUpdate(nil)
	if ok {
		t.Error("expected no output extraction for nil params")
	}
}

func TestExtractToolOutputFromSessionUpdate_NoUpdate(t *testing.T) {
	params := map[string]interface{}{
		"sessionId": "test-session",
		"message":   "thinking about something",
	}

	_, _, ok := extractToolOutputFromSessionUpdate(params)
	if ok {
		t.Error("expected no output extraction when no update present")
	}
}

func TestExtractToolOutputFromSessionUpdate_NoRawOutput(t *testing.T) {
	params := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"status":        "completed",
		},
	}

	_, _, ok := extractToolOutputFromSessionUpdate(params)
	if ok {
		t.Error("expected no output extraction when no rawOutput present")
	}
}

func TestExtractToolOutputFromSessionUpdate_EmptyRawOutput(t *testing.T) {
	params := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"status":        "completed",
			"rawOutput":     "",
		},
	}

	_, _, ok := extractToolOutputFromSessionUpdate(params)
	if ok {
		t.Error("expected no output extraction for empty rawOutput")
	}
}

func TestExtractToolOutputFromSessionUpdate_WhitespaceOnlyRawOutput(t *testing.T) {
	params := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"status":        "completed",
			"rawOutput":     "   \n  ",
		},
	}

	_, _, ok := extractToolOutputFromSessionUpdate(params)
	if ok {
		t.Error("expected no output extraction for whitespace-only rawOutput")
	}
}

func TestExtractToolOutputFromSessionUpdate_NonToolCallUpdate(t *testing.T) {
	// A sessionUpdate type that is not tool_call_update should not extract output
	params := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "agent_message_chunk",
			"rawOutput":     "some output",
		},
	}

	_, _, ok := extractToolOutputFromSessionUpdate(params)
	if ok {
		t.Error("expected no output extraction for non-tool_call_update sessionUpdate type")
	}
}

func TestExtractToolOutputFromSessionUpdate_CodexFailedMapFormat(t *testing.T) {
	params := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "command-456",
			"status":        "failed",
			"rawOutput": map[string]interface{}{
				"formatted_output": "bwrap: No permissions to create a new namespace\n",
				"exit_code":        float64(1),
			},
		},
	}

	output, stream, ok := extractToolOutputFromSessionUpdate(params)
	if !ok {
		t.Fatal("expected failed Codex output to be extracted")
	}
	if output != "bwrap: No permissions to create a new namespace" {
		t.Errorf("unexpected output: %q", output)
	}
	if stream != "stderr" {
		t.Errorf("expected stderr, got %q", stream)
	}
}

func TestExtractToolOutputFromSessionUpdate_CodexJSONStringFormat(t *testing.T) {
	params := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"status":        "completed",
			"rawOutput":     `{"formatted_output":"command completed\n","exit_code":0}`,
		},
	}

	output, stream, ok := extractToolOutputFromSessionUpdate(params)
	if !ok {
		t.Fatal("expected Codex output to be extracted")
	}
	if output != "command completed" {
		t.Errorf("unexpected output: %q", output)
	}
	if stream != "stdout" {
		t.Errorf("expected stdout, got %q", stream)
	}
}

func TestNotificationHandler_RelaysCodexFailureAsStderr(t *testing.T) {
	sink := &mockEventSink{}
	handler := NewHandler(sink, nil, "")
	params := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "tc-fail",
			"status":        "failed",
			"rawOutput": map[string]interface{}{
				"formatted_output": "bwrap: No permissions to create a new namespace",
				"exit_code":        float64(1),
			},
		},
	}

	handler.NotificationHandler(nil)("session/update", params, nil, false)

	if len(sink.outputs) != 1 {
		t.Fatalf("expected tool output only (diagnostics are gated without debug), got %d outputs: %+v", len(sink.outputs), sink.outputs)
	}
	toolOutput := sink.outputs[0]
	if toolOutput.line != "bwrap: No permissions to create a new namespace" {
		t.Errorf("unexpected output: %q", toolOutput.line)
	}
	if toolOutput.stream != "stderr" {
		t.Errorf("expected stderr, got %q", toolOutput.stream)
	}
	if len(sink.activities) != 1 {
		t.Fatalf("expected 1 failed activity, got %d: %+v", len(sink.activities), sink.activities)
	}
	if sink.activities[0].status != "failed" {
		t.Errorf("expected failed status, got %q", sink.activities[0].status)
	}
}

func TestNotificationHandler_CorrelatesToolCallActivityByActionID(t *testing.T) {
	sink := &mockEventSink{}
	handler := NewHandler(sink, nil, "")

	toolCall := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "tc-action-1",
			"kind":          "execute",
			"title":         "Running tests",
		},
	}
	toolCallUpdate := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "tc-action-1",
			"kind":          "execute",
			"status":        "in_progress",
			"title":         "Running tests",
		},
	}
	otherToolCall := map[string]interface{}{
		"sessionId": "test-session",
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "tc-action-2",
			"kind":          "read",
			"title":         "Reading main.go",
		},
	}

	handler.NotificationHandler(nil)("session/update", toolCall, nil, false)
	handler.NotificationHandler(nil)("session/update", toolCallUpdate, nil, false)
	handler.NotificationHandler(nil)("session/update", otherToolCall, nil, false)

	if len(sink.activities) != 3 {
		t.Fatalf("expected 3 activities, got %d: %+v", len(sink.activities), sink.activities)
	}
	if sink.activities[0].activityType != "COMMAND" || sink.activities[0].actionID != "tc-action-1" || sink.activities[0].status != "pending" {
		t.Errorf("unexpected first activity: %+v", sink.activities[0])
	}
	if sink.activities[1].activityType != "COMMAND" || sink.activities[1].actionID != "tc-action-1" || sink.activities[1].status != "in_progress" {
		t.Errorf("expected tool_call_update to share actionID tc-action-1 with in_progress, got %+v", sink.activities[1])
	}
	if sink.activities[2].activityType != "RESEARCH" || sink.activities[2].actionID != "tc-action-2" {
		t.Errorf("unexpected third activity: %+v", sink.activities[2])
	}
}

func TestExtractPermissionActionID(t *testing.T) {
	h := NewHandler(nil, nil, "")

	tests := []struct {
		name     string
		params   map[string]interface{}
		expected string
	}{
		{
			name: "toolCall with toolCallId",
			params: map[string]interface{}{
				"toolCall": map[string]interface{}{"toolCallId": "tc-42"},
			},
			expected: "tc-42",
		},
		{
			name:     "missing toolCall returns empty",
			params:   map[string]interface{}{},
			expected: "",
		},
		{
			name:     "unmarshalable params returns empty",
			params:   map[string]interface{}{"toolCall": make(chan int)},
			expected: "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := h.extractPermissionActionID(tt.params); got != tt.expected {
				t.Errorf("expected actionID %q, got %q", tt.expected, got)
			}
		})
	}
}

func TestHandlePermissionRequest_Cancellation(t *testing.T) {
	// Create a mock EventSink that simulates cancellation
	mockSink := &mockEventSink{
		cancelChan: make(chan struct{}),
	}
	close(mockSink.cancelChan) // Simulate immediate cancellation

	// Create a pipe to simulate stdin
	stdinR, stdinW, err := os.Pipe()
	if err != nil {
		t.Fatalf("failed to create pipe: %v", err)
	}
	defer func() { _ = stdinR.Close() }()
	defer func() { _ = stdinW.Close() }()

	transport := &AcpTransport{
		stdin: stdinW,
	}

	// Create handler with mock sink
	handler := NewHandler(mockSink, nil, "")

	params := map[string]interface{}{
		"sessionId": "test-session",
		"toolCall": map[string]interface{}{
			"toolCallId": "tc1",
			"title":      "test-command",
			"rawInput":   map[string]interface{}{"command": "test-command"},
		},
		"options": []interface{}{
			map[string]interface{}{"kind": "allow_once", "optionId": "allow"},
		},
	}

	// Call HandlePermissionRequest in a goroutine
	done := make(chan struct{})
	go func() {
		handler.HandlePermissionRequest(transport, params, float64(100))
		close(done)
	}()

	// Wait for the handler to complete
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("timeout waiting for HandlePermissionRequest to complete")
	}

	// The actionID must be extracted from the permission toolCall and passed to
	// the sink so the control plane can correlate it with the tool activity.
	if len(mockSink.permRequests) != 1 {
		t.Fatalf("expected 1 permission request, got %d", len(mockSink.permRequests))
	}
	if mockSink.permRequests[0].actionID != "tc1" {
		t.Errorf("expected actionID 'tc1', got %q", mockSink.permRequests[0].actionID)
	}
	if mockSink.permRequests[0].command != "test-command" {
		t.Errorf("expected command 'test-command', got %q", mockSink.permRequests[0].command)
	}

	// Close the write end to read from the read end
	if err := stdinW.Close(); err != nil {
		t.Fatalf("failed to close stdin writer: %v", err)
	}

	// Read the response from stdin
	var buf bytes.Buffer
	if _, err := io.Copy(&buf, stdinR); err != nil {
		t.Fatalf("failed to read from stdin: %v", err)
	}

	responseStr := buf.String()
	if responseStr == "" {
		t.Fatal("expected a response to be written to stdin")
	}

	// Parse the response
	var response map[string]interface{}
	if err := json.Unmarshal([]byte(responseStr), &response); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	// Verify it's a cancelled outcome
	if response["jsonrpc"] != "2.0" {
		t.Errorf("expected jsonrpc '2.0', got %v", response["jsonrpc"])
	}
	if response["id"] != float64(100) {
		t.Errorf("expected id 100, got %v", response["id"])
	}

	result, ok := response["result"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected result to be a map, got %T", response["result"])
	}

	outcome, ok := result["outcome"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected outcome to be a map, got %T", result["outcome"])
	}

	if outcome["outcome"] != "cancelled" {
		t.Errorf("expected outcome 'cancelled', got %v", outcome["outcome"])
	}
}

// permissionOutcomeReader captures the JSON-RPC response HandlePermissionRequest
// writes to the transport.
type permissionOutcomeReader struct {
	response map[string]interface{}
}

func runPermissionRequest(t *testing.T, sink *mockEventSink, params map[string]interface{}, id interface{}) *permissionOutcomeReader {
	t.Helper()
	stdinR, stdinW, err := os.Pipe()
	if err != nil {
		t.Fatalf("failed to create pipe: %v", err)
	}
	defer func() { _ = stdinR.Close() }()
	defer func() { _ = stdinW.Close() }()

	handler := NewHandler(sink, nil, "")
	handler.HandlePermissionRequest(&AcpTransport{stdin: stdinW}, params, id)

	if err := stdinW.Close(); err != nil {
		t.Fatalf("failed to close stdin writer: %v", err)
	}
	var buf bytes.Buffer
	if _, err := io.Copy(&buf, stdinR); err != nil {
		t.Fatalf("failed to read from stdin: %v", err)
	}
	if buf.Len() == 0 {
		t.Fatal("expected a response to be written to stdin")
	}
	var response map[string]interface{}
	if err := json.Unmarshal(buf.Bytes(), &response); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}
	return &permissionOutcomeReader{response: response}
}

func (r *permissionOutcomeReader) assertOutcome(t *testing.T, wantOutcome string, wantOptionID string) {
	t.Helper()
	if r.response["jsonrpc"] != "2.0" {
		t.Errorf("expected jsonrpc '2.0', got %v", r.response["jsonrpc"])
	}
	result, ok := r.response["result"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected result to be a map, got %T", r.response["result"])
	}
	outcome, ok := result["outcome"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected outcome to be a map, got %T", result["outcome"])
	}
	if outcome["outcome"] != wantOutcome {
		t.Errorf("expected outcome %q, got %v", wantOutcome, outcome["outcome"])
	}
	if wantOptionID == "" {
		if _, exists := outcome["optionId"]; exists {
			t.Errorf("expected no optionId, got %v", outcome["optionId"])
		}
		return
	}
	if outcome["optionId"] != wantOptionID {
		t.Errorf("expected optionId %q, got %v", wantOptionID, outcome["optionId"])
	}
}

func TestHandlePermissionRequest_SelectedOptionOutcomes(t *testing.T) {
	params := map[string]interface{}{
		"sessionId": "test-session",
		"toolCall": map[string]interface{}{
			"toolCallId": "tc1",
			"title":      "test-command",
			"rawInput":   map[string]interface{}{"command": "test-command"},
		},
		"options": []interface{}{
			map[string]interface{}{"kind": "allow_once", "optionId": "allow-once", "name": "Allow once"},
			map[string]interface{}{"kind": "allow_always", "optionId": "allow-always", "name": "Always allow"},
			map[string]interface{}{"kind": "reject_once", "optionId": "reject-once", "name": "Reject"},
			map[string]interface{}{"kind": "reject_always", "optionId": "reject-always", "name": "Always reject"},
		},
	}

	tests := []struct {
		name             string
		selectedOptionID string
		wantOutcome      string
		wantOptionID     string
		wantStatus       string
	}{
		{name: "allow_once", selectedOptionID: "allow-once", wantOutcome: "selected", wantOptionID: "allow-once", wantStatus: "in_progress"},
		{name: "allow_always", selectedOptionID: "allow-always", wantOutcome: "selected", wantOptionID: "allow-always", wantStatus: "in_progress"},
		{name: "reject_once is a selection, not cancelled", selectedOptionID: "reject-once", wantOutcome: "selected", wantOptionID: "reject-once", wantStatus: "failed"},
		{name: "reject_always is a selection, not cancelled", selectedOptionID: "reject-always", wantOutcome: "selected", wantOptionID: "reject-always", wantStatus: "failed"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			sink := &mockEventSink{approveOnce: true, approveResult: tt.selectedOptionID}
			reader := runPermissionRequest(t, sink, params, float64(100))
			reader.assertOutcome(t, tt.wantOutcome, tt.wantOptionID)

			if len(sink.activities) < 2 {
				t.Fatalf("expected pending + resolution activities, got %d", len(sink.activities))
			}
			resolution := sink.activities[len(sink.activities)-1]
			if resolution.status != tt.wantStatus {
				t.Errorf("expected resolution activity status %q, got %q", tt.wantStatus, resolution.status)
			}
			if resolution.detail.Hitl == nil {
				t.Fatal("expected hitl detail on resolution activity")
			}
			if resolution.detail.Hitl.OptionID != tt.selectedOptionID {
				t.Errorf("expected hitl optionId %q, got %q", tt.selectedOptionID, resolution.detail.Hitl.OptionID)
			}
		})
	}
}

func TestHandlePermissionRequest_OptionsSurviveRoundTrip(t *testing.T) {
	sink := &mockEventSink{approveOnce: true, approveResult: "allow-always"}
	params := map[string]interface{}{
		"sessionId": "test-session",
		"toolCall": map[string]interface{}{
			"toolCallId": "tc1",
			"title":      "test-command",
			"rawInput":   map[string]interface{}{"command": "test-command"},
		},
		"options": []interface{}{
			map[string]interface{}{"kind": "allow_once", "optionId": "allow", "name": "Allow once"},
			map[string]interface{}{"kind": "reject_once", "optionId": "reject", "name": "Reject"},
		},
	}
	runPermissionRequest(t, sink, params, float64(100))

	if len(sink.permRequests) != 1 {
		t.Fatalf("expected 1 permission request, got %d", len(sink.permRequests))
	}
	req := sink.permRequests[0]
	if req.command != "test-command" || req.actionID != "tc1" {
		t.Errorf("unexpected command/actionID: %+v", req)
	}
	if len(req.options) != 2 {
		t.Fatalf("expected 2 options, got %d", len(req.options))
	}
	if req.options[0].Name != "Allow once" || req.options[0].Kind != "allow_once" {
		t.Errorf("unexpected first option: %+v", req.options[0])
	}
}

func TestHandlePermissionRequest_SynthesisesDefaultOptions(t *testing.T) {
	sink := &mockEventSink{approveOnce: true, approveResult: "allow"}
	params := map[string]interface{}{
		"sessionId": "test-session",
		"toolCall": map[string]interface{}{
			"toolCallId": "tc1",
			"title":      "test-command",
			"rawInput":   map[string]interface{}{"command": "test-command"},
		},
		"options": []interface{}{},
	}
	reader := runPermissionRequest(t, sink, params, float64(100))
	reader.assertOutcome(t, "selected", "allow")

	if len(sink.permRequests) != 1 || len(sink.permRequests[0].options) != 1 {
		t.Fatalf("expected 1 synthesized option, got %+v", sink.permRequests)
	}
	if sink.permRequests[0].options[0].OptionID != "allow" || sink.permRequests[0].options[0].Kind != "allow_once" {
		t.Errorf("unexpected synthesized option: %+v", sink.permRequests[0].options[0])
	}
}

// runElicitationRequest captures the JSON-RPC response HandleElicitationRequest
// writes to the transport.
func runElicitationRequest(t *testing.T, sink *mockEventSink, params map[string]interface{}, id interface{}) map[string]interface{} {
	t.Helper()
	stdinR, stdinW, err := os.Pipe()
	if err != nil {
		t.Fatalf("failed to create pipe: %v", err)
	}
	defer func() { _ = stdinR.Close() }()
	defer func() { _ = stdinW.Close() }()

	handler := NewHandler(sink, nil, "")
	handler.HandleElicitationRequest(&AcpTransport{stdin: stdinW}, params, id)

	if err := stdinW.Close(); err != nil {
		t.Fatalf("failed to close stdin writer: %v", err)
	}
	var buf bytes.Buffer
	if _, err := io.Copy(&buf, stdinR); err != nil {
		t.Fatalf("failed to read from stdin: %v", err)
	}
	if buf.Len() == 0 {
		t.Fatal("expected a response to be written to stdin")
	}
	var response map[string]interface{}
	if err := json.Unmarshal(buf.Bytes(), &response); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}
	return response
}

func TestHandleElicitationRequest_AcceptRoundTrip(t *testing.T) {
	sink := &mockEventSink{
		elicResult: ElicitationResult{
			Action:  "accept",
			Content: map[string]any{"target": "staging"},
		},
	}
	params := map[string]interface{}{
		"elicitationId": "el-1",
		"message":       "Choose a deployment target",
		"mode":          "form",
		"requestedSchema": map[string]interface{}{
			"type":       "object",
			"properties": map[string]interface{}{"target": map[string]interface{}{"type": "string"}},
		},
	}
	response := runElicitationRequest(t, sink, params, float64(10))

	if response["jsonrpc"] != "2.0" || response["id"] != float64(10) {
		t.Errorf("unexpected envelope: %+v", response)
	}
	result, ok := response["result"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected result map, got %T", response["result"])
	}
	if result["action"] != "accept" {
		t.Errorf("expected action accept, got %v", result["action"])
	}
	content, ok := result["content"].(map[string]interface{})
	if !ok || content["target"] != "staging" {
		t.Errorf("expected content to carry the submitted payload, got %v", result["content"])
	}
}

func TestHandleElicitationRequest_CancelledReturnsCancel(t *testing.T) {
	sink := &mockEventSink{elicErr: fmt.Errorf("elicitation request cancelled")}
	params := map[string]interface{}{
		"elicitationId": "el-2",
		"message":       "Pick a region",
		"mode":          "form",
	}
	response := runElicitationRequest(t, sink, params, float64(11))

	result, ok := response["result"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected result map, got %T", response["result"])
	}
	if result["action"] != "cancel" {
		t.Errorf("expected action cancel, got %v", result["action"])
	}
	if _, exists := result["content"]; exists {
		t.Errorf("expected no content on cancel, got %v", result["content"])
	}
}

func TestHandleElicitationRequest_InvalidParamsSendsError(t *testing.T) {
	sink := &mockEventSink{}
	stdinR, stdinW, err := os.Pipe()
	if err != nil {
		t.Fatalf("failed to create pipe: %v", err)
	}
	defer func() { _ = stdinR.Close() }()
	defer func() { _ = stdinW.Close() }()

	handler := NewHandler(sink, nil, "")
	handler.HandleElicitationRequest(&AcpTransport{stdin: stdinW}, nil, float64(12))

	_ = stdinW.Close()
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, stdinR)
	var response map[string]interface{}
	if err := json.Unmarshal(buf.Bytes(), &response); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}
	if _, ok := response["error"]; !ok {
		t.Errorf("expected an error response for nil params, got %+v", response)
	}
}

func TestHandleElicitationRequest_RejectsUnsupportedModes(t *testing.T) {
	for _, mode := range []string{"url", "text", ""} {
		t.Run("mode_"+mode, func(t *testing.T) {
			sink := &mockEventSink{}
			params := map[string]interface{}{
				"elicitationId": "el-url",
				"message":       "Authorize access",
				"mode":          mode,
			}
			if mode == "url" {
				params["url"] = "https://example.com/oauth"
			}
			response := runElicitationRequest(t, sink, params, float64(13))

			errObj, ok := response["error"].(map[string]interface{})
			if !ok {
				t.Fatalf("expected error response for mode %q, got %+v", mode, response)
			}
			if errObj["code"] != float64(-32602) {
				t.Errorf("expected -32602, got %v", errObj["code"])
			}
			if sink.elicErr != nil {
				t.Errorf("unexpected sink error for unsupported mode %q: %v", mode, sink.elicErr)
			}
			if sink.elicResult.Action != "" {
				t.Errorf("expected sink not to be consulted for unsupported mode %q", mode)
			}
		})
	}
}

func TestHandleElicitationRequest_FormModeStillReachesSink(t *testing.T) {
	sink := &mockEventSink{
		elicResult: ElicitationResult{Action: "accept", Content: map[string]any{"target": "staging"}},
	}
	params := map[string]interface{}{
		"elicitationId": "el-form",
		"message":       "Choose a deployment target",
		"mode":          "form",
	}
	response := runElicitationRequest(t, sink, params, float64(14))

	result, ok := response["result"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected result map, got %T", response["result"])
	}
	if result["action"] != "accept" {
		t.Errorf("expected action accept, got %v", result["action"])
	}
}

// mockEventSink implements EventSink for testing

type mockEventSink struct {
	outputs      []capturedOutput
	activities   []capturedActivity
	permRequests []capturedPermissionRequest
	cancelChan   chan struct{}
	// Optional approval result; when zero-value the sink blocks on cancelChan.
	approveResult string
	approveErr    error
	approveOnce   bool
	// Optional elicitation result; zero-value returns cancel.
	elicResult ElicitationResult
	elicErr    error
}

type capturedOutput struct {
	line   string
	stream string
}

type capturedActivity struct {
	activityType ActivityType
	description  string
	actionID     string
	status       string
	detail       ActivityDetail
}

type capturedPermissionRequest struct {
	command  string
	actionID string
	kind     string
	diff     *ActivityDiff
	options  []PermissionOption
}

func (m *mockEventSink) SendOutput(line string, stream string) {
	m.outputs = append(m.outputs, capturedOutput{line: line, stream: stream})
}

func (m *mockEventSink) SendActivity(activity Activity) {
	m.activities = append(m.activities, capturedActivity{
		activityType: activity.ActivityType,
		description:  activity.Description,
		actionID:     activity.ActionID,
		status:       string(activity.Status),
		detail:       activity.Detail,
	})
}

func (m *mockEventSink) RequestPermission(req PermissionRequest) (string, error) {
	m.permRequests = append(m.permRequests, capturedPermissionRequest{
		command:  req.Command,
		actionID: req.ActionID,
		kind:     req.Kind,
		diff:     req.Diff,
		options:  req.Options,
	})
	if m.approveOnce {
		if m.approveErr != nil {
			return "", m.approveErr
		}
		return m.approveResult, nil
	}
	<-m.cancelChan
	return "", fmt.Errorf("permission request cancelled")
}

func (m *mockEventSink) PermissionCancelChan() <-chan struct{} {
	return m.cancelChan
}

func (m *mockEventSink) CreateElicitation(_ ElicitationRequest) (ElicitationResult, error) {
	if m.elicErr != nil {
		return ElicitationResult{}, m.elicErr
	}
	if m.elicResult.Action != "" {
		return m.elicResult, nil
	}
	return ElicitationResult{Action: "cancel"}, nil
}

func fsWriteTestTransport(t *testing.T) (*AcpTransport, chan string) {
	t.Helper()
	stdinR, stdinW, err := os.Pipe()
	if err != nil {
		t.Fatalf("failed to create pipe: %v", err)
	}
	t.Cleanup(func() {
		_ = stdinW.Close()
		_ = stdinR.Close()
	})
	responses := make(chan string, 4)
	go func() {
		scanner := bufio.NewScanner(stdinR)
		for scanner.Scan() {
			responses <- scanner.Text()
		}
	}()
	return &AcpTransport{stdin: stdinW}, responses
}

func runFsWrite(t *testing.T, h *Handler, transport *AcpTransport, params map[string]interface{}) {
	t.Helper()
	done := make(chan struct{})
	go func() {
		h.HandleFsWriteTextFile(transport, params, float64(7))
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("timeout waiting for HandleFsWriteTextFile")
	}
}

func TestHandleFsWriteTextFile_ApprovedWritesFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "out.txt")
	if err := os.WriteFile(path, []byte("old content"), 0600); err != nil {
		t.Fatalf("seed file: %v", err)
	}

	sink := &mockEventSink{approveOnce: true, approveResult: "allow"}
	h := NewHandler(sink, nil, "")
	transport, responses := fsWriteTestTransport(t)

	runFsWrite(t, h, transport, map[string]interface{}{"path": path, "content": "new content"})

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("expected file written: %v", err)
	}
	if string(data) != "new content" {
		t.Errorf("expected new content, got %q", string(data))
	}

	if len(sink.permRequests) != 1 {
		t.Fatalf("expected one permission request, got %+v", sink.permRequests)
	}
	req := sink.permRequests[0]
	if req.kind != "write" {
		t.Errorf("expected aliased tool kind 'write', got %q", req.kind)
	}
	if req.command != path || req.actionID != "" {
		t.Errorf("expected command=%q actionID='', got %+v", path, req)
	}
	if req.diff == nil || req.diff.OldText != "old content" || req.diff.NewText != "new content" {
		t.Errorf("expected diff with old/new content, got %+v", req.diff)
	}
	if len(req.options) != 2 {
		t.Errorf("expected synthesised option pair, got %+v", req.options)
	}

	select {
	case resp := <-responses:
		if !strings.Contains(resp, `"result"`) || strings.Contains(resp, `"error"`) {
			t.Errorf("expected success response, got %s", resp)
		}
	case <-time.After(time.Second):
		t.Fatal("no response written")
	}
}

func TestHandleFsWriteTextFile_DeniedDoesNotWrite(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "denied.txt")

	sink := &mockEventSink{approveOnce: true, approveResult: ""}
	h := NewHandler(sink, nil, "")
	transport, responses := fsWriteTestTransport(t)

	runFsWrite(t, h, transport, map[string]interface{}{"path": path, "content": "evil"})

	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Errorf("expected no file written, stat err=%v", err)
	}
	select {
	case resp := <-responses:
		if !strings.Contains(resp, "Write denied") || !strings.Contains(resp, "-32000") {
			t.Errorf("expected denied error response, got %s", resp)
		}
	case <-time.After(time.Second):
		t.Fatal("no response written")
	}
}

func TestHandleFsWriteTextFile_CancelledReturnsCancelledError(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "cancelled.txt")

	sink := &mockEventSink{approveOnce: true, approveErr: errors.New("permission request cancelled")}
	h := NewHandler(sink, nil, "")
	transport, responses := fsWriteTestTransport(t)

	runFsWrite(t, h, transport, map[string]interface{}{"path": path, "content": "x"})

	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Errorf("expected no file written, stat err=%v", err)
	}
	select {
	case resp := <-responses:
		if !strings.Contains(resp, "-32800") {
			t.Errorf("expected cancelled error response, got %s", resp)
		}
	case <-time.After(time.Second):
		t.Fatal("no response written")
	}
}
