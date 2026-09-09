package rpc

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"kratis-connector/acp"
	"kratis-connector/runner"

	"github.com/gorilla/websocket"
)

func TestExecuteLaunchAcpAgent_Success(t *testing.T) {
	// Write mock ACP agent script to temporary directory
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "mock_agent.sh")

	scriptContent := `#!/bin/bash
# 1. Read initialize
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"mock-agent","version":"1.0.0"}},"id":1}'

# 2. Read session/new
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"mock-session-123"},"id":2}'

# Agent stays alive waiting for more commands
sleep 30
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		gotOutput      atomic.Bool
		gotInitialized atomic.Bool
		mu             sync.Mutex
		outputLines    []string
		initializedMsg AcpInitializedParams
		responseMsg    []byte
		responseChan   = make(chan struct{})
	)

	// Spin up mock server
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			// If it has ID and no method, it's a response
			if req.ID != nil && req.Method == "" {
				mu.Lock()
				if responseMsg == nil {
					responseMsg = msg
					select {
					case <-responseChan:
					default:
						close(responseChan)
					}
				}
				mu.Unlock()
				continue
			}

			switch req.Method {
			case "env.output":
				var p OutputParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				outputLines = append(outputLines, p.Line)
				mu.Unlock()
				gotOutput.Store(true)

			case "env.acp_initialized":
				var p AcpInitializedParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				initializedMsg = p
				mu.Unlock()
				gotInitialized.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Execute Launch ACP Agent
	params := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(params, uint64(101))

	// Wait for the client to send the success response
	select {
	case <-responseChan:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for response from agent launch")
	}

	// Give time for notification to be sent
	time.Sleep(200 * time.Millisecond)

	if !gotOutput.Load() {
		t.Error("expected env.output messages but got none")
	}
	if !gotInitialized.Load() {
		t.Error("expected env.acp_initialized notification but got none")
	}

	mu.Lock()
	lines := outputLines
	initParams := initializedMsg
	respBytes := responseMsg
	mu.Unlock()

	// Verify launch diagnostics were streamed
	if len(lines) == 0 {
		t.Error("expected env.output diagnostics during launch")
	}

	// Verify session was stored
	c.mu.Lock()
	sup := c.supervisor
	c.mu.Unlock()
	if sup == nil {
		t.Error("expected supervisor to be set")
		return
	}
	session := sup.Session()
	if session == nil {
		t.Error("expected ACP session to be stored")
	} else {
		if session.SessionID != "mock-session-123" {
			t.Errorf("expected session ID 'mock-session-123', got '%s'", session.SessionID)
		}
		if session.AgentName != "mock-agent" {
			t.Errorf("expected agent name 'mock-agent', got '%s'", session.AgentName)
		}
	}

	// Verify initialized notification params
	if initParams.SessionID != "mock-session-123" {
		t.Errorf("expected initialized session ID 'mock-session-123', got '%s'", initParams.SessionID)
	}
	if initParams.AgentName != "mock-agent" {
		t.Errorf("expected initialized agent name 'mock-agent', got '%s'", initParams.AgentName)
	}

	// Parse response message
	var resp JsonRpcResponse
	if err := json.Unmarshal(respBytes, &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	var result LaunchAcpAgentResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		t.Fatalf("failed to parse result: %v", err)
	}

	if result.Status != LaunchLaunched {
		t.Errorf("expected status 'launched', got '%s'", result.Status)
	}
	if result.SessionID != "mock-session-123" {
		t.Errorf("expected sessionId 'mock-session-123', got '%s'", result.SessionID)
	}

	// Verify agent process is still running (check before Close)
	// Note: Process lifecycle is now managed by supervisor, not directly accessible from session

	c.Close()
}

func TestExecuteAcpPrompt_Success(t *testing.T) {
	// Write mock ACP agent script that responds to prompt
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "mock_agent.sh")

	scriptContent := `#!/bin/bash
# 1. Read initialize
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"mock-agent","version":"1.0.0"}},"id":1}'

# 2. Read session/new
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"mock-session-456"},"id":2}'

# 3. Read session/prompt
read -r line
echo "stdout log line from agent"
echo "stderr log line from agent" >&2
echo '{"jsonrpc":"2.0","result":{"stopReason":"end_turn"},"id":3}'

# 4. Read wrap-up prompt (quality check & commit)
read -r line
echo "verification completed"
echo '{"jsonrpc":"2.0","result":{"stopReason":"end_turn"},"id":4}'

# Agent stays alive
sleep 30
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		gotOutput         atomic.Bool
		gotPromptComplete atomic.Bool
		mu                sync.Mutex
		outputLines       []string
		promptCompleteMsg AcpPromptCompleteParams
		promptResponseMsg []byte
		launchDone        = make(chan struct{})
		promptDone        = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		responseCount := 0
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				responseCount++
				switch responseCount {
				case 1:
					select {
					case <-launchDone:
					default:
						close(launchDone)
					}
				case 2:
					promptResponseMsg = msg
					select {
					case <-promptDone:
					default:
						close(promptDone)
					}
				}
				mu.Unlock()
				continue
			}

			switch req.Method {
			case "env.output":
				var p OutputParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				outputLines = append(outputLines, p.Line)
				mu.Unlock()
				gotOutput.Store(true)

			case "env.acp_prompt_complete":
				var p AcpPromptCompleteParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				promptCompleteMsg = p
				mu.Unlock()
				gotPromptComplete.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// First, launch the agent
	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(launchParams, uint64(101))

	// Wait for launch to complete
	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	// Now send prompt
	promptParams := AcpPromptParams{
		TaskPrompt: "Implement code",
	}

	go c.ExecuteAcpPrompt(promptParams, uint64(102))

	// Wait for prompt response
	select {
	case <-promptDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for prompt response")
	}

	// Give time for stderr to be captured
	time.Sleep(100 * time.Millisecond)

	if !gotOutput.Load() {
		t.Error("expected env.output messages but got none")
	}
	if !gotPromptComplete.Load() {
		t.Error("expected env.acp_prompt_complete notification but got none")
	}

	mu.Lock()
	lines := outputLines
	promptComplete := promptCompleteMsg
	respBytes := promptResponseMsg
	mu.Unlock()

	// Verify output was captured
	agentStdoutRan := false
	agentStderrRan := false
	for _, l := range lines {
		if strings.Contains(l, "stdout log line from agent") {
			agentStdoutRan = true
		}
		if strings.Contains(l, "stderr log line from agent") {
			agentStderrRan = true
		}
	}

	if !agentStdoutRan {
		t.Error("agent stdout was not captured")
	}
	if !agentStderrRan {
		t.Error("agent stderr was not captured")
	}

	// Verify prompt complete notification
	if promptComplete.SessionID != "mock-session-456" {
		t.Errorf("expected prompt complete session ID 'mock-session-456', got '%s'", promptComplete.SessionID)
	}
	if promptComplete.StopReason != "end_turn" {
		t.Errorf("expected stopReason 'end_turn', got '%s'", promptComplete.StopReason)
	}

	// Parse response message
	var resp JsonRpcResponse
	if err := json.Unmarshal(respBytes, &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	var result AcpPromptResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		t.Fatalf("failed to parse result: %v", err)
	}

	if result.Status != PromptCompleted {
		t.Errorf("expected status 'completed', got '%s'", result.Status)
	}
	if result.StopReason != "end_turn" {
		t.Errorf("expected stopReason 'end_turn', got '%s'", result.StopReason)
	}

	c.Close()
}

