package runner

import (
	"context"
	"kratis-connector/acp"
	"os/exec"
	"strings"
	"syscall"
	"testing"
	"time"
)

// launchMockScript runs a mock agent that answers the handshake from a supplied
// shell body and then idles, returning the launch result.
func launchMockScript(t *testing.T, body string, resumeSessionID string) (LaunchResult, *mockEventSink) {
	t.Helper()
	tempDir := t.TempDir()
	scriptPath := tempDir + "/agent.sh"
	if err := writeScript(scriptPath, "#!/bin/bash\n"+body+"\nsleep 30\n"); err != nil {
		t.Fatalf("failed to write script: %v", err)
	}

	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}
	terminals := acp.NewTerminalManager(tempDir)
	sup := NewAgentSupervisor(tempDir, terminals, sink)
	SetTestTimeouts(sup)
	sup.SetResumeSessionID(resumeSessionID)

	cmd := exec.CommandContext(context.Background(), "sh", "-c", "/bin/bash "+scriptPath)
	cmd.Dir = tempDir
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	result, err := sup.Launch(cmd)
	if err != nil {
		t.Fatalf("Launch failed: %v", err)
	}
	t.Cleanup(func() { _, _ = sup.Terminate() })
	return *result, sink
}

const initWithResume = `read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"ra","version":"1"},"agentCapabilities":{"loadSession":true,"sessionCapabilities":{"resume":{}}}},"id":1}'`

const initWithLoad = `read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"ra","version":"1"},"agentCapabilities":{"loadSession":true}},"id":1}'`

const initNoReattach = `read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"ra","version":"1"},"agentCapabilities":{}},"id":1}'`

func TestLaunchResumesWhenResumeSupported(t *testing.T) {
	body := initWithResume + `
read -r line
echo '{"jsonrpc":"2.0","result":{},"id":2}'`

	result, _ := launchMockScript(t, body, "stored-session")
	if !result.Resumed {
		t.Error("expected Resumed=true for session/resume")
	}
	if result.Session.SessionID != "stored-session" {
		t.Errorf("expected stored session id, got %q", result.Session.SessionID)
	}
}

func TestLaunchLoadsWhenOnlyLoadSessionSupported(t *testing.T) {
	body := initWithLoad + `
read -r line
echo '{"jsonrpc":"2.0","result":{},"id":2}'`

	result, sink := launchMockScript(t, body, "stored-session")
	if !result.Resumed {
		t.Error("expected Resumed=true for session/load")
	}
	if result.Session.SessionID != "stored-session" {
		t.Errorf("expected stored session id, got %q", result.Session.SessionID)
	}
	sink.mu.Lock()
	defer sink.mu.Unlock()
	joined := strings.Join(sink.outputLines, "\n")
	if !strings.Contains(joined, "session/load") {
		t.Error("expected session/load path in output")
	}
}

func TestLaunchFallsBackToNewWhenResumeFails(t *testing.T) {
	body := initWithResume + `
read -r line
echo '{"jsonrpc":"2.0","error":{"code":-32000,"message":"unknown session"},"id":2}'
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"fresh-session"},"id":3}'`

	result, _ := launchMockScript(t, body, "stored-session")
	if result.Resumed {
		t.Error("expected Resumed=false after a failed re-attach")
	}
	if result.Session.SessionID != "fresh-session" {
		t.Errorf("expected fresh session id, got %q", result.Session.SessionID)
	}
}

func TestLaunchCreatesNewWhenNoStoredSession(t *testing.T) {
	body := initWithResume + `
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"new-session"},"id":2}'`

	result, _ := launchMockScript(t, body, "")
	if result.Resumed {
		t.Error("expected Resumed=false without a stored session")
	}
	if result.Session.SessionID != "new-session" {
		t.Errorf("expected new session id, got %q", result.Session.SessionID)
	}
}

func TestLaunchCreatesNewWhenReattachUnsupported(t *testing.T) {
	body := initNoReattach + `
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"new-session"},"id":2}'`

	result, _ := launchMockScript(t, body, "stored-session")
	if result.Resumed {
		t.Error("expected Resumed=false when the agent supports no re-attach")
	}
	if result.Session.SessionID != "new-session" {
		t.Errorf("expected new session id, got %q", result.Session.SessionID)
	}
}

