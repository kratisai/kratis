package acp

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"sync"
	"time"
)

// maxScanTokenSize bounds the maximum size of a single line (one JSON-RPC
// message) read from the agent's stdout/stderr. bufio.Scanner's default
// limit is bufio.MaxScanTokenSize (64KB), too small for some agents
const maxScanTokenSize = 10 * 1024 * 1024 // 10MB

type AcpTransport struct {
	stdin  io.WriteCloser
	stdout io.ReadCloser
	stderr io.ReadCloser
	mu     sync.Mutex

	pending   map[interface{}]chan map[string]interface{}
	pendingMu sync.Mutex
	nextID    float64

	onNotification func(method string, params map[string]interface{}, id interface{}, hasID bool)
	onOutput       func(line string, stream string)

	stdoutDone chan struct{}
	stderrDone chan struct{}

	notificationWg sync.WaitGroup
}

func NewAcpTransport(
	stdin io.WriteCloser,
	stdout io.ReadCloser,
	stderr io.ReadCloser,
	onNotification func(string, map[string]interface{}, interface{}, bool),
	onOutput func(string, string),
) *AcpTransport {
	return &AcpTransport{
		stdin:          stdin,
		stdout:         stdout,
		stderr:         stderr,
		pending:        make(map[interface{}]chan map[string]interface{}),
		nextID:         1,
		onNotification: onNotification,
		onOutput:       onOutput,
		stdoutDone:     make(chan struct{}),
		stderrDone:     make(chan struct{}),
	}
}

func (t *AcpTransport) Start() {
	if t.stdout != nil {
		go t.readStdout()
	} else {
		close(t.stdoutDone)
	}
	if t.stderr != nil {
		go t.readStderr()
	} else {
		close(t.stderrDone)
	}
}

func (t *AcpTransport) SendRequest(method string, params interface{}, timeout time.Duration) (map[string]interface{}, error) {
	id := t.nextIDLocked()
	ch, err := t.dispatchRequest(method, params, id)
	if err != nil {
		return nil, err
	}

	select {
	case resp := <-ch:
		return resp, nil
	case <-time.After(timeout):
		t.pendingMu.Lock()
		delete(t.pending, id)
		t.pendingMu.Unlock()
		return nil, fmt.Errorf("timeout waiting for response to %s", method)
	}
}

// SendRequestUntilClosed sends a request and waits indefinitely for a response,
// with no artificial deadline. This is intended for calls like session/prompt,
// where the agent may legitimately work for an arbitrarily long time (minutes
// or longer) — progress is reported via session/update notifications rather
// than by resolving this call quickly. The only failure conditions are:
//   - the agent's stdout closes (process exited/crashed) before a response
//     arrives, detected via the transport's stdoutDone channel
//   - a write error when sending the request
//
// Callers that need to bound how long they wait (e.g. for cancellation) should
// race this against their own context/deadline externally.
func (t *AcpTransport) SendRequestUntilClosed(method string, params interface{}) (map[string]interface{}, error) {
	id := t.nextIDLocked()
	ch, err := t.dispatchRequest(method, params, id)
	if err != nil {
		return nil, err
	}

	select {
	case resp := <-ch:
		return resp, nil
	case <-t.stdoutDone:
		t.pendingMu.Lock()
		delete(t.pending, id)
		t.pendingMu.Unlock()
		return nil, fmt.Errorf("agent process terminated before responding to %s", method)
	}
}

// dispatchRequest marshals and writes a JSON-RPC request, registering a
// pending-response channel keyed by id. Shared by SendRequest (bounded wait)
// and SendRequestUntilClosed (unbounded wait).
func (t *AcpTransport) dispatchRequest(method string, params interface{}, id interface{}) (chan map[string]interface{}, error) {
	req := map[string]interface{}{
		"jsonrpc": "2.0",
		"method":  method,
		"params":  params,
		"id":      id,
	}
	reqBytes, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}

	ch := make(chan map[string]interface{}, 1)
	t.pendingMu.Lock()
	t.pending[id] = ch
	t.pendingMu.Unlock()

	if t.stdin == nil {
		t.pendingMu.Lock()
		delete(t.pending, id)
		t.pendingMu.Unlock()
		return nil, fmt.Errorf("stdin is nil")
	}
	t.mu.Lock()
	_, err = t.stdin.Write(append(reqBytes, '\n'))
	t.mu.Unlock()
	if err != nil {
		t.pendingMu.Lock()
		delete(t.pending, id)
		t.pendingMu.Unlock()
		return nil, err
	}

	return ch, nil
}