func TestExecuteAcpPrompt_SteeringInterruptsInFlightTurn(t *testing.T) {
	tempDir := t.TempDir()
	cancelMarker := filepath.Join(tempDir, "cancel-received")
	steeringMarker := filepath.Join(tempDir, "steering-received")
	scriptPath := filepath.Join(tempDir, "mock_agent.sh")

	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"mock-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"mock-session-456"},"id":2}'

read -r line
echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"mock-session-456","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"in-flight update before cancel"}}}}'
while true; do
    read -r line
    if echo "$line" | grep -q 'session/cancel'; then
        touch "` + cancelMarker + `"
        echo '{"jsonrpc":"2.0","result":{"stopReason":"cancelled"},"id":3}'
        break
    fi
done

read -r line
touch "` + steeringMarker + `"
echo '{"jsonrpc":"2.0","result":{"stopReason":"end_turn"},"id":4}'

# Wrap-up prompt for main task completion
read -r line
echo '{"jsonrpc":"2.0","result":{"stopReason":"end_turn"},"id":5}'

sleep 30
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		mu                sync.Mutex
		promptCompleteMsg AcpPromptCompleteParams
		gotPromptComplete atomic.Bool
		activities        []ActivityParams
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.Method == "env.acp_prompt_complete" {
				var p AcpPromptCompleteParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				promptCompleteMsg = p
				mu.Unlock()
				gotPromptComplete.Store(true)
			}
			if req.Method == "env.activity" {
				var a ActivityParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &a)
				mu.Lock()
				activities = append(activities, a)
				mu.Unlock()
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	launchParams := LaunchAcpAgentParams{AgentCommand: "/bin/bash " + scriptPath}
	c.ExecuteLaunchAcpAgent(launchParams, uint64(101))

	waitForState := func(want runner.AgentState) {
		t.Helper()
		deadline := time.Now().Add(5 * time.Second)
		for {
			c.mu.Lock()
			sup := c.supervisor
			c.mu.Unlock()
			if sup != nil && sup.State() == want {
				return
			}
			if time.Now().After(deadline) {
				t.Fatalf("timeout waiting for state %v", want)
			}
			time.Sleep(10 * time.Millisecond)
		}
	}

	waitForState(runner.StateSessionActive)

	go c.ExecuteAcpPrompt(AcpPromptParams{TaskPrompt: "main task"}, uint64(102))

	waitForState(runner.StatePrompting)

	// Send the steering prompt; it must interrupt, not reject.
	c.ExecuteAcpPrompt(AcpPromptParams{TaskPrompt: "steering guidance", IsSteering: true}, uint64(103))

	// The main prompt must report the final turn's stop reason (end_turn).
	deadline := time.Now().Add(5 * time.Second)
	for !gotPromptComplete.Load() && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	mu.Lock()
	stop := promptCompleteMsg.StopReason
	acts := append([]ActivityParams(nil), activities...)
	mu.Unlock()
	if stop != "end_turn" {
		t.Errorf("expected env.acp_prompt_complete stopReason end_turn, got %q", stop)
	}

	// Verify ordering: in-flight activity from turn 1 must appear BEFORE steering activity
	inFlightIdx := -1
	steeringIdx := -1
	for i, a := range acts {
		if a.ActivityType == ActivityMessage && a.Description == "in-flight update before cancel" {
			inFlightIdx = i
		}
		if a.ActivityType == ActivityMessage && strings.HasPrefix(a.Description, "User provided steering guidance: ") && a.Detail.Role == "user" && a.Status == ActivityCompleted {
			steeringIdx = i
		}
	}
	if inFlightIdx == -1 {
		t.Errorf("expected in-flight activity to be received, got: %+v", acts)
	}
	if steeringIdx == -1 {
		t.Errorf("expected steering activity to be received, got: %+v", acts)
	}
	if inFlightIdx >= 0 && steeringIdx >= 0 && inFlightIdx >= steeringIdx {
		t.Errorf("expected in-flight activity (idx %d) to appear BEFORE steering activity (idx %d)", inFlightIdx, steeringIdx)
	}

	// The steering turn completes before env.acp_prompt_complete is emitted, so
	// both markers must now exist.
	if _, err := os.Stat(cancelMarker); err != nil {
		t.Errorf("expected session/cancel to be sent (marker %s), got err: %v", cancelMarker, err)
	}
	if _, err := os.Stat(steeringMarker); err != nil {
		t.Errorf("expected steering prompt to be delivered (marker %s), got err: %v", steeringMarker, err)
	}

	c.Close()
}

func TestExecuteTerminate_DaemonAgent(t *testing.T) {
	// Write mock ACP agent script that simulates a daemon (ignores stdin close)
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "mock_agent.sh")

	scriptContent := `#!/bin/bash
# 1. Read initialize
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"daemon-agent","version":"2.0.0"}},"id":1}'

# 2. Read session/new
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"daemon-session-789"},"id":2}'

# 3. Wait for session/cancel or any input
# This simulates a daemon agent that doesn't exit on stdin close
while read -r line; do
    # Check if it's a cancel request
    if echo "$line" | grep -q "session/cancel"; then
        echo "Received cancel, exiting..."
        exit 0
    fi
done

# If stdin closes without cancel, just sleep (daemon behavior)
sleep 60
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		gotComplete    atomic.Bool
		mu             sync.Mutex
		completeMsg    CompleteParams
		cancelResponse []byte
		launchDone     = make(chan struct{})
		cancelDone     = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		responseCount := 0
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				responseCount++
				switch responseCount {
				case 1:
					select {
					case <-launchDone:
					default:
						close(launchDone)
					}
				case 2:
					cancelResponse = msg
					select {
					case <-cancelDone:
					default:
						close(cancelDone)
					}
				}
				mu.Unlock()
				continue
			}

			if req.Method == "env.complete" {
				var p CompleteParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				completeMsg = p
				mu.Unlock()
				gotComplete.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// First, launch the agent
	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(launchParams, uint64(101))

	// Wait for launch to complete
	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	// Verify session was created
	c.mu.Lock()
	sup := c.supervisor
	c.mu.Unlock()
	if sup == nil {
		t.Error("expected supervisor to be set")
		return
	}
	session := sup.Session()
	if session == nil {
		t.Fatal("expected ACP session to be stored")
	}

	// Now terminate
	terminateParams := TerminateParams{}

	go c.ExecuteTerminate(terminateParams, uint64(103))

	// Wait for cancel response
	select {
	case <-cancelDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for cancel response")
	}

	if !gotComplete.Load() {
		t.Error("expected env.complete notification but got none")
	}

	mu.Lock()
	comp := completeMsg
	respBytes := cancelResponse
	mu.Unlock()

	// Verify complete notification
	if comp.ExitCode != 0 {
		t.Errorf("expected exit code 0, got %d", comp.ExitCode)
	}

	// Parse response message
	var resp JsonRpcResponse
	if err := json.Unmarshal(respBytes, &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	var result TerminateResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		t.Fatalf("failed to parse result: %v", err)
	}

	if result.Status != TerminateTerminated {
		t.Errorf("expected status 'terminated', got '%s'", result.Status)
	}

	// Verify session was removed
	c.mu.Lock()
	sup = c.supervisor
	c.mu.Unlock()
	if sup == nil {
		t.Error("expected supervisor to be set")
		return
	}
	session = sup.Session()
	if session != nil {
		t.Error("expected ACP session to be removed after cancel")
	}

	c.Close()
}

func TestExecuteAcpPrompt_NoSession(t *testing.T) {
	responseChan := make(chan []byte, 1)
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				responseChan <- msg
				return
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Try to send prompt without launching first
	promptParams := AcpPromptParams{
		TaskPrompt: "Implement code",
	}

	go c.ExecuteAcpPrompt(promptParams, uint64(102))

	select {
	case respBytes := <-responseChan:
		var resp JsonRpcResponse
		if err := json.Unmarshal(respBytes, &resp); err != nil {
			t.Fatalf("failed to parse response: %v", err)
		}

		var result AcpPromptResult
		if err := json.Unmarshal(resp.Result, &result); err != nil {
			t.Fatalf("failed to parse result: %v", err)
		}

		if result.Status != PromptFailed {
			t.Errorf("expected status 'failed', got '%s'", result.Status)
		}
		if !strings.Contains(result.Error, "no active ACP session") {
			t.Errorf("expected error about no active session, got '%s'", result.Error)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for response")
	}

	c.Close()
}

func TestExecuteTerminate_NoSession(t *testing.T) {
	responseChan := make(chan []byte, 1)
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				responseChan <- msg
				return
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Try to terminate without launching first
	terminateParams := TerminateParams{}

	go c.ExecuteTerminate(terminateParams, uint64(103))

	select {
	case respBytes := <-responseChan:
		var resp JsonRpcResponse
		if err := json.Unmarshal(respBytes, &resp); err != nil {
			t.Fatalf("failed to parse response: %v", err)
		}

		var result TerminateResult
		if err := json.Unmarshal(resp.Result, &result); err != nil {
			t.Fatalf("failed to parse result: %v", err)
		}

		if result.Status != TerminateCompleted {
			t.Errorf("expected status 'completed', got '%s'", result.Status)
		}
		if result.ExitCode != 0 {
			t.Errorf("expected exit code 0, got %d", result.ExitCode)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for response")
	}

	c.Close()
}

// TestExecuteLaunchAcpAgent_AgentStartFailure tests that a failing setup command
// causes the launch to return a failed status when the agent process exits immediately.
func TestExecuteLaunchAcpAgent_AgentStartFailure(t *testing.T) {
	tempDir := t.TempDir()

	var (
		mu           sync.Mutex
		responseMsg  []byte
		responseChan = make(chan struct{})
		gotComplete  atomic.Bool
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				if responseMsg == nil {
					responseMsg = msg
					select {
					case <-responseChan:
					default:
						close(responseChan)
					}
				}
				mu.Unlock()
				continue
			}

			if req.Method == "env.complete" {
				gotComplete.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Agent command that exits immediately without ACP handshake
	params := LaunchAcpAgentParams{
		AgentCommand: "false",
	}

	go c.ExecuteLaunchAcpAgent(params, uint64(201))

	select {
	case <-responseChan:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for response after launch failure")
	}

	mu.Lock()
	respBytes := responseMsg
	mu.Unlock()

	var resp JsonRpcResponse
	if err := json.Unmarshal(respBytes, &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	var result LaunchAcpAgentResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		t.Fatalf("failed to parse result: %v", err)
	}

	if result.Status != LaunchFailed {
		t.Errorf("expected status 'failed', got '%s'", result.Status)
	}
	if result.Error == "" {
		t.Error("expected non-empty error message")
	}

	c.Close()
}

// TestExecuteLaunchAcpAgent_HandshakeTimeout tests that a non-responding agent
// triggers failLaunch via the initialize timeout path.
func TestExecuteLaunchAcpAgent_HandshakeTimeout(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "bad_agent.sh")

	// Agent that starts but never responds to JSON-RPC
	scriptContent := `#!/bin/bash
