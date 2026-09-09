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

	return bareDir, workDir
}

func TestExecuteGitPush_SuccessWithUncommittedChanges(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client := &Client{workspace: workDir}

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
		TargetBranch:  "main",
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

	client := &Client{workspace: workDir}

	// Add uncommitted file
	file := filepath.Join(workDir, "alice_work.txt")
	if err := os.WriteFile(file, []byte("Alice's work\n"), 0600); err != nil {
		t.Fatalf("Failed to write file: %v", err)
	}

	targetBranch := "kratis/alice-feature"
	client.ExecuteGitPush(GitPushParams{
		BranchName:    targetBranch,
		CommitMessage: "Alice's feature updates",
		TargetBranch:  "main",
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

	client := &Client{workspace: workDir}

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
		CommitMessage: "feat: squashed feature commit",
		Squash:        true,
		TargetBranch:  "main",
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

	client := &Client{workspace: workDir}

	// Empty branch name should not panic
	client.ExecuteGitPush(GitPushParams{
		BranchName: "",
	}, "req-empty-branch")
}

func TestExecuteGitPush_RequiresTargetBranch(t *testing.T) {
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
		BranchName:    "kratis/feature",
		CommitMessage: "missing target branch",
	}, "req-no-target")

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
		t.Fatalf("Expected a -32602 invalid params error for missing targetBranch")
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

func TestExecuteGitPush_RepublishSquashesOnlyDelta(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client := &Client{workspace: workDir}

	// First publish: modify README and push an initial feature branch.
	if err := os.WriteFile(filepath.Join(workDir, "README.md"), []byte("# Updated Readme\n"), 0600); err != nil {
		t.Fatalf("Failed to write README: %v", err)
	}
	client.ExecuteGitPush(GitPushParams{
		BranchName:    "kratis/feature",
		CommitMessage: "feat: initial feature",
		Squash:        true,
		TargetBranch:  "main",
	}, "req-push-1")

	first := gitOutput(t, bareDir, "rev-parse", "kratis/feature")

	// Second publish: new uncommitted delta only.
	if err := os.WriteFile(filepath.Join(workDir, "delta.txt"), []byte("delta\n"), 0600); err != nil {
		t.Fatalf("Failed to write delta: %v", err)
	}
	client.ExecuteGitPush(GitPushParams{
		BranchName:    "kratis/feature",
		CommitMessage: "feat: incremental delta",
		Squash:        true,
		TargetBranch:  "main",
	}, "req-push-2")

	count := gitOutput(t, bareDir, "rev-list", "--count", "main..kratis/feature")
	if count != "2" {
		t.Errorf("Expected 2 commits ahead of main after republish (pushed tip + delta), got %s", count)
	}

	// The previously pushed commit must still exist in history.
	subjects := gitOutput(t, bareDir, "log", "--format=%s", "kratis/feature")
	if !strings.Contains(subjects, "feat: initial feature") {
		t.Errorf("Expected previously pushed commit subject to be preserved, got:\n%s", subjects)
	}
	if !strings.Contains(subjects, "feat: incremental delta") {
		t.Errorf("Expected delta commit subject to be present, got:\n%s", subjects)
	}

	second := gitOutput(t, bareDir, "rev-parse", "kratis/feature")
	if first == second {
		t.Errorf("Expected feature branch to advance on republish")
	}
}

func TestExecuteGitPush_RebasesOntoAdvancedTarget(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client := &Client{workspace: workDir}

	// First publish creates the feature branch based on the old main, touching
	// only feature.txt so the upstream main change does not conflict.
	if err := os.WriteFile(filepath.Join(workDir, "feature.txt"), []byte("feature\n"), 0600); err != nil {
		t.Fatalf("Failed to write feature file: %v", err)
	}
	client.ExecuteGitPush(GitPushParams{
		BranchName:    "kratis/feature",
		CommitMessage: "feat: initial feature",
		Squash:        true,
		TargetBranch:  "main",
	}, "req-push-1")

	// Upstream main advances with unrelated changes.
	advanceMainOnBare(t, bareDir, "# Advanced main\n")

	// New local delta, republish with targetBranch; the branch must be rebased
	// onto the advanced main before pushing.
	if err := os.WriteFile(filepath.Join(workDir, "delta.txt"), []byte("delta\n"), 0600); err != nil {
		t.Fatalf("Failed to write delta: %v", err)
	}
	client.ExecuteGitPush(GitPushParams{
		BranchName:    "kratis/feature",
		CommitMessage: "feat: rebased delta",
		Squash:        true,
		TargetBranch:  "main",
		Force:         true,
	}, "req-push-2")

	// The advanced main must now be an ancestor of the feature branch.
	isAncestor := exec.Command("git", "merge-base", "--is-ancestor", "main", "kratis/feature")
	isAncestor.Dir = bareDir
	if err := isAncestor.Run(); err != nil {
		t.Errorf("Expected advanced main to be an ancestor of the rebased feature branch")
	}

	// Exactly the replayed old feature commit + the new delta.
	count := gitOutput(t, bareDir, "rev-list", "--count", "main..kratis/feature")
	if count != "2" {
		t.Errorf("Expected 2 commits ahead of advanced main after rebase, got %s", count)
	}
	subjects := gitOutput(t, bareDir, "log", "--format=%s", "kratis/feature")
	if !strings.Contains(subjects, "feat: rebased delta") {
		t.Errorf("Expected delta commit subject to be present after rebase, got:\n%s", subjects)
	}
}

func TestExecuteGitPush_RepublishPreservesExternalPushes(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client := &Client{workspace: workDir}

	if err := os.WriteFile(filepath.Join(workDir, "README.md"), []byte("# Feature change\n"), 0600); err != nil {
		t.Fatalf("Failed to write README: %v", err)
	}
	client.ExecuteGitPush(GitPushParams{
		BranchName:    "kratis/feature",
		CommitMessage: "feat: initial feature",
		Squash:        true,
		TargetBranch:  "main",
	}, "req-push-1")

	// Another contributor pushes a commit to the feature branch.
	externalDir, err := os.MkdirTemp("", "kratis-git-external-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(externalDir) }()
	runGitCmd(t, externalDir, "clone", bareDir, ".")
	runGitCmd(t, externalDir, "config", "user.name", "External")
	runGitCmd(t, externalDir, "config", "user.email", "external@kratis.ai")
	runGitCmd(t, externalDir, "checkout", "kratis/feature")
	if err := os.WriteFile(filepath.Join(externalDir, "external.txt"), []byte("external\n"), 0600); err != nil {
		t.Fatalf("Failed to write external file: %v", err)
	}
	runGitCmd(t, externalDir, "add", "external.txt")
	runGitCmd(t, externalDir, "commit", "-m", "feat: external contribution")
	runGitCmd(t, externalDir, "push", "origin", "kratis/feature")

	// Local republish with a new delta must rebase onto the external push.
	if err := os.WriteFile(filepath.Join(workDir, "delta.txt"), []byte("delta\n"), 0600); err != nil {
		t.Fatalf("Failed to write delta: %v", err)
	}
	client.ExecuteGitPush(GitPushParams{
		BranchName:    "kratis/feature",
		CommitMessage: "feat: local delta",
		Squash:        true,
		TargetBranch:  "main",
		Force:         true,
	}, "req-push-2")

	subjects := gitOutput(t, bareDir, "log", "--format=%s", "kratis/feature")
	if !strings.Contains(subjects, "feat: external contribution") {
		t.Errorf("Expected external contribution to be preserved, got:\n%s", subjects)
	}
	if !strings.Contains(subjects, "feat: local delta") {
		t.Errorf("Expected local delta commit to be present, got:\n%s", subjects)
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
	preConflictTip := gitOutput(t, workDir, "rev-parse", "HEAD")

	// Upstream main advances with a conflicting edit to the same file.
	advanceMainOnBare(t, bareDir, "# Conflicting main change\n")

	// Local conflicting edit.
	if err := os.WriteFile(filepath.Join(workDir, "README.md"), []byte("# Local conflicting change\n"), 0600); err != nil {
		t.Fatalf("Failed to write README: %v", err)
	}
	client.ExecuteGitPush(GitPushParams{
		BranchName:    "kratis/feature",
		CommitMessage: "feat: conflicting delta",
		TargetBranch:  "main",
		Force:         true,
	}, "req-push-conflict")

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

	// The local branch must keep its delta commit and the failed rebase must not
	// have applied the upstream commits; the remote feature branch is unchanged.
	subjects := gitOutput(t, workDir, "log", "--format=%s", "HEAD")
	if !strings.Contains(subjects, "feat: conflicting delta") {
		t.Errorf("Expected the local delta commit to be preserved after abort, got:\n%s", subjects)
	}
	if strings.Contains(subjects, "chore: advance main") {
		t.Errorf("Expected the failed rebase not to apply upstream commits, got:\n%s", subjects)
	}
	if remoteTip := gitOutput(t, bareDir, "rev-parse", "kratis/feature"); remoteTip != preConflictTip {
		t.Errorf("Expected remote feature branch to remain at its pushed state, got %s want %s", remoteTip, preConflictTip)
	}
}
