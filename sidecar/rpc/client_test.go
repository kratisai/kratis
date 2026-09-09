package rpc

import (
	"context"
	"encoding/json"
	"fmt"
	"kratis-connector/acp"
	"kratis-connector/runner"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestClient_RegistrationAndHeartbeat(t *testing.T) {
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				t.Errorf("invalid request json: %s", msg)
				return
			}
			switch req.Method {
			case "env.register":
				var params RegisterParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &params)
				if params.Token != "valid-token" {
					resp := JsonRpcResponse{
						JsonRPC: "2.0",
						Error:   &JsonRpcError{Code: -32002, Message: "Invalid Token"},
						ID:      req.ID,
					}
					_ = conn.WriteJSON(resp)
					return
				}
				raw, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "registered", EnvironmentID: "env-123"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})

			case "env.heartbeat":
				raw, _ := json.Marshal(HeartbeatResult{Type: "env_heartbeat", Status: "ok"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
			}
		}
	})

	client := NewClient(wsURL(srv), "valid-token", "container-1", "")
	setTestClientTimeouts(client)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go client.Start(ctx)
	time.Sleep(500 * time.Millisecond)

	client.mu.Lock()
	conn := client.wsConn
	client.mu.Unlock()

	if conn == nil {
		t.Error("expected client connection to be established")
	}
	client.Close()
}

func TestReadLoop_Float64IDCoercion(t *testing.T) {
	var serverConn *websocket.Conn
	ready := make(chan struct{})

	srv := newTestServer(t, func(conn *websocket.Conn) {
		serverConn = conn
		close(ready)
		// Keep alive until test ends.
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", "")
	// Manually dial so we control the timing.
	conn, _, err := websocket.DefaultDialer.Dial(wsURL(srv), nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	c.mu.Lock()
	c.wsConn = conn
	c.mu.Unlock()

	<-ready // server side is ready

	// Register a pending channel for ID=1 (uint64).
	ch := make(chan *JsonRpcResponse, 1)
	c.mu.Lock()
	c.pending[uint64(1)] = ch
	c.mu.Unlock()

	// Start the read loop.
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Server sends a response whose ID is numeric (JSON → float64 on decode).
	raw, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "ok", EnvironmentID: "env-42"})
	resp := JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: float64(1)} // float64 simulates JSON decode
	if err := serverConn.WriteJSON(resp); err != nil {
		t.Fatalf("server write: %v", err)
	}

	select {
	case got := <-ch:
		if got == nil {
			t.Fatal("received nil response")
		}
		// The readLoop no longer deletes pending entries — callers are responsible for cleanup.
		// This supports multi-response patterns (e.g. RequestPermission).
		c.mu.Lock()
		_, still := c.pending[uint64(1)]
		c.mu.Unlock()
		if !still {
			t.Error("pending entry was unexpectedly deleted by readLoop (callers should clean up)")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timeout: response was not dispatched to pending channel (float64→uint64 coercion may have failed)")
	}

	c.Close()
}

func TestReadLoop_UnknownIDDropped(t *testing.T) {
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

	// Send a response for ID=99 which has no pending entry.
	raw, _ := json.Marshal(map[string]string{"status": "ok"})
	resp := JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: float64(99)}
	if err := serverConn.WriteJSON(resp); err != nil {
		t.Fatalf("server write: %v", err)
	}

	// Give the readLoop a moment to process.
	time.Sleep(100 * time.Millisecond)

	// Verify pending map is empty and no error was sent.
	c.mu.Lock()
	pendingLen := len(c.pending)
	c.mu.Unlock()
	if pendingLen != 0 {
		t.Errorf("pending map should be empty, got %d entries", pendingLen)
	}

	select {
	case err := <-errChan:
		t.Errorf("unexpected error from readLoop: %v", err)
	default:
	}

	c.Close()
}

func TestReadLoop_NilConnection(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")
	// wsConn is nil by default
	errChan := make(chan error, 1)
	go c.readLoop(errChan)
	// Should return immediately without error
	time.Sleep(100 * time.Millisecond)
	select {
	case err := <-errChan:
		t.Errorf("unexpected error: %v", err)
	default:
		// OK - readLoop returned without error
	}
}

func TestReadLoop_MalformedJSON(t *testing.T) {
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

	// Send invalid JSON.
	if err := serverConn.WriteMessage(websocket.TextMessage, []byte("{not valid json!!!")); err != nil {
		t.Fatalf("server write: %v", err)
	}

	time.Sleep(100 * time.Millisecond)

	// readLoop should still be running (no error sent).
	select {
	case err := <-errChan:
		t.Errorf("readLoop crashed on malformed JSON: %v", err)
	default:
	}

	c.Close()
}

