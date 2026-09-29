package acp

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"
)

// TestDebugf_GatesDiagnosticsFromTerminal verifies Slice 1: sidecar-internal
// diagnostics reach the sink only when the debug flag is set, while the Go log
// always receives them. Deliberate [ACP] handshake lines are never gated.
func TestDebugf_GatesDiagnosticsFromTerminal(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")
	h.SetDebug(false)

	h.debugf("[NOTIF] Agent notification method=session/update")
	h.debugf("[REQ] Agent request method=fs/read_text_file id=1")
	h.debugf("[PERM] Permission request id=1")
	h.debugf("[ACP][TERM] Received terminal/create request")
	h.debugf("[ACP][FS] Writing file: /tmp/x (3 bytes)")
	h.debugf("[WARN] Unhandled agent request method=unknown")

	if len(sink.outputs) != 1 {
		t.Fatalf("expected only the non-gated [WARN] line, got %d outputs: %+v", len(sink.outputs), sink.outputs)
	}
	if !strings.HasPrefix(sink.outputs[0].line, "[WARN]") {
		t.Errorf("expected [WARN] line to remain visible, got %q", sink.outputs[0].line)
	}

	sink2 := &mockEventSink{}
	h2 := NewHandler(sink2, nil, "")
	h2.SetDebug(true)
	h2.debugf("[NOTIF] Agent notification method=session/update")
	h2.debugf("[PERM] Permission request id=1")
	if len(sink2.outputs) != 2 {
		t.Fatalf("expected diagnostics with debug enabled, got %d outputs: %+v", len(sink2.outputs), sink2.outputs)
	}
}

// TestHandleSessionUpdate_DeltaEmitsCumulativeContent verifies Slice 2: a
// cumulative content sequence prints each byte exactly once (replace semantics
// with delta emission by length).
func TestHandleSessionUpdate_DeltaEmitsCumulativeContent(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	update := func(content string, status string) map[string]interface{} {
		return map[string]interface{}{
			"update": map[string]interface{}{
				"sessionUpdate": "tool_call_update",
				"toolCallId":    "tc-delta",
				"status":        status,
				"content": []interface{}{
					map[string]interface{}{
						"type":    "content",
						"content": map[string]interface{}{"type": "text", "text": content},
					},
				},
			},
		}
	}

	h.handleSessionUpdate(update("build output: 32", "in_progress"))
	h.handleSessionUpdate(update("build output: 32 plus more 621", "in_progress"))
	h.handleSessionUpdate(update("build output: 32 plus more 621 and 1707 chars", "in_progress"))
	h.handleSessionUpdate(update("build output: 32 plus more 621 and 1707 chars and 4035 total", "completed"))

	var relayed strings.Builder
	for _, out := range sink.outputs {
		relayed.WriteString(out.line)
	}
	expected := "build output: 32 plus more 621 and 1707 chars and 4035 total"
	if relayed.String() != expected {
		t.Errorf("expected delta relay %q, got %q", expected, relayed.String())
	}
}

// TestHandleSessionUpdate_StatusOnlyUpdateEmitsToolLine verifies Slice 2/3: a
// status-only tool_call_update (no content) emits the [Tool] line from the
// accumulated state.
func TestHandleSessionUpdate_StatusOnlyUpdateEmitsToolLine(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "tc-status",
			"kind":          "execute",
			"title":         "shell · git status",
		},
	})
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "tc-status",
			"status":        "completed",
		},
	})

	lastOutput := sink.outputs[len(sink.outputs)-1]
	if lastOutput.line != "[Tool] shell · git status (completed)" {
		t.Errorf("expected status-only [Tool] line from accumulated state, got %q", lastOutput.line)
	}
	if len(sink.activities) != 2 {
		t.Fatalf("expected 2 lifecycle activities (pending, completed), got %d: %+v", len(sink.activities), sink.activities)
	}
	if sink.activities[1].status != "completed" {
		t.Errorf("expected completed transition, got %+v", sink.activities[1])
	}
}