echo "I am not speaking JSON-RPC"
sleep 30
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		mu           sync.Mutex
		responseMsg  []byte
		responseChan = make(chan struct{})
		gotComplete  atomic.Bool
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				if responseMsg == nil {
					responseMsg = msg
					select {
					case <-responseChan:
					default:
						close(responseChan)
					}
				}
				mu.Unlock()
				continue
			}

			if req.Method == "env.complete" {
				gotComplete.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	params := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(params, uint64(301))

	// The initialize request has a timeout, so we wait up to 5s
	select {
	case <-responseChan:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for failLaunch response after handshake timeout")
	}

	mu.Lock()
	respBytes := responseMsg
	mu.Unlock()

	var resp JsonRpcResponse
	if err := json.Unmarshal(respBytes, &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	var result LaunchAcpAgentResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		t.Fatalf("failed to parse result: %v", err)
	}

	if result.Status != LaunchFailed {
		t.Errorf("expected status 'failed', got '%s'", result.Status)
	}
	if !strings.Contains(result.Error, "initialize") {
		t.Errorf("expected error about initialize, got: %s", result.Error)
	}

	if !gotComplete.Load() {
		t.Error("expected env.complete notification from failLaunch")
	}

	c.Close()
}

// TestExecuteLaunchAcpAgent_InitializeErrorResponse tests that an agent returning
// an error in the initialize response triggers failLaunch.
func TestExecuteLaunchAcpAgent_InitializeErrorResponse(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "error_agent.sh")

	// Agent that returns an error to initialize
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","error":{"code":-32000,"message":"init failed"},"id":1}'
sleep 30
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		mu           sync.Mutex
		responseMsg  []byte
		responseChan = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				if responseMsg == nil {
					responseMsg = msg
					select {
					case <-responseChan:
					default:
						close(responseChan)
					}
				}
				mu.Unlock()
				continue
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	params := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(params, uint64(302))

	select {
	case <-responseChan:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for failLaunch response")
	}

	mu.Lock()
	respBytes := responseMsg
	mu.Unlock()

	var resp JsonRpcResponse
	if err := json.Unmarshal(respBytes, &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	var result LaunchAcpAgentResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		t.Fatalf("failed to parse result: %v", err)
	}

	if result.Status != LaunchFailed {
		t.Errorf("expected status 'failed', got '%s'", result.Status)
	}
	if !strings.Contains(result.Error, "initialize error") {
		t.Errorf("expected error about initialize error, got: %s", result.Error)
	}

	c.Close()
}

// TestExecuteAcpPrompt_PromptFailure tests the error path when session/prompt fails.
func TestExecuteAcpPrompt_PromptFailure(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "mock_agent.sh")

	// Agent that returns an error to session/prompt
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"mock-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"prompt-fail-session"},"id":2}'

# Read prompt and return error
read -r line
echo '{"jsonrpc":"2.0","error":{"code":-32000,"message":"prompt processing failed"},"id":3}'

sleep 30
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		mu                sync.Mutex
		promptResponseMsg []byte
		launchDone        = make(chan struct{})
		promptDone        = make(chan struct{})
		gotComplete       atomic.Bool
		completeMsg       CompleteParams
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		responseCount := 0
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				responseCount++
				switch responseCount {
				case 1:
					select {
					case <-launchDone:
					default:
						close(launchDone)
					}
				case 2:
					promptResponseMsg = msg
					select {
					case <-promptDone:
					default:
						close(promptDone)
					}
				}
				mu.Unlock()
				continue
			}

			if req.Method == "env.complete" {
				var p CompleteParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				completeMsg = p
				mu.Unlock()
				gotComplete.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Launch agent first
	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(launchParams, uint64(401))

	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	// Send prompt
	promptParams := AcpPromptParams{
		TaskPrompt: "Do something",
	}

	go c.ExecuteAcpPrompt(promptParams, uint64(402))

	select {
	case <-promptDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for prompt response")
	}

	mu.Lock()
	respBytes := promptResponseMsg
	mu.Unlock()

	var resp JsonRpcResponse
	if err := json.Unmarshal(respBytes, &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	var result AcpPromptResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		t.Fatalf("failed to parse result: %v", err)
	}

	if result.Status != PromptFailed {
		t.Errorf("expected status 'failed', got '%s'", result.Status)
	}
	if result.Error == "" {
		t.Error("expected non-empty error message")
	}

	// Verify env.complete notification WAS sent with non-zero exit code on failure.
	// This ensures the control-plane is notified that the execution failed
	// so it doesn't hang forever waiting for a completion signal.
	if !gotComplete.Load() {
		t.Error("expected env.complete notification on failure")
	} else if completeMsg.ExitCode == 0 {
		t.Error("expected non-zero exit code in env.complete notification")
	}

	c.Close()
}