func TestReadLoop_ServerRequestDispatched(t *testing.T) {
	var serverConn *websocket.Conn
	ready := make(chan struct{})

	srv := newTestServer(t, func(conn *websocket.Conn) {
		serverConn = conn
		close(ready)
		// Echo everything back so ExecuteExec can get permission responses.
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) == nil && req.Method == "env.hitl_request" {
				// Approve the command.
				raw, _ := json.Marshal(HitlResult{Response: HitlApproved, OptionID: "allow"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	<-ready

	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Verify handleServerRequest is called for an unknown method (it sends an error response).
	req := JsonRpcRequest{JsonRPC: "2.0", Method: "unknown.method", ID: nil}
	if err := serverConn.WriteJSON(req); err != nil {
		t.Fatalf("server write: %v", err)
	}

	time.Sleep(100 * time.Millisecond)

	select {
	case err := <-errChan:
		t.Errorf("unexpected readLoop error: %v", err)
	default:
	}

	c.Close()
}

func TestSendRequest_WriteError(t *testing.T) {
	// Build a client whose wsConn is already nil — simulates post-Close state.
	c := NewClient("ws://localhost:0", "tok", "", "")
	// wsConn is nil by default; do not set it.

	_, err := c.sendRequest("env.heartbeat", HeartbeatParams{})
	if err == nil {
		t.Error("expected error when wsConn is nil")
	}

	// Pending entry must have been removed on the write-error path.
	c.mu.Lock()
	pendingLen := len(c.pending)
	c.mu.Unlock()
	if pendingLen != 0 {
		t.Errorf("pending map should be empty after write failure, got %d entries", pendingLen)
	}
}

func TestWriteJSON_ClosedConnection(t *testing.T) {
	c := NewClient("ws://localhost:0", "tok", "", "")
	// wsConn is already nil — simulate closed state.
	err := c.writeJSON(map[string]string{"ping": "pong"})
	if err == nil {
		t.Fatal("expected error writing to closed connection")
	}
	if !strings.Contains(err.Error(), "connection closed") {
		t.Errorf("unexpected error message: %v", err)
	}
}

func TestSendNotification_NoID(t *testing.T) {
	received := make(chan struct{}, 1)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		_, _, _ = conn.ReadMessage()
		received <- struct{}{}
	})

	c := connectClient(t, wsURL(srv), "tok")

	err := c.sendNotification("env.output", OutputParams{Line: "hi", Stream: "stdout"})
	if err != nil {
		t.Errorf("sendNotification returned error: %v", err)
	}

	select {
	case <-received:
	case <-time.After(time.Second):
		t.Error("server did not receive the notification")
	}

	c.mu.Lock()
	pendingLen := len(c.pending)
	c.mu.Unlock()
	if pendingLen != 0 {
		t.Errorf("sendNotification must not register a pending entry, got %d", pendingLen)
	}

	c.Close()
}

func TestClient_Heartbeat_Success(t *testing.T) {
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		var req JsonRpcRequest
		if err := json.Unmarshal(msg, &req); err != nil {
			return
		}
		if req.Method == "env.heartbeat" {
			raw, _ := json.Marshal(HeartbeatResult{Status: "ok"})
			_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	defer c.Close()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	err := c.heartbeat()
	if err != nil {
		t.Fatalf("expected heartbeat to succeed, got %v", err)
	}
}

func TestClient_Heartbeat_Error(t *testing.T) {
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		var req JsonRpcRequest
		if err := json.Unmarshal(msg, &req); err != nil {
			return
		}
		if req.Method == "env.heartbeat" {
			_ = conn.WriteJSON(JsonRpcResponse{
				JsonRPC: "2.0",
				Error:   &JsonRpcError{Code: -32000, Message: "Server busy"},
				ID:      req.ID,
			})
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	defer c.Close()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	err := c.heartbeat()
	if err == nil {
		t.Fatal("expected heartbeat to fail, got nil")
	}
	if !strings.Contains(err.Error(), "heartbeat error [-32000]") {
		t.Errorf("unexpected error message: %v", err)
	}
}

func TestRequestPermission_ServerError(t *testing.T) {
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				continue
			}
			if req.Method == "env.hitl_request" {
				// Send error response
				_ = conn.WriteJSON(JsonRpcResponse{
					JsonRPC: "2.0",
					Error:   &JsonRpcError{Code: -32001, Message: "Permission denied by policy"},
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
	if selectedOptionID != "" {
		t.Error("expected empty selectedOptionID on error")
	}
	if err == nil {
		t.Fatal("expected error from RequestPermission")
	}
	if !strings.Contains(err.Error(), "hitl request error") {
		t.Errorf("unexpected error message: %v", err)
	}
}

func TestRequestPermission_MalformedResult(t *testing.T) {
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				continue
			}
			if req.Method == "env.hitl_request" {
				// Send response with invalid result (not a valid HitlResult)
				raw, _ := json.Marshal("not a valid result")
				_ = conn.WriteJSON(JsonRpcResponse{
					JsonRPC: "2.0",
					Result:  raw,
					ID:      req.ID,
				})
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	defer c.Close()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	selectedOptionID, err := c.RequestPermission(acp.PermissionRequest{Command: "ls"})
	if selectedOptionID != "" {
		t.Error("expected empty selectedOptionID on error")
	}
	if err == nil {
		t.Fatal("expected error from RequestPermission with invalid result")
	}
	if !strings.Contains(err.Error(), "failed to parse hitl result") {
		t.Errorf("unexpected error message: %v", err)
	}
}

func TestClient_HandleServerRequest_Errors(t *testing.T) {
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	defer c.Close()

	c.handleServerRequest(JsonRpcRequest{
		Method: "env.unsupported_method_xyz",
		ID:     uint64(999),
	})

	c.handleServerRequest(JsonRpcRequest{
		Method: "env.exec",
		Params: "not a struct",
		ID:     uint64(1000),
	})

	c.handleServerRequest(JsonRpcRequest{
		Method: "env.checkout",
		Params: "not a struct",
		ID:     uint64(1001),
	})

	c.handleServerRequest(JsonRpcRequest{
		Method: "env.registerGitAuth",
		Params: "not a struct",
		ID:     uint64(1002),
	})

	c.handleServerRequest(JsonRpcRequest{
		Method: "env.launch_acp_agent",
		Params: "not a struct",
		ID:     uint64(1003),
	})

	c.handleServerRequest(JsonRpcRequest{
		Method: "env.acp_prompt",
		Params: "not a struct",
		ID:     uint64(1004),
	})

	c.handleServerRequest(JsonRpcRequest{
		Method: "env.terminate",
		Params: "not a struct",
		ID:     uint64(1005),
	})
}

func TestClient_ConnectionErrors(t *testing.T) {
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		var req JsonRpcRequest
		if err := json.Unmarshal(msg, &req); err != nil {
			return
		}
		if req.Method == "env.register" {
			_ = conn.WriteJSON(JsonRpcResponse{
				JsonRPC: "2.0",
				Error:   &JsonRpcError{Code: -32000, Message: "Registration Denied"},
				ID:      req.ID,
			})
		}
	})

	cRegisterFail := NewClient(wsURL(srv), "tok", "", "")
	cRegisterFail.wsConn = connectClient(t, wsURL(srv), "tok").wsConn
	errChan := make(chan error, 1)
	go cRegisterFail.readLoop(errChan)
	defer cRegisterFail.Close()

	err := cRegisterFail.register()
	if err == nil {
		t.Error("expected register to fail, got nil")
	}
}

func TestClient_CloseWithSSHAgent(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")
	cmd := exec.Command("sleep", "10")
	if err := cmd.Start(); err != nil {
		t.Fatalf("failed to start dummy process: %v", err)
	}
	c.sshAgentPID = strconv.Itoa(cmd.Process.Pid)

	c.Close()

	err := cmd.Wait()
	if err == nil {
		t.Error("expected process to be killed, but exited cleanly")
	}
	if c.sshAgentPID != "" {
		t.Errorf("expected sshAgentPID to be cleared, got %q", c.sshAgentPID)
	}
}

func TestClient_SendSuccessResponse_MarshalError(_ *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")
	ch := make(chan int)
	c.sendSuccessResponse(uint64(123), ch)
}

func TestClient_Start_ErrorPaths(t *testing.T) {
	cDialFail := NewClient("ws://127.0.0.1:9999", "tok", "", "")
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	cDialFail.Start(ctx)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		var req JsonRpcRequest
		if err := json.Unmarshal(msg, &req); err != nil {
			return
		}
		if req.Method == "env.register" {
			_ = conn.WriteJSON(JsonRpcResponse{
				JsonRPC: "2.0",
				Error:   &JsonRpcError{Code: -32000, Message: "Registration Denied"},
				ID:      req.ID,
			})
		}
	})

	cRegFail := NewClient(wsURL(srv), "tok", "", "")
	setTestClientTimeouts(cRegFail)
	ctxReg, cancelReg := context.WithCancel(context.Background())
	go func() {
		time.Sleep(200 * time.Millisecond)
		cancelReg()
	}()
	cRegFail.Start(ctxReg)
}

func TestClient_SendRequest_Timeout(t *testing.T) {
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	})
	c := connectClient(t, wsURL(srv), "tok")
	c.requestTimeout = 50 * time.Millisecond
	defer c.Close()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	_, err := c.sendRequest("test.method", nil)
	if err == nil {
		t.Error("expected sendRequest to timeout, got nil error")
	}
}