// TestHandleSessionUpdate_LifecycleFieldsSplitAcrossMessages verifies Slice 3:
// a lifecycle with fields split across messages (kind on one update, title on
// another, completion on a third) yields one activity per status transition
// with the union of fields and no type conflicts.
func TestHandleSessionUpdate_LifecycleFieldsSplitAcrossMessages(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	// Goose-style: tool_call carries title + rawInput; a middle update carries
	// only the kind; the completion carries only the status.
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "tc-goose",
			"title":         "shell · git status",
			"rawInput":      map[string]interface{}{"command": "git status"},
		},
	})
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "tc-goose",
			"kind":          "execute",
		},
	})
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "tc-goose",
			"status":        "completed",
		},
	})

	if len(sink.activities) != 2 {
		t.Fatalf("expected 2 lifecycle activities, got %d: %+v", len(sink.activities), sink.activities)
	}
	for i, act := range sink.activities {
		if act.actionID != "tc-goose" {
			t.Errorf("activity %d: expected actionID tc-goose, got %q", i, act.actionID)
		}
		if act.activityType != "COMMAND" {
			t.Errorf("activity %d: expected type COMMAND from accumulated kind, got %q", i, act.activityType)
		}
		if act.description != "shell · git status" {
			t.Errorf("activity %d: expected description from accumulated title, got %q", i, act.description)
		}
	}
	if sink.activities[0].status != "pending" || sink.activities[1].status != "completed" {
		t.Errorf("expected pending → completed transitions, got %+v", sink.activities)
	}
}

// TestHandleSessionUpdate_OpenCodeStyleCompletionWithoutKind verifies Slice 3:
// OpenCode's completed update omits kind — the activity type comes from the
// accumulated kind, not the single message.
func TestHandleSessionUpdate_OpenCodeStyleCompletionWithoutKind(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "tc-opencode",
			"kind":          "execute",
			"title":         "bash",
			"status":        "pending",
		},
	})
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "tc-opencode",
			"status":        "in_progress",
			"kind":          "execute",
			"title":         "find / -name javac",
		},
	})
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "tc-opencode",
			"status":        "completed",
			"title":         "find / -name javac",
			"content": []interface{}{
				map[string]interface{}{
					"type":    "content",
					"content": map[string]interface{}{"type": "text", "text": "(no output)"},
				},
			},
			"rawOutput": map[string]interface{}{
				"output":   "(no output)",
				"metadata": map[string]interface{}{"output": "(no output)", "exit": float64(1), "truncated": false},
			},
		},
	})

	if len(sink.activities) != 3 {
		t.Fatalf("expected 3 lifecycle activities, got %d: %+v", len(sink.activities), sink.activities)
	}
	if sink.activities[2].activityType != "COMMAND" {
		t.Errorf("expected COMMAND from accumulated kind despite kind-less completion, got %+v", sink.activities[2])
	}
	// Slice 8: completed with metadata.exit != 0 derives failed.
	if sink.activities[2].status != "failed" {
		t.Errorf("expected failed status derived from exit code, got %+v", sink.activities[2])
	}
	// The failure output relays on stderr.
	lastOutput := sink.outputs[len(sink.outputs)-1]
	if lastOutput.stream != "stderr" {
		t.Errorf("expected failure output on stderr, got %+v", lastOutput)
	}
}

// TestExtractExitCode verifies Slice 8 exit-code extraction across the
// OpenCode (metadata.exit) and flat (exit_code) shapes.
func TestExtractExitCode(t *testing.T) {
	raw := json.RawMessage(`{"output":"x","metadata":{"exit":1,"truncated":false}}`)
	exit := extractExitCode(raw)
	if exit == nil || *exit != 1 {
		t.Errorf("expected metadata.exit=1, got %v", exit)
	}

	raw2 := json.RawMessage(`{"formatted_output":"boom","exit_code":2}`)
	exit2 := extractExitCode(raw2)
	if exit2 == nil || *exit2 != 2 {
		t.Errorf("expected exit_code=2, got %v", exit2)
	}

	raw3 := json.RawMessage(`{"output":"ok","metadata":{"exit":0}}`)
	exit3 := extractExitCode(raw3)
	if exit3 == nil || *exit3 != 0 {
		t.Errorf("expected exit=0, got %v", exit3)
	}

	if exit4 := extractExitCode(json.RawMessage(`{"output":"no exit"}`)); exit4 != nil {
		t.Errorf("expected nil exit code, got %v", exit4)
	}
	if exit5 := extractExitCode(nil); exit5 != nil {
		t.Errorf("expected nil exit code for empty rawOutput, got %v", exit5)
	}
}

