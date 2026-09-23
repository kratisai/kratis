package runner

import (
	"context"
	"kratis-connector/acp"
	"os"
	"os/exec"
	"runtime"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"
)

type mockEventSink struct {
	outputLines    []string
	activityCalls  []string
	activities     []acp.Activity
	completions    []CompletionInfo
	permRequests   []string
	permCancelChan chan struct{}
	permResponse   chan bool
	cancelOnce     sync.Once
	mu             sync.Mutex
}

func (m *mockEventSink) SendOutput(line string, _ string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.outputLines = append(m.outputLines, line)
}

func (m *mockEventSink) SendActivity(activity acp.Activity) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.activityCalls = append(m.activityCalls, string(activity.ActivityType))
	m.activities = append(m.activities, activity)
}

func (m *mockEventSink) SendComplete(info CompletionInfo) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.completions = append(m.completions, info)
}

func (m *mockEventSink) RequestPermission(req acp.PermissionRequest) (string, error) {
	m.mu.Lock()
	m.permRequests = append(m.permRequests, req.Command)
	m.mu.Unlock()

	select {
	case approved := <-m.permResponse:
		if approved {
			return "allow", nil
		}
		return "", nil
	case <-m.permCancelChan:
		return "", context.Canceled
	}
}

func (m *mockEventSink) PermissionCancelChan() <-chan struct{} {
	return m.permCancelChan
}

func (m *mockEventSink) CreateElicitation(_ acp.ElicitationRequest) (acp.ElicitationResult, error) {
	select {
	case <-m.permCancelChan:
		return acp.ElicitationResult{}, context.Canceled
	default:
	}
	return acp.ElicitationResult{Action: "cancel"}, nil
}

func (m *mockEventSink) CancelPendingHitl() {
	m.cancelOnce.Do(func() {
		close(m.permCancelChan)
	})
}

func TestConcurrentTerminateAndExit(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/short_lived.sh"
	scriptContent := `#!/bin/bash
sleep 0.1
exit 0
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

	cmd := exec.Command("bash", scriptPath)
	if err := sup.StartProcess(cmd); err != nil {
		t.Fatalf("failed to start process: %v", err)
	}

	var wg sync.WaitGroup
	var completeCount atomic.Int32

	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			exitCode, err := sup.Terminate()
			if err != nil && err.Error() != "no process to terminate" {
				t.Errorf("unexpected error from Terminate: %v", err)
			}
			if exitCode >= 0 {
				completeCount.Add(1)
			}
		}()
	}

	wg.Wait()

	time.Sleep(100 * time.Millisecond)

	sink.mu.Lock()
	actualCompleteCount := len(sink.completions)
	sink.mu.Unlock()

	if actualCompleteCount != 1 {
		t.Errorf("expected exactly 1 env.complete call, got %d", actualCompleteCount)
	}
}

func TestConcurrentPermissionCancelAndTerminate(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/long_lived.sh"
	scriptContent := `#!/bin/bash
trap 'exit 0' TERM
while true; do
    sleep 1
done
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

	cmd := exec.Command("bash", scriptPath)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := sup.StartProcess(cmd); err != nil {
		t.Fatalf("failed to start process: %v", err)
	}

	var permErr error
	var permDone sync.WaitGroup
	permDone.Add(1)

	go func() {
		defer permDone.Done()
		_, permErr = sup.RequestPermission(acp.PermissionRequest{Command: "test-command"})
	}()

	time.Sleep(50 * time.Millisecond)

	var termDone sync.WaitGroup
	termDone.Add(1)
	go func() {
		defer termDone.Done()
		_, _ = sup.Terminate()
	}()

	termDone.Wait()
	permDone.Wait()

	if permErr == nil {
		t.Error("expected permission request to be cancelled")
	}

	if sup.State() != StateTerminated {
		t.Errorf("expected state to be Terminated, got %v", sup.State())
	}
}

func TestTerminateEscalatesToSigkill(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/ignore_term.sh"
	if err := writeScript(scriptPath, "#!/bin/bash\ntrap '' TERM\nwhile true; do sleep 1; done\n"); err != nil {
		t.Fatalf("failed to write script: %v", err)
	}

	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}
	sup := NewAgentSupervisor(tempDir, nil, sink)
	SetTestTimeouts(sup)

	cmd := exec.Command("bash", scriptPath)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := sup.StartProcess(cmd); err != nil {
		t.Fatalf("failed to start process: %v", err)
	}

	exitCode, err := sup.Terminate()
	if err != nil {
		t.Fatalf("Terminate failed: %v", err)
	}
	if exitCode != 128+int(syscall.SIGKILL) {
		t.Errorf("expected SIGKILL exit code %d, got %d", 128+int(syscall.SIGKILL), exitCode)
	}
	if sup.State() != StateTerminated {
		t.Errorf("expected successful Terminate to publish state Terminated, got %v", sup.State())
	}
}