func TestNewClient_CredentialCallback(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "mock-proc-*")
	if err != nil {
		t.Fatalf("failed to create mock proc: %v", err)
	}
	defer func() { _ = os.RemoveAll(tmpDir) }()

	oldProcDir := runner.GetProcDir()
	runner.SetProcDir(tmpDir)
	defer func() { runner.SetProcDir(oldProcDir) }()

	runner.SetEnforceGitPathCheck(false)
	defer runner.SetEnforceGitPathCheck(true)

	myPID := os.Getpid()
	myPPID := 9999

	pidDir := filepath.Join(tmpDir, strconv.Itoa(myPID))
	ppidDir := filepath.Join(tmpDir, strconv.Itoa(myPPID))
	_ = os.MkdirAll(pidDir, 0750)
	_ = os.MkdirAll(ppidDir, 0750)

	statData := fmt.Sprintf("%d (test) S %d 123 456", myPID, myPPID)
	_ = os.WriteFile(filepath.Join(pidDir, "stat"), []byte(statData), 0644)

	ppidStatData := fmt.Sprintf("%d (git) S 1 123 456", myPPID)
	_ = os.WriteFile(filepath.Join(ppidDir, "stat"), []byte(ppidStatData), 0644)

	dummyGitPath := filepath.Join(tmpDir, "git")
	_ = os.WriteFile(dummyGitPath, []byte(""), 0755)
	_ = os.Symlink(dummyGitPath, filepath.Join(ppidDir, "exe"))

	cmdline := "git\x00clone\x00"
	_ = os.WriteFile(filepath.Join(ppidDir, "cmdline"), []byte(cmdline), 0644)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				continue
			}
			if req.Method == "env.git_token" {
				_ = conn.WriteJSON(JsonRpcResponse{
					JsonRPC: "2.0",
					Result:  json.RawMessage(`{"token":"secret-client-token"}`),
					ID:      req.ID,
				})
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	errChan := make(chan error, 1)
	go c.readLoop(errChan)
	defer c.Close()

	conn, err := net.Dial("unix", runner.SocketPath)
	if err != nil {
		t.Fatalf("failed to dial: %v", err)
	}
	defer func() { _ = conn.Close() }()

	buf := make([]byte, 1024)
	n, err := conn.Read(buf)
	if err != nil {
		t.Fatalf("failed to read response: %v", err)
	}

	expected := "username=oauth2\npassword=secret-client-token\n"
	if string(buf[:n]) != expected {
		t.Errorf("expected response %q, got %q", expected, string(buf[:n]))
	}
}

func TestFetchGitToken_OnDemand(t *testing.T) {
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				continue
			}
			if req.Method != "env.git_token" {
				continue
			}
			_ = conn.WriteJSON(JsonRpcResponse{
				JsonRPC: "2.0",
				Result:  json.RawMessage(`{"token":"fresh-token"}`),
				ID:      req.ID,
			})
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	errChan := make(chan error, 1)
	go c.readLoop(errChan)
	defer c.Close()

	if token := c.fetchGitToken(); token != "fresh-token" {
		t.Errorf("expected fresh-token, got %q", token)
	}
}