// TestHandleTerminalWaitForExit_ForwardsOutputAndDerivesFailure verifies
// Slice 5/8: terminal output is forwarded exactly once at exit (stderr on
// non-zero exit) and the correlated tool call transitions to completed/failed.
func TestHandleTerminalWaitForExit_ForwardsOutputAndDerivesFailure(t *testing.T) {
	tm := NewTerminalManager(t.TempDir())
	term, err := tm.CreateTerminal("echo hello from terminal", "")
	if err != nil {
		t.Fatalf("failed to create terminal: %v", err)
	}

	sink := &mockEventSink{}
	h := NewHandler(sink, tm, "")
	// Correlate the tool call with the terminal (Goose content block).
	h.mu.Lock()
	h.toolCalls["tc-term"] = &ToolCallInfo{ToolCallID: "tc-term", Kind: "execute", Title: "shell · echo", TerminalID: term.ID, Status: ActivityPending}
	h.mu.Unlock()

	exitStatus, err := tm.WaitForExit(term.ID, 10*time.Second)
	if err != nil {
		t.Fatalf("failed to wait for exit: %v", err)
	}
	stdinR, stdinW, err := os.Pipe()
	if err != nil {
		t.Fatalf("failed to create pipe: %v", err)
	}
	defer func() { _ = stdinR.Close() }()
	defer func() { _ = stdinW.Close() }()
	transport := &AcpTransport{stdin: stdinW}
	h.HandleTerminalWaitForExit(transport, map[string]interface{}{"terminalId": term.ID}, float64(1))

	if exitStatus == nil || exitStatus.ExitCode == nil || *exitStatus.ExitCode != 0 {
		t.Fatalf("expected exit code 0, got %+v", exitStatus)
	}

	var outputLines []string
	for _, out := range sink.outputs {
		outputLines = append(outputLines, out.line)
	}
	if !strings.Contains(strings.Join(outputLines, "\n"), "hello from terminal") {
		t.Fatalf("expected terminal output forwarded once, got %+v", sink.outputs)
	}
	if len(sink.activities) != 1 || sink.activities[0].status != "completed" {
		t.Fatalf("expected one completed transition, got %+v", sink.activities)
	}
	if sink.activities[0].detail.ExitCode == nil || *sink.activities[0].detail.ExitCode != 0 {
		t.Errorf("expected exit code 0 in detail, got %+v", sink.activities[0].detail.ExitCode)
	}

	// The output must not be forwarded a second time (once at exit).
	before := len(sink.outputs)
	h.HandleTerminalOutput(transport, map[string]interface{}{"terminalId": term.ID}, float64(2))
	if len(sink.outputs) != before {
		t.Errorf("expected no duplicate output forward, got %d outputs", len(sink.outputs))
	}
}

// TestHandleTerminalWaitForExit_FailureExitCode verifies Slice 8: a non-zero
// terminal exit derives a failed activity on stderr.
func TestHandleTerminalWaitForExit_FailureExitCode(t *testing.T) {
	tm := NewTerminalManager(t.TempDir())
	term, err := tm.CreateTerminal("echo boom && exit 3", "")
	if err != nil {
		t.Fatalf("failed to create terminal: %v", err)
	}

	sink := &mockEventSink{}
	h := NewHandler(sink, tm, "")
	h.mu.Lock()
	h.toolCalls["tc-fail-term"] = &ToolCallInfo{ToolCallID: "tc-fail-term", Kind: "execute", Title: "shell · boom", TerminalID: term.ID, Status: ActivityPending}
	h.mu.Unlock()

	stdinR, stdinW, err := os.Pipe()
	if err != nil {
		t.Fatalf("failed to create pipe: %v", err)
	}
	defer func() { _ = stdinR.Close() }()
	defer func() { _ = stdinW.Close() }()
	transport := &AcpTransport{stdin: stdinW}
	h.HandleTerminalWaitForExit(transport, map[string]interface{}{"terminalId": term.ID}, float64(1))

	if len(sink.activities) != 1 || sink.activities[0].status != "failed" {
		t.Fatalf("expected one failed transition, got %+v", sink.activities)
	}
	if sink.activities[0].detail.ExitCode == nil || *sink.activities[0].detail.ExitCode != 3 {
		t.Errorf("expected exit code 3 in detail, got %+v", sink.activities[0].detail.ExitCode)
	}
	var relayed string
	for _, out := range sink.outputs {
		relayed += out.line + "\n"
	}
	if !strings.Contains(relayed, "boom") {
		t.Errorf("expected live-streamed output to contain boom, got %q", relayed)
	}
}

