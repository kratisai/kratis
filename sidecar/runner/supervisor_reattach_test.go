package runner

import (
	"context"
	"kratis-connector/acp"
	"os/exec"
	"strings"
	"syscall"
	"testing"
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
