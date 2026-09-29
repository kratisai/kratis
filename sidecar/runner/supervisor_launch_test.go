package runner

import (
	"context"
	"kratis-connector/acp"
	"os"
	"os/exec"
	"syscall"
	"testing"
	"time"
)

func TestLaunchSuccess(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/mock_agent.sh"
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"test-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"test-session-123"},"id":2}'

sleep 30
`
	if err := writeScript(scriptPath, scriptContent); err != nil {
		t.Fatalf("failed to write script: %v", err)
	}

	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}
	terminals := acp.NewTerminalManager(tempDir)
	sup := NewAgentSupervisor(tempDir, terminals, sink)
	SetTestTimeouts(sup)

	agentCmd := "/bin/bash " + scriptPath
	cmd := exec.CommandContext(context.Background(), "sh", "-c", agentCmd)
	cmd.Dir = tempDir
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	result, err := sup.Launch(cmd)
	if err != nil {
		t.Fatalf("Launch failed: %v", err)
	}

	if result.Session == nil {
		t.Fatal("expected session to be set")
	}
	if result.Session.SessionID != "test-session-123" {
		t.Errorf("expected session ID 'test-session-123', got '%s'", result.Session.SessionID)
	}
	if result.AgentName != "test-agent" {
		t.Errorf("expected agent name 'test-agent', got '%s'", result.AgentName)
	}
	if result.AgentVersion != "1.0.0" {
		t.Errorf("expected agent version '1.0.0', got '%s'", result.AgentVersion)
	}
	if sup.State() != StateSessionActive {
		t.Errorf("expected state SessionActive, got %v", sup.State())
	}

	sink.mu.Lock()
	hasHandshakeOutput := false
	for _, line := range sink.outputLines {
		if line == "[ACP] session/new successful" {
			hasHandshakeOutput = true
		}
	}
	sink.mu.Unlock()
	if !hasHandshakeOutput {
		t.Error("expected handshake output messages via event sink")
	}

	_, _ = sup.Terminate()
}

// TestLaunchSlowInitializeSucceeds verifies that a cold-starting agent that
// takes longer than the steady-state RequestTimeout to answer the handshake
// still launches successfully: initialize and session/new use the separate,
// more generous InitializeTimeout.
func TestLaunchSlowInitializeSucceeds(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/slow_agent.sh"
	scriptContent := `#!/bin/bash
read -r line
sleep 3
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"test-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"test-session-123"},"id":2}'

sleep 30
`
	if err := writeScript(scriptPath, scriptContent); err != nil {
		t.Fatalf("failed to write script: %v", err)
	}

	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}
	terminals := acp.NewTerminalManager(tempDir)
	sup := NewAgentSupervisor(tempDir, terminals, sink)
	// RequestTimeout is tight (shorter than the agent's startup delay); the
	// handshake must rely on InitializeTimeout instead.
	sup.Timeouts = SupervisorTimeouts{
		CancelDelay:       10 * time.Millisecond,
		ExitWait:          100 * time.Millisecond,
		SigkillWait:       300 * time.Millisecond,
		DrainWait:         100 * time.Millisecond,
		SessionCloseWait:  200 * time.Millisecond,
		RequestTimeout:    1 * time.Second,
		InitializeTimeout: 5 * time.Second,
	}

	agentCmd := "/bin/bash " + scriptPath
	cmd := exec.CommandContext(context.Background(), "sh", "-c", agentCmd)
	cmd.Dir = tempDir
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	result, err := sup.Launch(cmd)
	if err != nil {
		t.Fatalf("Launch failed for slow-initializing agent: %v", err)
	}

	if result.Session == nil {
		t.Fatal("expected session to be set")
	}
	if result.Session.SessionID != "test-session-123" {
		t.Errorf("expected session ID 'test-session-123', got '%s'", result.Session.SessionID)
	}
	if sup.State() != StateSessionActive {
		t.Errorf("expected state SessionActive, got %v", sup.State())
	}

	_, _ = sup.Terminate()
}

// TestLaunchInitializeTimeout tests that a non-responding agent is detected
// within the handshake timeout.
func TestLaunchInitializeTimeout(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/bad_agent.sh"
	scriptContent := `#!/bin/bash
echo "I am not speaking JSON-RPC"
sleep 30
`
	if err := writeScript(scriptPath, scriptContent); err != nil {
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

	start := time.Now()
	_, err := sup.Launch(cmd)
	elapsed := time.Since(start)

	if err == nil {
		t.Fatal("expected error from Launch with non-responding agent")
	}
	if elapsed < 1*time.Second {
		t.Errorf("expected timeout to take at least 1 second, took %v", elapsed)
	}
}

func TestLaunchInitializeError(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/error_agent.sh"
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","error":{"code":-32000,"message":"init failed"},"id":1}'
sleep 30
`
	if err := writeScript(scriptPath, scriptContent); err != nil {
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

	_, err := sup.Launch(cmd)
	if err == nil {
		t.Fatal("expected error from Launch with initialize error response")
	}
}

func TestLaunchSessionNewError(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/session_error_agent.sh"
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"test-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","error":{"code":-32000,"message":"session creation failed"},"id":2}'
sleep 30
`
	if err := writeScript(scriptPath, scriptContent); err != nil {
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

	_, err := sup.Launch(cmd)
	if err == nil {
		t.Fatal("expected error from Launch with session/new error response")
	}
}

func TestLaunchProcessExitsDuringHandshake(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/early_exit_agent.sh"
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"early-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"early-session"},"id":2}'

exit 42
`
	if err := writeScript(scriptPath, scriptContent); err != nil {
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

	result, err := sup.Launch(cmd)
	if err != nil {
		t.Fatalf("Launch should succeed (handshake completes before exit): %v", err)
	}

	if result.Session == nil {
		t.Fatal("expected session to be set")
	}

	time.Sleep(200 * time.Millisecond)

	session := sup.Session()
	if session != nil {
		t.Error("expected session to be cleared after process exit")
	}
}

func TestLaunchStateTransitions(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/mock_agent.sh"
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"test-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"test-session"},"id":2}'

sleep 30
`
	if err := writeScript(scriptPath, scriptContent); err != nil {
		t.Fatalf("failed to write script: %v", err)
	}

	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}
	terminals := acp.NewTerminalManager(tempDir)
	sup := NewAgentSupervisor(tempDir, terminals, sink)
	SetTestTimeouts(sup)

	if sup.State() != StateUninitialized {
		t.Errorf("expected initial state Uninitialized, got %v", sup.State())
	}

	cmd := exec.CommandContext(context.Background(), "sh", "-c", "/bin/bash "+scriptPath)
	cmd.Dir = tempDir
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	_, err := sup.Launch(cmd)
	if err != nil {
		t.Fatalf("Launch failed: %v", err)
	}

	if sup.State() != StateSessionActive {
		t.Errorf("expected state SessionActive after Launch, got %v", sup.State())
	}

	_, _ = sup.Terminate()
}

func TestLaunchFailsFromWrongState(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/mock_agent.sh"
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"test-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"test-session"},"id":2}'

sleep 30
`
	if err := writeScript(scriptPath, scriptContent); err != nil {
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

	_, err := sup.Launch(cmd)
	if err != nil {
		t.Fatalf("first Launch failed: %v", err)
	}

	cmd2 := exec.CommandContext(context.Background(), "sh", "-c", "/bin/bash "+scriptPath)
	cmd2.Dir = tempDir
	cmd2.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	_, err = sup.Launch(cmd2)
	if err == nil {
		t.Error("expected second Launch to fail (wrong state)")
	}

	_, _ = sup.Terminate()
}

func TestLaunchCleanupOnFailure(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/error_agent.sh"
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","error":{"code":-32000,"message":"init failed"},"id":1}'
sleep 30
`
	if err := writeScript(scriptPath, scriptContent); err != nil {
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

	_, err := sup.Launch(cmd)
	if err == nil {
		t.Fatal("expected error from Launch")
	}

	time.Sleep(500 * time.Millisecond)

	if sup.State() != StateTerminated {
		t.Errorf("expected state Terminated after failed Launch, got %v", sup.State())
	}

	sink.mu.Lock()
	hasErrorOutput := false
	for _, line := range sink.outputLines {
		if line == "[ACP] Launch failed: ACP initialize error: map[code:-32000 message:init failed] — terminating agent" {
			hasErrorOutput = true
		}
	}
	sink.mu.Unlock()
	if !hasErrorOutput {
		t.Error("expected error output via event sink on failed Launch")
	}
}

func TestLaunchVersionNegotiation(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/old_agent.sh"
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":0,"serverInfo":{"name":"old-agent","version":"0.5.0"}},"id":1}'
sleep 30
`
	if err := writeScript(scriptPath, scriptContent); err != nil {
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

	_, err := sup.Launch(cmd)
	if err == nil {
		t.Fatal("expected version negotiation error")
	}

	_, _ = sup.Terminate()
}

func TestLaunchStdinPipeFailure(t *testing.T) {
	tempDir := t.TempDir()

	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}
	terminals := acp.NewTerminalManager(tempDir)
	sup := NewAgentSupervisor(tempDir, terminals, sink)
	SetTestTimeouts(sup)

	cmd := exec.Command("false")
	cmd.Stdin = os.Stdin

	_, err := sup.Launch(cmd)
	if err == nil {
		t.Fatal("expected error when stdin pipe cannot be created")
	}
}