// TestLaunchKeepsMetadataUpdatesOnFreshSession: a fresh session must not arm
// replay suppression, so agent metadata sent right after session/new is
// captured instead of dropped.
func TestLaunchKeepsMetadataUpdatesOnFreshSession(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/agent.sh"
	body := initNoReattach + `
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"new-session"},"id":2}'
echo '{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"review"}]}}}'
sleep 30
`
	if err := writeScript(scriptPath, "#!/bin/bash\n"+body+"\n"); err != nil {
		t.Fatalf("failed to write script: %v", err)
	}

	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}
	terminals := acp.NewTerminalManager(tempDir)
	sup := NewAgentSupervisor(tempDir, terminals, sink)
	SetTestTimeouts(sup)

	cmd := exec.CommandContext(context.Background(), "sh", "-c", "/bin/bash "+scriptPath)
	cmd.Dir = tempDir
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	if _, err := sup.Launch(cmd); err != nil {
		t.Fatalf("Launch failed: %v", err)
	}
	t.Cleanup(func() { _, _ = sup.Terminate() })

	deadline := time.Now().Add(2 * time.Second)
	for {
		if metadata := sup.handler.SessionMetadata(); len(metadata["available_commands_update"]) == 1 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf(
				"available_commands_update must be captured on a fresh session, got %+v",
				sup.handler.SessionMetadata())
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// TestLaunchSuppressesAsyncHistoryReplayUntilWireQuiet: a transcript replay
// arriving after the session/load response (Gemini CLI streams its history
// asynchronously) must stay suppressed until the wire goes quiet, and live
// traffic must flow afterwards.
func TestLaunchSuppressesAsyncHistoryReplayUntilWireQuiet(t *testing.T) {
	originalQuiet := attachReplayQuietWindow
	attachReplayQuietWindow = 150 * time.Millisecond
	t.Cleanup(func() { attachReplayQuietWindow = originalQuiet })

	tempDir := t.TempDir()
	scriptPath := tempDir + "/agent.sh"
	body := initWithLoad + `
read -r line
echo '{"jsonrpc":"2.0","result":{},"id":2}'
echo '{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"replayed history"}}}}'
read -r line
echo '{"jsonrpc":"2.0","result":{"stopReason":"end_turn"},"id":3}'
echo '{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"live turn text"}}}}'
sleep 30
`
	if err := writeScript(scriptPath, "#!/bin/bash\n"+body+"\n"); err != nil {
		t.Fatalf("failed to write script: %v", err)
	}

	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}
	terminals := acp.NewTerminalManager(tempDir)
	sup := NewAgentSupervisor(tempDir, terminals, sink)
	SetTestTimeouts(sup)
	sup.SetResumeSessionID("stored-session")

	cmd := exec.CommandContext(context.Background(), "sh", "-c", "/bin/bash "+scriptPath)
	cmd.Dir = tempDir
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	// Launch returns only after the replay drained and the wire went quiet.
	if _, err := sup.Launch(cmd); err != nil {
		t.Fatalf("Launch failed: %v", err)
	}
	t.Cleanup(func() { _, _ = sup.Terminate() })

	sink.mu.Lock()
	relayed := len(sink.activities)
	sink.mu.Unlock()
	if relayed != 0 {
		t.Fatalf("async history replay must stay suppressed until the wire goes quiet, got activities: %+v", sink.activities)
	}

	if _, err := sup.Prompt("task", ""); err != nil {
		t.Fatalf("Prompt failed: %v", err)
	}

	// Live traffic after the attach window flows again.
	deadline := time.Now().Add(5 * time.Second)
	for {
		sink.mu.Lock()
		live := false
		for _, a := range sink.activities {
			if a.Description == "live turn text" {
				live = true
			}
		}
		sink.mu.Unlock()
		if live {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("expected the live turn text to be relayed after the prompt, got activities: %+v", sink.activities)
		}
		time.Sleep(10 * time.Millisecond)
	}

	sink.mu.Lock()
	defer sink.mu.Unlock()
	for _, a := range sink.activities {
		if strings.Contains(a.Description, "replayed history") {
			t.Errorf("replayed history leaked into activities: %+v", a)
		}
	}
}
