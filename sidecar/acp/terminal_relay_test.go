package acp

import (
	"strings"
	"sync"
	"testing"
	"time"
)

// terminalRelayUpdate builds a tool_call_update carrying _meta terminal relay
// data in the codex-acp/claude-agent-acp shape (terminal_id keys every piece).
func terminalRelayUpdate(status, toolCallID, terminalID, data string, exitCode any, info bool) map[string]interface{} {
	meta := map[string]interface{}{}
	if info {
		meta["terminal_info"] = map[string]interface{}{"terminal_id": terminalID, "cwd": "/kratis/workspace"}
	}
	if data != "" {
		meta["terminal_output"] = map[string]interface{}{"terminal_id": terminalID, "data": data}
	}
	if exitCode != nil {
		meta["terminal_exit"] = map[string]interface{}{"terminal_id": terminalID, "exit_code": exitCode}
	}
	return map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    toolCallID,
			"status":        status,
			"kind":          "execute",
			"title":         "apt-get install",
			"_meta":         meta,
		},
	}
}

// TestHandleSessionUpdate_TerminalRelay_ChunksAppendAndRelay verifies the
// agent-owned terminal relay end-to-end (the reported UI-gap scenario):
// terminal_info opens the terminal, each _meta.terminal_output chunk is
// appended in order (delta semantics) and relayed live, the final chunk +
// _meta.terminal_exit derive failed, and the full transcript persists in the
// final activity detail for replay.
func TestHandleSessionUpdate_TerminalRelay_ChunksAppendAndRelay(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "tc-termrelay",
			"kind":          "execute",
			"title":         "apt-get install",
			"content": []interface{}{
				map[string]interface{}{"type": "terminal", "terminalId": "term-relay"},
			},
			"_meta": map[string]interface{}{
				"terminal_info": map[string]interface{}{"terminal_id": "term-relay", "cwd": "/kratis/workspace"},
			},
		},
	})
	h.handleSessionUpdate(terminalRelayUpdate("in_progress", "tc-termrelay", "term-relay", "Reading package lists...", nil, false))
	h.handleSessionUpdate(terminalRelayUpdate("in_progress", "tc-termrelay", "term-relay", "\nE: Permission denied\n", nil, false))
	h.handleSessionUpdate(terminalRelayUpdate("failed", "tc-termrelay", "term-relay", "\n\nCommand exited with code 100", 1, false))

	var relayed strings.Builder
	for _, out := range sink.outputs {
		if strings.HasPrefix(out.line, "[Tool]") {
			continue
		}
		relayed.WriteString(out.line)
	}
	expected := "Reading package lists...\nE: Permission denied\n\n\nCommand exited with code 100"
	if relayed.String() != expected {
		t.Errorf("expected delta-relayed transcript %q, got %q", expected, relayed.String())
	}

	last := sink.activities[len(sink.activities)-1]
	if last.status != "failed" {
		t.Errorf("expected failed activity, got %v", last.status)
	}
	if last.detail.ExitCode == nil || *last.detail.ExitCode != 1 {
		t.Errorf("expected exit code 1 in detail, got %+v", last.detail.ExitCode)
	}
	if last.detail.Output != expected {
		t.Errorf("expected full transcript in detail.output for replay, got %q", last.detail.Output)
	}
}

// TestHandleSessionUpdate_TerminalRelay_BuffersOutputBeforeToolCall verifies
// order-independence: _meta.terminal_output arriving before the tool_call
// announcing the terminal is buffered and drained onto the tool call once the
// terminal_info arrives, then relayed on the next update.
func TestHandleSessionUpdate_TerminalRelay_BuffersOutputBeforeToolCall(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(terminalRelayUpdate("in_progress", "tc-late", "term-late", "early output", nil, false))
	h.handleSessionUpdate(terminalRelayUpdate("in_progress", "tc-late", "term-late", " more", nil, false))

	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "tc-late",
			"kind":          "execute",
			"title":         "cmd",
			"_meta": map[string]interface{}{
				"terminal_info": map[string]interface{}{"terminal_id": "term-late"},
			},
		},
	})
	h.handleSessionUpdate(terminalRelayUpdate("completed", "tc-late", "term-late", "", nil, false))

	var relayed strings.Builder
	for _, out := range sink.outputs {
		relayed.WriteString(out.line)
	}
	if !strings.Contains(relayed.String(), "early output more") {
		t.Errorf("expected buffered output relayed after tool_call, got %q", relayed.String())
	}
	last := sink.activities[len(sink.activities)-1]
	if last.status != "completed" {
		t.Errorf("expected completed activity, got %v", last.status)
	}
	if last.detail.Output != "early output more" {
		t.Errorf("expected full transcript in detail, got %q", last.detail.Output)
	}
}

// TestHandleSessionUpdate_TerminalRelay_BuffersExitBeforeToolCall verifies the
// exit and transcript buffered ahead of the tool_call land on the final
// activity (re-emitted with the drained detail).
func TestHandleSessionUpdate_TerminalRelay_BuffersExitBeforeToolCall(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(terminalRelayUpdate("failed", "tc-x", "term-x", "no sudo", 127, false))

	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "tc-x",
			"kind":          "execute",
			"title":         "cmd",
			"_meta": map[string]interface{}{
				"terminal_info": map[string]interface{}{"terminal_id": "term-x"},
			},
		},
	})
	h.handleSessionUpdate(terminalRelayUpdate("failed", "tc-x", "term-x", "", nil, false))

	last := sink.activities[len(sink.activities)-1]
	if last.status != "failed" {
		t.Errorf("expected failed activity, got %v", last.status)
	}
	if last.detail.ExitCode == nil || *last.detail.ExitCode != 127 {
		t.Errorf("expected buffered exit code 127, got %+v", last.detail.ExitCode)
	}
	if last.detail.Output != "no sudo" {
		t.Errorf("expected buffered output in detail, got %q", last.detail.Output)
	}
}