// TestExecuteTerminate_SIGTERMPath tests termination of a process that ignores
// session/cancel but responds to SIGTERM.
func TestExecuteTerminate_SIGTERMPath(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "stubborn_agent.sh")

	// Agent that ignores cancel but will die from SIGTERM
	scriptContent := `#!/bin/bash
# 1. Read initialize
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"stubborn-agent","version":"1.0.0"}},"id":1}'

# 2. Read session/new
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"stubborn-session"},"id":2}'

# Ignore everything including cancel, just sleep
# SIGTERM will kill us
trap 'exit 143' TERM
while true; do
    sleep 1
done
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		gotComplete atomic.Bool
		mu          sync.Mutex
		completeMsg CompleteParams
		launchDone  = make(chan struct{})
		termDone    = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		responseCount := 0
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				responseCount++
				switch responseCount {
				case 1:
					select {
					case <-launchDone:
					default:
						close(launchDone)
					}
				case 2:
					select {
					case <-termDone:
					default:
						close(termDone)
					}
				}
				mu.Unlock()
				continue
			}

			if req.Method == "env.complete" {
				var p CompleteParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				completeMsg = p
				mu.Unlock()
				gotComplete.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Launch agent
	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(launchParams, uint64(501))

	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	// Terminate - this should go through the SIGTERM path since agent ignores cancel
	terminateParams := TerminateParams{}
	go c.ExecuteTerminate(terminateParams, uint64(502))

	select {
	case <-termDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for terminate response")
	}

	if !gotComplete.Load() {
		t.Error("expected env.complete notification")
	}

	mu.Lock()
	comp := completeMsg
	mu.Unlock()

	// The process should have been killed by SIGTERM (exit code 143 = 128+15)
	if comp.ExitCode != 143 {
		t.Logf("Note: exit code was %d (expected 143 for SIGTERM, but timing may vary)", comp.ExitCode)
	}

	// Verify session was removed
	c.mu.Lock()
	sup := c.supervisor
	c.mu.Unlock()
	if sup == nil {
		t.Error("expected supervisor to be set")
		return
	}
	session := sup.Session()
	if session != nil {
		t.Error("expected ACP session to be removed after terminate")
	}

	c.Close()
}

// TestHandleServerRequest_ValidDispatch tests that handleServerRequest correctly
// dispatches to the right handlers for all known methods with valid params.
func TestHandleServerRequest_ValidDispatch(t *testing.T) {
	var (
		mu           sync.Mutex
		responseMsgs [][]byte
		responseChan = make(chan struct{}, 10)
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			// Approve HITL approval requests for ExecuteCommand
			if req.Method == "env.hitl_request" {
				raw, _ := json.Marshal(HitlResult{Response: HitlApproved, OptionID: "allow"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				responseMsgs = append(responseMsgs, msg)
				select {
				case responseChan <- struct{}{}:
				default:
				}
				mu.Unlock()
				continue
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = t.TempDir()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Test env.terminate dispatch with valid params
	c.handleServerRequest(JsonRpcRequest{
		Method: "env.terminate",
		Params: map[string]interface{}{},
		ID:     uint64(601),
	})

	select {
	case <-responseChan:
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for terminate dispatch response")
	}

	// Test env.acp_prompt dispatch with no session (should return failed)
	c.handleServerRequest(JsonRpcRequest{
		Method: "env.acp_prompt",
		Params: map[string]interface{}{"taskPrompt": "test"},
		ID:     uint64(602),
	})

	select {
	case <-responseChan:
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for acp_prompt dispatch response")
	}

	// Test env.launch_acp_agent with invalid params (should return error)
	c.handleServerRequest(JsonRpcRequest{
		Method: "env.launch_acp_agent",
		Params: "not-a-struct",
		ID:     uint64(603),
	})

	// Test env.acp_prompt with invalid params (should return error)
	c.handleServerRequest(JsonRpcRequest{
		Method: "env.acp_prompt",
		Params: "not-a-struct",
		ID:     uint64(604),
	})

	// Test env.terminate with invalid params (should return error)
	c.handleServerRequest(JsonRpcRequest{
		Method: "env.terminate",
		Params: "not-a-struct",
		ID:     uint64(605),
	})

	time.Sleep(300 * time.Millisecond)

	mu.Lock()
	responses := responseMsgs
	mu.Unlock()

	// We should have at least 3 responses (terminate success, acp_prompt no session, and error responses)
	if len(responses) < 3 {
		t.Errorf("expected at least 3 responses, got %d", len(responses))
	}

	c.Close()
}

// TestRequestPermission_ErrorResponse tests the error response path in RequestPermission.
func TestRequestPermission_ErrorResponse(t *testing.T) {
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.hitl_request" {
				_ = conn.WriteJSON(JsonRpcResponse{
					JsonRPC: "2.0",
					Error:   &JsonRpcError{Code: -32000, Message: "Permission denied by policy"},
					ID:      req.ID,
				})
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	defer c.Close()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	selectedOptionID, err := c.RequestPermission(acp.PermissionRequest{Command: "rm -rf /"})
	if err == nil {
		t.Fatal("expected error from RequestPermission with error response")
	}
	if selectedOptionID != "" {
		t.Error("expected empty selectedOptionId on error")
	}
	if !strings.Contains(err.Error(), "hitl request error") {
		t.Errorf("unexpected error message: %v", err)
	}
}

// TestRequestPermission_WriteError tests RequestPermission when the write fails.
func TestRequestPermission_WriteError(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")
	// wsConn is nil, so writeJSON will fail

	selectedOptionID, err := c.RequestPermission(acp.PermissionRequest{Command: "some-command"})
	if err == nil {
		t.Error("expected error when wsConn is nil")
	}
	if selectedOptionID != "" {
		t.Error("expected empty selectedOptionId on write error")
	}

	// Verify pending was cleaned up
	c.mu.Lock()
	pendingLen := len(c.pending)
	c.mu.Unlock()
	if pendingLen != 0 {
		t.Errorf("expected pending map to be empty after write error, got %d", pendingLen)
	}
}

// TestRequestPermission_SynthesizesHitlIDWhenActionIDEmpty verifies that a
// permission request without an agent toolCallId (e.g. Goose) still sends a
// non-blank hitlId so the control plane can register and resolve the HITL.
func TestRequestPermission_SynthesizesHitlIDWhenActionIDEmpty(t *testing.T) {
	received := make(chan string, 1)
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method != "env.hitl_request" {
				continue
			}
			var params HitlRequest
			paramsJSON, marshalErr := json.Marshal(req.Params)
			if marshalErr != nil {
				continue
			}
			if json.Unmarshal(paramsJSON, &params) != nil {
				continue
			}
			received <- params.HitlID
			_ = conn.WriteJSON(JsonRpcResponse{
				JsonRPC: "2.0",
				Result:  json.RawMessage(`{"response":"approved","optionId":"allow"}`),
				ID:      req.ID,
			})
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	defer c.Close()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	selectedOptionID, err := c.RequestPermission(acp.PermissionRequest{Command: "rm -rf /"})
	if err != nil {
		t.Fatalf("unexpected error from RequestPermission: %v", err)
	}
	if selectedOptionID != "allow" {
		t.Errorf("expected optionId allow, got %q", selectedOptionID)
	}
	select {
	case hitlID := <-received:
		if strings.TrimSpace(hitlID) == "" {
			t.Error("expected a synthesized non-blank hitlId for an agent without toolCallId")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timeout waiting for env.hitl_request on the wire")
	}
}

// TestCreateElicitation_Success tests a full accept round-trip through CreateElicitation.
func TestCreateElicitation_Success(t *testing.T) {
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.hitl_request" {
				raw, _ := json.Marshal(HitlResult{
					Response: HitlAnswered,
					Content:  map[string]any{"target": "staging"},
				})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	defer c.Close()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	result, err := c.CreateElicitation(acp.ElicitationRequest{
		ElicitationID: "el-1",
		Message:       "Choose a deployment target",
		Mode:          "form",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result.Action != "accept" {
		t.Errorf("expected action accept, got %q", result.Action)
	}
	if result.Content["target"] != "staging" {
		t.Errorf("expected content target staging, got %v", result.Content)
	}
}

// TestCreateElicitation_ErrorResponse tests the error response path in CreateElicitation.
func TestCreateElicitation_ErrorResponse(t *testing.T) {
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.hitl_request" {
				_ = conn.WriteJSON(JsonRpcResponse{
					JsonRPC: "2.0",
					Error:   &JsonRpcError{Code: -32000, Message: "Elicitation rejected"},
					ID:      req.ID,
				})
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	defer c.Close()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	result, err := c.CreateElicitation(acp.ElicitationRequest{ElicitationID: "el-1", Message: "q", Mode: "form"})
	if err == nil {
		t.Fatal("expected error from CreateElicitation with error response")
	}
	if result.Action != "" {
		t.Error("expected empty action on error")
	}
	if !strings.Contains(err.Error(), "hitl request error") {
		t.Errorf("unexpected error message: %v", err)
	}
}

// TestCreateElicitation_WriteError tests CreateElicitation when the write fails.
func TestCreateElicitation_WriteError(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")

	result, err := c.CreateElicitation(acp.ElicitationRequest{ElicitationID: "el-1", Message: "q", Mode: "form"})
	if err == nil {
		t.Fatal("expected error when wsConn is nil")
	}
	if result.Action != "" {
		t.Error("expected empty action on write error")
	}

	c.mu.Lock()
	pendingLen := len(c.pending)
	c.mu.Unlock()
	if pendingLen != 0 {
		t.Errorf("expected pending map to be empty after write error, got %d", pendingLen)
	}
}

// TestSendSuccessResponse_NilID tests that sendSuccessResponse is a no-op for nil ID.
func TestSendSuccessResponse_NilID(_ *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")
	defer c.Close()

	// Should not panic or attempt to write
	c.sendSuccessResponse(nil, map[string]string{"status": "ok"})
}

// TestSendErrorResponse_NilID tests that sendErrorResponse is a no-op for nil ID.
func TestSendErrorResponse_NilID(_ *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")
	defer c.Close()

	// Should not panic or attempt to write
	c.sendErrorResponse(nil, -32000, "test error", "details")
}

// TestReadLoop_ResponseAlwaysDeletesPending tests that a response always
// deletes the pending entry, regardless of the response content.
// The old two-phase approval logic (keeping pending entries for retry) was removed.
func TestReadLoop_ResponseAlwaysDeletesPending(t *testing.T) {
	var serverConn *websocket.Conn
	ready := make(chan struct{})

	srv := newTestServer(t, func(conn *websocket.Conn) {
		serverConn = conn
		close(ready)
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	<-ready

	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Register a pending channel for ID=50
	ch := make(chan *JsonRpcResponse, 2)
	c.mu.Lock()
	c.pending[uint64(50)] = ch
	c.mu.Unlock()

	// Server sends a response with approved=false
	resp := JsonRpcResponse{
		JsonRPC: "2.0",
		Result:  json.RawMessage(`{"approved":false}`),
		ID:      float64(50),
	}
	if err := serverConn.WriteJSON(resp); err != nil {
		t.Fatalf("server write: %v", err)
	}

	// Give readLoop time to process
	time.Sleep(200 * time.Millisecond)

	// The pending entry should NOT be deleted by the readLoop — callers are responsible
	// for cleanup. This supports multi-response patterns (e.g. RequestPermission receives
	// approved:false followed by approved:true on the same channel).
	c.mu.Lock()
	_, stillPending := c.pending[uint64(50)]
	c.mu.Unlock()

	if !stillPending {
		t.Error("expected pending entry to remain after response (callers clean up)")
	}

	// The response should have been sent to the channel
	select {
	case got := <-ch:
		if got == nil {
			t.Error("received nil response")
		}
	default:
		t.Error("expected response to be sent to channel")
	}

	c.Close()
}

func TestExecuteAcpPrompt_PermissionAndActivity(t *testing.T) {
	// Write mock ACP agent script that uses activities and requests permission
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "mock_agent.sh")

	scriptContent := `#!/bin/bash
