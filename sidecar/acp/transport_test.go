package acp

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// discardWriteCloser is a no-op io.WriteCloser used as a stub stdin for tests
// that need SendRequest/SendRequestUntilClosed to succeed writing a request
// without caring about the written bytes.
type discardWriteCloser struct{}

func (discardWriteCloser) Write(p []byte) (int, error) { return len(p), nil }
func (discardWriteCloser) Close() error                { return nil }

// TestReadStdout_NotificationDoesNotBlockResponse verifies that a slow notification
// handler does not prevent the transport from routing responses to pending requests.
// This is the core fix for the Qwen deadlock: when the agent sends a notification
// (e.g. session/request_permission) while the sidecar is waiting for a response
// (e.g. to session/prompt), the reader must continue processing lines so the
// response can be routed to the pending channel.
func TestReadStdout_NotificationDoesNotBlockResponse(t *testing.T) {
	// Create pipes to simulate agent stdout
	pr, pw := io.Pipe()

	var notificationReceived atomic.Bool
	var notificationStarted = make(chan struct{})
	var notificationRelease = make(chan struct{})

	transport := NewAcpTransport(
		nil, // stdin not needed for this test
		pr,
		nil, // stderr not needed
		func(_ string, _ map[string]interface{}, _ interface{}, _ bool) {
			notificationReceived.Store(true)
			close(notificationStarted)
			// Simulate a slow handler (e.g. permission request waiting for control plane)
			<-notificationRelease
		},
		nil,
	)

	transport.Start()

	// Write a notification first, then a response
	go func() {
		// Send a notification (has method, no id means no response expected — but
		// in our case the notification HAS an id, making it a request)
		notif := map[string]interface{}{
			"jsonrpc": "2.0",
			"method":  "session/request_permission",
			"params":  map[string]interface{}{},
			"id":      float64(100),
		}
		notifBytes, _ := json.Marshal(notif)
		_, _ = fmt.Fprintf(pw, "%s\n", notifBytes)

		// Small delay to ensure notification is being processed
		time.Sleep(50 * time.Millisecond)

		// Now send a response to a pending request
		resp := map[string]interface{}{
			"jsonrpc": "2.0",
			"result":  map[string]interface{}{"stopReason": "complete"},
			"id":      float64(3),
		}
		respBytes, _ := json.Marshal(resp)
		_, _ = fmt.Fprintf(pw, "%s\n", respBytes)
	}()

	// Register a pending request for id=3
	ch := make(chan map[string]interface{}, 1)
	transport.pendingMu.Lock()
	transport.pending[float64(3)] = ch
	transport.pendingMu.Unlock()

	// Wait for the notification handler to start (proves it's running in a goroutine)
	select {
	case <-notificationStarted:
		// Good — notification handler started
	case <-time.After(2 * time.Second):
		t.Fatal("notification handler did not start within timeout")
	}

	// The notification handler is now blocked. The response should still be routed.
	select {
	case resp := <-ch:
		result, ok := resp["result"].(map[string]interface{})
		if !ok {
			t.Fatalf("expected result map, got %T", resp["result"])
		}
		if result["stopReason"] != "complete" {
			t.Errorf("expected stopReason 'complete', got %v", result["stopReason"])
		}
	case <-time.After(2 * time.Second):
		t.Fatal("response was not routed while notification handler was blocked — DEADLOCK")
	}

	// Clean up
	close(notificationRelease)
	_ = pw.Close()
	<-transport.StdoutDone()
}

// TestReadStdout_MultipleConcurrentNotifications verifies that multiple notifications
// are dispatched concurrently and don't block each other.
func TestReadStdout_MultipleConcurrentNotifications(t *testing.T) {
	pr, pw := io.Pipe()

	var mu sync.Mutex
	var handlerStartOrder []string
	allStarted := make(chan struct{}, 3)
	release := make(chan struct{})

	transport := NewAcpTransport(
		nil, pr, nil,
		func(method string, _ map[string]interface{}, _ interface{}, _ bool) {
			mu.Lock()
			handlerStartOrder = append(handlerStartOrder, method)
			mu.Unlock()
			allStarted <- struct{}{}
			<-release
		},
		nil,
	)

	transport.Start()

	go func() {
		for i := 0; i < 3; i++ {
			notif := map[string]interface{}{
				"jsonrpc": "2.0",
				"method":  fmt.Sprintf("notification/%d", i),
				"params":  map[string]interface{}{},
				"id":      float64(200 + i),
			}
			notifBytes, _ := json.Marshal(notif)
			_, _ = fmt.Fprintf(pw, "%s\n", notifBytes)
		}
	}()

	// All three handlers should start concurrently (not sequentially)
	timeout := time.After(3 * time.Second)
	for i := 0; i < 3; i++ {
		select {
		case <-allStarted:
		case <-timeout:
			mu.Lock()
			started := len(handlerStartOrder)
			mu.Unlock()
			t.Fatalf("only %d/3 notification handlers started within timeout — handlers are serialized", started)
		}
	}

	close(release)
	_ = pw.Close()
	<-transport.StdoutDone()
}