// TestHandleSessionUpdate_TerminalRelay_DerivesStatusFromExit verifies a
// terminal_exit without a wire status transition finalizes the tool call from
// the exit code.
func TestHandleSessionUpdate_TerminalRelay_DerivesStatusFromExit(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "tc-exit",
			"kind":          "execute",
			"title":         "cmd",
			"_meta": map[string]interface{}{
				"terminal_info": map[string]interface{}{"terminal_id": "term-e"},
			},
		},
	})
	h.handleSessionUpdate(terminalRelayUpdate("in_progress", "tc-exit", "term-e", "boom", nil, false))
	h.handleSessionUpdate(terminalRelayUpdate("in_progress", "tc-exit", "term-e", "", 2, false))

	last := sink.activities[len(sink.activities)-1]
	if last.status != "failed" {
		t.Errorf("expected failed derived from terminal_exit, got %v", last.status)
	}
	if last.detail.ExitCode == nil || *last.detail.ExitCode != 2 {
		t.Errorf("expected exit code 2, got %+v", last.detail.ExitCode)
	}
}

// TestHandleSessionUpdate_TerminalRelay_SignalDerivesFailed verifies a
// terminal_exit with only a signal (no exit code) still derives failed.
func TestHandleSessionUpdate_TerminalRelay_SignalDerivesFailed(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "tc-sig",
			"kind":          "execute",
			"title":         "cmd",
			"_meta": map[string]interface{}{
				"terminal_info": map[string]interface{}{"terminal_id": "term-s"},
			},
		},
	})
	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call_update",
			"toolCallId":    "tc-sig",
			"status":        "in_progress",
			"_meta": map[string]interface{}{
				"terminal_exit": map[string]interface{}{"terminal_id": "term-s", "exit_code": nil, "signal": "SIGKILL"},
			},
		},
	})

	last := sink.activities[len(sink.activities)-1]
	if last.status != "failed" {
		t.Errorf("expected failed derived from termination signal, got %v", last.status)
	}
}

// TestParseTerminalMeta verifies the three _meta terminal relay shapes decode,
// and that missing/odd shapes are rejected without error.
func TestParseTerminalMeta(t *testing.T) {
	infoMeta := map[string]any{"terminal_info": map[string]any{"terminal_id": "t1", "cwd": "/w"}}
	tid, cwd, ok := parseTerminalInfo(infoMeta)
	if !ok || tid != "t1" || cwd != "/w" {
		t.Errorf("expected terminal_info decoded, got tid=%q cwd=%q ok=%v", tid, cwd, ok)
	}
	if _, _, ok := parseTerminalInfo(map[string]any{}); ok {
		t.Error("expected empty meta to fail parseTerminalInfo")
	}
	if _, _, ok := parseTerminalInfo(map[string]any{"terminal_info": map[string]any{"cwd": "/w"}}); ok {
		t.Error("expected missing terminal_id to fail parseTerminalInfo")
	}

	outMeta := map[string]any{"terminal_output": map[string]any{"terminal_id": "t1", "data": "chunk"}}
	tid, data, ok := parseTerminalOutput(outMeta)
	if !ok || tid != "t1" || data != "chunk" {
		t.Errorf("expected terminal_output decoded, got tid=%q data=%q ok=%v", tid, data, ok)
	}
	// The presence of the key is reported even when data is empty; the caller
	// treats an empty chunk as a no-op.
	emptyMeta := map[string]any{"terminal_output": map[string]any{"terminal_id": "t1"}}
	tid, data, ok = parseTerminalOutput(emptyMeta)
	if !ok || tid != "t1" || data != "" {
		t.Errorf("expected terminal_output with empty data, got tid=%q data=%q ok=%v", tid, data, ok)
	}

	exitMeta := map[string]any{"terminal_exit": map[string]any{"terminal_id": "t1", "exit_code": 3, "signal": nil}}
	tid, code, signal, ok := parseTerminalExit(exitMeta)
	if !ok || tid != "t1" || code == nil || *code != 3 || signal != nil {
		t.Errorf("expected terminal_exit decoded, got tid=%q code=%v signal=%v ok=%v", tid, code, signal, ok)
	}
}

// TestTerminalManager_LiveOutputSink_StreamsBeforeExit verifies the P2 live
// relay: output reaches the sink as it is produced (before exit), and the
// buffer still holds the full transcript for terminal/output RPCs.
func TestTerminalManager_LiveOutputSink_StreamsBeforeExit(t *testing.T) {
	tm := NewTerminalManager(t.TempDir())
	var mu sync.Mutex
	var lines []string
	tm.SetOutputSink(func(line, _ string) {
		mu.Lock()
		defer mu.Unlock()
		lines = append(lines, line)
	})

	term, err := tm.CreateTerminal("echo live-one; echo live-two", "")
	if err != nil {
		t.Fatalf("CreateTerminal failed: %v", err)
	}
	if _, err := tm.WaitForExit(term.ID, 5*time.Second); err != nil {
		t.Fatalf("WaitForExit failed: %v", err)
	}

	mu.Lock()
	defer mu.Unlock()
	if len(lines) != 2 || lines[0] != "live-one" || lines[1] != "live-two" {
		t.Errorf("expected two live-streamed lines, got %+v", lines)
	}

	stdout, _, _ := tm.GetOutput(term.ID)
	if stdout != "live-one\nlive-two\n" {
		t.Errorf("expected full buffered output, got %q", stdout)
	}
}