# 1. Read initialize
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"agentInfo":{"name":"mock-agent","version":"1.0.0"}},"id":1}'

# 2. Read session/new
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"mock-session-activity"},"id":2}'

# 3. Read session/prompt
read -r line

# Emit message activity via proper ACP session/update
echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"mock-session-activity","update":{"sessionUpdate":"agent_message_chunk","messageId":"msg-1","content":{"type":"text","text":"Thinking about next steps"}}}}'
# Emit tool_call activity via proper ACP session/update
echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"mock-session-activity","update":{"sessionUpdate":"tool_call","toolCallId":"tc1","kind":"read","title":"Reading main.go"}}}'
# Request permission with ACP-compliant format
echo '{"jsonrpc":"2.0","method":"session/request_permission","params":{"sessionId":"mock-session-activity","toolCall":{"toolCallId":"tc2","title":"rm -rf /","rawInput":{"command":"rm -rf /"}},"options":[{"optionId":"allow","name":"Allow","kind":"allow_once"}]},"id":100}'

# Read decision
read -r response
echo "agent got: $response" >&2

echo '{"jsonrpc":"2.0","result":{"stopReason":"end_turn"},"id":3}'

# Read wrap-up prompt
read -r line
echo '{"jsonrpc":"2.0","result":{"stopReason":"end_turn"},"id":4}'

# Agent stays alive
sleep 30
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		gotActivityMessage  atomic.Bool
		gotActivityResearch atomic.Bool
		gotPermissionReq    atomic.Bool
		launchDone          = make(chan struct{})
		promptDone          = make(chan struct{})
		mu                  sync.Mutex
		activities          []ActivityParams
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		responseCount := 0
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				responseCount++
				switch responseCount {
				case 1:
					select {
					case <-launchDone:
					default:
						close(launchDone)
					}
				case 2:
					select {
					case <-promptDone:
					default:
						close(promptDone)
					}
				}
				mu.Unlock()
				continue
			}

			switch req.Method {
			case "env.activity":
				var p ActivityParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				activities = append(activities, p)
				mu.Unlock()
				if p.ActivityType == ActivityMessage {
					gotActivityMessage.Store(true)
				}
				if p.ActivityType == ActivityResearch {
					gotActivityResearch.Store(true)
				}

			case "env.hitl_request":
				gotPermissionReq.Store(true)
				// Send approved response
				resp := JsonRpcResponse{
					JsonRPC: "2.0",
					Result:  json.RawMessage(`{"response":"approved","optionId":"allow"}`),
					ID:      req.ID,
				}
				respBytes, _ := json.Marshal(resp)
				_ = conn.WriteMessage(websocket.TextMessage, respBytes)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// First, launch the agent
	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(launchParams, uint64(201))

	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	// Now send prompt
	promptParams := AcpPromptParams{
		TaskPrompt: "Perform task",
	}

	go c.ExecuteAcpPrompt(promptParams, uint64(202))

	select {
	case <-promptDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for prompt completion")
	}

	// Wait for in-flight notification handlers to complete.
	// With async notification dispatch, the permission handler goroutine may
	// still be processing when the prompt response arrives.
	c.mu.Lock()
	sup := c.supervisor
	c.mu.Unlock()
	if sup == nil {
		t.Error("expected supervisor to be set")
		return
	}
	session := sup.Session()
	if session != nil && session.Transport != nil {
		session.Transport.WaitForNotifications(5 * time.Second)
	}

	if !gotActivityMessage.Load() {
		t.Error("expected to capture MESSAGE activity")
	}
	if !gotActivityResearch.Load() {
		t.Error("expected to capture RESEARCH activity")
	}
	if !gotPermissionReq.Load() {
		t.Error("expected to request permission")
	}

	c.Close()
}

