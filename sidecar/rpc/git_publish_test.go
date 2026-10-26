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

func TestGitObservePublish_CleanAndRebase(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client := &Client{
		workspace: workDir,
		envFile:   filepath.Join(bareDir, ".test-env"),
	}

	// 1. Initial checkout state: base SHA should be recorded
	headCmd := exec.Command("git", "rev-parse", "HEAD")
	headCmd.Dir = workDir
	initialHeadBytes, _ := headCmd.Output()
	initialHead := strings.TrimSpace(string(initialHeadBytes))

	client.persistExecutionBaseSha(initialHead)

	// Verify persistence survives reading
	if got := client.readPersistedExecutionBaseSha(); got != initialHead {
		t.Fatalf("Expected execution base %s, got %s", initialHead, got)
	}

	// Make an uncommitted file
	featureFile := filepath.Join(workDir, "feature.txt")
	if err := os.WriteFile(featureFile, []byte("feature work line 1\nline 2\n"), 0600); err != nil {
		t.Fatalf("Failed to write feature file: %v", err)
	}

	// Also make a file in .kratis that should be ignored
	kratisDir := filepath.Join(workDir, ".kratis")
	_ = os.MkdirAll(kratisDir, 0750)
	_ = os.WriteFile(filepath.Join(kratisDir, "internal.json"), []byte("{}"), 0600)

	// Connect test server
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

	// 2. Observe publish
	client.ExecuteGitObservePublish(GitObservePublishParams{
		ExecutionID: "exec-test-1",
		HeadBranch:  "kratis/test-branch",
	}, "req-obs-1")

	select {
	case data := <-received:
		var resp jsonRpcSuccessEnvelope[GitObservePublishResult]
		if err := json.Unmarshal(data, &resp); err != nil {
			t.Fatalf("Failed to parse observe response: %v, raw: %s", err, string(data))
		}
		res := resp.Result
		if res.DefaultBranch != "main" {
			t.Errorf("Expected defaultBranch=main, got %s", res.DefaultBranch)
		}
		if res.ExecutionBaseSha != initialHead {
			t.Errorf("Expected executionBaseSha=%s, got %s", initialHead, res.ExecutionBaseSha)
		}
		if !res.DefaultIsAncestorOfHead {
			t.Errorf("Expected defaultIsAncestorOfHead=true")
		}
		if res.IncomingCommitCount != 0 {
			t.Errorf("Expected incomingCommitCount=0, got %d", res.IncomingCommitCount)
		}
		if !res.HasUncommittedChanges {
			t.Errorf("Expected hasUncommittedChanges=true")
		}
		if res.ChangeSet.Additions != 2 {
			t.Errorf("Expected 2 additions, got %d", res.ChangeSet.Additions)
		}
		// Ensure .kratis is not in files
		for _, f := range res.ChangeSet.Files {
			if strings.HasPrefix(f.Path, ".kratis") {
				t.Errorf(".kratis file leaked into changeset: %s", f.Path)
			}
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for observe response")
	}

	// 3. Advance origin/main with another commit to simulate upstream change
	otherWorkDir, _ := os.MkdirTemp("", "kratis-git-upstream-*")
	defer func() { _ = os.RemoveAll(otherWorkDir) }()
	cloneCmd := exec.Command("git", "clone", bareDir, otherWorkDir)
	_ = cloneCmd.Run()
	_ = exec.Command("git", "-C", otherWorkDir, "config", "user.name", "Upstream").Run()
	_ = exec.Command("git", "-C", otherWorkDir, "config", "user.email", "upstream@kratis.ai").Run()
	_ = os.WriteFile(filepath.Join(otherWorkDir, "upstream.txt"), []byte("upstream change\n"), 0600)
	_ = exec.Command("git", "-C", otherWorkDir, "add", ".").Run()
	_ = exec.Command("git", "-C", otherWorkDir, "commit", "-m", "upstream commit").Run()
	_ = exec.Command("git", "-C", otherWorkDir, "push", "origin", "main").Run()

	// Observe again: should show incomingCommitCount = 1
	client.ExecuteGitObservePublish(GitObservePublishParams{
		ExecutionID: "exec-test-1",
	}, "req-obs-2")

	var newDefaultSha string
	select {
	case data := <-received:
		var resp jsonRpcSuccessEnvelope[GitObservePublishResult]
		_ = json.Unmarshal(data, &resp)
		if resp.Result.IncomingCommitCount != 1 {
			t.Errorf("Expected incomingCommitCount=1, got %d", resp.Result.IncomingCommitCount)
		}
		newDefaultSha = resp.Result.DefaultSha
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for second observe response")
	}

	// 4. Publish without rebase should fail with REBASE_REQUIRED
	client.ExecuteGitPublish(GitPushParams{
		BranchName:         "kratis/test-branch",
		DefaultBranch:      "main",
		ExpectedDefaultSha: newDefaultSha,
		CreateBranch:       true,
	}, "req-pub-1")

	select {
	case data := <-received:
		var errResp JsonRpcResponse
		_ = json.Unmarshal(data, &errResp)
		if errResp.Error == nil || errResp.Error.Message != "REBASE_REQUIRED" {
			t.Errorf("Expected REBASE_REQUIRED error, got %v", errResp)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for publish rejection")
	}

	// 5. Rebase
	client.ExecuteGitRebase(GitRebaseParams{
		DefaultBranch:      "main",
		ExpectedDefaultSha: newDefaultSha,
	}, "req-reb-1")

	select {
	case data := <-received:
		var resp jsonRpcSuccessEnvelope[GitRebaseResult]
		if err := json.Unmarshal(data, &resp); err != nil {
			t.Fatalf("Failed to parse rebase response: %v, raw: %s", err, string(data))
		}
		if resp.Result.NewBaseSha != newDefaultSha {
			t.Errorf("Expected newBaseSha=%s, got %s", newDefaultSha, resp.Result.NewBaseSha)
		}
		if client.readPersistedExecutionBaseSha() != newDefaultSha {
			t.Errorf("Expected persisted execution base to be updated to %s", newDefaultSha)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for rebase response")
	}

	// Verify uncommitted file survived rebase
	content, err := os.ReadFile(featureFile)
	if err != nil || !strings.Contains(string(content), "feature work line 1") {
		t.Fatalf("Uncommitted changes were lost during rebase: %v", err)
	}

	// 6. Publish with squash=true succeeds
	publishedSha := ""
	client.ExecuteGitPublish(GitPushParams{
		BranchName:         "kratis/test-branch",
		DefaultBranch:      "main",
		ExpectedDefaultSha: newDefaultSha,
		CreateBranch:       true,
		Squash:             true,
		CommitMessage:      "Publish feature",
	}, "req-pub-2")

	select {
	case data := <-received:
		var resp jsonRpcSuccessEnvelope[GitPushResult]
		if err := json.Unmarshal(data, &resp); err != nil {
			t.Fatalf("Failed to parse publish response: %v, raw: %s", err, string(data))
		}
		if resp.Result.Status != "success" {
			t.Errorf("Expected status=success, got %s", resp.Result.Status)
		}
		if resp.Result.CommitSha == "" {
			t.Errorf("Expected non-empty commitSha")
		}
		publishedSha = resp.Result.CommitSha
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for publish success")
	}

	// 7. After publish, the target branch has no delta to push
	client.ExecuteGitObservePublish(GitObservePublishParams{
		ExecutionID: "exec-test-1",
		HeadBranch:  "kratis/test-branch",
	}, "req-obs-3")

	select {
	case data := <-received:
		var resp jsonRpcSuccessEnvelope[GitObservePublishResult]
		if err := json.Unmarshal(data, &resp); err != nil {
			t.Fatalf("Failed to parse post-publish observe response: %v, raw: %s", err, string(data))
		}
		if resp.Result.HeadSha != publishedSha {
			t.Errorf("Expected headSha=%s, got %s", publishedSha, resp.Result.HeadSha)
		}
		if resp.Result.CommitCount != 0 {
			t.Errorf("Expected no unpushed commits, got %d", resp.Result.CommitCount)
		}
		if resp.Result.HasUncommittedChanges {
			t.Errorf("Expected hasUncommittedChanges=false after publish")
		}
		if len(resp.Result.ChangeSet.Files) != 0 {
			t.Errorf("Expected empty change set after publish, got %v", resp.Result.ChangeSet.Files)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for post-publish observe response")
	}

	// 8. A later uncommitted change is the only publish delta
	deltaPath := filepath.Join(workDir, "delta.txt")
	if err := os.WriteFile(deltaPath, []byte("delta line\n"), 0600); err != nil {
		t.Fatalf("Failed to write delta file: %v", err)
	}

	client.ExecuteGitObservePublish(GitObservePublishParams{
		ExecutionID: "exec-test-1",
		HeadBranch:  "kratis/test-branch",
	}, "req-obs-4")

	select {
	case data := <-received:
		var resp jsonRpcSuccessEnvelope[GitObservePublishResult]
		if err := json.Unmarshal(data, &resp); err != nil {
			t.Fatalf("Failed to parse delta observe response: %v, raw: %s", err, string(data))
		}
		if resp.Result.CommitCount != 0 {
			t.Errorf("Expected no unpushed commits, got %d", resp.Result.CommitCount)
		}
		if !resp.Result.HasUncommittedChanges {
			t.Errorf("Expected hasUncommittedChanges=true")
		}
		if len(resp.Result.ChangeSet.Files) != 1 {
			t.Fatalf("Expected only the new delta file, got %v", resp.Result.ChangeSet.Files)
		}
		if resp.Result.ChangeSet.Files[0].Path != "delta.txt" {
			t.Errorf("Expected delta.txt, got %s", resp.Result.ChangeSet.Files[0].Path)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for delta observe response")
	}
}
