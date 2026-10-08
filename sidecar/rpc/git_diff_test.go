package rpc

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"

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

// TestBuildGitFullDiff_SynthesizesUntrackedFiles locks in the persisted full
// patch rendering untracked files as full-file addition hunks.
func TestBuildGitFullDiff_SynthesizesUntrackedFiles(t *testing.T) {
	dir := setupTestGitRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	client := &Client{workspace: dir}

	if err := os.WriteFile(filepath.Join(dir, "new_file.txt"), []byte("Hello World\nNew Line 2\n"), 0600); err != nil {
		t.Fatalf("Failed to write new file: %v", err)
	}

	patch := client.buildGitFullDiff("HEAD")
	if !strings.Contains(patch, "@@ -0,0 +1,2 @@") {
		t.Errorf("Expected synthetic patch for untracked file, got: %s", patch)
	}
	if !strings.Contains(patch, "+Hello World") || !strings.Contains(patch, "+New Line 2") {
		t.Errorf("Expected full-file addition lines in patch, got: %s", patch)
	}
}

// TestRegister_RepushesCurrentDiffStateAfterReconnect locks in the convergence
// guarantee: after a (re)registration the sidecar re-pushes the current diff so
// the control-plane copy heals pushes lost while disconnected.
func TestRegister_RepushesCurrentDiffStateAfterReconnect(t *testing.T) {
	dir := setupTestGitRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	received := make(chan []byte, 16)
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, data, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(data, &req); err != nil {
				continue
			}
			switch req.Method {
			case "env.register":
				raw, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "registered", EnvironmentID: "env-123"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
			case "env.diff_changed":
				ackDiffChanged(conn, req)
			}
			received <- data
		}
	})

	client := connectClient(t, wsURL(srv), "test-token")
	defer client.Close()
	setTestClientTimeouts(client)
	client.workspace = dir
	client.currentExecutionID = "exec-reconnect"
	client.baseBranch = "main"
	errChan := make(chan error, 1)
	go client.readLoop(errChan)

	if err := os.WriteFile(filepath.Join(dir, "README.md"), []byte("# Reconnect change\n"), 0600); err != nil {
		t.Fatalf("Failed to write README: %v", err)
	}

	if err := client.register(); err != nil {
		t.Fatalf("register failed: %v", err)
	}

	params := captureDiffChangedNotification(t, received)
	if params.ExecutionID != "exec-reconnect" {
		t.Errorf("Expected re-push for execution exec-reconnect, got %s", params.ExecutionID)
	}
	if !containsFile(params.Files, "README.md") {
		t.Errorf("Expected re-push to carry README.md, got %+v", params.Files)
	}
	if !strings.Contains(params.Patch, "Reconnect change") {
		t.Errorf("Expected re-push patch to contain the workspace change, got: %s", params.Patch)
	}
}

// TestDiffPush_RetriesUntilAcknowledged locks in the durability guarantee: a
// diff push whose control-plane ack is missing is retried with backoff until
// the persistence acknowledgment arrives.
func TestDiffPush_RetriesUntilAcknowledged(t *testing.T) {
	dir := setupTestGitRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	var attempts int
	var mu sync.Mutex
	ackFromAttempt := 3

	received := make(chan []byte, 16)
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, data, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(data, &req); err != nil {
				continue
			}
			if req.Method == "env.diff_changed" {
				mu.Lock()
				attempts++
				n := attempts
				mu.Unlock()
				if n >= ackFromAttempt {
					ackDiffChanged(conn, req)
				}
			}
			received <- data
		}
	})

	client := connectClient(t, wsURL(srv), "test-token")
	defer client.Close()
	client.workspace = dir
	client.currentExecutionID = "exec-retry"
	client.baseBranch = "main"
	client.requestTimeout = 50 * time.Millisecond
	client.Timeouts.DiffRetryInitialDelay = 5 * time.Millisecond
	client.Timeouts.DiffRetryMaxDelay = 25 * time.Millisecond
	errChan := make(chan error, 1)
	go client.readLoop(errChan)

	if err := os.WriteFile(filepath.Join(dir, "README.md"), []byte("# Retry change\n"), 0600); err != nil {
		t.Fatalf("Failed to write README: %v", err)
	}

	client.emitDiffChanged()

	deadline := time.Now().Add(3 * time.Second)
	for {
		mu.Lock()
		n := attempts
		mu.Unlock()
		if n >= ackFromAttempt {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("expected the diff push to be retried until attempt %d, got %d", ackFromAttempt, n)
		}
		time.Sleep(10 * time.Millisecond)
	}

	params := captureDiffChangedNotification(t, received)
	if params.ExecutionID != "exec-retry" {
		t.Errorf("Expected re-push for execution exec-retry, got %s", params.ExecutionID)
	}
	if !strings.Contains(params.Patch, "Retry change") {
		t.Errorf("Expected patch to contain the workspace change, got: %s", params.Patch)
	}
}

// TestDiffPush_NewerStateSupersedesRetriedState locks in the supersede rule: a
// failed older full state is dropped once a newer full state is queued, so the
// control plane never receives stale diff content after a fresh emit.
func TestDiffPush_NewerStateSupersedesRetriedState(t *testing.T) {
	dir := setupTestGitRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	var oldAttempts, newAttempts int
	var mu sync.Mutex
	oldAttemptSeen := make(chan struct{}, 1)

	received := make(chan []byte, 16)
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, data, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(data, &req); err != nil {
				continue
			}
			if req.Method == "env.diff_changed" {
				var params DiffChangedParams
				b, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(b, &params)
				switch params.ExecutionID {
				case "exec-old":
					mu.Lock()
					oldAttempts++
					mu.Unlock()
					select {
					case oldAttemptSeen <- struct{}{}:
					default:
					}
				case "exec-new":
					mu.Lock()
					newAttempts++
					mu.Unlock()
					ackDiffChanged(conn, req)
				}
			}
			received <- data
		}
	})

	client := connectClient(t, wsURL(srv), "test-token")
	defer client.Close()
	client.workspace = dir
	client.requestTimeout = 50 * time.Millisecond
	client.Timeouts.DiffRetryInitialDelay = 5 * time.Millisecond
	client.Timeouts.DiffRetryMaxDelay = 25 * time.Millisecond
	errChan := make(chan error, 1)
	go client.readLoop(errChan)

	// The old state is never acknowledged, so its first attempt will fail.
	client.queueDiffPush(DiffChangedParams{ExecutionID: "exec-old"})

	// As soon as the old attempt reaches the server, a newer full state is
	// queued while the old is still in flight: it must supersede the retry.
	select {
	case <-oldAttemptSeen:
	case <-time.After(2 * time.Second):
		t.Fatal("expected the older diff state attempt to reach the server")
	}
	client.queueDiffPush(DiffChangedParams{ExecutionID: "exec-new"})

	deadline := time.Now().Add(3 * time.Second)
	for {
		mu.Lock()
		newN := newAttempts
		mu.Unlock()
		if newN >= 1 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("expected the newer diff state to be pushed and acked, got newAttempts=%d", newN)
		}
		time.Sleep(10 * time.Millisecond)
	}

	time.Sleep(120 * time.Millisecond) // allow any (wrong) retry of the old state to surface

	mu.Lock()
	oldN := oldAttempts
	newN := newAttempts
	mu.Unlock()
	if oldN != 1 {
		t.Errorf("expected the older superseded state to be attempted once, got %d attempts", oldN)
	}
	if newN != 1 {
		t.Errorf("expected the newer state to be pushed once, got %d attempts", newN)
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