// TestHandleTerminalCreate_CorrelatesPermissionByCommand verifies Slice 6:
// a terminal/create permission is correlated with the accumulated tool call
// whose command matches, so the approval lands on the tool activity.
func TestHandleTerminalCreate_CorrelatesPermissionByCommand(t *testing.T) {
	tm := NewTerminalManager(t.TempDir())
	sink := &mockEventSink{}
	h := NewHandler(sink, tm, "")

	h.mu.Lock()
	h.toolCalls["tc-shell"] = &ToolCallInfo{
		ToolCallID: "tc-shell",
		Kind:       "execute",
		Title:      "shell · git status",
		Command:    "git status",
	}
	h.mu.Unlock()

	// Resolve the permission without blocking: mock sink returns immediately.
	sink.cancelChan = make(chan struct{})
	close(sink.cancelChan)

	stdinR, stdinW, err := os.Pipe()
	if err != nil {
		t.Fatalf("failed to create pipe: %v", err)
	}
	defer func() { _ = stdinR.Close() }()
	defer func() { _ = stdinW.Close() }()
	transport := &AcpTransport{stdin: stdinW}
	done := make(chan struct{})
	go func() {
		h.HandleTerminalCreate(transport, map[string]interface{}{
			"command": "git status",
			"cwd":     "/kratis/workspace",
		}, float64(1))
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("timeout waiting for HandleTerminalCreate")
	}

	if len(sink.permRequests) != 1 || sink.permRequests[0].actionID != "tc-shell" {
		t.Fatalf("expected permission correlated with tc-shell, got %+v", sink.permRequests)
	}
	// The permission lifecycle emits pending then failed (mock cancels).
	if len(sink.activities) < 2 {
		t.Fatalf("expected permission lifecycle activities, got %+v", sink.activities)
	}
	if sink.activities[0].actionID != "tc-shell" || sink.activities[0].status != "pending" {
		t.Errorf("expected pending activity for tc-shell, got %+v", sink.activities[0])
	}
	if sink.activities[1].actionID != "tc-shell" || sink.activities[1].status != "failed" {
		t.Errorf("expected failed resolution activity for tc-shell, got %+v", sink.activities[1])
	}
}

// TestHandlePermissionRequest_ApprovalEmitTransitions verifies Slice 6: the
// permission resolution emits in_progress with the approval detail on approval.
func TestHandlePermissionRequest_ApprovalEmitTransitions(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	params := map[string]interface{}{
		"sessionId": "test-session",
		"toolCall": map[string]interface{}{
			"toolCallId": "tc-perm",
			"title":      "git push",
			"kind":       "execute",
			"rawInput":   map[string]interface{}{"command": "git push"},
		},
		"options": []interface{}{
			map[string]interface{}{"kind": "allow_once", "optionId": "allow"},
		},
	}

	// Mock sink approves synchronously.
	sink.cancelChan = make(chan struct{})
	sink.approveOnce = true
	sink.approveResult = "allow"

	stdinR, stdinW, err := os.Pipe()
	if err != nil {
		t.Fatalf("failed to create pipe: %v", err)
	}
	defer func() { _ = stdinR.Close() }()
	defer func() { _ = stdinW.Close() }()
	transport := &AcpTransport{stdin: stdinW}
	done := make(chan struct{})
	go func() {
		h.HandlePermissionRequest(transport, params, float64(7))
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("timeout waiting for HandlePermissionRequest")
	}

	if len(sink.activities) != 2 {
		t.Fatalf("expected pending + in_progress activities, got %+v", sink.activities)
	}
	if sink.activities[0].status != "pending" || sink.activities[0].actionID != "tc-perm" {
		t.Errorf("expected pending activity, got %+v", sink.activities[0])
	}
	if sink.activities[1].status != "in_progress" || sink.activities[1].actionID != "tc-perm" {
		t.Errorf("expected in_progress resolution, got %+v", sink.activities[1])
	}
	if sink.activities[1].detail.Hitl == nil ||
		!sink.activities[1].detail.Hitl.Approved ||
		sink.activities[1].detail.Hitl.OptionID != "allow" {
		t.Errorf("expected hitl detail on resolution, got %+v", sink.activities[1].detail.Hitl)
	}
}