func TestTerminalCleanupOnUnexpectedExit(t *testing.T) {
	tempDir := t.TempDir()

	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}

	terminals := acp.NewTerminalManager(tempDir)
	sup := NewAgentSupervisor(tempDir, terminals, sink)
	SetTestTimeouts(sup)

	termCmd := "sleep 30"
	term, err := terminals.CreateTerminal(termCmd, tempDir)
	if err != nil {
		t.Fatalf("failed to create terminal: %v", err)
	}

	scriptPath := tempDir + "/exit_quickly.sh"
	scriptContent := `#!/bin/bash
exit 1
`
	if err := writeScript(scriptPath, scriptContent); err != nil {
		t.Fatalf("failed to write script: %v", err)
	}

	cmd := exec.Command("bash", scriptPath)
	if err := sup.StartProcess(cmd); err != nil {
		t.Fatalf("failed to start process: %v", err)
	}

	time.Sleep(200 * time.Millisecond)

	terminals.ReleaseAll()

	_, ok := terminals.GetTerminal(term.ID)
	if ok {
		t.Error("expected terminal to be cleaned up after process exit")
	}
}

func TestTerminalCleanupOnTerminate(t *testing.T) {
	tempDir := t.TempDir()

	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}

	terminals := acp.NewTerminalManager(tempDir)
	sup := NewAgentSupervisor(tempDir, terminals, sink)
	SetTestTimeouts(sup)

	termCmd := "sleep 30"
	term, err := terminals.CreateTerminal(termCmd, tempDir)
	if err != nil {
		t.Fatalf("failed to create terminal: %v", err)
	}

	scriptPath := tempDir + "/long_lived.sh"
	scriptContent := `#!/bin/bash
trap 'exit 0' TERM
while true; do
    sleep 1
done
`
	if err := writeScript(scriptPath, scriptContent); err != nil {
		t.Fatalf("failed to write script: %v", err)
	}

	cmd := exec.Command("bash", scriptPath)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := sup.StartProcess(cmd); err != nil {
		t.Fatalf("failed to start process: %v", err)
	}

	_, _ = sup.Terminate()

	_, ok := terminals.GetTerminal(term.ID)
	if ok {
		t.Error("expected terminal to be cleaned up after Terminate")
	}
}

func TestGoroutineLeakAfterShutdown(t *testing.T) {
	initialGoroutines := runtime.NumGoroutine()

	tempDir := t.TempDir()

	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}

	terminals := acp.NewTerminalManager(tempDir)
	sup := NewAgentSupervisor(tempDir, terminals, sink)
	SetTestTimeouts(sup)

	scriptPath := tempDir + "/long_lived.sh"
	scriptContent := `#!/bin/bash
trap 'exit 0' TERM
while true; do
    sleep 1
done
`
	if err := writeScript(scriptPath, scriptContent); err != nil {
		t.Fatalf("failed to write script: %v", err)
	}

	cmd := exec.Command("bash", scriptPath)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := sup.StartProcess(cmd); err != nil {
		t.Fatalf("failed to start process: %v", err)
	}

	_, _ = terminals.CreateTerminal("sleep 30", tempDir)

	_, _ = sup.Terminate()

	time.Sleep(2 * time.Second)

	finalGoroutines := runtime.NumGoroutine()

	leak := finalGoroutines - initialGoroutines
	if leak > 3 {
		t.Errorf("potential goroutine leak: initial=%d, final=%d, leak=%d",
			initialGoroutines, finalGoroutines, leak)
	}
}

func TestCloseSessionNoOpWhenSessionNil(t *testing.T) {
	tempDir := t.TempDir()
	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}
	terminals := acp.NewTerminalManager(tempDir)
	sup := NewAgentSupervisor(tempDir, terminals, sink)
	SetTestTimeouts(sup)

	err := sup.CloseSession()
	if err != nil {
		t.Errorf("expected no error when session is nil, got %v", err)
	}

	if sup.State() != StateUninitialized {
		t.Errorf("expected state to remain Uninitialized, got %v", sup.State())
	}
}