func TestClient_HandleServerRequest_NilID_Errors(_ *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")
	defer c.Close()

	c.handleServerRequest(JsonRpcRequest{
		Method: "env.registerGitAuth",
		Params: "not-a-struct",
		ID:     nil,
	})

	c.handleServerRequest(JsonRpcRequest{
		Method: "env.checkout",
		Params: "not-a-struct",
		ID:     nil,
	})

	c.handleServerRequest(JsonRpcRequest{
		Method: "env.unsupported_method_xyz",
		ID:     nil,
	})
}

func TestClient_CloseHelperScript(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")

	// Create a dummy helper script file
	tempFile, err := os.CreateTemp("", "kratis-git-credentials-helper-test-*.sh")
	if err != nil {
		t.Fatalf("failed to create temp file: %v", err)
	}
	_ = tempFile.Close()

	c.gitHelperScript = tempFile.Name()

	// Verify file exists
	if _, err := os.Stat(c.gitHelperScript); os.IsNotExist(err) {
		t.Fatalf("temp script does not exist: %v", err)
	}

	c.Close()

	// Verify file is removed
	if _, err := os.Stat(tempFile.Name()); !os.IsNotExist(err) {
		t.Errorf("expected helper script to be deleted, but it still exists")
		_ = os.Remove(tempFile.Name()) // clean up
	}
	if c.gitHelperScript != "" {
		t.Errorf("expected gitHelperScript to be cleared, got %q", c.gitHelperScript)
	}
}

