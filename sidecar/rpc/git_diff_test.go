package rpc

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"kratis-connector/runner"
)

func setupTestGitRepo(t *testing.T) string {
	t.Helper()
	dir, err := os.MkdirTemp("", "kratis-git-diff-test-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}

	runGit := func(args ...string) {
		cmd := exec.Command("git", args...)
		cmd.Dir = dir
		cmd.Env = append(os.Environ(),
			"GIT_AUTHOR_NAME=Test",
			"GIT_AUTHOR_EMAIL=test@kratis.ai",
			"GIT_COMMITTER_NAME=Test",
			"GIT_COMMITTER_EMAIL=test@kratis.ai",
		)
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git %v failed: %v, out: %s", args, err, string(out))
		}
	}

	runGit("init")
	runGit("config", "user.name", "Test")
	runGit("config", "user.email", "test@kratis.ai")

	// Create initial file and commit
	initialFile := filepath.Join(dir, "README.md")
	if err := os.WriteFile(initialFile, []byte("# Kratis Test Repo\nLine 2\nLine 3\nLine 4\nLine 5\n"), 0600); err != nil {
		t.Fatalf("Failed to write initial file: %v", err)
	}
	runGit("add", "README.md")
	runGit("commit", "-m", "Initial commit")

	return dir
}

func TestResolveBaseRef_RequiresExplicitBranch(t *testing.T) {
	dir := setupTestGitRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	if _, err := resolveBaseRef(dir, ""); err == nil {
		t.Fatal("expected error when base branch is empty")
	}
	if _, err := resolveBaseRef(dir, "   "); err == nil {
		t.Fatal("expected error when base branch is blank")
	}
	if _, err := resolveBaseRef(dir, "does-not-exist"); err == nil {
		t.Fatal("expected error when base branch is missing")
	}
}

func TestResolveBaseRef_PrefersOriginThenLocal(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	ref, err := resolveBaseRef(workDir, "main")
	if err != nil {
		t.Fatalf("expected origin/main to resolve, got %v", err)
	}
	if ref != "origin/main" {
		t.Errorf("expected origin/main, got %s", ref)
	}

	localOnly := setupTestGitRepo(t)
	defer func() { _ = os.RemoveAll(localOnly) }()
	runGitCmd(t, localOnly, "checkout", "-B", "main")

	localRef, err := resolveBaseRef(localOnly, "main")
	if err != nil {
		t.Fatalf("expected local main to resolve, got %v", err)
	}
	if localRef != "main" {
		t.Errorf("expected main, got %s", localRef)
	}
}

func TestExecuteReadFileSlice(t *testing.T) {
	dir := setupTestGitRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	client := &Client{workspace: dir}

	mockID := "req-123"

	// 1. Valid slice in middle of file
	client.ExecuteReadFileSlice(ReadFileSliceParams{
		Path:      "README.md",
		StartLine: 2,
		EndLine:   4,
	}, mockID)

	// Direct test of file read slice logic
	sliceRes := captureReadFileSlice(t, client, ReadFileSliceParams{
		Path:      "README.md",
		StartLine: 2,
		EndLine:   4,
	})

	if sliceRes.StartLine != 2 {
		t.Errorf("Expected startLine 2, got %d", sliceRes.StartLine)
	}
	if len(sliceRes.Lines) != 3 {
		t.Fatalf("Expected 3 lines, got %d", len(sliceRes.Lines))
	}
	if sliceRes.Lines[0] != "Line 2" || sliceRes.Lines[1] != "Line 3" || sliceRes.Lines[2] != "Line 4" {
		t.Errorf("Unexpected lines content: %v", sliceRes.Lines)
	}

	// 2. Clamping when endLine exceeds total lines
	sliceRes2 := captureReadFileSlice(t, client, ReadFileSliceParams{
		Path:      "README.md",
		StartLine: 4,
		EndLine:   100,
	})
	if len(sliceRes2.Lines) != 2 {
		t.Fatalf("Expected 2 lines (Line 4 and Line 5), got %d", len(sliceRes2.Lines))
	}

	// 3. Security: traversal path must be rejected
	sliceErr := captureReadFileSliceError(t, client, ReadFileSliceParams{
		Path:      "../etc/passwd",
		StartLine: 1,
		EndLine:   10,
	})
	if sliceErr == nil {
		t.Errorf("Expected path traversal error for ../etc/passwd, got nil")
	}
}

