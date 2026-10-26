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

func setupTestGitRepoWithRemote(t *testing.T) (string, string) {
	t.Helper()
	bareDir, err := os.MkdirTemp("", "kratis-git-push-bare-*")
	if err != nil {
		t.Fatalf("Failed to create bare temp dir: %v", err)
	}

	workDir, err := os.MkdirTemp("", "kratis-git-push-work-*")
	if err != nil {
		t.Fatalf("Failed to create work temp dir: %v", err)
	}

	runGitIn := func(dir string, args ...string) {
		cmd := exec.Command("git", args...)
		cmd.Dir = dir
		cmd.Env = append(os.Environ(),
			"GIT_AUTHOR_NAME=Test",
			"GIT_AUTHOR_EMAIL=test@kratis.ai",
			"GIT_COMMITTER_NAME=Test",
			"GIT_COMMITTER_EMAIL=test@kratis.ai",
		)
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git in %s %v failed: %v, out: %s", dir, args, err, string(out))
		}
	}

	// 1. Initialize bare repository
	runGitIn(bareDir, "init", "--bare", "-b", "main")

	// 2. Clone to workDir
	runGitIn(workDir, "clone", bareDir, ".")
	runGitIn(workDir, "config", "user.name", "Test")
	runGitIn(workDir, "config", "user.email", "test@kratis.ai")

	// 3. Initial commit
	initialFile := filepath.Join(workDir, "README.md")
	if err := os.WriteFile(initialFile, []byte("# Initial Readme\n"), 0600); err != nil {
		t.Fatalf("Failed to write initial file: %v", err)
	}
	runGitIn(workDir, "add", "README.md")
	runGitIn(workDir, "commit", "-m", "Initial commit")
	runGitIn(workDir, "push", "-u", "origin", "main")
	runGitIn(workDir, "remote", "set-head", "origin", "main")

	return bareDir, workDir
}

func TestExecuteGitPush_SuccessWithUncommittedChanges(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client := newTestClient(t, workDir)

	// Create uncommitted modifications
	modFile := filepath.Join(workDir, "README.md")
	if err := os.WriteFile(modFile, []byte("# Updated Readme\nLine 2\n"), 0600); err != nil {
		t.Fatalf("Failed to modify file: %v", err)
	}

	newFile := filepath.Join(workDir, "new_feature.txt")
	if err := os.WriteFile(newFile, []byte("New feature code\n"), 0600); err != nil {
		t.Fatalf("Failed to create new file: %v", err)
	}

	// Execute git push to new feature branch
	targetBranch := "kratis/feature-test"
	client.ExecuteGitPush(GitPushParams{
		BranchName:    targetBranch,
		CommitMessage: "Add new feature and update readme",
		DefaultBranch: "main",
		CreateBranch:  true,
	}, "req-push-1")

	// Verify remote bare repository has target branch
	checkCmd := exec.Command("git", "branch", "--list", targetBranch)
	checkCmd.Dir = bareDir
	out, err := checkCmd.CombinedOutput()
	if err != nil {
		t.Fatalf("Failed to list branches in bare repo: %v", err)
	}
	if !strings.Contains(string(out), targetBranch) {
		t.Errorf("Expected bare repo to contain branch %s, got: %s", targetBranch, string(out))
	}

	// Verify author and committer preserved the configured repo user ("Test <test@kratis.ai>")
	logCmd := exec.Command("git", "log", "-1", "--format=%an <%ae>", targetBranch)
	logCmd.Dir = bareDir
	logOut, err := logCmd.CombinedOutput()
	if err != nil {
		t.Fatalf("Failed to get log: %v", err)
	}
	if strings.TrimSpace(string(logOut)) != "Test <test@kratis.ai>" {
		t.Errorf("Expected commit author to be 'Test <test@kratis.ai>', got: %q", strings.TrimSpace(string(logOut)))
	}
}