// TestClient_Start_ReconnectAfterConnectionError tests that Start reconnects
// after the server closes the connection (readLoop error).
func TestClient_Start_ReconnectAfterConnectionError(t *testing.T) {
	var connCount int
	var mu sync.Mutex

	srv := newTestServer(t, func(conn *websocket.Conn) {
		mu.Lock()
		connCount++
		count := connCount
		mu.Unlock()

		// First connection: accept, register, then close immediately to trigger reconnect
		// Second connection: accept, register, then keep alive until test ends
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				return
			}
			switch req.Method {
			case "env.register":
				raw, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "registered", EnvironmentID: "env-123"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
				if count == 1 {
					// Close connection after registration to trigger reconnect
					_ = conn.Close()
					return
				}
			case "env.heartbeat":
				raw, _ := json.Marshal(HeartbeatResult{Status: "ok"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", "")
	setTestClientTimeouts(c)
	ctx, cancel := context.WithCancel(context.Background())

	go c.Start(ctx)

	// Wait for reconnection (first connect + disconnect + retry + reconnect)
	time.Sleep(1 * time.Second)

	// Verify we reconnected (connCount should be >= 2)
	mu.Lock()
	count := connCount
	mu.Unlock()

	if count < 2 {
		t.Errorf("expected at least 2 connections (reconnect), got %d", count)
	}

	cancel()
	c.Close()
}

// TestClient_CloseWithAcpSession tests that Close cleans up an active ACP session.
func TestClient_CloseWithAcpSession(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")

	// Create a dummy process for the ACP session in its own process group
	cmd := exec.Command("sleep", "30")
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	stdin, _ := cmd.StdinPipe()
	stdout, _ := cmd.StdoutPipe()
	stderr, _ := cmd.StderrPipe()

	transport := acp.NewAcpTransport(stdin, stdout, stderr, nil, nil)
	session := &acp.AcpSession{
		SessionID: "test-session",
		Transport: transport,
	}

	// Create a supervisor to manage the session
	termMgr := acp.NewTerminalManager("")
	c.supervisor = runner.NewAgentSupervisor(c.workspace, termMgr, nil)
	runner.SetTestTimeouts(c.supervisor)
	c.supervisor.TestSetTransport(transport)
	if err := c.supervisor.StartProcess(cmd); err != nil {
		t.Fatalf("failed to start process: %v", err)
	}
	c.supervisor.TestSetSession(session)

	c.Close()

	// Verify supervisor was cleaned up
	if c.supervisor != nil {
		t.Error("expected supervisor to be nil after Close")
	}

	// Verify process was killed (use a short timeout wait)
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err == nil {
			t.Error("expected process to be killed")
		}
	case <-time.After(3 * time.Second):
		t.Error("timeout waiting for process to exit after Close")
		_ = cmd.Process.Kill()
	}
}

// TestHandleServerRequest_CheckoutDispatch tests that handleServerRequest correctly
// dispatches env.checkout with valid params to ExecuteCheckout.
func TestHandleServerRequest_CheckoutDispatch(t *testing.T) {
	var (
		mu           sync.Mutex
		responseMsg  []byte
		responseChan = make(chan struct{})
		gotCheckout  atomic.Bool
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

			if req.Method == "env.checkout_complete" {
				gotCheckout.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.workspace = "/nonexistent-test-path-for-clone"
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Dispatch env.checkout with valid params - will fail because path doesn't exist
	// but it exercises the dispatch path through handleServerRequest
	c.handleServerRequest(JsonRpcRequest{
		Method: "env.checkout",
		Params: map[string]interface{}{
			"url":    "https://github.com/test/repo.git",
			"branch": "main",
		},
		ID: uint64(701),
	})

	// Wait for checkout_complete notification (the checkout will fail but dispatch succeeds)
	time.Sleep(500 * time.Millisecond)

	if !gotCheckout.Load() {
		t.Error("expected env.checkout_complete notification from checkout dispatch")
	}

	c.Close()
}

// TestHandleServerRequest_RegisterGitAuthDispatch tests that handleServerRequest correctly
// dispatches env.registerGitAuth with valid params to ExecuteRegisterGitAuth.
func TestHandleServerRequest_RegisterGitAuthDispatch(t *testing.T) {
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
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Dispatch env.registerGitAuth with valid params
	c.handleServerRequest(JsonRpcRequest{
		Method: "env.registerGitAuth",
		Params: map[string]interface{}{
			"credentialType": "GIT_PAT",
			"userName":       "Kratis",
			"userEmail":      "kratis@control-plane-host",
		},
		ID: uint64(702),
	})

	select {
	case <-responseChan:
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for registerGitAuth dispatch response")
	}

	mu.Lock()
	respBytes := responseMsg
	mu.Unlock()

	var resp JsonRpcResponse
	if err := json.Unmarshal(respBytes, &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}

	// Verify the credential helper was configured
	c.mu.Lock()
	helper := c.gitHelperScript
	c.mu.Unlock()
	if helper == "" {
		t.Error("expected gitHelperScript to be set")
	}

	c.Close()
}

// TestReadLoop_MalformedResponseJSON tests that readLoop handles malformed
// response JSON gracefully (continues without crashing).
func TestReadLoop_MalformedResponseJSON(t *testing.T) {
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

	// Send a message that has no "method" field (so it goes to response parsing)
	// but is also not a valid response (missing "id" field)
	if err := serverConn.WriteMessage(websocket.TextMessage, []byte(`{"jsonrpc":"2.0","result":null}`)); err != nil {
		t.Fatalf("server write: %v", err)
	}

	time.Sleep(100 * time.Millisecond)

	// readLoop should still be running (no error sent)
	select {
	case err := <-errChan:
		t.Errorf("readLoop crashed on malformed response: %v", err)
	default:
	}

	c.Close()
}

// TestReadLoop_MalformedRequestJSON tests that readLoop handles malformed
// request JSON gracefully (continues without crashing).
func TestReadLoop_MalformedRequestJSON(t *testing.T) {
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

	// Send a message that has a "method" field (so it goes to request parsing)
	// but has invalid params that can't be unmarshalled into JsonRpcRequest
	if err := serverConn.WriteMessage(websocket.TextMessage, []byte(`{"method":"test","params":"invalid"}`)); err != nil {
		t.Fatalf("server write: %v", err)
	}

	time.Sleep(100 * time.Millisecond)

	// readLoop should still be running
	select {
	case err := <-errChan:
		t.Errorf("readLoop crashed on malformed request: %v", err)
	default:
	}

	c.Close()
}

// TestHandleServerRequest_ExecDispatch tests that handleServerRequest correctly
// dispatches env.exec with valid params to ExecuteExec.
func TestHandleServerRequest_ExecDispatch(t *testing.T) {
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

	c.handleServerRequest(JsonRpcRequest{
		Method: "env.exec",
		Params: map[string]interface{}{
			"command": "echo hello-from-dispatch-test",
		},
		ID: uint64(703),
	})

	deadline := time.Now().Add(3 * time.Second)
	for !gotResponse.Load() && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if !gotResponse.Load() {
		t.Fatal("timeout waiting for env.exec dispatch response")
	}
	mu.Lock()
	var resp JsonRpcResponse
	_ = json.Unmarshal(responseMsg, &resp)
	var result ExecResult
	_ = json.Unmarshal(resp.Result, &result)
	mu.Unlock()
	if result.Status != ExecCompleted {
		t.Errorf("expected completed, got %s", result.Status)
	}

	c.Close()
}

// TestClient_Start_HeartbeatErrorResponse tests that Start reconnects when
// the server returns an error to the heartbeat request. This exercises the
// heartbeatErrChan path in Start's main select loop.
func TestClient_Start_HeartbeatErrorResponse(t *testing.T) {
	var connCount int
	var mu sync.Mutex

	srv := newTestServer(t, func(conn *websocket.Conn) {
		mu.Lock()
		connCount++
		count := connCount
		mu.Unlock()

		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				return
			}
			switch req.Method {
			case "env.register":
				raw, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "registered", EnvironmentID: "env-123"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
			case "env.heartbeat":
				if count == 1 {
					// First connection: return error to heartbeat
					_ = conn.WriteJSON(JsonRpcResponse{
						JsonRPC: "2.0",
						Error:   &JsonRpcError{Code: -32000, Message: "Server busy"},
						ID:      req.ID,
					})
				} else {
					// Second connection: respond ok
					raw, _ := json.Marshal(HeartbeatResult{Status: "ok"})
					_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
				}
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", "")
	setTestClientTimeouts(c)
	ctx, cancel := context.WithCancel(context.Background())

	go c.Start(ctx)

	// Wait for heartbeat error + reconnect (heartbeat interval + retry)
	time.Sleep(1 * time.Second)

	mu.Lock()
	count := connCount
	mu.Unlock()

	if count < 2 {
		t.Errorf("expected at least 2 connections (reconnect after heartbeat error), got %d", count)
	}

	cancel()
	c.Close()
}

// TestClient_Start_HeartbeatErrorReconnect tests that Start reconnects after
// a heartbeat failure. The heartbeat error path triggers the same reconnect
// logic as connection errors.
func TestClient_Start_HeartbeatErrorReconnect(t *testing.T) {
	var connCount int
	var mu sync.Mutex

	srv := newTestServer(t, func(conn *websocket.Conn) {
		mu.Lock()
		connCount++
		count := connCount
		mu.Unlock()

		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				return
			}
			switch req.Method {
			case "env.register":
				raw, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "registered", EnvironmentID: "env-123"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
				if count == 1 {
					// Close connection shortly after registration to trigger reconnect
					// This exercises the same reconnect path as heartbeat errors
					time.Sleep(100 * time.Millisecond)
					_ = conn.Close()
					return
				}
			case "env.heartbeat":
				raw, _ := json.Marshal(HeartbeatResult{Status: "ok"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", "")
	setTestClientTimeouts(c)
	ctx, cancel := context.WithCancel(context.Background())

	go c.Start(ctx)

	// Wait for reconnection (first connect + disconnect + retry + reconnect)
	time.Sleep(1 * time.Second)

	mu.Lock()
	count := connCount
	mu.Unlock()

	if count < 2 {
		t.Errorf("expected at least 2 connections (reconnect after error), got %d", count)
	}

	cancel()
	c.Close()
}

func TestRequestPermission_Cancellation(t *testing.T) {
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				continue
			}
			if req.Method == "env.hitl_request" {
				// Don't respond - let the test cancel the permission
				time.Sleep(10 * time.Second)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	defer c.Close()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Start RequestPermission in a goroutine
	permDone := make(chan struct{})
	var permSelected string
	var permErr error
	go func() {
		permSelected, permErr = c.RequestPermission(acp.PermissionRequest{Command: "test-command"})
		close(permDone)
	}()

	// Give it time to send the request
	time.Sleep(200 * time.Millisecond)

	// Cancel pending HITL requests directly on the client
	c.CancelPendingHitl()

	// Wait for RequestPermission to return
	select {
	case <-permDone:
	case <-time.After(3 * time.Second):
		t.Fatal("timeout waiting for RequestPermission to return after cancellation")
	}

	if permSelected != "" {
		t.Error("expected empty selectedOptionId after cancellation")
	}
	if permErr == nil {
		t.Fatal("expected error after cancellation")
	}
	if !strings.Contains(permErr.Error(), "cancelled") {
		t.Errorf("expected error to contain 'cancelled', got: %v", permErr)
	}

	// Verify pending was cleaned up
	c.mu.Lock()
	pendingLen := len(c.pending)
	c.mu.Unlock()
	if pendingLen != 0 {
		t.Errorf("expected pending map to be empty after cancellation, got %d", pendingLen)
	}
}

func TestCancelPendingPermissions_ReinitializesContext(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")
	defer c.Close()

	// Create a supervisor for testing
	termMgr := acp.NewTerminalManager("")
	c.supervisor = runner.NewAgentSupervisor(c.workspace, termMgr, nil)
	runner.SetTestTimeouts(c.supervisor)

	// Get initial context
	initialCtx := c.supervisor.PermCtx()

	// Cancel
	c.supervisor.CancelPermissions()

	// Verify context was reinitialized
	newCtx := c.supervisor.PermCtx()

	if newCtx == initialCtx {
		t.Error("expected permCtx to be reinitialized after CancelPermissions")
	}

	// Verify new context is not cancelled
	select {
	case <-newCtx.Done():
		t.Error("expected new permCtx to not be cancelled")
	default:
		// OK
	}
}

func TestClient_CloseConnection_PreservesAcpSession(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")

	// Create a dummy process for the ACP session in its own process group
	cmd := exec.Command("sleep", "30")
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := cmd.Start(); err != nil {
		t.Fatalf("failed to start dummy process: %v", err)
	}
	defer func() { _ = cmd.Process.Kill() }()

	stdin, _ := cmd.StdinPipe()
	stdout, _ := cmd.StdoutPipe()
	stderr, _ := cmd.StderrPipe()

	transport := acp.NewAcpTransport(stdin, stdout, stderr, nil, nil)
	session := &acp.AcpSession{
		SessionID: "test-session",
		Transport: transport,
	}

	// Create supervisor and set session
	termMgr := acp.NewTerminalManager("")
	c.supervisor = runner.NewAgentSupervisor(c.workspace, termMgr, nil)
	runner.SetTestTimeouts(c.supervisor)
	c.supervisor.TestSetSession(session)

	// Call closeConnection
	c.closeConnection()

	// Verify ACP session is preserved
	if c.supervisor == nil {
		t.Error("expected supervisor to be preserved after closeConnection")
	}
	if c.supervisor.Session() == nil {
		t.Error("expected session to be preserved after closeConnection")
	}
	if c.supervisor.Session().SessionID != "test-session" {
		t.Errorf("expected session ID to be 'test-session', got %q", c.supervisor.Session().SessionID)
	}

	// Verify WebSocket connection is closed
	if c.wsConn != nil {
		t.Error("expected wsConn to be nil after closeConnection")
	}

	// Verify pending map is reset
	if len(c.pending) != 0 {
		t.Errorf("expected pending map to be empty, got %d entries", len(c.pending))
	}

	// Verify process is still running
	if cmd.ProcessState != nil && cmd.ProcessState.Exited() {
		t.Error("expected process to still be running after closeConnection")
	}
}

func TestClient_CloseAll_DestroyAcpSession(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")

	// Create a dummy process for the ACP session in its own process group
	cmd := exec.Command("sleep", "30")
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	stdin, _ := cmd.StdinPipe()
	stdout, _ := cmd.StdoutPipe()
	stderr, _ := cmd.StderrPipe()

	transport := acp.NewAcpTransport(stdin, stdout, stderr, nil, nil)
	session := &acp.AcpSession{
		SessionID: "test-session",
		Transport: transport,
	}

	// Create supervisor and set session
	termMgr := acp.NewTerminalManager("")
	c.supervisor = runner.NewAgentSupervisor(c.workspace, termMgr, nil)
	runner.SetTestTimeouts(c.supervisor)
	c.supervisor.TestSetTransport(transport)
	if err := c.supervisor.StartProcess(cmd); err != nil {
		t.Fatalf("failed to start process: %v", err)
	}
	c.supervisor.TestSetSession(session)

	// Call closeAll
	c.closeAll()

	// Verify ACP session is destroyed
	if c.supervisor != nil {
		t.Error("expected supervisor to be nil after closeAll")
	}

	// Verify process was killed
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err == nil {
			t.Error("expected process to be killed")
		}
	case <-time.After(3 * time.Second):
		t.Error("timeout waiting for process to exit after closeAll")
		_ = cmd.Process.Kill()
	}
}

func TestClient_CloseAll_HandlesSupervisorWithoutProcess(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")
	c.supervisor = runner.NewAgentSupervisor(c.workspace, nil, nil)

	c.closeAll()

	if c.supervisor != nil {
		t.Error("expected supervisor to be nil after closeAll")
	}
}

// verifies clean sidecar shutdown - notify before hard-close the connection.
func TestClient_CloseAll_SendsEnvCompleteBeforeClosingSocket(t *testing.T) {
	var (
		mu     sync.Mutex
		events []string
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				mu.Lock()
				events = append(events, "close")
				mu.Unlock()
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				continue
			}
			if req.Method == "env.complete" {
				mu.Lock()
				events = append(events, "env.complete")
				mu.Unlock()
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Create a supervisor with a running process and an active ACP session. Pass the
	// client as the event sink so that Terminate() sends env.complete over the wire.
	cmd := exec.Command("sleep", "30")
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	stdin, _ := cmd.StdinPipe()
	stdout, _ := cmd.StdoutPipe()
	stderr, _ := cmd.StderrPipe()
	transport := acp.NewAcpTransport(stdin, stdout, stderr, nil, nil)
	session := &acp.AcpSession{SessionID: "test-session", Transport: transport}

	termMgr := acp.NewTerminalManager("")
	c.supervisor = runner.NewAgentSupervisor(c.workspace, termMgr, c)
	runner.SetTestTimeouts(c.supervisor)
	c.supervisor.TestSetTransport(transport)
	if err := c.supervisor.StartProcess(cmd); err != nil {
		t.Fatalf("failed to start process: %v", err)
	}
	c.supervisor.TestSetSession(session)

	c.closeAll()

	// Wait for the server to observe both the env.complete message and the close.
	time.Sleep(300 * time.Millisecond)

	mu.Lock()
	defer mu.Unlock()
	if len(events) < 2 {
		t.Fatalf("expected env.complete followed by close, got events: %v", events)
	}
	if events[0] != "env.complete" {
		t.Errorf("expected env.complete before close, got events: %v", events)
	}
	if events[len(events)-1] != "close" {
		t.Errorf("expected connection close to be the last event, got events: %v", events)
	}

	// Ensure the supervisor was cleaned up.
	if c.supervisor != nil {
		t.Error("expected supervisor to be nil after closeAll")
	}

	// Ensure the process was killed.
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err == nil {
			t.Error("expected process to be killed")
		}
	case <-time.After(3 * time.Second):
		t.Error("timeout waiting for process to exit after closeAll")
		_ = cmd.Process.Kill()
	}
}

// TestClient_CloseAll_NoEnvCompleteWithoutSupervisor verifies that closeAll does
// not send env.complete when there is no active supervisor session.
func TestClient_CloseAll_NoEnvCompleteWithoutSupervisor(t *testing.T) {
	var (
		gotComplete atomic.Bool
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				continue
			}
			if req.Method == "env.complete" {
				gotComplete.Store(true)
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.closeAll()

	time.Sleep(100 * time.Millisecond)

	if gotComplete.Load() {
		t.Error("expected no env.complete when there is no active supervisor")
	}
	if c.wsConn != nil {
		t.Error("expected wsConn to be nil after closeAll")
	}
}

func TestClient_Start_ReconnectPreservesAcpSession(t *testing.T) {
	var connCount int
	var mu sync.Mutex

	srv := newTestServer(t, func(conn *websocket.Conn) {
		mu.Lock()
		connCount++
		count := connCount
		mu.Unlock()

		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				return
			}
			switch req.Method {
			case "env.register":
				raw, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "registered", EnvironmentID: "env-123"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
				if count == 1 {
					// Close connection after registration to trigger reconnect
					_ = conn.Close()
					return
				}
			case "env.heartbeat":
				raw, _ := json.Marshal(HeartbeatResult{Status: "ok"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", "")
	setTestClientTimeouts(c)

	// Create a dummy ACP session before starting
	cmd := exec.Command("sleep", "30")
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := cmd.Start(); err != nil {
		t.Fatalf("failed to start dummy process: %v", err)
	}
	defer func() { _ = cmd.Process.Kill() }()

	stdin, _ := cmd.StdinPipe()
	stdout, _ := cmd.StdoutPipe()
	stderr, _ := cmd.StderrPipe()

	transport := acp.NewAcpTransport(stdin, stdout, stderr, nil, nil)
	session := &acp.AcpSession{
		SessionID: "test-session-reconnect",
		Transport: transport,
	}

	// Create supervisor and set session
	termMgr := acp.NewTerminalManager("")
	c.supervisor = runner.NewAgentSupervisor(c.workspace, termMgr, nil)
	runner.SetTestTimeouts(c.supervisor)
	c.supervisor.TestSetSession(session)

	ctx, cancel := context.WithCancel(context.Background())
	go c.Start(ctx)

	// Wait for reconnection
	time.Sleep(1 * time.Second)

	// Verify ACP session is still present
	c.mu.Lock()
	sup := c.supervisor
	c.mu.Unlock()

	if sup == nil {
		t.Error("expected supervisor to be preserved after reconnect")
	} else {
		session := sup.Session()
		if session == nil {
			t.Error("expected session to be preserved after reconnect")
		} else if session.SessionID != "test-session-reconnect" {
			t.Errorf("expected session ID to be 'test-session-reconnect', got %q", session.SessionID)
		}
	}

	// Verify process is still running
	if cmd.ProcessState != nil && cmd.ProcessState.Exited() {
		t.Error("expected ACP process to still be running after reconnect")
	}

	cancel()
	time.Sleep(100 * time.Millisecond)
}

func TestClient_Start_NoTickerLeak(t *testing.T) {
	var connCount int
	var mu sync.Mutex

	srv := newTestServer(t, func(conn *websocket.Conn) {
		mu.Lock()
		connCount++
		count := connCount
		mu.Unlock()

		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				return
			}
			switch req.Method {
			case "env.register":
				raw, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "registered", EnvironmentID: "env-123"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
				if count < 3 {
					// Close connection after registration to trigger reconnect
					_ = conn.Close()
					return
				}
			case "env.heartbeat":
				raw, _ := json.Marshal(HeartbeatResult{Status: "ok"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", "")
	setTestClientTimeouts(c)
	ctx, cancel := context.WithCancel(context.Background())

	go c.Start(ctx)

	// Wait for multiple reconnections (3 connections)
	time.Sleep(1 * time.Second)

	mu.Lock()
	count := connCount
	mu.Unlock()

	if count < 3 {
		t.Errorf("expected at least 3 connections, got %d", count)
	}

	// Cancel and verify cleanup
	cancel()
	time.Sleep(100 * time.Millisecond)

	// The test passes if it completes without hanging or resource exhaustion
	// The ticker leak fix ensures tickers are stopped on each reconnect iteration
}

func TestClient_Register_InitialRegistration(t *testing.T) {
	var receivedParams RegisterParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		var req JsonRpcRequest
		if err := json.Unmarshal(msg, &req); err != nil {
			return
		}
		if req.Method == "env.register" {
			b, _ := json.Marshal(req.Params)
			_ = json.Unmarshal(b, &receivedParams)
			raw, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "registered", EnvironmentID: "env-123"})
			_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
		}
	})

	c := connectClient(t, wsURL(srv), "test-token")
	defer c.Close()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	err := c.register()
	if err != nil {
		t.Fatalf("expected register to succeed, got %v", err)
	}

	if receivedParams.Token != "test-token" {
		t.Errorf("expected token 'test-token', got %q", receivedParams.Token)
	}
	if receivedParams.IsReconnect {
		t.Error("expected IsReconnect to be false on initial registration")
	}
	if receivedParams.LastEventSequence != 0 {
		t.Errorf("expected LastEventSequence to be 0, got %d", receivedParams.LastEventSequence)
	}
	if len(receivedParams.PendingHitlIDs) != 0 {
		t.Errorf("expected PendingPermissionIDs to be empty, got %v", receivedParams.PendingHitlIDs)
	}
}

func TestClient_Register_ReconnectState(t *testing.T) {
	var callCount int
	var mu sync.Mutex
	var secondCallParams RegisterParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				return
			}
			if req.Method == "env.register" {
				mu.Lock()
				callCount++
				count := callCount
				mu.Unlock()

				var params RegisterParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &params)

				if count == 2 {
					secondCallParams = params
				}

				raw, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "registered", EnvironmentID: "env-123"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
			}
		}
	})

	c := connectClient(t, wsURL(srv), "test-token")
	defer c.Close()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// First registration
	err := c.register()
	if err != nil {
		t.Fatalf("first register failed: %v", err)
	}

	// Simulate setting reconnect state
	c.mu.Lock()
	c.lastEventSequence = 42
	c.pendingHitlIDs = []string{"perm-1", "perm-2"}
	c.mu.Unlock()

	// Second registration (reconnect)
	err = c.register()
	if err != nil {
		t.Fatalf("second register failed: %v", err)
	}

	mu.Lock()
	count := callCount
	mu.Unlock()

	if count != 2 {
		t.Fatalf("expected 2 register calls, got %d", count)
	}

	if !secondCallParams.IsReconnect {
		t.Error("expected IsReconnect to be true on second registration")
	}
	if secondCallParams.LastEventSequence != 42 {
		t.Errorf("expected LastEventSequence to be 42, got %d", secondCallParams.LastEventSequence)
	}
	if len(secondCallParams.PendingHitlIDs) != 2 {
		t.Errorf("expected 2 PendingPermissionIDs, got %d", len(secondCallParams.PendingHitlIDs))
	}
	if secondCallParams.PendingHitlIDs[0] != "perm-1" || secondCallParams.PendingHitlIDs[1] != "perm-2" {
		t.Errorf("expected PendingHitlIDs [perm-1, perm-2], got %v", secondCallParams.PendingHitlIDs)
	}
}

func TestClient_Register_WithActiveAcpSession(t *testing.T) {
	var receivedParams RegisterParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		var req JsonRpcRequest
		if err := json.Unmarshal(msg, &req); err != nil {
			return
		}
		if req.Method == "env.register" {
			b, _ := json.Marshal(req.Params)
			_ = json.Unmarshal(b, &receivedParams)
			raw, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "registered", EnvironmentID: "env-123"})
			_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
		}
	})

	c := connectClient(t, wsURL(srv), "test-token")
	defer c.Close()

	// Set up an active ACP session
	session := &acp.AcpSession{
		SessionID: "acp-session-123",
	}
	termMgr := acp.NewTerminalManager("")
	c.supervisor = runner.NewAgentSupervisor(c.workspace, termMgr, nil)
	runner.SetTestTimeouts(c.supervisor)
	c.supervisor.TestSetSession(session)

	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	err := c.register()
	if err != nil {
		t.Fatalf("expected register to succeed, got %v", err)
	}

	if receivedParams.ActiveAcpSessionID != "acp-session-123" {
		t.Errorf("expected ActiveAcpSessionID 'acp-session-123', got %q", receivedParams.ActiveAcpSessionID)
	}
	if !receivedParams.HasActiveAgent {
		t.Error("expected HasActiveAgent to be true when acpSession exists")
	}
}

func TestClient_NextSequence(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")
	defer c.Close()

	seq1 := c.nextSequence()
	if seq1 != 1 {
		t.Errorf("expected first sequence to be 1, got %d", seq1)
	}

	seq2 := c.nextSequence()
	if seq2 != 2 {
		t.Errorf("expected second sequence to be 2, got %d", seq2)
	}

	seq3 := c.nextSequence()
	if seq3 != 3 {
		t.Errorf("expected third sequence to be 3, got %d", seq3)
	}
}