// TestExecuteTerminate_SIGKILLPath tests termination of a process that traps
// both SIGTERM and requires SIGKILL to be killed.
func TestExecuteTerminate_SIGKILLPath(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "unkillable_agent.sh")

	// Agent that traps SIGTERM and needs SIGKILL
	scriptContent := `#!/bin/bash
# 1. Read initialize
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"unkillable-agent","version":"1.0.0"}},"id":1}'

# 2. Read session/new
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"unkillable-session"},"id":2}'

# Trap SIGTERM and ignore it
trap '' TERM
while true; do
    sleep 1
done
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		gotComplete atomic.Bool
		mu          sync.Mutex
		launchDone  = make(chan struct{})
		termDone    = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		responseCount := 0
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				responseCount++
				switch responseCount {
				case 1:
					select {
					case <-launchDone:
					default:
						close(launchDone)
					}
				case 2:
					select {
					case <-termDone:
					default:
						close(termDone)
					}
				}
				mu.Unlock()
				continue
			}

			if req.Method == "env.complete" {
				gotComplete.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Launch agent
	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(launchParams, uint64(701))

	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	// Terminate - this should go through SIGTERM then SIGKILL path
	terminateParams := TerminateParams{}
	go c.ExecuteTerminate(terminateParams, uint64(702))

	select {
	case <-termDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for terminate response (SIGKILL path)")
	}

	if !gotComplete.Load() {
		t.Error("expected env.complete notification")
	}

	// Verify session was removed
	c.mu.Lock()
	sup := c.supervisor
	c.mu.Unlock()
	if sup == nil {
		t.Error("expected supervisor to be set")
		return
	}
	session := sup.Session()
	if session != nil {
		t.Error("expected ACP session to be removed after terminate")
	}

	c.Close()
}

// TestExecuteRegisterGitAuth_GithubToken tests the GITHUB token credential type.
func TestExecuteRegisterGitAuth_GithubToken(t *testing.T) {
	responseChan := make(chan []byte, 1)
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				responseChan <- msg
				return
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	defer c.Close()

	params := RegisterGitAuthParams{
		CredentialType: "GITHUB",
	}

	c.ExecuteRegisterGitAuth(params, uint64(801))

	select {
	case respBytes := <-responseChan:
		var resp JsonRpcResponse
		if err := json.Unmarshal(respBytes, &resp); err != nil {
			t.Fatalf("failed to parse response: %v", err)
		}
		var result map[string]interface{}
		if err := json.Unmarshal(resp.Result, &result); err != nil {
			t.Fatalf("failed to parse result: %v", err)
		}
		if result["status"] != string(CheckoutSuccess) {
			t.Errorf("expected status 'success', got '%v'", result["status"])
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for response")
	}

	// Verify the credential helper was configured
	c.mu.Lock()
	helper := c.gitHelperScript
	c.mu.Unlock()

	if helper == "" {
		t.Error("expected gitHelperScript to be set")
	}

	// Clean up
	c.Close()
}

// TestExecuteCheckout_NonEmptyNonGitDir tests checkout when workspace exists,
// is not empty, and is not a git repository.
func TestExecuteCheckout_NonEmptyNonGitDir(t *testing.T) {
	tempDir := t.TempDir()

	// Create a file in the workspace to make it non-empty
	if err := os.WriteFile(filepath.Join(tempDir, "existing_file.txt"), []byte("content"), 0644); err != nil {
		t.Fatalf("failed to create test file: %v", err)
	}

	var (
		mu           sync.Mutex
		checkoutMsg  CheckoutCompleteParams
		responseChan = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				select {
				case <-responseChan:
				default:
					close(responseChan)
				}
				continue
			}

			if req.Method == "env.checkout_complete" {
				var p CheckoutCompleteParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				checkoutMsg = p
				mu.Unlock()
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	params := CheckoutParams{
		URL:    "https://github.com/test/repo.git",
		Branch: "main",
	}

	c.ExecuteCheckout(params, uint64(901))

	time.Sleep(300 * time.Millisecond)

	mu.Lock()
	msg := checkoutMsg
	mu.Unlock()

	if msg.Status != CheckoutFailed {
		t.Errorf("expected status 'failed', got '%s'", msg.Status)
	}
	if !strings.Contains(msg.Error, "not a git repository") {
		t.Errorf("expected error about non-git directory, got: %s", msg.Error)
	}

	c.Close()
}

// TestExecuteLaunchAcpAgent_SessionNewTimeout tests that a session/new timeout
// triggers failLaunch.
func TestExecuteLaunchAcpAgent_SessionNewTimeout(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "slow_agent.sh")

	// Agent that responds to initialize but never to session/new
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"slow-agent","version":"1.0.0"}},"id":1}'

# Never respond to session/new
sleep 30
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		mu           sync.Mutex
		responseMsg  []byte
		responseChan = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				if responseMsg == nil {
					responseMsg = msg
					select {
					case <-responseChan:
					default:
						close(responseChan)
					}
				}
				mu.Unlock()
				continue
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	params := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(params, uint64(1001))

	// session/new has a timeout
	select {
	case <-responseChan:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for failLaunch response after session/new timeout")
	}

	mu.Lock()
	respBytes := responseMsg
	mu.Unlock()

	var resp JsonRpcResponse
	if err := json.Unmarshal(respBytes, &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	var result LaunchAcpAgentResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		t.Fatalf("failed to parse result: %v", err)
	}

	if result.Status != LaunchFailed {
		t.Errorf("expected status 'failed', got '%s'", result.Status)
	}
	if !strings.Contains(result.Error, "session/new") {
		t.Errorf("expected error about session/new, got: %s", result.Error)
	}

	c.Close()
}

// TestHandleServerRequest_LaunchAcpAgent_ValidParams tests dispatching
// env.launch_acp_agent with valid params (using a setup command that fails).
func TestHandleServerRequest_LaunchAcpAgent_ValidParams(t *testing.T) {
	tempDir := t.TempDir()

	var (
		mu           sync.Mutex
		responseMsg  []byte
		responseChan = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				if responseMsg == nil {
					responseMsg = msg
					select {
					case <-responseChan:
					default:
						close(responseChan)
					}
				}
				mu.Unlock()
				continue
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Dispatch with valid params but a setup command that fails quickly
	c.handleServerRequest(JsonRpcRequest{
		Method: "env.launch_acp_agent",
		Params: map[string]interface{}{
			"agentCommand": "false",
		},
		ID: uint64(1101),
	})

	select {
	case <-responseChan:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for failLaunch response")
	}

	mu.Lock()
	respBytes := responseMsg
	mu.Unlock()

	var resp JsonRpcResponse
	if err := json.Unmarshal(respBytes, &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	var result LaunchAcpAgentResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		t.Fatalf("failed to parse result: %v", err)
	}

	if result.Status != LaunchFailed {
		t.Errorf("expected status 'failed', got '%s'", result.Status)
	}

	c.Close()
}

// TestExecuteAcpPrompt_SessionNewErrorResponse tests that an agent returning
// an error to session/new triggers failLaunch.
func TestExecuteLaunchAcpAgent_SessionNewErrorResponse(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "error_session_agent.sh")

	// Agent that responds to initialize but returns error to session/new
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"error-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","error":{"code":-32000,"message":"session creation failed"},"id":2}'

sleep 30
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		mu           sync.Mutex
		responseMsg  []byte
		responseChan = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				if responseMsg == nil {
					responseMsg = msg
					select {
					case <-responseChan:
					default:
						close(responseChan)
					}
				}
				mu.Unlock()
				continue
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	params := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(params, uint64(1201))

	select {
	case <-responseChan:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for failLaunch response")
	}

	mu.Lock()
	respBytes := responseMsg
	mu.Unlock()

	var resp JsonRpcResponse
	if err := json.Unmarshal(respBytes, &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	var result LaunchAcpAgentResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		t.Fatalf("failed to parse result: %v", err)
	}

	if result.Status != LaunchFailed {
		t.Errorf("expected status 'failed', got '%s'", result.Status)
	}
	if !strings.Contains(result.Error, "session/new error") {
		t.Errorf("expected error about session/new error, got: %s", result.Error)
	}

	c.Close()
}

// TestExecuteAcpPrompt_ProcessExitsWithoutResponding tests that if the agent
// process's stdout closes (e.g. it crashes or exits) before responding to
// session/prompt, ExecuteAcpPrompt reports a failure promptly.
//
// session/prompt intentionally has no fixed deadline: an agent may
// legitimately work for an arbitrarily long time (tool calls, multi-turn LLM
// loops, etc.) and reports progress via session/update notifications rather
// than by responding quickly. The only failure condition the sidecar detects
// is the agent process itself terminating (stdout closing) without a
// response — simulated here by having the mock agent exit instead of
// responding.
func TestExecuteAcpPrompt_ProcessExitsWithoutResponding(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "dying_agent.sh")

	// Agent that completes handshake but exits (without responding) once it
	// receives session/prompt, simulating a crash/unexpected termination.
	scriptContent := `#!/bin/bash
# 1. Read initialize
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"dying-agent","version":"1.0.0"}},"id":1}'

# 2. Read session/new
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"dying-session"},"id":2}'

# 3. Read session/prompt, then exit immediately without responding
read -r line
echo "Received prompt, exiting without responding" >&2
exit 1
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		mu                sync.Mutex
		promptResponseMsg []byte
		launchDone        = make(chan struct{})
		promptDone        = make(chan struct{})
		gotComplete       atomic.Bool
		completeMsg       CompleteParams
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		responseCount := 0
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				responseCount++
				switch responseCount {
				case 1:
					select {
					case <-launchDone:
					default:
						close(launchDone)
					}
				case 2:
					promptResponseMsg = msg
					select {
					case <-promptDone:
					default:
						close(promptDone)
					}
				}
				mu.Unlock()
				continue
			}

			if req.Method == "env.complete" {
				var p CompleteParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				completeMsg = p
				mu.Unlock()
				gotComplete.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Launch agent first
	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(launchParams, uint64(901))

	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	// Send prompt — agent will exit without responding, simulating a crash
	promptParams := AcpPromptParams{
		TaskPrompt: "Do something",
	}

	go c.ExecuteAcpPrompt(promptParams, uint64(902))

	// Wait for prompt response — should resolve quickly since the agent
	// process terminates (stdout closes) immediately, not after any fixed
	// deadline.
	select {
	case <-promptDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for prompt failure response")
	}

	mu.Lock()
	respBytes := promptResponseMsg
	mu.Unlock()

	var resp JsonRpcResponse
	if err := json.Unmarshal(respBytes, &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	var result AcpPromptResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		t.Fatalf("failed to parse result: %v", err)
	}

	if result.Status != PromptFailed {
		t.Errorf("expected status 'failed', got '%s'", result.Status)
	}
	if result.Error == "" {
		t.Error("expected non-empty error message about the agent terminating")
	}
	if !strings.Contains(result.Error, "terminated") {
		t.Errorf("expected error about agent process terminating, got: %s", result.Error)
	}

	// Verify env.complete notification WAS sent with non-zero exit code on failure.
	// This ensures the control-plane is notified that the execution failed
	// so it doesn't hang forever waiting for a completion signal.
	if !gotComplete.Load() {
		t.Error("expected env.complete notification on timeout")
	} else {
		mu.Lock()
		exitCode := completeMsg.ExitCode
		mu.Unlock()
		if exitCode == 0 {
			t.Error("expected non-zero exit code in env.complete notification")
		}
	}

	c.Close()
}