func TestExecuteGitPush_PreservesConfiguredUserIdentity(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	// Configure a specific developer identity
	_ = exec.Command("git", "-C", workDir, "config", "user.name", "Alice Engineer").Run()
	_ = exec.Command("git", "-C", workDir, "config", "user.email", "alice@enterprise.com").Run()

	client := newTestClient(t, workDir)

	// Add uncommitted file
	file := filepath.Join(workDir, "alice_work.txt")
	if err := os.WriteFile(file, []byte("Alice's work\n"), 0600); err != nil {
		t.Fatalf("Failed to write file: %v", err)
	}

	targetBranch := "kratis/alice-feature"
	client.ExecuteGitPush(GitPushParams{
		BranchName:    "kratis/alice-feature",
		CommitMessage: "Alice commit",
		DefaultBranch: "main",
		CreateBranch:  true,
	}, "req-push-alice")

	// Verify commit author was preserved as Alice Engineer
	logCmd := exec.Command("git", "log", "-1", "--format=%an <%ae>", targetBranch)
	logCmd.Dir = bareDir
	logOut, err := logCmd.CombinedOutput()
	if err != nil {
		t.Fatalf("Failed to get log: %v", err)
	}
	if strings.TrimSpace(string(logOut)) != "Alice Engineer <alice@enterprise.com>" {
		t.Errorf("Expected commit author to be 'Alice Engineer <alice@enterprise.com>', got: %q", strings.TrimSpace(string(logOut)))
	}
}

func TestExecuteGitPush_Squash(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client := newTestClient(t, workDir)

	// Create 2 intermediate commits
	f1 := filepath.Join(workDir, "f1.txt")
	_ = os.WriteFile(f1, []byte("f1\n"), 0600)
	_ = exec.Command("git", "-C", workDir, "add", "f1.txt").Run()
	_ = exec.Command("git", "-C", workDir, "commit", "-m", "Commit 1").Run()

	f2 := filepath.Join(workDir, "f2.txt")
	_ = os.WriteFile(f2, []byte("f2\n"), 0600)
	_ = exec.Command("git", "-C", workDir, "add", "f2.txt").Run()
	_ = exec.Command("git", "-C", workDir, "commit", "-m", "Commit 2").Run()

	targetBranch := "kratis/squashed-feature"
	client.ExecuteGitPush(GitPushParams{
		BranchName:    targetBranch,
		CommitMessage: "Squashed commit message",
		Squash:        true,
		DefaultBranch: "main",
		CreateBranch:  true,
	}, "req-push-squash")

	// Verify remote has 1 single commit ahead of main
	countCmd := exec.Command("git", "rev-list", "--count", "main.."+targetBranch)
	countCmd.Dir = bareDir
	out, err := countCmd.CombinedOutput()
	if err != nil {
		t.Fatalf("Failed to count commits: %v, out: %s", err, string(out))
	}
	if strings.TrimSpace(string(out)) != "1" {
		t.Errorf("Expected 1 squashed commit ahead of main, got %s", strings.TrimSpace(string(out)))
	}
}

func TestExecuteGitPush_Validation(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client := newTestClient(t, workDir)

	// Empty branch name should not panic
	client.ExecuteGitPush(GitPushParams{
		BranchName:    "",
		DefaultBranch: "main",
	}, "req-empty-branch")
}

func TestExecuteGitPush_RequiresBranchName(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

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
	client.workspace = workDir

	client.ExecuteGitPush(GitPushParams{
		BranchName:    "",
		CommitMessage: "missing branch",
	}, "req-no-branch")

	var envelope jsonRpcErrorEnvelope
	found := false
	deadline := time.Now().Add(3 * time.Second)
	for !found && time.Now().Before(deadline) {
		select {
		case data := <-received:
			if err := json.Unmarshal(data, &envelope); err == nil && envelope.Error.Code == -32602 {
				found = true
			}
		case <-time.After(50 * time.Millisecond):
		}
	}
	if !found {
		t.Fatalf("Expected a -32602 invalid params error for missing branch")
	}
	if envelope.Error.Message != "Invalid parameters" {
		t.Errorf("Expected error message 'Invalid parameters', got %q", envelope.Error.Message)
	}
}

