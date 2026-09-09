package rpc

import (
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"

	"kratis-connector/runner"
)

// ExecuteRegisterGitAuth configures Git credentials and shell proxy helpers for the workspace daemon.
func (c *Client) ExecuteRegisterGitAuth(params RegisterGitAuthParams, reqID interface{}) {
	log.Printf("Registering Git credentials: credType=%s", params.CredentialType)

	// Clean up previous agent, helper script, generated gitconfig, and process-wide env
	c.mu.Lock()
	if c.sshAgentPID != "" {
		cmd := exec.Command("kill", c.sshAgentPID) //nolint:gosec // G204: kills the ssh-agent helper this client spawned
		_ = cmd.Run()
		c.sshAgentPID = ""
		c.sshAuthSock = ""
	}
	if c.gitHelperScript != "" {
		_ = os.Remove(c.gitHelperScript)
		c.gitHelperScript = ""
	}
	if c.gitConfigGlobal != "" {
		_ = os.Remove(c.gitConfigGlobal)
		c.gitConfigGlobal = ""
	}
	c.mu.Unlock()
	_ = os.Unsetenv("SSH_AUTH_SOCK")
	_ = os.Unsetenv("GIT_SSH_COMMAND")
	_ = os.Unsetenv("GIT_CONFIG_GLOBAL")

	var err error

	switch params.CredentialType {
	case CredentialTypeSSHKey:
		if params.PrivateKey == "" {
			if reqID != nil {
				c.sendErrorResponse(reqID, -32000, "SSH_KEY credentials require a privateKey", "")
			}
			return
		}
		_, err = c.setupSSHKey(params.PrivateKey)
		if err != nil {
			if reqID != nil {
				c.sendErrorResponse(reqID, -32000, err.Error(), "")
			}
			return
		}
		c.mu.Lock()
		sshAuthSock := c.sshAuthSock
		c.mu.Unlock()
		_ = os.Setenv("SSH_AUTH_SOCK", sshAuthSock)
		_ = os.Setenv("GIT_SSH_COMMAND", "ssh -o StrictHostKeyChecking=no")
		c.writeGlobalGitConfig("", params.UserName, params.UserEmail)
	case CredentialTypePAT, CredentialTypeGitHubApp, "GIT_PAT", "GITHUB":
		c.setupTokenHelper(params.UserName, params.UserEmail)
	default:
		if reqID != nil {
			c.sendErrorResponse(reqID, -32602, "invalid params", "unsupported credentialType: "+string(params.CredentialType))
		}
		return
	}

	c.mu.Lock()
	c.gitUserName = params.UserName
	c.gitUserEmail = params.UserEmail
	c.mu.Unlock()

	if reqID != nil {
		c.sendSuccessResponse(reqID, RegisterGitAuthResult{Status: GitAuthStatusSuccess})
	}
}

// credentialHelperScript returns the stable path of the token credential helper.
func (c *Client) credentialHelperScript() string {
	return filepath.Join(c.credentialsDir, "git-credentials-helper.sh")
}

// writeCredentialHelper writes the nc-based credential helper that forwards git
// credential prompts to the local Kratis credential server socket.
func (c *Client) writeCredentialHelper() string {
	helperScript := c.credentialHelperScript()
	scriptContent := fmt.Sprintf(
		"#!/bin/sh\nif [ \"$1\" = \"get\" ]; then\n  if ! command -v nc >/dev/null 2>&1; then\n    echo \"kratis credential helper: 'nc' not found in PATH; the sandbox image must install netcat-openbsd to reach the Kratis credential socket\" >&2\n    exit 1\n  fi\n  exec nc -U %s\nfi\n",
		runner.SocketPath)
	// 0700: git executes the helper directly, so it must be owner-executable;
	// owner-only keeps the embedded socket path private.
	_ = os.WriteFile(helperScript, []byte(scriptContent), 0700) //nolint:gosec // G306: executable credential helper
	return helperScript
}

// reassertGitIdentity rewrites the generated global gitconfig (and the token
// credential helper when present) from the identity captured at registerGitAuth
// time. This makes git identity and credentials resilient to external deletion
// of /kratis/gitconfig between sandbox init and a later publish.
func (c *Client) reassertGitIdentity() {
	c.mu.Lock()
	userName := c.gitUserName
	userEmail := c.gitUserEmail
	helperScript := c.gitHelperScript
	c.mu.Unlock()

	if userName == "" && userEmail == "" {
		return
	}
	if helperScript != "" {
		c.writeCredentialHelper()
	}
	c.writeGlobalGitConfig(helperScript, userName, userEmail)
}

