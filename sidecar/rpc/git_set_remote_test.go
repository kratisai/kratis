package rpc

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// setupTestProvisionedNewRepo mirrors sandbox provisioning for a new repository:
// git init with a single empty "Initial commit" and no remote.
func setupTestProvisionedNewRepo(t *testing.T) (string, string) {
	t.Helper()
	dir, err := os.MkdirTemp("", "kratis-git-new-repo-work-*")
	if err != nil {
		t.Fatalf("Failed to create work temp dir: %v", err)
	}
	runGitCmd(t, dir, "init")
	runGitCmd(t, dir, "config", "user.name", "Test")
	runGitCmd(t, dir, "config", "user.email", "test@kratis.ai")
	runGitCmd(t, dir, "commit", "--allow-empty", "-m", "Initial commit")
	return dir, gitOutput(t, dir, "rev-parse", "HEAD")
}

func setupTestBareRemote(t *testing.T) string {
	t.Helper()
	dir, err := os.MkdirTemp("", "kratis-git-new-repo-bare-*")
	if err != nil {
		t.Fatalf("Failed to create bare temp dir: %v", err)
	}
	runGitCmd(t, dir, "init", "--bare", "-b", "main")
	return dir
}

func TestExecuteGitSetRemote_SeedsDefaultBranch(t *testing.T) {
	workDir, rootSHA := setupTestProvisionedNewRepo(t)
	remoteDir := setupTestBareRemote(t)
	defer func() {
		_ = os.RemoveAll(workDir)
		_ = os.RemoveAll(remoteDir)
	}()

	client, received := connectedGitClient(t, workDir)
	client.ExecuteGitSetRemote(
		GitSetRemoteParams{RemoteURL: remoteDir, DefaultBranch: "main", ExecutionID: "exec-new"},
		"req-set-remote")

	var envelope struct {
		Result GitSetRemoteResult `json:"result"`
	}
	if err := json.Unmarshal(captureNextResponse(t, received), &envelope); err != nil {
		t.Fatalf("Failed to unmarshal set_remote result: %v", err)
	}
	result := envelope.Result
	if result.Status != "success" {
		t.Errorf("Expected status success, got %q", result.Status)
	}
	if result.DefaultBranch != "main" {
		t.Errorf("Expected defaultBranch main, got %q", result.DefaultBranch)
	}
	if result.SeedCommit != rootSHA {
		t.Errorf("Expected seedCommit %s, got %s", rootSHA, result.SeedCommit)
	}

	remoteMain := gitOutput(t, workDir, "rev-parse", "refs/remotes/origin/main")
	if remoteMain != rootSHA {
		t.Errorf("Expected origin/main at the seed commit %s, got %s", rootSHA, remoteMain)
	}
}

func TestExecuteGitPush_NewRepoAfterSetRemote(t *testing.T) {
	workDir, rootSHA := setupTestProvisionedNewRepo(t)
	remoteDir := setupTestBareRemote(t)
	defer func() {
		_ = os.RemoveAll(workDir)
		_ = os.RemoveAll(remoteDir)
	}()

	client, received := connectedGitClient(t, workDir)
	client.ExecuteGitSetRemote(
		GitSetRemoteParams{RemoteURL: remoteDir, DefaultBranch: "main", ExecutionID: "exec-new"},
		"req-set-remote")
	_ = captureNextResponse(t, received)

	if err := os.WriteFile(filepath.Join(workDir, "main.go"), []byte("package main\n"), 0600); err != nil {
		t.Fatalf("Failed to write main.go: %v", err)
	}

	client.ExecuteGitPush(
		GitPushParams{
			BranchName:    "kratis/feature",
			CommitMessage: "feat: initial project",
			Squash:        true,
			TargetBranch:  "main",
			ExecutionID:   "exec-new",
		},
		"req-push")

	var envelope struct {
		Result GitPushResult `json:"result"`
	}
	if err := json.Unmarshal(captureNextResponse(t, received), &envelope); err != nil {
		t.Fatalf("Failed to unmarshal push result: %v", err)
	}
	if envelope.Result.Status != "success" {
		t.Fatalf("Expected push success, got %q", envelope.Result.Status)
	}

	bareMain := gitOutput(t, remoteDir, "rev-parse", "refs/heads/main")
	if bareMain != rootSHA {
		t.Errorf("Expected remote main to remain at the seed commit %s, got %s", rootSHA, bareMain)
	}
	featureTip := gitOutput(t, remoteDir, "rev-parse", "refs/heads/kratis/feature")
	if featureTip != envelope.Result.CommitSha {
		t.Errorf("Expected feature branch tip %s, got %s", envelope.Result.CommitSha, featureTip)
	}
}