// TestReadStdout_ResponseRoutedDuringSlowPermissionHandler is an integration-style
// test that simulates the exact Qwen deadlock scenario:
// 1. Sidecar sends session/prompt (id=3) and blocks waiting for response
// 2. Agent sends session/request_permission (id=100) — handler blocks
// 3. Agent sends the session/prompt response (id=3)
// 4. Verify: session/prompt response is received despite permission handler blocking
func TestReadStdout_ResponseRoutedDuringSlowPermissionHandler(t *testing.T) {
	pr, pw := io.Pipe()

	permissionHandlerStarted := make(chan struct{})
	permissionHandlerRelease := make(chan struct{})

	transport := NewAcpTransport(
		nil, pr, nil,
		func(method string, _ map[string]interface{}, _ interface{}, _ bool) {
			if method == "session/request_permission" {
				close(permissionHandlerStarted)
				<-permissionHandlerRelease
			}
		},
		nil,
	)

	transport.Start()

	// Register pending request for session/prompt (id=3)
	promptCh := make(chan map[string]interface{}, 1)
	transport.pendingMu.Lock()
	transport.pending[float64(3)] = promptCh
	transport.pendingMu.Unlock()

	// Simulate agent sending messages
	go func() {
		// 1. Agent sends permission request
		permReq := map[string]interface{}{
			"jsonrpc": "2.0",
			"method":  "session/request_permission",
			"params": map[string]interface{}{
				"toolCall": map[string]interface{}{
					"title": "Run: ls -la",
				},
			},
			"id": float64(100),
		}
		permBytes, _ := json.Marshal(permReq)
		_, _ = fmt.Fprintf(pw, "%s\n", permBytes)

		// Wait for permission handler to start (it will block)
		time.Sleep(100 * time.Millisecond)

		// 2. Agent sends session/prompt response (this should be routed despite
		// the permission handler still being blocked)
		promptResp := map[string]interface{}{
			"jsonrpc": "2.0",
			"result":  map[string]interface{}{"stopReason": "complete"},
			"id":      float64(3),
		}
		promptBytes, _ := json.Marshal(promptResp)
		_, _ = fmt.Fprintf(pw, "%s\n", promptBytes)
	}()

	// Wait for permission handler to start
	select {
	case <-permissionHandlerStarted:
	case <-time.After(2 * time.Second):
		t.Fatal("permission handler did not start")
	}

	// The prompt response should arrive even though permission handler is blocked
	select {
	case resp := <-promptCh:
		result, _ := resp["result"].(map[string]interface{})
		if result["stopReason"] != "complete" {
			t.Errorf("expected stopReason 'complete', got %v", result["stopReason"])
		}
	case <-time.After(2 * time.Second):
		t.Fatal("DEADLOCK: session/prompt response was not routed while permission handler was blocked")
	}

	// Clean up
	close(permissionHandlerRelease)
	_ = pw.Close()
	<-transport.StdoutDone()
}

// TestWaitForNotifications verifies that WaitForNotifications blocks until
// all in-flight handlers complete.
func TestWaitForNotifications(t *testing.T) {
	pr, pw := io.Pipe()

	handlerDone := make(chan struct{})

	transport := NewAcpTransport(
		nil, pr, nil,
		func(_ string, _ map[string]interface{}, _ interface{}, _ bool) {
			time.Sleep(100 * time.Millisecond)
			close(handlerDone)
		},
		nil,
	)

	transport.Start()

	go func() {
		notif := map[string]interface{}{
			"jsonrpc": "2.0",
			"method":  "test/notification",
			"id":      float64(1),
		}
		notifBytes, _ := json.Marshal(notif)
		_, _ = fmt.Fprintf(pw, "%s\n", notifBytes)
		_ = pw.Close()
	}()

	// Wait for stdout reader to finish
	<-transport.StdoutDone()

	// WaitForNotifications should block until handler completes
	transport.WaitForNotifications(5 * time.Second)

	select {
	case <-handlerDone:
		// Good — handler completed before WaitForNotifications returned
	default:
		t.Fatal("WaitForNotifications returned before handler completed")
	}
}