func (c *Client) setupSSHKey(privateKey string) (string, error) {
	sshAuthSock, agentPID, err := runner.StartSSHAgent()
	if err != nil {
		return "", fmt.Errorf("failed to start ssh-agent: %w", err)
	}
	c.mu.Lock()
	c.sshAgentPID = agentPID
	c.sshAuthSock = sshAuthSock
	c.mu.Unlock()

	if err := runner.AddSSHKey(sshAuthSock, privateKey); err != nil {
		return "", fmt.Errorf("failed to add SSH key to agent: %w", err)
	}
	return sshAuthSock, nil
}

func (c *Client) setupTokenHelper(userName, userEmail string) {
	// Persistent, stable paths: credentials must survive the whole sandbox
	// lifetime, so they cannot live under /tmp (ephemeral) or use the connector
	// PID (which changes if the sidecar restarts).
	c.mu.Lock()
	helperScript := c.credentialHelperScript()
	c.gitHelperScript = helperScript
	c.mu.Unlock()

	c.writeCredentialHelper()
	c.writeGlobalGitConfig(helperScript, userName, userEmail)
}

// writeGlobalGitConfig persists global gitconfig (exported via GIT_CONFIG_GLOBAL)
// providing git "identity" for both sidecar and agent git-ops.
func (c *Client) writeGlobalGitConfig(helperScript, userName, userEmail string) {
	c.mu.Lock()
	gitConfigGlobal := filepath.Join(c.credentialsDir, "gitconfig")
	c.gitConfigGlobal = gitConfigGlobal
	c.mu.Unlock()

	var content strings.Builder
	if userName != "" || userEmail != "" {
		content.WriteString("[user]\n")
		if userName != "" {
			_, _ = fmt.Fprintf(&content, "\tname = %s\n", userName)
		}
		if userEmail != "" {
			_, _ = fmt.Fprintf(&content, "\temail = %s\n", userEmail)
		}
	}
	if helperScript != "" {
		_, _ = fmt.Fprintf(&content, "[credential]\n\thelper = %s\n", helperScript)
	}
	_ = os.WriteFile(gitConfigGlobal, []byte(content.String()), 0600)
	_ = os.Setenv("GIT_CONFIG_GLOBAL", gitConfigGlobal)
}

// ExecuteCheckout handles git checkout/clone operations. Auth is a prerequisite.
func (c *Client) ExecuteCheckout(params CheckoutParams, _ interface{}) {
	log.Printf("Executing repository checkout: url=%s, branch=%s, executionId=%s", params.URL, params.Branch, params.ExecutionID)
	if params.ExecutionID != "" {
		c.mu.Lock()
		c.currentExecutionID = params.ExecutionID
		c.mu.Unlock()
	}

	cmdEnv := os.Environ()

	// Merge caller-provided environment variables (e.g. GIT_TERMINAL_PROMPT, SSH_ASKPASS)
	for k, v := range params.Env {
		cmdEnv = append(cmdEnv, k+"="+v)
	}

	// Prepare workspace directory
	workspace := c.workspace
	if workspace == "" {
		workspace = "workspace"
	}

	isGitRepo := false
	if _, err := os.Stat(filepath.Join(workspace, ".git")); err == nil {
		isGitRepo = true
	}

	if isGitRepo {
		// Check for uncommitted changes (ignore untracked files)
		statusCmd := exec.Command("git", "status", "--porcelain", "--untracked-files=no")
		statusCmd.Dir = workspace
		statusCmd.Env = cmdEnv
		statusOut, err := statusCmd.CombinedOutput()
		if err != nil {
			c.sendCheckoutComplete("failed", fmt.Sprintf("failed to check git status: %v, output: %s", err, string(statusOut)), "")
			return
		}
		if len(strings.TrimSpace(string(statusOut))) > 0 {
			c.sendCheckoutComplete("failed", fmt.Sprintf("workspace has uncommitted changes, aborting checkout: %s", string(statusOut)), "")
			return
		}

		// Check if remote origin URL matches
		originCmd := exec.Command("git", "remote", "get-url", "origin")
		originCmd.Dir = workspace
		originCmd.Env = cmdEnv
		originOut, err := originCmd.CombinedOutput()
		if err != nil {
			c.sendCheckoutComplete("failed", fmt.Sprintf("failed to get remote origin URL: %v, output: %s", err, string(originOut)), "")
			return
		}
		currentOrigin := strings.TrimSpace(string(originOut))
		if !urlsMatch(currentOrigin, params.URL) {
			c.sendCheckoutComplete("failed", fmt.Sprintf("workspace remote origin URL '%s' does not match target URL '%s', aborting", currentOrigin, params.URL), "")
			return
		}

		log.Printf("Workspace %s is already a git repository and matches target URL. Fetching and checking out branch %s...", workspace, params.Branch)

		// Fetch origin
		fetchCmd := exec.Command("git", "fetch", "origin")
		fetchCmd.Dir = workspace
		fetchCmd.Env = cmdEnv
		if out, err := fetchCmd.CombinedOutput(); err != nil {
			c.sendCheckoutComplete("failed", fmt.Sprintf("git fetch failed: %v, output: %s", err, string(out)), "")
			return
		}

		// Checkout branch
		branch := params.Branch
		if branch == "" {
			branch = "main"
		}
		checkoutCmd := exec.Command("git", "checkout", branch) //nolint:gosec // G204: branch comes from the control-plane checkout request
		checkoutCmd.Dir = workspace
		checkoutCmd.Env = cmdEnv
		if out, err := checkoutCmd.CombinedOutput(); err != nil {
			c.sendCheckoutComplete("failed", fmt.Sprintf("git checkout failed: %v, output: %s", err, string(out)), "")
			return
		}

		// Reset hard
		resetCmd := exec.Command("git", "reset", "--hard", "origin/"+branch) //nolint:gosec // G204: branch comes from the control-plane checkout request
		resetCmd.Dir = workspace
		resetCmd.Env = cmdEnv
		if out, err := resetCmd.CombinedOutput(); err != nil {
			log.Printf("git reset warning: %v, output: %s", err, string(out))
		}
	} else {
		// If the directory exists but is not empty, fail instead of violently deleting
		if _, err := os.Stat(workspace); err == nil {
			empty, err := isDirEmpty(workspace)
			if err != nil {
				c.sendCheckoutComplete("failed", fmt.Sprintf("failed to check if workspace is empty: %v", err), "")
				return
			}
			if !empty {
				c.sendCheckoutComplete("failed", fmt.Sprintf("workspace directory %s already exists, is populated, and is not a git repository", workspace), "")
				return
			}
		} else {
			//nolint:gosec // G204: creating the agent workspace directory
			_ = exec.Command("mkdir", "-p", workspace).Run()
		}

		// Build git clone command
		gitArgs := []string{"clone"}
		if params.Branch != "" {
			gitArgs = append(gitArgs, "--branch", params.Branch)
		}
		gitArgs = append(gitArgs, params.URL, workspace)

		cmd := exec.Command("git", gitArgs...)
		cmd.Dir = "/"
		cmd.Env = cmdEnv

		out, err := cmd.CombinedOutput()
		if err != nil {
			c.sendCheckoutComplete("failed", fmt.Sprintf("git clone failed: %v, output: %s", err, string(out)), "")
			return
		}
	}

	// Get the commit hash of the cloned repository
	revCmd := exec.Command("git", "rev-parse", "HEAD")
	revCmd.Dir = workspace
	commitHashBytes, err := revCmd.Output()
	commitHash := strings.TrimSpace(string(commitHashBytes))
	if err != nil {
		commitHash = "unknown"
	}

	// Success!
	c.sendCheckoutComplete("success", "", commitHash)
}