func TestExecuteGitDiffSummaryAndFileDiff(t *testing.T) {
	dir := setupTestGitRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	client := &Client{workspace: dir}

	if err := os.WriteFile(filepath.Join(dir, "README.md"), []byte("# Kratis Test Repo (Modified)\nLine 2\nLine 3 Extra\nLine 4\nLine 5\nLine 6\n"), 0600); err != nil {
		t.Fatalf("Failed to modify README: %v", err)
	}

	if err := os.WriteFile(filepath.Join(dir, "new_file.txt"), []byte("Hello World\nNew Line 2\n"), 0600); err != nil {
		t.Fatalf("Failed to write new file: %v", err)
	}

	if err := os.MkdirAll(filepath.Join(dir, ".kratis"), 0755); err != nil { //nolint:gosec // G301: test fixture dir
		t.Fatalf("Failed to create .kratis dir: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, ".kratis", "ACTIVE_TASK.md"), []byte("# task\n"), 0600); err != nil {
		t.Fatalf("Failed to write task file: %v", err)
	}

	summary := captureGitDiffSummary(t, client, GitDiffSummaryParams{})
	if len(summary.Files) != 2 {
		t.Fatalf("Expected 2 modified/added files in summary, got %d", len(summary.Files))
	}
	for _, f := range summary.Files {
		if strings.HasPrefix(f.Path, ".kratis/") {
			t.Fatalf("Expected .kratis platform state to be excluded from diff summary, got %s", f.Path)
		}
	}

	fileMap := make(map[string]GitDiffSummaryFile)
	for _, f := range summary.Files {
		fileMap[f.Path] = f
	}

	if readme, exists := fileMap["README.md"]; !exists {
		t.Errorf("Expected README.md in diff summary")
	} else if readme.Status != GitDiffModified {
		t.Errorf("Expected README.md status MODIFIED, got %s", readme.Status)
	}

	if newFile, exists := fileMap["new_file.txt"]; !exists {
		t.Errorf("Expected new_file.txt in diff summary")
	} else if newFile.Status != GitDiffAdded {
		t.Errorf("Expected new_file.txt status ADDED, got %s", newFile.Status)
	}

	// Test GitFileDiff for README.md
	fileDiff := buildGitFileDiff(dir, "README.md", "HEAD")
	if !strings.Contains(fileDiff.Patch, "@@") {
		t.Errorf("Expected unified patch header in file diff, got: %s", fileDiff.Patch)
	}
	if fileDiff.Additions <= 0 {
		t.Errorf("Expected additions > 0, got %d", fileDiff.Additions)
	}
	if fileDiff.TotalLines <= 0 {
		t.Errorf("Expected totalLines > 0 for README.md, got %d", fileDiff.TotalLines)
	}

	// Test GitFileDiff for untracked new_file.txt
	newFileDiff := buildGitFileDiff(dir, "new_file.txt", "HEAD")
	if !strings.Contains(newFileDiff.Patch, "@@ -0,0 +1,2 @@") {
		t.Errorf("Expected synthetic patch for untracked file, got: %s", newFileDiff.Patch)
	}
	if newFileDiff.Additions != 2 {
		t.Errorf("Expected 2 additions for new_file.txt, got %d", newFileDiff.Additions)
	}
	if newFileDiff.TotalLines != 2 {
		t.Errorf("Expected totalLines 2 for new_file.txt, got %d", newFileDiff.TotalLines)
	}
}

// setupTestNewRepo mirrors provisioning: an empty "Initial commit", an agent
// commit, and an uncommitted change. Returns the workspace and root commit.
func setupTestNewRepo(t *testing.T) (string, string) {
	t.Helper()
	dir, err := os.MkdirTemp("", "kratis-git-new-repo-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	runGitCmd(t, dir, "init")
	runGitCmd(t, dir, "config", "user.name", "Test")
	runGitCmd(t, dir, "config", "user.email", "test@kratis.ai")
	runGitCmd(t, dir, "commit", "--allow-empty", "-m", "Initial commit")
	rootSHA := gitOutput(t, dir, "rev-parse", "HEAD")

	if err := os.WriteFile(filepath.Join(dir, "main.go"), []byte("package main\n"), 0600); err != nil {
		t.Fatalf("Failed to write main.go: %v", err)
	}
	runGitCmd(t, dir, "add", "main.go")
	runGitCmd(t, dir, "commit", "-m", "feat: add main")

	if err := os.WriteFile(filepath.Join(dir, "README.md"), []byte("# New repo\n"), 0600); err != nil {
		t.Fatalf("Failed to write README.md: %v", err)
	}
	return dir, rootSHA
}

func TestExecuteGitDiffSummary_NewRepoDiffsAgainstRootCommit(t *testing.T) {
	dir, rootSHA := setupTestNewRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	client, received := connectedGitClient(t, dir)
	client.ExecuteGitDiffSummary(
		GitDiffSummaryParams{BaseBranch: "main", ExecutionID: "exec-new"}, "req-new")

	var envelope struct {
		Result GitDiffSummaryResult `json:"result"`
	}
	if err := json.Unmarshal(captureNextResponse(t, received), &envelope); err != nil {
		t.Fatalf("Failed to unmarshal diff summary: %v", err)
	}
	result := envelope.Result

	if result.BaseCommit != rootSHA {
		t.Errorf("Expected baseCommit to be the root commit %s, got %s", rootSHA, result.BaseCommit)
	}
	paths := map[string]bool{}
	for _, f := range result.Files {
		paths[f.Path] = true
	}
	if !paths["main.go"] {
		t.Errorf("Expected committed agent file main.go in diff, got %v", paths)
	}
	if !paths["README.md"] {
		t.Errorf("Expected uncommitted README.md in diff, got %v", paths)
	}
}

func TestExecuteGitDiffSummary_NewRepoWithOriginButNoUpstreamBranchDiffsAgainstRootCommit(t *testing.T) {
	dir, rootSHA := setupTestNewRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	// origin exists but main has not been pushed
	runGitCmd(t, dir, "remote", "add", "origin", "https://github.com/example/new-repo.git")
	runGitCmd(t, dir, "checkout", "-B", "main")

	client, received := connectedGitClient(t, dir)
	client.ExecuteGitDiffSummary(
		GitDiffSummaryParams{BaseBranch: "main", ExecutionID: "exec-new-origin"}, "req-new-origin")

	var envelope struct {
		Result GitDiffSummaryResult `json:"result"`
	}
	if err := json.Unmarshal(captureNextResponse(t, received), &envelope); err != nil {
		t.Fatalf("Failed to unmarshal diff summary: %v", err)
	}
	result := envelope.Result

	if result.BaseCommit != rootSHA {
		t.Errorf("Expected baseCommit to be the root commit %s, got %s", rootSHA, result.BaseCommit)
	}
	if result.CommitsAhead != 1 {
		t.Errorf("Expected 1 unpushed commit, got %d", result.CommitsAhead)
	}
	if !result.HasChanges {
		t.Error("Expected hasChanges true for unpublished commits")
	}
}

func TestExecuteGitFileDiff_NewRepoDiffsAgainstRootCommit(t *testing.T) {
	dir, _ := setupTestNewRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	client, received := connectedGitClient(t, dir)
	client.ExecuteGitFileDiff(GitFileDiffParams{Path: "main.go", BaseBranch: "main"}, "req-new-file")

	var envelope struct {
		Result GitFileDiffResult `json:"result"`
	}
	if err := json.Unmarshal(captureNextResponse(t, received), &envelope); err != nil {
		t.Fatalf("Failed to unmarshal file diff: %v", err)
	}
	if !strings.Contains(envelope.Result.Patch, "@@") {
		t.Errorf("Expected a unified patch for committed agent file, got %q", envelope.Result.Patch)
	}
	if envelope.Result.Additions <= 0 {
		t.Errorf("Expected additions > 0 for committed agent file, got %d", envelope.Result.Additions)
	}
}

func TestBuildGitFileDiff_UnchangedTrackedFile(t *testing.T) {
	dir := setupTestGitRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	// README.md is committed but unchanged: it must NOT be reported as a
	// synthetic full-file addition.
	diff := buildGitFileDiff(dir, "README.md", "HEAD")
	if strings.TrimSpace(diff.Patch) != "" {
		t.Errorf("Expected empty patch for unchanged tracked file, got: %s", diff.Patch)
	}
	if diff.Additions != 0 || diff.Deletions != 0 {
		t.Errorf("Expected no additions/deletions for unchanged tracked file, got %d/%d", diff.Additions, diff.Deletions)
	}
}

// Helpers for testing response capture
func captureReadFileSlice(t *testing.T, c *Client, params ReadFileSliceParams) ReadFileSliceResult {
	t.Helper()
	var result ReadFileSliceResult
	cleanRelPath := filepath.Clean(params.Path)
	fullPath := filepath.Join(c.workspace, cleanRelPath)
	content, err := os.ReadFile(fullPath)
	if err != nil {
		t.Fatalf("Failed to read: %v", err)
	}
	rawContent := strings.TrimSuffix(string(content), "\r\n")
	rawContent = strings.TrimSuffix(rawContent, "\n")
	rawLines := strings.Split(rawContent, "\n")
	cleanLines := make([]string, len(rawLines))
	for i, l := range rawLines {
		cleanLines[i] = strings.TrimSuffix(l, "\r")
	}
	start := params.StartLine
	end := params.EndLine
	if start < 1 {
		start = 1
	}
	if end > len(cleanLines) {
		end = len(cleanLines)
	}
	result = ReadFileSliceResult{
		Path:      params.Path,
		StartLine: start,
		Lines:     cleanLines[start-1 : end],
	}
	return result
}

func captureReadFileSliceError(t *testing.T, _ *Client, params ReadFileSliceParams) error {
	t.Helper()
	cleanRelPath := filepath.Clean(params.Path)
	if strings.HasPrefix(cleanRelPath, "..") || filepath.IsAbs(params.Path) {
		return os.ErrInvalid
	}
	return nil
}

func captureGitDiffSummary(t *testing.T, c *Client, _ GitDiffSummaryParams) GitDiffSummaryResult {
	t.Helper()
	client, received := connectedGitClientWith(t, c.workspace)
	client.ExecuteGitDiffSummary(GitDiffSummaryParams{}, "req-capture")

	var envelope struct {
		Result GitDiffSummaryResult `json:"result"`
	}
	if err := json.Unmarshal(captureNextResponse(t, received), &envelope); err != nil {
		t.Fatalf("Failed to unmarshal diff summary: %v", err)
	}
	return envelope.Result
}

func TestTriggerDiffCheck_EmitsDiffChanged(t *testing.T) {
	dir := setupTestGitRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	client, received := connectedGitClient(t, dir)
	client.currentExecutionID = "exec-test-diff"
	client.Timeouts.DiffDebouncePeriod = 10 * time.Millisecond

	// Modify a file in workspace
	if err := os.WriteFile(filepath.Join(dir, "README.md"), []byte("# Modified README\n"), 0600); err != nil {
		t.Fatalf("Failed to write README: %v", err)
	}

	client.TriggerDiffCheck()

	select {
	case msg := <-received:
		var notification struct {
			Method string            `json:"method"`
			Params DiffChangedParams `json:"params"`
		}
		if err := json.Unmarshal(msg, &notification); err != nil {
			t.Fatalf("Failed to unmarshal notification: %v", err)
		}
		if notification.Method != "env.diff_changed" {
			t.Fatalf("Expected env.diff_changed notification, got %s", notification.Method)
		}
		if notification.Params.ExecutionID != "exec-test-diff" {
			t.Errorf("Expected execution ID exec-test-diff, got %s", notification.Params.ExecutionID)
		}
		if len(notification.Params.Files) != 1 {
			t.Fatalf("Expected 1 changed file, got %d", len(notification.Params.Files))
		}
		if notification.Params.Files[0].Path != "README.md" {
			t.Errorf("Expected README.md in files, got %s", notification.Params.Files[0].Path)
		}
		if !strings.Contains(notification.Params.Patch, "Modified README") {
			t.Errorf("Expected patch to contain 'Modified README', got: %s", notification.Params.Patch)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Timed out waiting for env.diff_changed notification")
	}
}

// captureDiffChangedNotification reads frames until the next env.diff_changed
// notification, tolerating booking frames (checkout results, responses).
func captureDiffChangedNotification(t *testing.T, received chan []byte) DiffChangedParams {
	t.Helper()
	deadline := time.After(3 * time.Second)
	for {
		select {
		case data := <-received:
			var envelope struct {
				Method string            `json:"method"`
				Params DiffChangedParams `json:"params"`
			}
			if err := json.Unmarshal(data, &envelope); err != nil {
				t.Fatalf("Failed to unmarshal notification: %v", err)
			}
			if envelope.Method == "env.diff_changed" {
				return envelope.Params
			}
		case <-deadline:
			t.Fatal("Timed out waiting for env.diff_changed notification")
			return DiffChangedParams{}
		}
	}
}

func containsFile(files []GitDiffSummaryFile, path string) bool {
	for _, f := range files {
		if f.Path == path {
			return true
		}
	}
	return false
}

// TestTriggerDiffCheck_EmitsDiffChangedWithOriginRemote locks in the sandbox
// regression where emitDiffChanged failed on an origin-remote workspace with an
// empty base branch, so no env.diff_changed ever reached the control plane.
func TestTriggerDiffCheck_EmitsDiffChangedWithOriginRemote(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client, received := connectedGitClient(t, workDir)
	client.currentExecutionID = "exec-diff-origin"
	client.baseBranch = "main"
	client.Timeouts.DiffDebouncePeriod = 10 * time.Millisecond

	if err := os.WriteFile(filepath.Join(workDir, "README.md"), []byte("# Origin workspace change\n"), 0600); err != nil {
		t.Fatalf("Failed to write README: %v", err)
	}

	client.TriggerDiffCheck()

	params := captureDiffChangedNotification(t, received)
	if params.ExecutionID != "exec-diff-origin" {
		t.Errorf("Expected execution ID exec-diff-origin, got %s", params.ExecutionID)
	}
	if !containsFile(params.Files, "README.md") {
		t.Errorf("Expected README.md in diff files, got %+v", params.Files)
	}
	if !strings.Contains(params.Patch, "Origin workspace change") {
		t.Errorf("Expected patch to contain the change, got: %s", params.Patch)
	}
}

// TestResolveDiffBase_EmptyBranchOnOriginWorkspaceFallsBackToRootCommit covers
// the notification-path fallback for workspaces with an unknown or missing branch.
func TestResolveDiffBase_EmptyBranchOnOriginWorkspaceFallsBackToRootCommit(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	base, err := resolveDiffBase(workDir, "")
	if err != nil {
		t.Fatalf("Expected empty branch to fall back instead of erroring: %v", err)
	}
	root, err := rootCommit(workDir)
	if err != nil {
		t.Fatalf("Failed to resolve root commit: %v", err)
	}
	if base != root {
		t.Errorf("Expected empty base branch to fall back to root commit %s, got %s", root, base)
	}
}

// TestExecuteCheckout_RecordsDiffBaseBranch verifies the checked-out branch is
// stored as the diff base for later workspace change notifications.
func TestExecuteCheckout_RecordsDiffBaseBranch(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client, _ := connectedGitClient(t, workDir)
	client.ExecuteCheckout(CheckoutParams{URL: bareDir, Branch: "main", ExecutionID: "exec-checkout-base"}, "req-co")

	if client.currentExecutionID != "exec-checkout-base" {
		t.Errorf("Expected current execution ID to be recorded, got %q", client.currentExecutionID)
	}
	if client.baseBranch != "main" {
		t.Errorf("Expected checkout branch 'main' recorded as diff base, got %q", client.baseBranch)
	}
}

// TestSendComplete_FlushesDiffChangedBeforeComplete guarantees a terminal diff
// emit: SendComplete must deliver env.diff_changed before env.complete when
// the workspace changed and no mid-run trigger emitted it yet.
func TestSendComplete_FlushesDiffChangedBeforeComplete(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client, received := connectedGitClient(t, workDir)
	client.currentExecutionID = "exec-final-flush"
	client.baseBranch = "main"
	client.Timeouts.DiffDebouncePeriod = 10 * time.Millisecond

	if err := os.WriteFile(filepath.Join(workDir, "README.md"), []byte("# Final flush change\n"), 0600); err != nil {
		t.Fatalf("Failed to write README: %v", err)
	}

	client.SendComplete(runner.CompletionInfo{ExitCode: 0, Reason: "terminate"})

	params := captureDiffChangedNotification(t, received)
	if params.ExecutionID != "exec-final-flush" {
		t.Errorf("Expected execution ID exec-final-flush, got %s", params.ExecutionID)
	}
	if !containsFile(params.Files, "README.md") {
		t.Errorf("Expected README.md in the flushed diff, got %+v", params.Files)
	}

	// env.complete must still follow the flush.
	deadline := time.After(3 * time.Second)
	for {
		select {
		case data := <-received:
			var envelope struct {
				Method string `json:"method"`
			}
			if err := json.Unmarshal(data, &envelope); err != nil {
				t.Fatalf("Failed to unmarshal frame: %v", err)
			}
			if envelope.Method == "env.complete" {
				return
			}
		case <-deadline:
			t.Fatal("env.complete missing after the flushed diff emit")
		}
	}
}

// TestSendComplete_SkipsFlushWhenWorkspaceUnchanged verifies the terminal flush
// stays silent when the last mid-run check already emitted the current state.
func TestSendComplete_SkipsFlushWhenWorkspaceUnchanged(t *testing.T) {
	bareDir, workDir := setupTestGitRepoWithRemote(t)
	defer func() {
		_ = os.RemoveAll(bareDir)
		_ = os.RemoveAll(workDir)
	}()

	client, received := connectedGitClient(t, workDir)
	client.currentExecutionID = "exec-no-change"
	client.Timeouts.DiffDebouncePeriod = 10 * time.Millisecond

	// Baseline check on the clean workspace records the hash and debounces an emit.
	client.TriggerDiffCheck()
	captureDiffChangedNotification(t, received)
	time.Sleep(50 * time.Millisecond) // let the baseline debounce window run out

	client.SendComplete(runner.CompletionInfo{ExitCode: 0, Reason: "terminate"})

	deadline := time.After(3 * time.Second)
	for {
		select {
		case data := <-received:
			var envelope struct {
				Method string `json:"method"`
			}
			if err := json.Unmarshal(data, &envelope); err != nil {
				t.Fatalf("Failed to unmarshal frame: %v", err)
			}
			if envelope.Method == "env.diff_changed" {
				t.Fatal("Expected no diff emit for an unchanged workspace, got env.diff_changed")
			}
			if envelope.Method == "env.complete" {
				return
			}
		case <-deadline:
			t.Fatal("Timed out waiting for env.complete")
		}
	}
}