// TestSendRequestUntilClosed_ReturnsResponseImmediately verifies the normal
// case: a response arriving promptly is returned without waiting on any
// deadline.
func TestSendRequestUntilClosed_ReturnsResponseImmediately(t *testing.T) {
	stdinR, stdinW := io.Pipe()
	pr, pw := io.Pipe()

	transport := NewAcpTransport(stdinW, pr, nil, nil, nil)
	transport.Start()

	go func() {
		// Block until the request is written to stdin. dispatchRequest registers
		// the pending response channel before writing, so once the request is
		// observable the response is guaranteed to be routed (writing the
		// response first would race the registration and drop it).
		if _, err := bufio.NewReader(stdinR).ReadString('\n'); err != nil {
			return
		}
		resp := map[string]interface{}{
			"jsonrpc": "2.0",
			"result":  map[string]interface{}{"stopReason": "end_turn"},
			"id":      float64(1),
		}
		respBytes, _ := json.Marshal(resp)
		_, _ = fmt.Fprintf(pw, "%s\n", respBytes)
	}()

	resultCh := make(chan map[string]interface{}, 1)
	errCh := make(chan error, 1)
	go func() {
		resp, err := transport.SendRequestUntilClosed("session/prompt", map[string]interface{}{})
		if err != nil {
			errCh <- err
			return
		}
		resultCh <- resp
	}()

	select {
	case resp := <-resultCh:
		result, _ := resp["result"].(map[string]interface{})
		if result["stopReason"] != "end_turn" {
			t.Errorf("expected stopReason 'end_turn', got %v", result["stopReason"])
		}
	case err := <-errCh:
		t.Fatalf("unexpected error: %v", err)
	case <-time.After(2 * time.Second):
		t.Fatal("SendRequestUntilClosed did not return in time")
	}

	_ = pw.Close()
	<-transport.StdoutDone()
}

// TestSendRequestUntilClosed_WaitsBeyondFixedDeadline verifies that
// SendRequestUntilClosed has no deadline: a response arriving after a delay
// longer than any bounded-request timeout would allow is still returned
// successfully rather than timing out.
func TestSendRequestUntilClosed_WaitsBeyondFixedDeadline(t *testing.T) {
	pr, pw := io.Pipe()

	transport := NewAcpTransport(discardWriteCloser{}, pr, nil, nil, nil)
	transport.Start()

	delay := 300 * time.Millisecond
	go func() {
		time.Sleep(delay)
		resp := map[string]interface{}{
			"jsonrpc": "2.0",
			"result":  map[string]interface{}{"stopReason": "end_turn"},
			"id":      float64(1),
		}
		respBytes, _ := json.Marshal(resp)
		_, _ = fmt.Fprintf(pw, "%s\n", respBytes)
	}()

	resultCh := make(chan map[string]interface{}, 1)
	errCh := make(chan error, 1)
	go func() {
		resp, err := transport.SendRequestUntilClosed("session/prompt", map[string]interface{}{})
		if err != nil {
			errCh <- err
			return
		}
		resultCh <- resp
	}()

	select {
	case resp := <-resultCh:
		result, _ := resp["result"].(map[string]interface{})
		if result["stopReason"] != "end_turn" {
			t.Errorf("expected stopReason 'end_turn', got %v", result["stopReason"])
		}
	case err := <-errCh:
		t.Fatalf("unexpected error: %v — request should not time out", err)
	case <-time.After(2 * time.Second):
		t.Fatal("SendRequestUntilClosed did not return after delayed response")
	}

	_ = pw.Close()
	<-transport.StdoutDone()
}

