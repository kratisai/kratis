package rpc

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// commitToBareMain pushes a new commit touching the given file to the bare
// repository's main branch, simulating upstream changes after the sandbox
// workspace was provisioned.
func commitToBareMain(t *testing.T, bareDir string, file string, content string) {
	t.Helper()
	cloneDir, err := os.MkdirTemp("", "kratis-git-advance-main-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(cloneDir) }()

	runGitCmd(t, cloneDir, "clone", bareDir, ".")
	runGitCmd(t, cloneDir, "config", "user.name", "Test")
	runGitCmd(t, cloneDir, "config", "user.email", "test@kratis.ai")
	if err := os.WriteFile(filepath.Join(cloneDir, file), []byte(content), 0600); err != nil {
		t.Fatalf("Failed to write %s: %v", file, err)
	}
	runGitCmd(t, cloneDir, "add", file)
	runGitCmd(t, cloneDir, "commit", "-m", "chore: advance main")
	runGitCmd(t, cloneDir, "push", "origin", "main")
}

// captureNextResponse waits for a JSON-RPC response written by the client.
func captureNextResponse(t *testing.T, received chan []byte) []byte {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case data := <-received:
			return data
		case <-time.After(50 * time.Millisecond):
		}
	}
	t.Fatal("timed out waiting for RPC response")
	return nil
}

func connectedGitClient(t *testing.T, workspace string) (*Client, chan []byte) {
	t.Helper()
	received := make(chan []byte, 16)
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, data, err := conn.ReadMessage()
			if err != nil {
				return
			}
			received <- data
		}
	})
	client := connectClient(t, wsURL(srv), "tok")
	client.workspace = workspace
	return client, received
}

func TestExecuteGitDiffSummary_DiffsAgainstMergeBase(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client, received := connectedGitClient(t, workDir)

	// Agent changes: modify README.md and add a new untracked file.
	if err := os.WriteFile(filepath.Join(workDir, "README.md"), []byte("# Agent change\n"), 0600); err != nil {
		t.Fatalf("Failed to write README: %v", err)
	}
	if err := os.WriteFile(filepath.Join(workDir, "agent.txt"), []byte("agent\n"), 0600); err != nil {
		t.Fatalf("Failed to write agent file: %v", err)
	}

	// Upstream main adds a file the agent never touched, then the workspace
	// learns about it (e.g. the publish flow's fetch) — simulating the branch
	// point diverging from the current target tip.
	commitToBareMain(t, bareDir, "upstream.txt", "upstream\n")
	runGitCmd(t, workDir, "fetch", "origin")

	client.ExecuteGitDiffSummary(GitDiffSummaryParams{BaseBranch: "main", ExecutionID: "exec-1"}, "req-diff")

	var envelope struct {
		Result GitDiffSummaryResult `json:"result"`
	}
	if err := json.Unmarshal(captureNextResponse(t, received), &envelope); err != nil {
		t.Fatalf("Failed to unmarshal diff summary: %v", err)
	}
	result := envelope.Result

	paths := map[string]bool{}
	for _, f := range result.Files {
		if f.Path == "upstream.txt" {
			t.Fatalf("Expected upstream-only change to be excluded from the diff, got %s", f.Path)
		}
		paths[f.Path] = true
	}
	if !paths["README.md"] {
		t.Errorf("Expected README.md in diff summary, got %v", paths)
	}
	if !paths["agent.txt"] {
		t.Errorf("Expected agent.txt in diff summary, got %v", paths)
	}

	expectedBase := gitOutput(t, workDir, "merge-base", "origin/main", "HEAD")
	if result.BaseCommit != expectedBase {
		t.Errorf("Expected baseCommit to be the merge-base %s, got %s", expectedBase, result.BaseCommit)
	}
	if result.HeadCommit == "" {
		t.Errorf("Expected headCommit to be set")
	}
}

func TestExecuteGitFileDiff_ExcludesUpstreamChanges(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client, received := connectedGitClient(t, workDir)

	if err := os.WriteFile(filepath.Join(workDir, "README.md"), []byte("# Agent change\n"), 0600); err != nil {
		t.Fatalf("Failed to write README: %v", err)
	}
	commitToBareMain(t, bareDir, "upstream.txt", "upstream\n")
	runGitCmd(t, workDir, "fetch", "origin")

	client.ExecuteGitFileDiff(GitFileDiffParams{Path: "upstream.txt", BaseBranch: "main"}, "req-file")

	var envelope struct {
		Result GitFileDiffResult `json:"result"`
	}
	if err := json.Unmarshal(captureNextResponse(t, received), &envelope); err != nil {
		t.Fatalf("Failed to unmarshal file diff: %v", err)
	}
	if envelope.Result.Patch != "" {
		t.Errorf("Expected empty patch for upstream-only file, got %q", envelope.Result.Patch)
	}
	if envelope.Result.Additions != 0 || envelope.Result.Deletions != 0 {
		t.Errorf("Expected zero additions/deletions for upstream-only file, got +%d/-%d", envelope.Result.Additions, envelope.Result.Deletions)
	}
}

func TestExecuteGitPush_FirstPublishSquashPreservesUpstreamChanges(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client := &Client{workspace: workDir}

	// Agent change to README.md.
	if err := os.WriteFile(filepath.Join(workDir, "README.md"), []byte("# Agent change\n"), 0600); err != nil {
		t.Fatalf("Failed to write README: %v", err)
	}

	// Upstream main advances with a file the agent never touched.
	commitToBareMain(t, bareDir, "upstream.txt", "upstream\n")
	runGitCmd(t, workDir, "fetch", "origin")

	client.ExecuteGitPush(GitPushParams{
		BranchName:    "kratis/feature",
		CommitMessage: "feat: agent feature",
		Squash:        true,
		TargetBranch:  "main",
	}, "req-push-1")

	// The pushed branch must include the upstream change, not revert it.
	if got := gitOutput(t, bareDir, "show", "kratis/feature:upstream.txt"); strings.TrimSpace(got) != "upstream" {
		t.Errorf("Expected upstream.txt content %q on pushed branch, got %q", "upstream", strings.TrimSpace(got))
	}
	if got := gitOutput(t, bareDir, "show", "kratis/feature:README.md"); strings.TrimSpace(got) != "# Agent change" {
		t.Errorf("Expected README.md content %q on pushed branch, got %q", "# Agent change", strings.TrimSpace(got))
	}
	if count := gitOutput(t, bareDir, "rev-list", "--count", "main..kratis/feature"); count != "1" {
		t.Errorf("Expected exactly 1 squashed commit ahead of main, got %s", count)
	}
}