// TestHandleSessionUpdate_RepeatedIdenticalUpdatesCollapse verifies Slice 3:
// agents that re-send the same in_progress payload do not produce duplicate
// activities or output.
func TestHandleSessionUpdate_RepeatedIdenticalUpdatesCollapse(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	payload := map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "tc-dup",
			"status":        "in_progress",
			"kind":          "execute",
			"title":         "npm test",
			"rawInput":      map[string]interface{}{"command": "npm test"},
		},
	}
	for i := 0; i < 5; i++ {
		h.handleSessionUpdate(payload)
	}

	if len(sink.activities) != 1 {
		t.Fatalf("expected exactly 1 activity for repeated identical updates, got %d: %+v", len(sink.activities), sink.activities)
	}
}

// TestHandleSessionUpdate_ToolCallWithoutIDWarnsLoudly verifies that an ACP
// tool_call or tool_call_update missing the required toolCallId is reported on
// stderr regardless of the debug flag, instead of being dropped silently.
func TestHandleSessionUpdate_ToolCallWithoutIDWarnsLoudly(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")
	h.SetDebug(false)

	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"title":         "write_file",
			"kind":          "edit",
			"status":        "in_progress",
		},
	})
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"title":         "write_file",
			"status":        "completed",
		},
	})

	if len(sink.activities) != 0 {
		t.Errorf("expected no activity without a toolCallId, got %+v", sink.activities)
	}

	warnings := []string{}
	for _, o := range sink.outputs {
		if o.stream == "stderr" && strings.Contains(o.line, "[WARN]") {
			warnings = append(warnings, o.line)
		}
	}
	if len(warnings) != 2 {
		t.Fatalf("expected one stderr warning per ID-less tool call message, got %d: %+v", len(warnings), sink.outputs)
	}
	for _, expected := range []struct {
		discriminator string
		substring     string
	}{
		{"tool_call", "write_file"},
		{"tool_call_update", "write_file"},
	} {
		found := false
		for _, w := range warnings {
			if strings.Contains(w, expected.discriminator) && strings.Contains(w, expected.substring) {
				found = true
			}
		}
		if !found {
			t.Errorf("expected a warning naming %q and the tool title, got %v", expected.discriminator, warnings)
		}
	}
}

// terminal/create carries no toolCallId: on ambiguity the most recently
// recorded live match wins and the ambiguity is reported on stderr.
func TestFindToolCallByCommand_DeterministicOnAmbiguity(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	toolCall := func(id, command string) map[string]interface{} {
		return map[string]interface{}{
			"update": map[string]interface{}{
				"sessionUpdate": "tool_call",
				"toolCallId":    id,
				"title":         command,
				"kind":          "execute",
				"rawInput":      map[string]interface{}{"command": command},
			},
		}
	}

	h.handleSessionUpdate(toolCall("tc-1", "ls -la"))
	h.handleSessionUpdate(toolCall("tc-2", "ls -la"))

	if got := h.findToolCallByCommand("ls -la"); got != "tc-2" {
		t.Errorf("expected most recently recorded match tc-2, got %q", got)
	}

	warned := false
	for _, o := range sink.outputs {
		if o.stream == "stderr" && strings.Contains(o.line, "Ambiguous terminal/create correlation") && strings.Contains(o.line, "tc-2") {
			warned = true
		}
	}
	if !warned {
		t.Errorf("expected an ambiguity warning on stderr, got %+v", sink.outputs)
	}

	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "tc-2",
			"status":        "completed",
		},
	})
	if got := h.findToolCallByCommand("ls -la"); got != "tc-1" {
		t.Errorf("expected tc-1 once tc-2 completed, got %q", got)
	}
}