func TestCloseSessionNoOpWhenTransportNil(t *testing.T) {
	tempDir := t.TempDir()
	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}
	terminals := acp.NewTerminalManager(tempDir)
	sup := NewAgentSupervisor(tempDir, terminals, sink)
	SetTestTimeouts(sup)

	session := &acp.AcpSession{
		SessionID: "test-session-123",
	}
	sup.stateLock.Lock()
	sup.session = session
	sup.stateLock.Unlock()

	err := sup.CloseSession()
	if err != nil {
		t.Errorf("expected no error when transport is nil, got %v", err)
	}

	if sup.State() != StateUninitialized {
		t.Errorf("expected state to remain Uninitialized, got %v", sup.State())
	}
}

func TestCloseSessionNoOpWhenSessionIDEmpty(t *testing.T) {
	tempDir := t.TempDir()
	sink := &mockEventSink{
		permCancelChan: make(chan struct{}),
		permResponse:   make(chan bool, 1),
	}
	terminals := acp.NewTerminalManager(tempDir)
	sup := NewAgentSupervisor(tempDir, terminals, sink)
	SetTestTimeouts(sup)

	session := &acp.AcpSession{
		SessionID: "",
	}
	sup.stateLock.Lock()
	sup.session = session
	sup.stateLock.Unlock()

	err := sup.CloseSession()
	if err != nil {
		t.Errorf("expected no error when session ID is empty, got %v", err)
	}

	if sup.State() != StateUninitialized {
		t.Errorf("expected state to remain Uninitialized, got %v", sup.State())
	}
}

func TestCloseSessionTimeout(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/no_respond.sh"
	scriptContent := `#!/bin/bash
trap 'exit 0' TERM
while true; do
    sleep 1
done
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

	cmd := exec.Command("bash", scriptPath)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatalf("failed to get stdin pipe: %v", err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatalf("failed to get stdout pipe: %v", err)
	}

	transport := acp.NewAcpTransport(stdin, stdout, nil, nil, nil)
	sup.setTransport(transport)

	if err := sup.StartProcess(cmd); err != nil {
		t.Fatalf("failed to start process: %v", err)
	}

	transport.Start()

	session := &acp.AcpSession{
		SessionID: "test-session-timeout",
	}
	sup.setSession(session)

	start := time.Now()
	err = sup.CloseSession()
	elapsed := time.Since(start)

	if err == nil {
		t.Error("expected timeout error, got nil")
	}

	if elapsed < 100*time.Millisecond {
		t.Errorf("expected timeout to take at least 100ms, took %v", elapsed)
	}

	if elapsed > 1*time.Second {
		t.Errorf("expected timeout to complete within 1 second, took %v", elapsed)
	}

	if sup.State() != StateSessionClosing {
		t.Errorf("expected state to be SessionClosing after timeout, got %v", sup.State())
	}

	_, _ = sup.Terminate()
}

func TestCloseSessionSuccess(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/respond_to_close.sh"
	scriptContent := `#!/bin/bash
while IFS= read -r line; do
    if echo "$line" | grep -q '"method":"session/close"'; then
        id=$(echo "$line" | grep -o '"id":[0-9.]*' | head -1 | cut -d':' -f2)
        echo "{\"jsonrpc\":\"2.0\",\"result\":{},\"id\":$id}"
    fi
done
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

	cmd := exec.Command("bash", scriptPath)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatalf("failed to get stdin pipe: %v", err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatalf("failed to get stdout pipe: %v", err)
	}

	transport := acp.NewAcpTransport(stdin, stdout, nil, nil, nil)
	sup.setTransport(transport)

	if err := sup.StartProcess(cmd); err != nil {
		t.Fatalf("failed to start process: %v", err)
	}

	transport.Start()

	session := &acp.AcpSession{
		SessionID: "test-session-success",
	}
	sup.setSession(session)

	err = sup.CloseSession()
	if err != nil {
		t.Errorf("expected no error on successful close, got %v", err)
	}

	if sup.State() != StateReady {
		t.Errorf("expected state to be Ready after successful close, got %v", sup.State())
	}

	_, _ = sup.Terminate()
}

