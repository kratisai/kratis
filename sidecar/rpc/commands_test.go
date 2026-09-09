package rpc

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestExecuteExec_Success(t *testing.T) {
	var (
		mu          sync.Mutex
		outputs     []OutputParams
		responseMsg []byte
		gotResponse atomic.Bool
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var envelope map[string]interface{}
			if json.Unmarshal(msg, &envelope) != nil {
				continue
			}
			if method, ok := envelope["method"].(string); ok {
				if method == "env.output" {
					raw, _ := json.Marshal(envelope["params"])
					var p OutputParams
					_ = json.Unmarshal(raw, &p)
					mu.Lock()
					outputs = append(outputs, p)
					mu.Unlock()
				}
			} else if _, ok := envelope["id"]; ok {
				mu.Lock()
				responseMsg = msg
				mu.Unlock()
				gotResponse.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = t.TempDir()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	c.ExecuteExec(ExecParams{Command: "echo hello_from_test", ExecutionID: "exec-123"}, uint64(42))

	deadline := time.Now().Add(3 * time.Second)
	for !gotResponse.Load() && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if !gotResponse.Load() {
		t.Fatal("timeout waiting for env.exec response")
	}

	mu.Lock()
	defer mu.Unlock()
	var resp JsonRpcResponse
	if err := json.Unmarshal(responseMsg, &resp); err != nil {
		t.Fatalf("parse response: %v", err)
	}
	var result ExecResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		t.Fatalf("parse result: %v", err)
	}
	if result.Status != ExecCompleted {
		t.Errorf("expected completed, got %s error=%s", result.Status, result.Error)
	}
	if result.ExitCode != 0 {
		t.Errorf("expected exit 0, got %d", result.ExitCode)
	}
	found := false
	for _, o := range outputs {
		if o.Line == "hello_from_test" && o.Stream == StreamStdout {
			found = true
			if o.ExecutionID != "exec-123" {
				t.Errorf("expected output executionId exec-123, got %q", o.ExecutionID)
			}
		}
	}
	if !found {
		t.Errorf("expected stdout line hello_from_test, got %+v", outputs)
	}
	c.Close()
}

func TestExecuteExec_OutputFallsBackToCurrentExecutionID(t *testing.T) {
	var (
		mu          sync.Mutex
		outputs     []OutputParams
		gotResponse atomic.Bool
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var envelope map[string]interface{}
			if json.Unmarshal(msg, &envelope) != nil {
				continue
			}
			if method, ok := envelope["method"].(string); ok {
				if method == "env.output" {
					raw, _ := json.Marshal(envelope["params"])
					var p OutputParams
					_ = json.Unmarshal(raw, &p)
					mu.Lock()
					outputs = append(outputs, p)
					mu.Unlock()
				}
			} else if _, ok := envelope["id"]; ok {
				gotResponse.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = t.TempDir()
	c.mu.Lock()
	c.currentExecutionID = "exec-fallback"
	c.mu.Unlock()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	c.ExecuteExec(ExecParams{Command: "echo fallback_output"}, uint64(43))

	deadline := time.Now().Add(3 * time.Second)
	for !gotResponse.Load() && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if !gotResponse.Load() {
		t.Fatal("timeout waiting for env.exec response")
	}

	mu.Lock()
	defer mu.Unlock()
	found := false
	for _, o := range outputs {
		if o.Line == "fallback_output" {
			found = true
			if o.ExecutionID != "exec-fallback" {
				t.Errorf("expected fallback executionId exec-fallback, got %q", o.ExecutionID)
			}
		}
	}
	if !found {
		t.Errorf("expected stdout line fallback_output, got %+v", outputs)
	}
	c.Close()
}

func TestExecuteExec_Failure(t *testing.T) {
	var (
		mu          sync.Mutex
		responseMsg []byte
		gotResponse atomic.Bool
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var envelope map[string]interface{}
			if json.Unmarshal(msg, &envelope) != nil {
				continue
			}
			if _, hasMethod := envelope["method"]; !hasMethod {
				if _, ok := envelope["id"]; ok {
					mu.Lock()
					responseMsg = msg
					mu.Unlock()
					gotResponse.Store(true)
				}
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = t.TempDir()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	c.ExecuteExec(ExecParams{Command: "exit 7"}, uint64(99))

	deadline := time.Now().Add(3 * time.Second)
	for !gotResponse.Load() && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if !gotResponse.Load() {
		t.Fatal("timeout waiting for env.exec response")
	}

	mu.Lock()
	defer mu.Unlock()
	var resp JsonRpcResponse
	if err := json.Unmarshal(responseMsg, &resp); err != nil {
		t.Fatalf("parse response: %v", err)
	}
	var result ExecResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		t.Fatalf("parse result: %v", err)
	}
	if result.Status != ExecFailed {
		t.Errorf("expected failed, got %s", result.Status)
	}
	if result.ExitCode != 7 {
		t.Errorf("expected exit 7, got %d", result.ExitCode)
	}
	c.Close()
}

func TestExecuteExec_PersistEnv(t *testing.T) {
	var (
		mu          sync.Mutex
		responseMsg []byte
		gotResponse atomic.Bool
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var envelope map[string]interface{}
			if json.Unmarshal(msg, &envelope) != nil {
				continue
			}
			if _, hasMethod := envelope["method"]; !hasMethod {
				if _, ok := envelope["id"]; ok {
					mu.Lock()
					responseMsg = append([]byte(nil), msg...)
					mu.Unlock()
					gotResponse.Store(true)
				}
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = t.TempDir()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	gotResponse.Store(false)
	c.ExecuteExec(ExecParams{Command: "export KRATIS_TEST_VAR=persisted_value", PersistEnv: true}, uint64(1))
	deadline := time.Now().Add(3 * time.Second)
	for !gotResponse.Load() && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if !gotResponse.Load() {
		t.Fatal("timeout on persist export")
	}
	mu.Lock()
	var resp1 JsonRpcResponse
	_ = json.Unmarshal(responseMsg, &resp1)
	var result1 ExecResult
	_ = json.Unmarshal(resp1.Result, &result1)
	mu.Unlock()
	if result1.Status != ExecCompleted {
		t.Fatalf("export step failed: status=%s exit=%d err=%s", result1.Status, result1.ExitCode, result1.Error)
	}

	// Second exec with persist should see the var via sourced env file
	gotResponse.Store(false)
	c.ExecuteExec(ExecParams{Command: `test "$KRATIS_TEST_VAR" = "persisted_value"`, PersistEnv: true}, uint64(2))
	deadline = time.Now().Add(3 * time.Second)
	for !gotResponse.Load() && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if !gotResponse.Load() {
		t.Fatal("timeout on persist verify")
	}
	mu.Lock()
	var resp JsonRpcResponse
	_ = json.Unmarshal(responseMsg, &resp)
	var result ExecResult
	_ = json.Unmarshal(resp.Result, &result)
	mu.Unlock()
	if result.Status != ExecCompleted || result.ExitCode != 0 {
		t.Fatalf("expected env var to persist, status=%s exit=%d err=%s", result.Status, result.ExitCode, result.Error)
	}
	c.Close()
}

func TestExecuteExec_PersistEnvWritesOutsideWorkspace(t *testing.T) {
	var gotResponse atomic.Bool

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var envelope map[string]interface{}
			if json.Unmarshal(msg, &envelope) != nil {
				continue
			}
			if _, hasMethod := envelope["method"]; !hasMethod {
				if _, ok := envelope["id"]; ok {
					gotResponse.Store(true)
				}
			}
		}
	})

	workspace := t.TempDir()
	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = workspace
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	c.ExecuteExec(ExecParams{Command: "export KRATIS_ENV_LOCATION_TEST=1", PersistEnv: true}, uint64(1))
	deadline := time.Now().Add(3 * time.Second)
	for !gotResponse.Load() && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if !gotResponse.Load() {
		t.Fatal("timeout on persist export")
	}

	if _, err := os.Stat(c.envFilePath()); err != nil {
		t.Fatalf("expected persisted env file at %s, got err: %v", c.envFilePath(), err)
	}
	if _, err := os.Stat(filepath.Join(workspace, ".kratis-env")); !os.IsNotExist(err) {
		t.Errorf("expected no .kratis-env inside the git workspace, but found one (err=%v)", err)
	}
	c.Close()
}

func TestExecuteExec_EmptyCommand(t *testing.T) {
	var (
		mu          sync.Mutex
		responseMsg []byte
		gotResponse atomic.Bool
	)
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var envelope map[string]interface{}
			if json.Unmarshal(msg, &envelope) != nil {
				continue
			}
			if _, hasMethod := envelope["method"]; !hasMethod {
				if _, ok := envelope["id"]; ok {
					mu.Lock()
					responseMsg = msg
					mu.Unlock()
					gotResponse.Store(true)
				}
			}
		}
	})
	c := connectClient(t, wsURL(srv), "tok")
	errChan := make(chan error, 1)
	go c.readLoop(errChan)
	c.ExecuteExec(ExecParams{Command: ""}, uint64(1))
	deadline := time.Now().Add(2 * time.Second)
	for !gotResponse.Load() && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	mu.Lock()
	var resp JsonRpcResponse
	_ = json.Unmarshal(responseMsg, &resp)
	var result ExecResult
	_ = json.Unmarshal(resp.Result, &result)
	mu.Unlock()
	if result.Status != ExecFailed {
		t.Errorf("expected failed for empty command, got %s", result.Status)
	}
	c.Close()
}