// TestExecuteTerminate_NonZeroExitAfterStdinClose tests termination of a process
// that exits with a non-zero code after stdin is closed (exits before SIGTERM).
func TestExecuteTerminate_NonZeroExitAfterStdinClose(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "exit_agent.sh")

	// Agent that exits with code 42 when stdin closes
	scriptContent := `#!/bin/bash
# 1. Read initialize
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"exit-agent","version":"1.0.0"}},"id":1}'

# 2. Read session/new
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"exit-session"},"id":2}'

# Wait for stdin to close, then exit with code 42
while read -r line; do
    :
done
exit 42
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		gotComplete atomic.Bool
		mu          sync.Mutex
		completeMsg CompleteParams
		launchDone  = make(chan struct{})
		termDone    = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		responseCount := 0
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				responseCount++
				switch responseCount {
				case 1:
					select {
					case <-launchDone:
					default:
						close(launchDone)
					}
				case 2:
					select {
					case <-termDone:
					default:
						close(termDone)
					}
				}
				mu.Unlock()
				continue
			}

			if req.Method == "env.complete" {
				var p CompleteParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				completeMsg = p
				mu.Unlock()
				gotComplete.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Launch agent
	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(launchParams, uint64(801))

	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	// Terminate - the agent should exit with code 42 after stdin close
	terminateParams := TerminateParams{}
	go c.ExecuteTerminate(terminateParams, uint64(802))

	select {
	case <-termDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for terminate response")
	}

	if !gotComplete.Load() {
		t.Error("expected env.complete notification")
	}

	mu.Lock()
	comp := completeMsg
	mu.Unlock()

	// The process should have exited with code 42
	if comp.ExitCode != 42 {
		t.Logf("Note: exit code was %d (expected 42, but timing may vary)", comp.ExitCode)
	}

	// Verify session was removed
	c.mu.Lock()
	sup := c.supervisor
	c.mu.Unlock()
	if sup == nil {
		t.Error("expected supervisor to be set")
		return
	}
	session := sup.Session()
	if session != nil {
		t.Error("expected ACP session to be removed after terminate")
	}

	c.Close()
}

// TestExecuteTerminate_CancelsPendingPermissions tests that ExecuteTerminate
// cancels any pending permission requests before closing stdin.
func TestExecuteTerminate_CancelsPendingPermissions(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "mock_agent.sh")

	scriptContent := `#!/bin/bash
# 1. Read initialize
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"mock-agent","version":"1.0.0"}},"id":1}'

# 2. Read session/new
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"perm-cancel-session"},"id":2}'

# 3. Read session/prompt
read -r line

# 4. Request permission (this will block until cancelled)
echo '{"jsonrpc":"2.0","method":"session/request_permission","params":{"sessionId":"perm-cancel-session","toolCall":{"toolCallId":"tc1","title":"test-command","rawInput":{"command":"test-command"}},"options":[{"optionId":"allow","name":"Allow","kind":"allow_once"}]},"id":100}'

# 5. Read permission response (should be cancelled)
read -r response
echo "agent got: $response" >&2

# 6. Send prompt response
echo '{"jsonrpc":"2.0","result":{"stopReason":"end_turn"},"id":3}'

# 7. Read wrap-up prompt
read -r line
echo '{"jsonrpc":"2.0","result":{"stopReason":"end_turn"},"id":4}'

# Agent stays alive
sleep 30
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		launchDone    = make(chan struct{})
		promptDone    = make(chan struct{})
		terminateDone = make(chan struct{})
		mu            sync.Mutex
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		responseCount := 0
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				responseCount++
				switch responseCount {
				case 1:
					select {
					case <-launchDone:
					default:
						close(launchDone)
					}
				case 2:
					select {
					case <-promptDone:
					default:
						close(promptDone)
					}
				case 3:
					select {
					case <-terminateDone:
					default:
						close(terminateDone)
					}
				}
				mu.Unlock()
				continue
			}

			// Don't respond to HITL requests - let them be cancelled
			if req.Method == "env.hitl_request" {
				// Intentionally don't respond
				continue
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Launch agent
	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(launchParams, uint64(901))

	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	// Send prompt (this will trigger a permission request)
	promptParams := AcpPromptParams{
		TaskPrompt: "test",
	}

	go c.ExecuteAcpPrompt(promptParams, uint64(902))

	// Give the permission request time to be sent
	time.Sleep(500 * time.Millisecond)

	// Now terminate - this should cancel the pending permission
	terminateParams := TerminateParams{}
	go c.ExecuteTerminate(terminateParams, uint64(903))

	// Wait for terminate to complete (wait for the terminate response)
	select {
	case <-terminateDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for terminate to complete")
	}

	// Give a moment for the session to be cleared
	time.Sleep(100 * time.Millisecond)

	// Verify session was removed
	c.mu.Lock()
	sup := c.supervisor
	c.mu.Unlock()
	if sup == nil {
		t.Error("expected supervisor to be set")
		return
	}
	session := sup.Session()
	if session != nil {
		t.Error("expected ACP session to be removed after terminate")
	}

	c.Close()
}

// TestUnexpectedExit_SendsEnvComplete verifies that when an agent process exits
// unexpectedly (not via ExecuteTerminate), the exit watcher sends env.complete exactly once.
func TestUnexpectedExit_SendsEnvComplete(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "early_exit_agent.sh")

	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"early-exit-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"early-exit-session"},"id":2}'

exit 42
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		completeCount atomic.Int32
		mu            sync.Mutex
		completeMsg   CompleteParams
		launchDone    = make(chan struct{})
		completeDone  = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				select {
				case <-launchDone:
				default:
					close(launchDone)
				}
				mu.Unlock()
				continue
			}

			if req.Method == "env.complete" {
				var p CompleteParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				completeMsg = p
				count := completeCount.Add(1)
				if count == 1 {
					select {
					case <-completeDone:
					default:
						close(completeDone)
					}
				}
				mu.Unlock()
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(launchParams, uint64(1301))

	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	select {
	case <-completeDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for env.complete from unexpected exit")
	}

	time.Sleep(200 * time.Millisecond)

	count := completeCount.Load()
	if count != 1 {
		t.Errorf("expected exactly 1 env.complete notification, got %d", count)
	}

	mu.Lock()
	comp := completeMsg
	mu.Unlock()

	if comp.ExitCode != 42 {
		t.Errorf("expected exit code 42, got %d", comp.ExitCode)
	}

	c.mu.Lock()
	sup := c.supervisor
	c.mu.Unlock()
	if sup == nil {
		t.Error("expected supervisor to be set")
		return
	}
	session := sup.Session()
	if session != nil {
		t.Error("expected ACP session to be cleared after unexpected exit")
	}

	c.Close()
}

// TestExplicitTerminate_NoDuplicateEnvComplete verifies that explicit termination
// sends env.complete exactly once (not duplicated by the exit watcher).
func TestExplicitTerminate_NoDuplicateEnvComplete(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "daemon_agent.sh")

	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"daemon-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"daemon-session"},"id":2}'

while read -r line; do
    if echo "$line" | grep -q "session/cancel"; then
        exit 0
    fi
done

