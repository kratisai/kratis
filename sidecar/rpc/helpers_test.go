package rpc

import (
	"kratis-connector/runner"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// TestMain redirects the default credentials dir (production: /kratis) to a
// temp dir. The sidecar test-suite is regularly run INSIDE a live sandbox
// (self-hosting development), where /kratis is the running daemon's real state.
// Without this redirect, any test creating a Client via NewClient — or a future
// test that forgets c.credentialsDir = t.TempDir() — would overwrite/delete the
// live daemon's gitconfig and credential helper.
func TestMain(m *testing.M) {
	credentialsDir, err := os.MkdirTemp("", "kratis-test-credentials-*")
	if err != nil {
		panic("failed to create temp credentials dir: " + err.Error())
	}
	defaultCredentialsDir = credentialsDir

	code := m.Run()
	_ = os.RemoveAll(credentialsDir)
	os.Exit(code)
}

var upgrader = websocket.Upgrader{CheckOrigin: func(_ *http.Request) bool { return true }}

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
	c := NewClient(url, token, "", "")
	setTestClientTimeouts(c)
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("direct dial failed: %v", err)
	}
	c.mu.Lock()
	c.wsConn = conn
	c.envFile = filepath.Join(t.TempDir(), ".kratis-env")
	c.credentialsDir = t.TempDir()
	c.mu.Unlock()
	return c
}

// setTestClientTimeouts configures a Client with short timeouts for testing.
// This must be called before Start() for reconnect/heartbeat tests.
func setTestClientTimeouts(c *Client) {
	c.Timeouts = ClientTimeouts{
		ReconnectDelay:    100 * time.Millisecond,
		HeartbeatInterval: 200 * time.Millisecond,
		PermissionTimeout: 2 * time.Second,
	}
	c.supervisorTimeouts = runner.SupervisorTimeouts{
		CancelDelay:       10 * time.Millisecond,
		ExitWait:          100 * time.Millisecond,
		SigkillWait:       300 * time.Millisecond,
		DrainWait:         100 * time.Millisecond,
		SessionCloseWait:  200 * time.Millisecond,
		RequestTimeout:    2 * time.Second,
		InitializeTimeout: 2 * time.Second,
	}
}