// TestSendRequest_UsesSuppliedTimeout verifies that a response arriving within
// the caller-supplied timeout is returned successfully.
func TestSendRequest_UsesSuppliedTimeout(t *testing.T) {
	pr, pw := io.Pipe()

	transport := NewAcpTransport(discardWriteCloser{}, pr, nil, nil, nil)
	transport.Start()

	delay := 300 * time.Millisecond
	go func() {
		time.Sleep(delay)
		resp := map[string]interface{}{
			"jsonrpc": "2.0",
			"result":  map[string]interface{}{"protocolVersion": 1},
			"id":      float64(1),
		}
		respBytes, _ := json.Marshal(resp)
		_, _ = fmt.Fprintf(pw, "%s\n", respBytes)
	}()

	resultCh := make(chan map[string]interface{}, 1)
	errCh := make(chan error, 1)
	go func() {
		resp, err := transport.SendRequest("initialize", map[string]interface{}{}, 2*time.Second)
		if err != nil {
			errCh <- err
			return
		}
		resultCh <- resp
	}()

	select {
	case resp := <-resultCh:
		result, _ := resp["result"].(map[string]interface{})
		if result["protocolVersion"] != float64(1) {
			t.Errorf("expected protocolVersion 1, got %v", result["protocolVersion"])
		}
	case err := <-errCh:
		t.Fatalf("unexpected error: %v — response arrived within the supplied timeout", err)
	case <-time.After(2 * time.Second):
		t.Fatal("SendRequest did not return after delayed response")
	}

	_ = pw.Close()
	<-transport.StdoutDone()
}

// TestSendRequest_RespectsSuppliedTimeout verifies that a response arriving
// after the supplied timeout is treated as a timeout error.
func TestSendRequest_RespectsSuppliedTimeout(t *testing.T) {
	pr, pw := io.Pipe()

	transport := NewAcpTransport(discardWriteCloser{}, pr, nil, nil, nil)
	transport.Start()

	// Agent never responds within the short supplied timeout.
	shortTimeout := 100 * time.Millisecond
	start := time.Now()
	resp, err := transport.SendRequest("initialize", map[string]interface{}{}, shortTimeout)
	elapsed := time.Since(start)

	if err == nil {
		t.Fatalf("expected timeout error, got response: %v", resp)
	}
	if !strings.Contains(err.Error(), "timeout waiting for response to initialize") {
		t.Errorf("unexpected error: %v", err)
	}
	if elapsed < shortTimeout {
		t.Errorf("expected to wait at least %v, returned after %v", shortTimeout, elapsed)
	}

	_ = pw.Close()
	<-transport.StdoutDone()
}

// TestSendRequestUntilClosed_ReturnsErrorWhenStdoutCloses verifies that if
// the agent process's stdout closes (simulating a crash/exit) before a
// response arrives, SendRequestUntilClosed returns an error promptly instead
// of waiting forever.
func TestSendRequestUntilClosed_ReturnsErrorWhenStdoutCloses(t *testing.T) {
	pr, pw := io.Pipe()

	transport := NewAcpTransport(discardWriteCloser{}, pr, nil, nil, nil)
	transport.Start()

	resultCh := make(chan map[string]interface{}, 1)
	errCh := make(chan error, 1)
	go func() {
		resp, err := transport.SendRequestUntilClosed("session/prompt", map[string]interface{}{})
		if err != nil {
			errCh <- err
			return
		}
		resultCh <- resp
	}()

	// Simulate the agent process dying: close stdout without ever responding.
	_ = pw.Close()

	select {
	case err := <-errCh:
		if err == nil {
			t.Fatal("expected an error when stdout closes without a response")
		}
	case resp := <-resultCh:
		t.Fatalf("expected an error, got a response: %v", resp)
	case <-time.After(2 * time.Second):
		t.Fatal("SendRequestUntilClosed did not return after stdout closed")
	}
}

