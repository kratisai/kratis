package rpc

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestGitPublish_RebaseConflictRestoresWorkspace(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client := newTestClient(t, workDir)

	// Make local modification to README.md
	readmePath := filepath.Join(workDir, "README.md")
	localReadmeContent := "# Initial Readme\nLocal conflict line\n"
	_ = os.WriteFile(readmePath, []byte(localReadmeContent), 0600)
	// Commit it locally
	_ = exec.Command("git", "-C", workDir, "add", ".").Run()
	_ = exec.Command("git", "-C", workDir, "commit", "-m", "local change").Run()

	// Make conflicting modification on upstream origin/main
	otherWorkDir, _ := os.MkdirTemp("", "kratis-conflict-upstream-*")
	defer func() { _ = os.RemoveAll(otherWorkDir) }()
	_ = exec.Command("git", "clone", bareDir, otherWorkDir).Run()
	_ = exec.Command("git", "-C", otherWorkDir, "config", "user.name", "Upstream").Run()
	_ = exec.Command("git", "-C", otherWorkDir, "config", "user.email", "upstream@kratis.ai").Run()
	_ = os.WriteFile(filepath.Join(otherWorkDir, "README.md"), []byte("# Initial Readme\nUpstream conflict line\n"), 0600)
	_ = exec.Command("git", "-C", otherWorkDir, "add", ".").Run()
	_ = exec.Command("git", "-C", otherWorkDir, "commit", "-m", "upstream conflicting commit").Run()
	_ = exec.Command("git", "-C", otherWorkDir, "push", "origin", "main").Run()

	received := make(chan []byte, 10)
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			received <- msg
		}
	})
	defer srv.Close()

	client.serverURL = wsURL(srv)
	conn, _, err := websocket.DefaultDialer.Dial(wsURL(srv), nil)
	if err != nil {
		t.Fatalf("Dial error: %v", err)
	}
	defer func() { _ = conn.Close() }()
	client.wsConn = conn

	// Attempt rebase
	client.ExecuteGitRebase(GitRebaseParams{
		DefaultBranch: "main",
	}, "req-reb-conf")

	select {
	case data := <-received:
		var errResp JsonRpcResponse
		_ = json.Unmarshal(data, &errResp)
		if errResp.Error == nil || !strings.Contains(errResp.Error.Message, "REBASE_CONFLICT") {
			t.Fatalf("Expected REBASE_CONFLICT error, got %v", errResp)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for rebase conflict response")
	}

	// Assert workspace was restored to pre-action state
	currentContent, err := os.ReadFile(readmePath)
	if err != nil || string(currentContent) != localReadmeContent {
		t.Fatalf("Expected workspace README.md to be restored, got %q", string(currentContent))
	}
}

func TestGitPublish_LeaseMismatchRejected(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client := newTestClient(t, workDir)

	received := make(chan []byte, 10)
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			received <- msg
		}
	})
	defer srv.Close()

	client.serverURL = wsURL(srv)
	conn, _, err := websocket.DefaultDialer.Dial(wsURL(srv), nil)
	if err != nil {
		t.Fatalf("Dial error: %v", err)
	}
	defer func() { _ = conn.Close() }()
	client.wsConn = conn

	// Push a branch first
	_ = exec.Command("git", "-C", workDir, "push", "origin", "main:refs/heads/kratis/lease-branch").Run()

	// Attempt publish with stale lease SHA
	client.ExecuteGitPublish(GitPushParams{
		BranchName:      "kratis/lease-branch",
		DefaultBranch:   "main",
		CreateBranch:    false,
		ExpectedHeadSha: "0000000000000000000000000000000000000000", // wrong sha
	}, "req-pub-lease")

	select {
	case data := <-received:
		var errResp JsonRpcResponse
		_ = json.Unmarshal(data, &errResp)
		if errResp.Error == nil || errResp.Error.Message != "HEAD_MOVED" {
			t.Fatalf("Expected HEAD_MOVED error, got %v", errResp)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for lease mismatch rejection")
	}
}