func (c *Client) sendCheckoutComplete(status, errMsg, commitHash string) {
	c.mu.Lock()
	execID := c.currentExecutionID
	c.mu.Unlock()
	_ = c.sendNotification("env.checkout_complete", CheckoutCompleteParams{
		Status:      CheckoutStatus(status),
		Error:       errMsg,
		CommitHash:  commitHash,
		ExecutionID: execID,
	})
}

func isDirEmpty(name string) (bool, error) {
	f, err := os.Open(name) //nolint:gosec // G304: helper opens the workspace directory passed by the caller
	if err != nil {
		return false, err
	}
	defer func() { _ = f.Close() }()

	_, err = f.Readdirnames(1)
	if errors.Is(err, io.EOF) {
		return true, nil
	}
	return false, err
}

func cleanGitURL(u string) string {
	u = strings.TrimSpace(u)
	if idx := strings.Index(u, "://"); idx != -1 {
		u = u[idx+3:]
	}
	if idx := strings.Index(u, "@"); idx != -1 {
		if strings.HasPrefix(u, "git@") {
			u = u[4:]
		} else {
			u = u[idx+1:]
		}
	}
	u = strings.ReplaceAll(u, ":", "/")
	u = strings.TrimSuffix(u, "/")
	u = strings.TrimSuffix(u, ".git")
	u = strings.TrimSuffix(u, "/")
	return u
}

func urlsMatch(urlA, urlB string) bool {
	return cleanGitURL(urlA) == cleanGitURL(urlB)
}

func (c *Client) resolveWorkspace() string {
	if c.workspace != "" {
		return c.workspace
	}
	return "/kratis/workspace"
}

// resolveBaseRef resolves the ref to diff against. baseBranch is required and
// must exist as origin/<branch> or <branch> in the workspace; otherwise an
// error is returned so callers fail loudly instead of silently diffing against
// HEAD.
func resolveBaseRef(workspace string, baseBranch string) (string, error) {
	baseBranch = strings.TrimSpace(baseBranch)
	if baseBranch == "" {
		return "", fmt.Errorf("base branch is required")
	}

	checkRemote := exec.Command("git", "rev-parse", "--verify", "origin/"+baseBranch) //nolint:gosec
	checkRemote.Dir = workspace
	if err := checkRemote.Run(); err == nil {
		return "origin/" + baseBranch, nil
	}

	checkLocal := exec.Command("git", "rev-parse", "--verify", baseBranch) //nolint:gosec
	checkLocal.Dir = workspace
	if err := checkLocal.Run(); err == nil {
		return baseBranch, nil
	}

	return "", fmt.Errorf("base branch %q does not exist as origin/%s or %s in the workspace", baseBranch, baseBranch, baseBranch)
}

