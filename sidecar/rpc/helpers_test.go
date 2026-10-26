package rpc

import (
	"encoding/json"
	"kratis-connector/runner"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// TestMain redirects the default state root (production: /kratis) to a temp
// dir. The sidecar test-suite is regularly run INSIDE a live sandbox
// (self-hosting development), where /kratis is the running daemon's real state.
// Without this redirect, any test creating a Client via NewClient would write
// into the live daemon's state. Clients built directly in tests must also set
// stateDir; stateRoot/statePath and resolveWorkspace panic otherwise.
func TestMain(m *testing.M) {
	stateDir, err := os.MkdirTemp("", "kratis-test-state-*")
	if err != nil {
		panic("failed to create temp state dir: " + err.Error())
	}
	defaultStateDir = stateDir

	code := m.Run()
	_ = os.RemoveAll(stateDir)
	os.Exit(code)
}

// newTestClient returns a Client whose workspace and persistent state are both
// isolated under t.TempDir(). Tests must never construct a Client that points
// at /kratis or /kratis/workspace.
func newTestClient(t *testing.T, workspace string) *Client {
	t.Helper()
	return &Client{workspace: workspace, stateDir: t.TempDir()}
}

var upgrader = websocket.Upgrader{CheckOrigin: func(_ *http.Request) bool { return true }}

// ackDiffSync answers env.diff_manifest and env.diff_sections requests
// with appropriate acknowledgments so tests never block.
func ackDiffSync(conn *websocket.Conn, req JsonRpcRequest) {
	if req.ID == nil {
		return
	}
	var raw json.RawMessage
	switch req.Method {
	case "env.diff_manifest":
		raw = json.RawMessage(`{"type":"env_diff_manifest","status":"committed","missing":[]}`)
	case "env.diff_sections":
		raw = json.RawMessage(`{"type":"env_diff_sections","status":"stored","missing":[]}`)
	default:
		return
	}
	data, err := json.Marshal(JsonRpcResponse{
		JsonRPC: "2.0",
		Result:  raw,
		ID:      req.ID,
	})
	if err != nil {
		return
	}
	_ = conn.WriteMessage(websocket.TextMessage, data)
}

// newTestServer spins up an httptest server that upgrades every connection to
// WebSocket and hands the gorilla.Conn to the provided handler goroutine.
func newTestServer(t *testing.T, handler func(conn *websocket.Conn)) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			t.Errorf("upgrade error: %v", err)
			return
		}
		handler(conn)
	}))
	t.Cleanup(srv.Close)
	return srv
}

// wsURL converts an http://... URL to ws://...
func wsURL(srv *httptest.Server) string {
	return "ws" + strings.TrimPrefix(srv.URL, "http")
}

// connectClient creates a Client, dials the given server, and returns the
// client with an active wsConn (waiting up to 500 ms).
func connectClient(t *testing.T, url, token string) *Client {
	t.Helper()
	c := NewClient(url, token, t.TempDir())
	setTestClientTimeouts(c)
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("direct dial failed: %v", err)
	}
	c.mu.Lock()
	c.wsConn = conn
	c.stateDir = t.TempDir()
	c.mu.Unlock()
	return c
}

// setTestClientTimeouts configures a Client with short timeouts for testing.
// This must be called before Start() for reconnect/heartbeat tests.
func setTestClientTimeouts(c *Client) {
	c.Timeouts = ClientTimeouts{
		ReconnectDelay:        100 * time.Millisecond,
		HeartbeatInterval:     200 * time.Millisecond,
		PermissionTimeout:     2 * time.Second,
		DiffDebouncePeriod:    10 * time.Millisecond,
		DiffRetryInitialDelay: 5 * time.Millisecond,
		DiffRetryMaxDelay:     50 * time.Millisecond,
	}
	c.supervisorTimeouts = runner.SupervisorTimeouts{
		CancelDelay:       10 * time.Millisecond,
		ExitWait:          100 * time.Millisecond,
		SigkillWait:       300 * time.Millisecond,
		DrainWait:         100 * time.Millisecond,
		RequestTimeout:    2 * time.Second,
		InitializeTimeout: 2 * time.Second,
	}
}