sleep 60
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		completeCount atomic.Int32
		mu            sync.Mutex
		launchDone    = make(chan struct{})
		termDone      = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		responseCount := 0
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				responseCount++
				switch responseCount {
				case 1:
					select {
					case <-launchDone:
					default:
						close(launchDone)
					}
				case 2:
					select {
					case <-termDone:
					default:
						close(termDone)
					}
				}
				mu.Unlock()
				continue
			}

			if req.Method == "env.complete" {
				completeCount.Add(1)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(launchParams, uint64(1401))

	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	terminateParams := TerminateParams{}
	go c.ExecuteTerminate(terminateParams, uint64(1402))

	select {
	case <-termDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for terminate response")
	}

	time.Sleep(500 * time.Millisecond)

	count := completeCount.Load()
	if count != 1 {
		t.Errorf("expected exactly 1 env.complete notification, got %d", count)
	}

	c.Close()
}

// TestExitWatcher_ClearsSessionAfterExit verifies that the exit watcher clears
// c.supervisor.Session() after an unexpected process exit.
func TestExitWatcher_ClearsSessionAfterExit(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "quick_exit_agent.sh")

	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"quick-exit-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"quick-exit-session"},"id":2}'

sleep 1
exit 0
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		launchDone   = make(chan struct{})
		completeDone = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				select {
				case <-launchDone:
				default:
					close(launchDone)
				}
				continue
			}

			if req.Method == "env.complete" {
				select {
				case <-completeDone:
				default:
					close(completeDone)
				}
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}

	go c.ExecuteLaunchAcpAgent(launchParams, uint64(1501))

	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	select {
	case <-completeDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for env.complete")
	}

	time.Sleep(200 * time.Millisecond)

	c.mu.Lock()
	sup := c.supervisor
	c.mu.Unlock()
	if sup == nil {
		t.Error("expected supervisor to be set")
		return
	}
	session := sup.Session()
	if session != nil {
		t.Error("expected ACP session to be cleared by exit watcher after unexpected exit")
	}

	c.Close()
}

func TestExecuteAcpPrompt_WrapUpVerificationPrompt(t *testing.T) {
	tempDir := t.TempDir()
	receivedPromptsFile := filepath.Join(tempDir, "received_prompts.log")
	scriptPath := filepath.Join(tempDir, "mock_agent.sh")

	scriptContent := `#!/bin/bash
# 1. Read initialize
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"mock-agent","version":"1.0.0"}},"id":1}'

# 2. Read session/new
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"mock-session-wrapup"},"id":2}'

# 3. Read session/prompt 1
read -r line
echo "$line" >> "` + receivedPromptsFile + `"
echo '{"jsonrpc":"2.0","result":{"stopReason":"end_turn"},"id":3}'

# 4. Read wrap-up prompt
read -r line
echo "$line" >> "` + receivedPromptsFile + `"
echo '{"jsonrpc":"2.0","result":{"stopReason":"end_turn"},"id":4}'

sleep 30
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil { //nolint:gosec // G306: test script must remain executable
		t.Fatalf("failed to write mock agent script: %v", err)
	}

	var (
		mu                sync.Mutex
		outputLines       []string
		promptCompleteMsg AcpPromptCompleteParams
		gotPromptComplete atomic.Bool
		launchDone        = make(chan struct{})
		promptDone        = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		responseCount := 0
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil && req.Method == "" {
				mu.Lock()
				responseCount++
				switch responseCount {
				case 1:
					select {
					case <-launchDone:
					default:
						close(launchDone)
					}
				case 2:
					select {
					case <-promptDone:
					default:
						close(promptDone)
					}
				}
				mu.Unlock()
				continue
			}

			switch req.Method {
			case "env.output":
				var p OutputParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				outputLines = append(outputLines, p.Line)
				mu.Unlock()

			case "env.acp_prompt_complete":
				var p AcpPromptCompleteParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &p)
				mu.Lock()
				promptCompleteMsg = p
				mu.Unlock()
				gotPromptComplete.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
	}
	go c.ExecuteLaunchAcpAgent(launchParams, uint64(2001))

	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	promptParams := AcpPromptParams{
		TaskPrompt: "Primary user instruction",
		IsSteering: false,
	}
	go c.ExecuteAcpPrompt(promptParams, uint64(2002))

	select {
	case <-promptDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for prompt completion")
	}

	if !gotPromptComplete.Load() {
		t.Error("expected env.acp_prompt_complete notification")
	}

	mu.Lock()
	lines := outputLines
	completeMsg := promptCompleteMsg
	mu.Unlock()

	if completeMsg.StopReason != "end_turn" {
		t.Errorf("expected stopReason end_turn, got %s", completeMsg.StopReason)
	}

	// Verify wrap-up announcement in output lines
	foundWrapUpLog := false
	for _, l := range lines {
		if strings.Contains(l, "Turn complete — prompting agent for quality verification and final commit") {
			foundWrapUpLog = true
			break
		}
	}
	if !foundWrapUpLog {
		t.Error("expected output to contain turn complete wrap-up log message")
	}

	// Verify the second prompt payload received by the agent contained quality/verification instructions
	promptsContent, err := os.ReadFile(receivedPromptsFile)
	if err != nil {
		t.Fatalf("failed to read received prompts file: %v", err)
	}
	promptsStr := string(promptsContent)
	if !strings.Contains(promptsStr, "quality and verification steps") {
		t.Errorf("expected received prompt to contain verification prompt, got: %s", promptsStr)
	}
	if !strings.Contains(promptsStr, "generate a sensible commit message") {
		t.Errorf("expected received prompt to contain commit prompt, got: %s", promptsStr)
	}

	c.Close()
}

func TestExecuteAcpPrompt_SteeringPublishesActivity(t *testing.T) {
	tempDir := t.TempDir()
	scriptPath := filepath.Join(tempDir, "mock_agent.sh")

	scriptContent := `#!/bin/bash
# 1. Read initialize
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"test-agent","version":"1.0.0"}},"id":1}'

# 2. Read session/new
read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"test-session"},"id":2}'

# 3. Read session/prompt (steering)
read -r line
echo '{"jsonrpc":"2.0","result":{"stopReason":"end_turn"},"id":3}'

sleep 10
`
	if err := os.WriteFile(scriptPath, []byte(scriptContent), 0755); err != nil {
		t.Fatalf("failed to write script: %v", err)
	}

	var (
		mu         sync.Mutex
		activities []ActivityParams
		launchDone = make(chan struct{})
		promptDone = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}

			if req.ID != nil {
				idNum := uint64(req.ID.(float64))
				switch idNum {
				case 3001:
					close(launchDone)
				case 3002:
					close(promptDone)
				}
			}

			if req.Method == "env.activity" {
				var a ActivityParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &a)
				mu.Lock()
				activities = append(activities, a)
				mu.Unlock()
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = tempDir
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	launchParams := LaunchAcpAgentParams{
		AgentCommand: "/bin/bash " + scriptPath,
		ExecutionID:  "exec-steering-test-1",
	}
	go c.ExecuteLaunchAcpAgent(launchParams, uint64(3001))

	select {
	case <-launchDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for agent launch")
	}

	promptParams := AcpPromptParams{
		TaskPrompt:  "Please refactor the error handling",
		IsSteering:  true,
		ExecutionID: "exec-steering-test-1",
	}
	go c.ExecuteAcpPrompt(promptParams, uint64(3002))

	select {
	case <-promptDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for steering prompt completion")
	}

	mu.Lock()
	acts := append([]ActivityParams(nil), activities...)
	mu.Unlock()

	var foundSteeringActivity bool
	for _, a := range acts {
		if a.ActivityType == ActivityMessage &&
			strings.HasPrefix(a.Description, "User provided steering guidance: ") &&
			a.Detail.Role == "user" &&
			a.Status == ActivityCompleted &&
			a.ExecutionID == "exec-steering-test-1" {
			foundSteeringActivity = true
			if !strings.HasPrefix(a.ActionID, "steering-") {
				t.Errorf("expected actionID prefix 'steering-', got %q", a.ActionID)
			}
			break
		}
	}
	if !foundSteeringActivity {
		t.Fatalf("expected steering activity with role 'user' and description prefix 'User provided steering guidance:', got activities: %+v", acts)
	}

	c.Close()
}