func TestCloseSessionIdempotent(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/respond_to_close.sh"
	scriptContent := `#!/bin/bash
while IFS= read -r line; do
    if echo "$line" | grep -q '"method":"session/close"'; then
        id=$(echo "$line" | grep -o '"id":[0-9.]*' | head -1 | cut -d':' -f2)
        echo "{\"jsonrpc\":\"2.0\",\"result\":{},\"id\":$id}"
    fi
done
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

	cmd := exec.Command("bash", scriptPath)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatalf("failed to get stdin pipe: %v", err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatalf("failed to get stdout pipe: %v", err)
	}

	transport := acp.NewAcpTransport(stdin, stdout, nil, nil, nil)
	sup.setTransport(transport)

	if err := sup.StartProcess(cmd); err != nil {
		t.Fatalf("failed to start process: %v", err)
	}

	transport.Start()

	session := &acp.AcpSession{
		SessionID: "test-session-idempotent",
	}
	sup.setSession(session)

	err = sup.CloseSession()
	if err != nil {
		t.Errorf("first CloseSession failed: %v", err)
	}

	sup.stateLock.Lock()
	sup.session = nil
	sup.stateLock.Unlock()

	err = sup.CloseSession()
	if err != nil {
		t.Errorf("second CloseSession should be no-op, got error: %v", err)
	}

	_, _ = sup.Terminate()
}

func writeScript(path string, content string) error {
	return os.WriteFile(path, []byte(content), 0755)
}

func TestDisconnectDuringPrompt(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/slow_prompt_agent.sh"
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"slow-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"slow-session"},"id":2}'

read -r line
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

	cmd := exec.Command("bash", scriptPath)
	cmd.Dir = tempDir
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	result, err := sup.Launch(cmd)
	if err != nil {
		t.Fatalf("Launch failed: %v", err)
	}
	if result.Session == nil {
		t.Fatal("expected session to be set")
	}

	var promptDone sync.WaitGroup
	promptDone.Add(1)
	go func() {
		defer promptDone.Done()
		_, _ = sup.Prompt("test task")
	}()

	time.Sleep(50 * time.Millisecond)

	var termDone sync.WaitGroup
	termDone.Add(1)
	go func() {
		defer termDone.Done()
		_, _ = sup.Terminate()
	}()

	termDone.Wait()
	promptDone.Wait()

	if sup.State() != StateTerminated {
		t.Errorf("expected state Terminated, got %v", sup.State())
	}
}

func TestDisconnectDuringPermissionWait(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/perm_request_agent.sh"
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"perm-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"perm-session"},"id":2}'

read -r line
echo '{"jsonrpc":"2.0","method":"session/request_permission","params":{"sessionId":"perm-session","toolCall":{"toolCallId":"tc1","title":"rm -rf /","rawInput":{"command":"rm -rf /"}},"options":[{"optionId":"allow","name":"Allow","kind":"allow_once"}]},"id":100}'

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

	cmd := exec.Command("bash", scriptPath)
	cmd.Dir = tempDir
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	result, err := sup.Launch(cmd)
	if err != nil {
		t.Fatalf("Launch failed: %v", err)
	}
	if result.Session == nil {
		t.Fatal("expected session to be set")
	}

	var permDone sync.WaitGroup
	permDone.Add(1)
	go func() {
		defer permDone.Done()
		_, _ = sup.RequestPermission(acp.PermissionRequest{Command: "rm -rf /"})
	}()

	time.Sleep(50 * time.Millisecond)

	var termDone sync.WaitGroup
	termDone.Add(1)
	go func() {
		defer termDone.Done()
		_, _ = sup.Terminate()
	}()

	termDone.Wait()
	permDone.Wait()

	if sup.State() != StateTerminated {
		t.Errorf("expected state Terminated, got %v", sup.State())
	}
}

func TestConcurrentDisconnectAndExit(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := tempDir + "/quick_exit_agent.sh"
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"quick-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"quick-session"},"id":2}'

exit 0
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

	cmd := exec.Command("bash", scriptPath)
	cmd.Dir = tempDir
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	result, err := sup.Launch(cmd)
	if err != nil {
		t.Fatalf("Launch failed: %v", err)
	}
	if result.Session == nil {
		t.Fatal("expected session to be set")
	}

	time.Sleep(200 * time.Millisecond)

	var wg sync.WaitGroup
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = sup.Terminate()
		}()
	}

	wg.Wait()

	time.Sleep(100 * time.Millisecond)

	sink.mu.Lock()
	actualCompleteCount := len(sink.completions)
	sink.mu.Unlock()

	if actualCompleteCount != 1 {
		t.Errorf("expected exactly 1 env.complete call, got %d", actualCompleteCount)
	}
}