func runGitCmd(t *testing.T, dir string, args ...string) {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	cmd.Env = append(os.Environ(),
		"GIT_AUTHOR_NAME=Test",
		"GIT_AUTHOR_EMAIL=test@kratis.ai",
		"GIT_COMMITTER_NAME=Test",
		"GIT_COMMITTER_EMAIL=test@kratis.ai",
	)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("git in %s %v failed: %v, out: %s", dir, args, err, string(out))
	}
}

func gitOutput(t *testing.T, dir string, args ...string) string {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git in %s %v failed: %v, out: %s", dir, args, err, string(out))
	}
	return strings.TrimSpace(string(out))
}

// advanceMainOnBare pushes a new commit to the bare repository's main branch.
func advanceMainOnBare(t *testing.T, bareDir string, content string) {
	t.Helper()
	cloneDir, err := os.MkdirTemp("", "kratis-git-advance-main-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(cloneDir) }()

	runGitCmd(t, cloneDir, "clone", bareDir, ".")
	runGitCmd(t, cloneDir, "config", "user.name", "Test")
	runGitCmd(t, cloneDir, "config", "user.email", "test@kratis.ai")
	if err := os.WriteFile(filepath.Join(cloneDir, "README.md"), []byte(content), 0600); err != nil {
		t.Fatalf("Failed to write README: %v", err)
	}
	runGitCmd(t, cloneDir, "add", "README.md")
	runGitCmd(t, cloneDir, "commit", "-m", "chore: advance main")
	runGitCmd(t, cloneDir, "push", "origin", "main")
}

func TestExecuteGitPush_PreservesMultipleCommitsWithoutSquash(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client, received := connectedGitClient(t, workDir)

	// Commit 1
	_ = os.WriteFile(filepath.Join(workDir, "f1.txt"), []byte("f1\n"), 0600)
	_ = exec.Command("git", "-C", workDir, "add", ".").Run()
	_ = exec.Command("git", "-C", workDir, "commit", "-m", "commit 1").Run()

	// Commit 2
	_ = os.WriteFile(filepath.Join(workDir, "f2.txt"), []byte("f2\n"), 0600)
	_ = exec.Command("git", "-C", workDir, "add", ".").Run()
	_ = exec.Command("git", "-C", workDir, "commit", "-m", "commit 2").Run()

	client.ExecuteGitPublish(GitPushParams{
		BranchName:    "kratis/multi-commit",
		DefaultBranch: "main",
		CreateBranch:  true,
		Squash:        false,
	}, "req-push-multi")

	for len(received) > 0 {
		<-received
	}

	subjects := gitOutput(t, workDir, "log", "--format=%s", "HEAD")
	if !strings.Contains(subjects, "commit 1") || !strings.Contains(subjects, "commit 2") {
		t.Errorf("Expected commits preserved, got: %s", subjects)
	}
}

type jsonRpcErrorEnvelope struct {
	Error struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

func TestExecuteGitPush_RebaseConflictAbortsAndRestores(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

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
	client.workspace = workDir

	// First publish modifies README.md.
	if err := os.WriteFile(filepath.Join(workDir, "README.md"), []byte("# Feature change\n"), 0600); err != nil {
		t.Fatalf("Failed to write README: %v", err)
	}
	client.ExecuteGitPush(GitPushParams{
		BranchName:    "kratis/feature",
		CommitMessage: "feat: initial feature",
		Squash:        true,
		TargetBranch:  "main",
	}, "req-push-1")
	for len(received) > 0 {
		<-received
	}

	// Upstream main advances with a conflicting edit to the same file.
	advanceMainOnBare(t, bareDir, "# Conflicting main change\n")

	// Since main advanced, git_rebase detects the conflict and aborts
	client.ExecuteGitRebase(GitRebaseParams{
		DefaultBranch: "main",
	}, "req-rebase-conflict")

	var envelope jsonRpcErrorEnvelope
	found := false
	deadline := time.Now().Add(3 * time.Second)
	for !found && time.Now().Before(deadline) {
		select {
		case data := <-received:
			if err := json.Unmarshal(data, &envelope); err == nil && envelope.Error.Code == -32001 {
				found = true
			}
		case <-time.After(50 * time.Millisecond):
		}
	}
	if !found {
		t.Fatalf("Expected a REBASE_CONFLICT error response, got none")
	}
	if !strings.Contains(envelope.Error.Message, "REBASE_CONFLICT") {
		t.Errorf("Expected error message to contain REBASE_CONFLICT, got %q", envelope.Error.Message)
	}
}

type jsonRpcSuccessEnvelope[T any] struct {
	Result T `json:"result"`
}

// setupMergedFeatureBranch creates a feature branch, pushes it, merges it into
// main on the bare remote, and leaves the workspace checked out on the feature.
func setupMergedFeatureBranch(t *testing.T, bareDir, workDir, branch string) {
	t.Helper()
	runGitCmd(t, workDir, "checkout", "-b", branch)
	if err := os.WriteFile(filepath.Join(workDir, "feature.txt"), []byte("feature\n"), 0600); err != nil {
		t.Fatalf("Failed to write feature file: %v", err)
	}
	runGitCmd(t, workDir, "add", "feature.txt")
	runGitCmd(t, workDir, "commit", "-m", "feat: merged feature")
	runGitCmd(t, workDir, "push", "-u", "origin", branch)

	mergeDir, err := os.MkdirTemp("", "kratis-git-merge-feature-*")
	if err != nil {
		t.Fatalf("Failed to create merge temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(mergeDir) }()
	runGitCmd(t, mergeDir, "clone", bareDir, ".")
	runGitCmd(t, mergeDir, "config", "user.name", "Test")
	runGitCmd(t, mergeDir, "config", "user.email", "test@kratis.ai")
	runGitCmd(t, mergeDir, "merge", "--no-ff", "-m", "Merge "+branch, "origin/"+branch)
	runGitCmd(t, mergeDir, "push", "origin", "main")
}

func TestExecuteGitPush_NewBranchAfterPreviousBranchMerged(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	const oldBranch = "kratis/feature-123"
	setupMergedFeatureBranch(t, bareDir, workDir, oldBranch)

	if err := os.WriteFile(filepath.Join(workDir, "delta.txt"), []byte("delta\n"), 0600); err != nil {
		t.Fatalf("Failed to write delta: %v", err)
	}

	client, received := connectedGitClient(t, workDir)
	// Under the new target state, the control plane decides the new branch name and passes CreateBranch: true
	client.ExecuteGitPublish(GitPushParams{
		BranchName:    "kratis/exec-abc-2",
		CommitMessage: "feat: follow-up after merge",
		Squash:        true,
		DefaultBranch: "main",
		CreateBranch:  true,
		ExecutionID:   "exec-abc",
	}, "req-push-new-branch")

	var envelope jsonRpcSuccessEnvelope[GitPushResult]
	found := false
	deadline := time.Now().Add(3 * time.Second)
	for !found && time.Now().Before(deadline) {
		select {
		case data := <-received:
			if err := json.Unmarshal(data, &envelope); err == nil && envelope.Result.BranchName != "" {
				found = true
			}
		case <-time.After(50 * time.Millisecond):
		}
	}
	if !found {
		t.Fatal("Expected a successful git_push response")
	}

	if envelope.Result.BranchName != "kratis/exec-abc-2" {
		t.Errorf("Expected derived branch kratis/exec-abc-2, got %q", envelope.Result.BranchName)
	}

	// The new branch must exist remotely and contain the follow-up delta.
	if got := gitOutput(t, bareDir, "rev-parse", envelope.Result.BranchName); got == "" {
		t.Fatal("Expected the derived branch to exist on the remote")
	}
	files := gitOutput(t, bareDir, "diff", "--name-only", "main.."+envelope.Result.BranchName)
	if !strings.Contains(files, "delta.txt") {
		t.Errorf("Expected delta.txt on the new branch, got %q", files)
	}
}

func TestRemoteBranchHeadSha_ErrorIsNotTreatedAsAbsent(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kratis-remote-test-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(tempDir) }()

	client := newTestClient(t, tempDir)
	// In a directory that is not a git repo or remote doesn't exist, git ls-remote fails with exit 128.
	_, err = client.remoteBranchHeadSha(tempDir, os.Environ(), "some-branch")
	if err == nil {
		t.Errorf("Expected error from remoteBranchHeadSha on non-git dir, got nil")
	}
}