// A messageId returning after an interruption opens run 2 as <messageId>#2;
// detail.messageId keeps the raw value.
func TestChunkRun_RepeatedMessageIdGetsRunSuffix(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	thought := func(messageID, text string) map[string]interface{} {
		return map[string]interface{}{
			"update": map[string]interface{}{
				"sessionUpdate": "agent_thought_chunk",
				"messageId":     messageID,
				"content":       map[string]interface{}{"type": "text", "text": text},
			},
		}
	}

	h.handleSessionUpdate(thought("A", "first thought"))
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "tc-1",
			"kind":          "read",
			"title":         "Reading",
		},
	})
	h.handleSessionUpdate(thought("A", "second thought"))
	h.CloseChunkRun("prompt turn ended")

	runs := map[string][]capturedActivity{}
	for _, a := range sink.activities {
		if a.activityType == ActivityTypeThinking {
			runs[a.actionID] = append(runs[a.actionID], a)
		}
	}
	first, second := runs["A"], runs["A#2"]
	if len(first) != 2 || len(second) != 2 {
		t.Fatalf("expected runs A and A#2 with in_progress+completed each, got %+v", sink.activities)
	}
	if first[1].status != "completed" || second[1].status != "completed" {
		t.Errorf("expected exactly one completed emission per closed run, got %+v / %+v", first, second)
	}
	if first[1].description != "first thought" || second[1].description != "second thought" {
		t.Errorf("expected per-run cumulative text, got %q / %q", first[1].description, second[1].description)
	}
	for _, a := range append(append([]capturedActivity{}, first...), second...) {
		if a.detail.MessageID != "A" {
			t.Errorf("expected raw messageId %q in detail, got %q", "A", a.detail.MessageID)
		}
	}
}

// Every emission carries the cumulative run text; the boundary emits exactly
// one completed.
func TestChunkRun_CumulativeTextAndSingleCompleted(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	chunk := func(text string) map[string]interface{} {
		return map[string]interface{}{
			"update": map[string]interface{}{
				"sessionUpdate": "agent_message_chunk",
				"messageId":     "m-1",
				"content":       map[string]interface{}{"type": "text", "text": text},
			},
		}
	}

	h.handleSessionUpdate(chunk("head"))
	h.handleSessionUpdate(chunk(strings.Repeat("x", 600)))
	h.handleSessionUpdate(chunk(strings.Repeat("y", 600)))
	h.CloseChunkRun("prompt turn ended")

	if len(sink.activities) < 3 {
		t.Fatalf("expected at least the two byte-throttled emissions plus the boundary, got %d", len(sink.activities))
	}
	completed := 0
	for _, a := range sink.activities {
		if a.actionID != "m-1" {
			t.Errorf("expected run key m-1 on every emission, got %q", a.actionID)
		}
		if a.status == "completed" {
			completed++
		}
	}
	if completed != 1 {
		t.Fatalf("expected exactly one completed emission, got %d", completed)
	}
	for i := 1; i < len(sink.activities); i++ {
		if !strings.HasPrefix(sink.activities[i].description, sink.activities[i-1].description) {
			t.Errorf("emission %d is not cumulative over emission %d", i, i-1)
		}
	}
	if last := sink.activities[len(sink.activities)-1]; last.status != "completed" ||
		last.description != "head"+strings.Repeat("x", 600)+strings.Repeat("y", 600) {
		t.Errorf("expected the boundary emission to carry the full run text as completed, got status=%q len=%d", last.status, len(last.description))
	}
}

// A terminal status absorbs later stale updates: a finished tool call never
// reopens, and the regressed status is not emitted.
func TestHandleSessionUpdate_TerminalStatusAbsorbsStaleReSend(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	toolCall := func(id, status string) map[string]interface{} {
		return map[string]interface{}{
			"update": map[string]interface{}{
				"sessionUpdate": "tool_call",
				"toolCallId":    id,
				"kind":          "execute",
				"title":         "shell · git status",
				"status":        status,
			},
		}
	}

	h.handleSessionUpdate(toolCall("tc-1", "in_progress"))
	h.handleSessionUpdate(toolCall("tc-1", "completed"))
	h.handleSessionUpdate(toolCall("tc-1", "in_progress"))
	h.handleSessionUpdate(toolCall("tc-1", "pending"))

	emissions := 0
	var lastStatus string
	for _, a := range sink.activities {
		if a.actionID == "tc-1" {
			emissions++
			lastStatus = a.status
		}
	}
	if emissions != 2 {
		t.Fatalf("expected in_progress and completed emissions only, got %d: %+v", emissions, sink.activities)
	}
	if lastStatus != "completed" {
		t.Errorf("expected the recorded status to stay completed, got %q", lastStatus)
	}
	if h.toolCalls["tc-1"].Status != ActivityCompleted {
		t.Errorf("expected accumulated state to stay completed, got %q", h.toolCalls["tc-1"].Status)
	}
}