// mergeBaseWithHead resolves the merge-base of ref and HEAD. Diffing against
// the merge-base (three-dot semantics) shows the consolidated branch changes
// without including (or reversing) changes that were merged into the base
// branch after this branch diverged.
func mergeBaseWithHead(workspace string, ref string) (string, error) {
	cmd := exec.Command("git", "merge-base", ref, "HEAD") //nolint:gosec
	cmd.Dir = workspace
	out, err := cmd.Output()
	if err != nil {
		return "", fmt.Errorf("unable to compute merge-base of %s and HEAD: %w", ref, err)
	}
	sha := strings.TrimSpace(string(out))
	if sha == "" {
		return "", fmt.Errorf("merge-base of %s and HEAD resolved to an empty value", ref)
	}
	return sha, nil
}

func isGeneratedOrLarge(path string, additions, deletions int) bool {
	if additions+deletions > 1000 {
		return true
	}
	base := filepath.Base(path)
	switch base {
	case "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "go.sum", "Cargo.lock", "composer.lock", "Gemfile.lock":
		return true
	}
	ext := strings.ToLower(filepath.Ext(path))
	switch ext {
	case ".min.js", ".min.css", ".map", ".svg", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".wasm", ".pdf":
		return true
	}
	normalized := filepath.ToSlash(path)
	for _, segment := range []string{"/dist/", "/build/", "/.next/", "/target/", "/vendor/", "/node_modules/"} {
		if strings.Contains(normalized, segment) {
			return true
		}
	}
	return false
}