func (t *AcpTransport) WriteResponse(responseBody map[string]interface{}) error {
	respBytes, err := json.Marshal(responseBody)
	if err != nil {
		return err
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	_, err = t.stdin.Write(append(respBytes, '\n'))
	return err
}

func (t *AcpTransport) Close() error {
	if t.stdin == nil {
		return nil
	}
	return t.stdin.Close()
}

func (t *AcpTransport) WaitForNotifications(timeout time.Duration) {
	done := make(chan struct{})
	go func() {
		t.notificationWg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(timeout):
		log.Printf("[ACP] WaitForNotifications: timed out after %v, some handlers may still be running", timeout)
	}
}

func (t *AcpTransport) StdoutDone() <-chan struct{} {
	return t.stdoutDone
}

func (t *AcpTransport) StderrDone() <-chan struct{} {
	return t.stderrDone
}

func (t *AcpTransport) SetOnNotification(handler func(string, map[string]interface{}, interface{}, bool)) {
	t.onNotification = handler
}

func (t *AcpTransport) nextIDLocked() float64 {
	t.pendingMu.Lock()
	defer t.pendingMu.Unlock()
	id := t.nextID
	t.nextID++
	return id
}

func (t *AcpTransport) readStdout() {
	defer close(t.stdoutDone)
	scanner := bufio.NewScanner(t.stdout)
	scanner.Buffer(make([]byte, 0, 64*1024), maxScanTokenSize)
	lineCount := 0
	for scanner.Scan() {
		line := scanner.Text()
		lineCount++
		log.Printf("[ACP] stdout line %d (len=%d): %s", lineCount, len(line), line)

		var raw map[string]interface{}
		err := json.Unmarshal([]byte(line), &raw)
		if err != nil || raw == nil || raw["jsonrpc"] != "2.0" {
			if t.onOutput != nil {
				t.onOutput(line, "stdout")
			}
			continue
		}
		log.Printf("[ACP] Received JSON-RPC message: %s", line)

		id, hasID := raw["id"]
		_, hasMethod := raw["method"]
		if hasID && !hasMethod {
			t.routeResponse(id, raw)
		} else if t.onNotification != nil {
			methodStr, _ := raw["method"].(string)
			var paramsMap map[string]interface{}
			if p, ok := raw["params"].(map[string]interface{}); ok {
				paramsMap = p
			}
			t.notificationWg.Add(1)
			go func(method string, params map[string]interface{}, msgID interface{}, msgHasID bool) {
				defer t.notificationWg.Done()
				t.onNotification(method, params, msgID, msgHasID)
			}(methodStr, paramsMap, id, hasID)
		}
	}
	if err := scanner.Err(); err != nil {
		log.Printf("[ACP] stdout scanner error: %v", err)
		if t.onOutput != nil {
			t.onOutput(fmt.Sprintf("[ACP] stdout scanner error: %v", err), "stdout")
		}
	}
	log.Printf("[ACP] stdout reader finished, total lines: %d", lineCount)
}

func (t *AcpTransport) readStderr() {
	defer close(t.stderrDone)
	scanner := bufio.NewScanner(t.stderr)
	scanner.Buffer(make([]byte, 0, 64*1024), maxScanTokenSize)
	errLineCount := 0
	for scanner.Scan() {
		line := scanner.Text()
		errLineCount++
		log.Printf("[ACP] stderr line %d: %s", errLineCount, line)
		if t.onOutput != nil {
			t.onOutput(line, "stderr")
		}
	}
	if err := scanner.Err(); err != nil {
		log.Printf("[ACP] stderr scanner error: %v", err)
		if t.onOutput != nil {
			t.onOutput(fmt.Sprintf("[ACP] stderr scanner error: %v", err), "stderr")
		}
	}
	log.Printf("[ACP] stderr reader finished, total lines: %d", errLineCount)
}

func (t *AcpTransport) routeResponse(id interface{}, raw map[string]interface{}) {
	t.pendingMu.Lock()
	defer t.pendingMu.Unlock()

	var lookupKey = id
	if f, ok := id.(float64); ok {
		lookupKey = f
	}

	for k, ch := range t.pending {
		if k == lookupKey {
			ch <- raw
			delete(t.pending, k)
			return
		}
		if kF, ok := k.(float64); ok {
			if lF, ok := lookupKey.(float64); ok && kF == lF {
				ch <- raw
				delete(t.pending, k)
				return
			}
		}
	}
}