// TestReadStdout_HandlesLinesLargerThanDefaultScannerBuffer verifies that a
// single ACP line larger than bufio.Scanner's default 64KB buffer limit is
// read correctly instead of silently ending the stdout reader.
//
// This is the root cause of the "silent Qwen deadlock": when readStdout used
// bufio.NewScanner without an explicit larger buffer, a single oversized
// line (larger than bufio.MaxScanTokenSize) caused Scan() to return false
// with bufio.ErrTooLong. Because the loop never checked scanner.Err(), this
// failure was completely silent: stdoutDone closed, no further agent output
// was ever processed, and callers waiting on a response (e.g. session/prompt)
// saw it as "the agent process terminated" even though the agent was still
// alive and working.
func TestReadStdout_HandlesLinesLargerThanDefaultScannerBuffer(t *testing.T) {
	pr, pw := io.Pipe()

	var outputLines []string
	var mu sync.Mutex

	transport := NewAcpTransport(
		nil, pr, nil,
		nil,
		func(line string, _ string) {
			mu.Lock()
			outputLines = append(outputLines, line)
			mu.Unlock()
		},
	)

	transport.Start()

	// Build a notification whose single line exceeds the old default 64KB
	// scanner buffer (bufio.MaxScanTokenSize), simulating an ACP message
	// carrying large embedded content (e.g. file contents in a tool call).
	largeContent := strings.Repeat("x", 100*1024) // 100KB > 64KB default limit
	notif := map[string]interface{}{
		"jsonrpc": "2.0",
		"method":  "session/update",
		"params":  map[string]interface{}{"content": largeContent},
	}
	notifBytes, _ := json.Marshal(notif)

	// Register a pending request for a response that arrives AFTER the
	// large line, proving the reader keeps working past it.
	respCh := make(chan map[string]interface{}, 1)
	transport.pendingMu.Lock()
	transport.pending[float64(1)] = respCh
	transport.pendingMu.Unlock()

	go func() {
		_, _ = fmt.Fprintf(pw, "%s\n", notifBytes)

		resp := map[string]interface{}{
			"jsonrpc": "2.0",
			"result":  map[string]interface{}{"stopReason": "end_turn"},
			"id":      float64(1),
		}
		respBytes, _ := json.Marshal(resp)
		_, _ = fmt.Fprintf(pw, "%s\n", respBytes)
		_ = pw.Close()
	}()

	select {
	case resp := <-respCh:
		result, _ := resp["result"].(map[string]interface{})
		if result["stopReason"] != "end_turn" {
			t.Errorf("expected stopReason 'end_turn', got %v", result["stopReason"])
		}
	case <-time.After(2 * time.Second):
		t.Fatal("response after large line was never routed — reader likely died on the oversized line")
	}

	<-transport.StdoutDone()
}

// TestReadStdout_JsonRpcLinesNeverReachOutputSink verifies JSON-RPC envelopes
// are transport, not content — they mustn't be passed to env.output.
func TestReadStdout_JsonRpcLinesNeverReachOutputSink(t *testing.T) {
	pr, pw := io.Pipe()

	var outputLines []string
	var mu sync.Mutex

	transport := NewAcpTransport(
		nil, pr, nil,
		func(_ string, _ map[string]interface{}, _ interface{}, _ bool) {},
		func(line string, _ string) {
			mu.Lock()
			outputLines = append(outputLines, line)
			mu.Unlock()
		},
	)

	transport.Start()

	go func() {
		notif := map[string]interface{}{
			"jsonrpc": "2.0",
			"method":  "session/update",
			"params":  map[string]interface{}{},
		}
		notifBytes, _ := json.Marshal(notif)
		_, _ = fmt.Fprintf(pw, "%s\n", notifBytes)
		_, _ = fmt.Fprintln(pw, "plain text output")
		_ = pw.Close()
	}()

	<-transport.StdoutDone()

	mu.Lock()
	defer mu.Unlock()
	for _, line := range outputLines {
		if strings.Contains(line, `"jsonrpc":"2.0"`) || strings.HasPrefix(line, "Agent RPC:") {
			t.Errorf("JSON-RPC envelope leaked into the output sink: %q", line)
		}
	}
	if len(outputLines) != 1 || outputLines[0] != "plain text output" {
		t.Errorf("expected only the plain text line, got %v", outputLines)
	}
}

// TestReadStdout_NonJsonLinesStillRouted verifies that non-JSON lines are
// still routed to onOutput even with the goroutine-based notification dispatch.
func TestReadStdout_NonJsonLinesStillRouted(t *testing.T) {
	pr, pw := io.Pipe()

	var outputLines []string
	var mu sync.Mutex

	transport := NewAcpTransport(
		nil, pr, nil,
		nil,
		func(line string, _ string) {
			mu.Lock()
			outputLines = append(outputLines, line)
			mu.Unlock()
		},
	)

	transport.Start()

	go func() {
		_, _ = fmt.Fprintln(pw, "plain text output")
		_, _ = fmt.Fprintln(pw, "another line")
		_ = pw.Close()
	}()

	<-transport.StdoutDone()

	mu.Lock()
	defer mu.Unlock()
	if len(outputLines) != 2 {
		t.Fatalf("expected 2 output lines, got %d: %v", len(outputLines), outputLines)
	}
	if !strings.Contains(outputLines[0], "plain text output") {
		t.Errorf("expected first line to contain 'plain text output', got %q", outputLines[0])
	}
}