// ExecuteGitDiffSummary produces a high-level summary manifest of all modified/added/deleted files.
func (c *Client) ExecuteGitDiffSummary(params GitDiffSummaryParams, reqID interface{}) {
	workspace := c.resolveWorkspace()

	if _, err := os.Stat(filepath.Join(workspace, ".git")); err != nil {
		c.sendSuccessResponse(reqID, GitDiffSummaryResult{
			BaseCommit:     "",
			HeadCommit:     "",
			TotalAdditions: 0,
			TotalDeletions: 0,
			Files:          []GitDiffSummaryFile{},
		})
		return
	}

	headCmd := exec.Command("git", "rev-parse", "HEAD") //nolint:gosec
	headCmd.Dir = workspace
	headBytes, _ := headCmd.Output()
	headCommit := strings.TrimSpace(string(headBytes))

	baseRef, err := resolveBaseRef(workspace, params.BaseBranch)
	if err != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Unable to resolve base branch", err.Error())
		}
		return
	}
	// Diff against the merge-base so changes merged into the target branch after
	// this branch diverged never appear (or get reversed) in the branch diff.
	baseCommit, err := mergeBaseWithHead(workspace, baseRef)
	if err != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Unable to resolve diff base", err.Error())
		}
		return
	}

	statusMap := make(map[string]GitDiffStatus)
	nameStatusCmd := exec.Command("git", "diff", "--name-status", baseCommit) //nolint:gosec
	nameStatusCmd.Dir = workspace
	if nsOut, err := nameStatusCmd.Output(); err == nil {
		for _, line := range strings.Split(string(nsOut), "\n") {
			line = strings.TrimSpace(line)
			if line == "" {
				continue
			}
			parts := strings.Fields(line)
			if len(parts) >= 2 {
				statusChar := parts[0]
				filePath := parts[len(parts)-1]
				switch {
				case strings.HasPrefix(statusChar, "A"):
					statusMap[filePath] = GitDiffAdded
				case strings.HasPrefix(statusChar, "D"):
					statusMap[filePath] = GitDiffDeleted
				case strings.HasPrefix(statusChar, "R"):
					statusMap[filePath] = GitDiffRenamed
				default:
					statusMap[filePath] = GitDiffModified
				}
			}
		}
	}

	filesMap := make(map[string]GitDiffSummaryFile)
	totalAdds := 0
	totalDels := 0

	numstatCmd := exec.Command("git", "diff", "--numstat", baseCommit) //nolint:gosec
	numstatCmd.Dir = workspace
	if numOut, err := numstatCmd.Output(); err == nil {
		for _, line := range strings.Split(string(numOut), "\n") {
			line = strings.TrimSpace(line)
			if line == "" {
				continue
			}
			parts := strings.Split(line, "\t")
			if len(parts) >= 3 {
				adds, _ := strconv.Atoi(parts[0])
				dels, _ := strconv.Atoi(parts[1])
				filePath := parts[2]

				status := statusMap[filePath]
				if status == "" {
					status = GitDiffModified
				}

				filesMap[filePath] = GitDiffSummaryFile{
					Path:                 filePath,
					Status:               status,
					Additions:            adds,
					Deletions:            dels,
					IsCollapsedByDefault: isGeneratedOrLarge(filePath, adds, dels),
				}
				totalAdds += adds
				totalDels += dels
			}
		}
	}

	porcelainCmd := exec.Command("git", "status", "--porcelain", "-uall") //nolint:gosec
	porcelainCmd.Dir = workspace
	if pOut, err := porcelainCmd.Output(); err == nil {
		for _, line := range strings.Split(string(pOut), "\n") {
			if len(line) < 4 {
				continue
			}
			statusCode := line[:2]
			filePath := strings.TrimSpace(line[3:])
			if statusCode == "??" {
				if _, exists := filesMap[filePath]; !exists {
					lineCount := 0
					if content, err := os.ReadFile(filepath.Join(workspace, filePath)); err == nil { //nolint:gosec
						rawContent := strings.TrimSuffix(string(content), "\r\n")
						rawContent = strings.TrimSuffix(rawContent, "\n")
						if len(rawContent) > 0 {
							lineCount = len(strings.Split(rawContent, "\n"))
						}
					}
					filesMap[filePath] = GitDiffSummaryFile{
						Path:                 filePath,
						Status:               GitDiffAdded,
						Additions:            lineCount,
						Deletions:            0,
						IsCollapsedByDefault: isGeneratedOrLarge(filePath, lineCount, 0),
					}
					totalAdds += lineCount
				}
			}
		}
	}

	fileList := make([]GitDiffSummaryFile, 0, len(filesMap))
	for _, f := range filesMap {
		fileList = append(fileList, f)
	}

	// Compute commits ahead and commit messages (commits reachable from HEAD but
	// not from the base branch tip).
	commitsAhead := 0
	var commitMessages []GitCommitMessage
	revRange := fmt.Sprintf("%s..HEAD", baseRef)
	countCmd := exec.Command("git", "rev-list", "--count", revRange) //nolint:gosec
	countCmd.Dir = workspace
	if cOut, err := countCmd.Output(); err == nil {
		if cCount, cErr := strconv.Atoi(strings.TrimSpace(string(cOut))); cErr == nil {
			commitsAhead = cCount
		}
	}

	if commitsAhead > 0 {
		logCmd := exec.Command("git", "log", "-n", "50", "--format=%H%x1f%s%x1f%b%x1e", revRange) //nolint:gosec
		logCmd.Dir = workspace
		if logOut, err := logCmd.Output(); err == nil {
			for _, entry := range strings.Split(string(logOut), "\x1e") {
				entry = strings.TrimSpace(entry)
				if entry == "" {
					continue
				}
				fields := strings.Split(entry, "\x1f")
				if len(fields) >= 2 {
					body := ""
					if len(fields) >= 3 {
						body = strings.TrimSpace(fields[2])
					}
					commitMessages = append(commitMessages, GitCommitMessage{
						Sha:     strings.TrimSpace(fields[0]),
						Subject: strings.TrimSpace(fields[1]),
						Body:    body,
					})
				}
			}
		}
	}

	// Compute staged and unstaged file counts
	stagedFiles := 0
	unstagedFiles := 0
	cachedCmd := exec.Command("git", "diff", "--cached", "--name-only") //nolint:gosec
	cachedCmd.Dir = workspace
	if cOut, err := cachedCmd.Output(); err == nil {
		for _, l := range strings.Split(strings.TrimSpace(string(cOut)), "\n") {
			if strings.TrimSpace(l) != "" {
				stagedFiles++
			}
		}
	}

	unstagedCmd := exec.Command("git", "diff", "--name-only") //nolint:gosec
	unstagedCmd.Dir = workspace
	if uOut, err := unstagedCmd.Output(); err == nil {
		for _, l := range strings.Split(strings.TrimSpace(string(uOut)), "\n") {
			if strings.TrimSpace(l) != "" {
				unstagedFiles++
			}
		}
	}

	// Count untracked files in unstaged count
	if pOut, err := porcelainCmd.Output(); err == nil {
		for _, line := range strings.Split(string(pOut), "\n") {
			if strings.HasPrefix(line, "??") {
				unstagedFiles++
			}
		}
	}

	hasChanges := commitsAhead > 0 || len(fileList) > 0 || stagedFiles > 0 || unstagedFiles > 0

	c.sendSuccessResponse(reqID, GitDiffSummaryResult{
		BaseCommit:     baseCommit,
		HeadCommit:     headCommit,
		TotalAdditions: totalAdds,
		TotalDeletions: totalDels,
		CommitsAhead:   commitsAhead,
		StagedFiles:    stagedFiles,
		UnstagedFiles:  unstagedFiles,
		HasChanges:     hasChanges,
		CommitMessages: commitMessages,
		Files:          fileList,
	})
}

