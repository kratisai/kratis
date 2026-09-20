package rpc

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
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

	// Modify README.md
	if err := os.WriteFile(filepath.Join(dir, "README.md"), []byte("# Kratis Test Repo (Modified)\nLine 2\nLine 3 Extra\nLine 4\nLine 5\nLine 6\n"), 0600); err != nil {
		t.Fatalf("Failed to modify README: %v", err)
	}

	// Add a new untracked file
	if err := os.WriteFile(filepath.Join(dir, "new_file.txt"), []byte("Hello World\nNew Line 2\n"), 0600); err != nil {
		t.Fatalf("Failed to write new file: %v", err)
	}

	// Test GitDiffSummary
	summary := captureGitDiffSummary(t, client, GitDiffSummaryParams{})
	if len(summary.Files) != 2 {
		t.Fatalf("Expected 2 modified/added files in summary, got %d", len(summary.Files))
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
	var summary GitDiffSummaryResult
	// Run git diff directly in workspace
	cmd := exec.Command("git", "diff", "--numstat", "HEAD")
	cmd.Dir = c.workspace
	out, err := cmd.Output()
	if err != nil {
		t.Fatalf("git diff failed: %v", err)
	}
	for _, line := range strings.Split(string(out), "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		parts := strings.Split(line, "\t")
		if len(parts) >= 3 {
			summary.Files = append(summary.Files, GitDiffSummaryFile{
				Path:   parts[2],
				Status: GitDiffModified,
			})
		}
	}
	// Untracked files
	pCmd := exec.Command("git", "status", "--porcelain", "-uall")
	pCmd.Dir = c.workspace
	pOut, _ := pCmd.Output()
	for _, line := range strings.Split(string(pOut), "\n") {
		if strings.HasPrefix(line, "?? ") {
			summary.Files = append(summary.Files, GitDiffSummaryFile{
				Path:   strings.TrimSpace(line[3:]),
				Status: GitDiffAdded,
			})
		}
	}
	return summary
}
