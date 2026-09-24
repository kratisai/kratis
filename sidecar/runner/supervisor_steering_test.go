package runner

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"kratis-connector/acp"
)

// TestSteerInterruptsInFlightTurn verifies that a steering prompt sent while a
// turn is in flight interrupts it (session/cancel) and delivers the steering as
// a follow-up session/prompt turn, with the caller reporting the final turn's
// stop reason rather than the interrupted turn's cancelled marker.
func TestSteerInterruptsInFlightTurn(t *testing.T) {
	tempDir := t.TempDir()
	markerPath := filepath.Join(tempDir, "steering-delivered")
	scriptPath := filepath.Join(tempDir, "mock_agent.sh")
	scriptContent := `#!/bin/bash
read -r line
echo '{"jsonrpc":"2.0","result":{"protocolVersion":1,"serverInfo":{"name":"test-agent","version":"1.0.0"}},"id":1}'

read -r line
echo '{"jsonrpc":"2.0","result":{"sessionId":"test-session"},"id":2}'

read -r line
while true; do
    read -r line
    if echo "$line" | grep -q 'session/cancel'; then
        echo '{"jsonrpc":"2.0","result":{"stopReason":"cancelled"},"id":3}'
        break
    fi
done

read -r line
touch "` + markerPath + `"
echo '{"jsonrpc":"2.0","result":{"stopReason":"end_turn"},"id":4}'

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

	if _, err := sup.Launch(cmd); err != nil {
		t.Fatalf("Launch failed: %v", err)
	}

	mainDone := make(chan *PromptResult, 1)
	mainErr := make(chan error, 1)
	go func() {
		result, err := sup.Prompt("main task", "")
		mainDone <- result
		mainErr <- err
	}()

	// Wait until the main prompt is actively being processed.
	deadline := time.Now().Add(2 * time.Second)
	for sup.State() != StatePrompting && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if sup.State() != StatePrompting {
		t.Fatalf("expected state Prompting, got %v", sup.State())
	}

	// A steering prompt must interrupt the in-flight turn, not reject or queue
	// behind a long-running turn.
	steerResult, steerErr := sup.Steer("steering guidance", "")
	if steerErr != nil {
		t.Fatalf("steering prompt returned error: %v", steerErr)
	}
	if steerResult.StopReason != "" {
		t.Errorf("expected steering prompt to report empty stopReason, got %q", steerResult.StopReason)
	}
	if steerResult.Error != nil {
		t.Errorf("expected steering prompt to have nil error, got %v", steerResult.Error)
	}

	select {
	case result := <-mainDone:
		if result == nil || result.StopReason != "end_turn" {
			t.Errorf("expected main prompt to report the final turn's stopReason end_turn, got %+v", result)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timeout waiting for main prompt to complete")
	}

	select {
	case err := <-mainErr:
		if err != nil {
			t.Errorf("main prompt returned error: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timeout waiting for main prompt error channel")
	}

	// The steering prompt must have been delivered as a follow-up turn.
	if _, err := os.Stat(markerPath); err != nil {
		t.Errorf("expected steering prompt to be delivered (marker %s), got err: %v", markerPath, err)
	}

	// Verify the steering MESSAGE activity was dispatched to eventSink.
	sink.mu.Lock()
	acts := append([]acp.Activity(nil), sink.activities...)
	sink.mu.Unlock()

	var foundSteeringActivity bool
	for _, a := range acts {
		if a.ActivityType == acp.ActivityTypeMessage &&
			strings.HasPrefix(a.Description, "User provided steering guidance: ") &&
			strings.Contains(a.Description, "steering guidance") &&
			a.Detail.Role == "user" && a.Status == acp.ActivityCompleted {
			foundSteeringActivity = true
			break
		}
	}
	if !foundSteeringActivity {
		t.Errorf("expected steering activity to be emitted to eventSink, got activities: %+v", acts)
	}

	_, _ = sup.Terminate()
}