// ExecuteGitFileDiff returns the unified diff patch for a single file.
func (c *Client) ExecuteGitFileDiff(params GitFileDiffParams, reqID interface{}) {
	workspace := c.resolveWorkspace()
	cleanRelPath := filepath.Clean(params.Path)
	if strings.HasPrefix(cleanRelPath, "..") || filepath.IsAbs(params.Path) {
		c.sendErrorResponse(reqID, -32602, "Invalid path: outside workspace", params.Path)
		return
	}

	baseRef, err := resolveBaseRef(workspace, params.BaseBranch)
	if err != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Unable to resolve base branch", err.Error())
		}
		return
	}
	baseCommit, err := mergeBaseWithHead(workspace, baseRef)
	if err != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Unable to resolve diff base", err.Error())
		}
		return
	}

	c.sendSuccessResponse(reqID, buildGitFileDiff(workspace, cleanRelPath, baseCommit))
}

// buildGitFileDiff computes the unified diff for a single workspace file.
func buildGitFileDiff(workspace, cleanRelPath, baseCommit string) GitFileDiffResult {
	diffCmd := exec.Command("git", "diff", "-U3", baseCommit, "--", cleanRelPath) //nolint:gosec
	diffCmd.Dir = workspace
	out, _ := diffCmd.Output()

	patch := string(out)
	fullPath := filepath.Join(workspace, cleanRelPath)
	content, readErr := os.ReadFile(fullPath) //nolint:gosec

	// Untracked files are invisible to `git diff <base>`, so synthesize a
	// full-file addition hunk instead of returning an empty patch.
	if strings.TrimSpace(patch) == "" && readErr == nil && !isTrackedFile(workspace, cleanRelPath) {
		patch = syntheticNewFilePatch(content)
	}

	adds := 0
	dels := 0
	totalLines := 0

	if readErr == nil {
		rawContent := strings.TrimSuffix(string(content), "\r\n")
		rawContent = strings.TrimSuffix(rawContent, "\n")
		if len(rawContent) > 0 {
			totalLines = len(strings.Split(rawContent, "\n"))
		}
	}

	for _, line := range strings.Split(patch, "\n") {
		if strings.HasPrefix(line, "+") && !strings.HasPrefix(line, "+++") {
			adds++
		} else if strings.HasPrefix(line, "-") && !strings.HasPrefix(line, "---") {
			dels++
		}
	}

	return GitFileDiffResult{
		Path:       cleanRelPath,
		Patch:      patch,
		Additions:  adds,
		Deletions:  dels,
		TotalLines: totalLines,
	}
}

// isTrackedFile reports whether a workspace path is tracked by git (present in
// the index or HEAD).
func isTrackedFile(workspace, cleanRelPath string) bool {
	cmd := exec.Command("git", "ls-files", "--error-unmatch", "--", cleanRelPath) //nolint:gosec
	cmd.Dir = workspace
	return cmd.Run() == nil
}

// syntheticNewFilePatch renders an untracked file as a full-file addition hunk
// so it can be displayed even though it has no base version to diff against.
func syntheticNewFilePatch(content []byte) string {
	rawContent := strings.TrimSuffix(string(content), "\r\n")
	rawContent = strings.TrimSuffix(rawContent, "\n")
	var lines []string
	if len(rawContent) > 0 {
		lines = strings.Split(rawContent, "\n")
	}
	var b strings.Builder
	_, _ = fmt.Fprintf(&b, "@@ -0,0 +1,%d @@\n", len(lines))
	for _, line := range lines {
		b.WriteString("+" + strings.TrimSuffix(line, "\r") + "\n")
	}
	return b.String()
}

// ExecuteReadFileSlice reads a line range from a workspace file.
func (c *Client) ExecuteReadFileSlice(params ReadFileSliceParams, reqID interface{}) {
	workspace := c.resolveWorkspace()
	cleanRelPath := filepath.Clean(params.Path)
	if strings.HasPrefix(cleanRelPath, "..") || filepath.IsAbs(params.Path) {
		c.sendErrorResponse(reqID, -32602, "Invalid path: outside workspace", params.Path)
		return
	}

	fullPath := filepath.Join(workspace, cleanRelPath)
	content, err := os.ReadFile(fullPath) //nolint:gosec
	if err != nil {
		c.sendErrorResponse(reqID, -32000, "File not found or unreadable", err.Error())
		return
	}

	lines := strings.Split(string(content), "\n")
	totalLines := len(lines)
	if totalLines > 0 && lines[totalLines-1] == "" && strings.HasSuffix(string(content), "\n") {
		totalLines--
	}

	start := params.StartLine
	end := params.EndLine

	if start < 1 {
		start = 1
	}
	if end > totalLines {
		end = totalLines
	}
	if start > end || totalLines == 0 {
		c.sendSuccessResponse(reqID, ReadFileSliceResult{
			Path:      params.Path,
			StartLine: start,
			Lines:     []string{},
		})
		return
	}

	cleanLines := lines[:totalLines]
	slice := cleanLines[start-1 : end]
	c.sendSuccessResponse(reqID, ReadFileSliceResult{
		Path:      params.Path,
		StartLine: start,
		Lines:     slice,
	})
}

// ExecuteGitPush creates the delta commit, rebases onto the remote feature and
// target branches when they moved, and pushes to remote origin using the git
// environment and credentials established during sandbox initialization.
//
// The delta commit is created BEFORE any rebase so that:
//   - first publish: the whole change set is squashed onto the target branch;
//   - republish: only the changes since the last pushed tip are folded into one
//     commit (previous pushed commits stay in the branch history).
//
// Rebases replay the delta commit as a regular commit. On conflict the rebase
// is aborted, the branch is left at its pre-rebase state, and a REBASE_CONFLICT
// error is sent so the control plane can escalate to the agent.
func (c *Client) ExecuteGitPush(params GitPushParams, reqID interface{}) {
	branchName := strings.TrimSpace(params.BranchName)
	if branchName == "" {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32602, "Invalid parameters", "branchName is required")
		}
		return
	}

	commitMessage := strings.TrimSpace(params.CommitMessage)
	if commitMessage == "" {
		commitMessage = "Kratis execution updates"
	}

	targetBranch := strings.TrimSpace(params.TargetBranch)
	if targetBranch == "" {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32602, "Invalid parameters", "targetBranch is required")
		}
		return
	}

	cmdEnv := append(os.Environ(), "GIT_TERMINAL_PROMPT=0")
	workspace := c.resolveWorkspace()

	// Identity was captured at registerGitAuth time; re-assert the global
	// gitconfig so a deleted/lost file can never break the publish commit.
	c.reassertGitIdentity()

	// 1. Refresh remote refs so rebases operate on the latest origin state.
	fetchCmd := exec.Command("git", "fetch", "origin") //nolint:gosec
	fetchCmd.Dir = workspace
	fetchCmd.Env = cmdEnv
	if out, err := fetchCmd.CombinedOutput(); err != nil {
		log.Printf("git fetch origin failed (continuing without fresh refs): %v, output: %s", err, string(out))
	}

	// 2. Resolve the target branch ref; the rebase target is required.
	targetRef, err := resolveBaseRef(workspace, targetBranch)
	if err != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Unable to resolve target branch", err.Error())
		}
		return
	}

	// 3. Determine whether the branch already exists remotely (republish).
	republished := c.revParseExists(workspace, cmdEnv, "origin/"+branchName)

	// 4. Stage and create the delta commit on top of the current branch.
	if !c.stageAll(workspace, cmdEnv, reqID) {
		return
	}
	if params.Squash {
		candidateBase := targetRef
		if republished {
			candidateBase = "origin/" + branchName
		}
		// Squash onto the merge-base: the squashed commit then contains only this
		// branch's changes, so upstream changes merged into the target branch (or
		// pushed to the branch externally) are never reverted. The rebase below
		// replays the delta on top of the current tips.
		squashBase, mbErr := mergeBaseWithHead(workspace, candidateBase)
		if mbErr != nil {
			if reqID != nil {
				c.sendErrorResponse(reqID, -32000, "Failed to squash changes", mbErr.Error())
			}
			return
		}
		resetCmd := exec.Command("git", "reset", "--soft", squashBase) //nolint:gosec
		resetCmd.Dir = workspace
		resetCmd.Env = cmdEnv
		if out, err := resetCmd.CombinedOutput(); err != nil {
			log.Printf("git reset --soft failed: %v, output: %s", err, string(out))
			if reqID != nil {
				c.sendErrorResponse(reqID, -32000, "Failed to squash changes", string(out))
			}
			return
		}
		c.commitWithMessage(workspace, cmdEnv, commitMessage, reqID)
	} else if c.hasStagedChanges(workspace, cmdEnv) {
		c.commitWithMessage(workspace, cmdEnv, commitMessage, reqID)
	}

	// 5. Create/reset the local feature branch to the current HEAD.
	checkoutCmd := exec.Command("git", "checkout", "-B", branchName) //nolint:gosec
	checkoutCmd.Dir = workspace
	checkoutCmd.Env = cmdEnv
	if out, err := checkoutCmd.CombinedOutput(); err != nil {
		log.Printf("git checkout -B failed: %v, output: %s", err, string(out))
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Failed to create/switch branch", string(out))
		}
		return
	}

	// 6. Rebase onto the remote feature branch when it advanced (preserves external pushes).
	if republished && !c.rebaseOnto(workspace, cmdEnv, "origin/"+branchName, reqID) {
		return
	}

	// 7. Rebase onto the refreshed target branch when it is not an ancestor.
	if targetRef != "" && !c.rebaseOnto(workspace, cmdEnv, targetRef, reqID) {
		return
	}

	// 8. Push to remote origin using registered credentials and environment.
	pushArgs := []string{"push", "-u", "origin", branchName}
	if params.Force {
		pushArgs = append(pushArgs, "--force-with-lease")
	}
	pushCmd := exec.Command("git", pushArgs...) //nolint:gosec
	pushCmd.Dir = workspace
	pushCmd.Env = cmdEnv
	if pushOut, pushErr := pushCmd.CombinedOutput(); pushErr != nil {
		log.Printf("git push failed: %v, output: %s", pushErr, string(pushOut))
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Failed to push branch to remote", string(pushOut))
		}
		return
	}

	// 9. Resolve head commit SHA
	revCmd := exec.Command("git", "rev-parse", "HEAD") //nolint:gosec
	revCmd.Dir = workspace
	revCmd.Env = cmdEnv
	revOut, revErr := revCmd.Output()
	commitSha := ""
	if revErr == nil {
		commitSha = strings.TrimSpace(string(revOut))
	}

	remoteRef := fmt.Sprintf("refs/heads/%s", branchName)
	if reqID != nil {
		c.sendSuccessResponse(reqID, GitPushResult{
			CommitSha:  commitSha,
			BranchName: branchName,
			RemoteRef:  remoteRef,
			Status:     "success",
		})
	}
}

// revParseExists reports whether the given ref resolves in the workspace.
func (c *Client) revParseExists(workspace string, cmdEnv []string, ref string) bool {
	cmd := exec.Command("git", "rev-parse", "--verify", ref) //nolint:gosec
	cmd.Dir = workspace
	cmd.Env = cmdEnv
	return cmd.Run() == nil
}

// stageAll runs git add -A and reports success; errors are sent to the caller.
func (c *Client) stageAll(workspace string, cmdEnv []string, reqID interface{}) bool {
	addCmd := exec.Command("git", "add", "-A") //nolint:gosec
	addCmd.Dir = workspace
	addCmd.Env = cmdEnv
	if out, err := addCmd.CombinedOutput(); err != nil {
		log.Printf("git add -A failed: %v, output: %s", err, string(out))
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Failed to stage changes", string(out))
		}
		return false
	}
	return true
}

// hasStagedChanges reports whether the index differs from HEAD.
func (c *Client) hasStagedChanges(workspace string, cmdEnv []string) bool {
	diffCached := exec.Command("git", "diff", "--cached", "--quiet") //nolint:gosec
	diffCached.Dir = workspace
	diffCached.Env = cmdEnv
	return diffCached.Run() != nil
}

// commitWithMessage commits the staged changes, ignoring the git "nothing to
// commit" no-op. Errors are sent to the caller.
func (c *Client) commitWithMessage(workspace string, cmdEnv []string, commitMessage string, reqID interface{}) {
	commitCmd := exec.Command("git", "commit", "-m", commitMessage) //nolint:gosec
	commitCmd.Dir = workspace
	commitCmd.Env = cmdEnv
	if out, err := commitCmd.CombinedOutput(); err != nil {
		if !strings.Contains(string(out), "nothing to commit") {
			log.Printf("git commit failed: %v, output: %s", err, string(out))
			if reqID != nil {
				c.sendErrorResponse(reqID, -32000, "Failed to commit changes", string(out))
			}
		}
	}
}

// rebaseOnto replays the current branch onto ref, skipping when ref is already
// an ancestor. On conflict the rebase is aborted (restoring the pre-rebase
// branch state) and a REBASE_CONFLICT error is sent so the control plane can
// escalate to the agent for a rebase & retest cycle.
func (c *Client) rebaseOnto(workspace string, cmdEnv []string, ref string, reqID interface{}) bool {
	isAncestor := exec.Command("git", "merge-base", "--is-ancestor", ref, "HEAD") //nolint:gosec
	isAncestor.Dir = workspace
	isAncestor.Env = cmdEnv
	if err := isAncestor.Run(); err == nil {
		return true
	}

	rebaseCmd := exec.Command("git", "rebase", ref) //nolint:gosec
	rebaseCmd.Dir = workspace
	rebaseCmd.Env = cmdEnv
	if out, err := rebaseCmd.CombinedOutput(); err != nil {
		log.Printf("git rebase %s failed: %v, output: %s", ref, err, string(out))
		abortCmd := exec.Command("git", "rebase", "--abort") //nolint:gosec
		abortCmd.Dir = workspace
		abortCmd.Env = cmdEnv
		if abortOut, abortErr := abortCmd.CombinedOutput(); abortErr != nil {
			log.Printf("git rebase --abort failed: %v, output: %s", abortErr, string(abortOut))
		}
		if reqID != nil {
			c.sendErrorResponse(reqID, -32001, "REBASE_CONFLICT: "+ref, string(out))
		}
		return false
	}
	return true
}
