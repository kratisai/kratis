package rpc

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// remoteBranchHeadSha resolves origin's remote SHA for a given branch name.
// It uses git ls-remote and tri-state logic:
// - sha string (branch present)
// - "" with nil error (exit code 2 or empty output: branch absent)
// - "" with error (any other non-zero exit code: network/auth failure)
func (c *Client) remoteBranchHeadSha(workspace string, cmdEnv []string, branchName string) (string, error) {
	branch := strings.TrimSpace(branchName)
	if branch == "" {
		return "", nil
	}
	cmd := exec.Command("git", "ls-remote", "--exit-code", "--heads", "origin", "refs/heads/"+branch) //nolint:gosec
	cmd.Dir = workspace
	cmd.Env = cmdEnv
	out, err := cmd.CombinedOutput()
	if err != nil {
		var exitErr *exec.ExitError
		if errors.As(err, &exitErr) && exitErr.ExitCode() == 2 {
			return "", nil
		}
		return "", fmt.Errorf("git ls-remote failed: %w, output: %s", err, string(out))
	}
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 1 && strings.HasPrefix(fields[len(fields)-1], "refs/heads/"+branch) {
			return fields[0], nil
		}
	}
	return "", nil
}

// resolvePublishStatsBase returns the commit that the publish change set is measured against.
// When the target branch exists on origin, the publish delta is relative to its tip; otherwise the
// delta starts at the execution base.
func resolvePublishStatsBase(workspace string, cmdEnv []string, execBaseSha, headSha string) string {
	headSha = strings.TrimSpace(headSha)
	if headSha == "" {
		return execBaseSha
	}
	verifyCmd := exec.Command("git", "cat-file", "-e", headSha+"^{commit}") //nolint:gosec
	verifyCmd.Dir = workspace
	verifyCmd.Env = cmdEnv
	if verifyCmd.Run() != nil {
		return execBaseSha
	}
	return headSha
}

// computeChangeSetStats computes the files modified/added/deleted between baseCommit and the working tree,
// including untracked files and ignoring .kratis.
func (c *Client) computeChangeSetStats(workspace string, cmdEnv []string, baseCommit string) (GitChangeSetStats, error) {
	if strings.TrimSpace(baseCommit) == "" {
		baseCommit = "HEAD"
	}

	// 1. Git diff --name-status baseCommit (tracked committed, staged, unstaged)
	statusMap := make(map[string]GitDiffStatus)
	nameStatusCmd := exec.Command("git", "diff", "--name-status", baseCommit) //nolint:gosec
	nameStatusCmd.Dir = workspace
	nameStatusCmd.Env = cmdEnv
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
				if isKratisPlatformPath(filePath) {
					continue
				}
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

	filesMap := make(map[string]GitChangeSetFile)
	totalAdds := 0
	totalDels := 0

	numstatCmd := exec.Command("git", "diff", "--numstat", baseCommit) //nolint:gosec
	numstatCmd.Dir = workspace
	numstatCmd.Env = cmdEnv
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
				if isKratisPlatformPath(filePath) {
					continue
				}
				status := GitDiffModified
				if s, ok := statusMap[filePath]; ok {
					status = s
				}
				totalAdds += adds
				totalDels += dels
				filesMap[filePath] = GitChangeSetFile{
					Path:      filePath,
					Status:    status,
					Additions: adds,
					Deletions: dels,
				}
			}
		}
	}

	// 2. Untracked files via git ls-files --others --exclude-standard
	untrackedCmd := exec.Command("git", "ls-files", "--others", "--exclude-standard") //nolint:gosec
	untrackedCmd.Dir = workspace
	untrackedCmd.Env = cmdEnv
	if uOut, err := untrackedCmd.Output(); err == nil {
		for _, line := range strings.Split(string(uOut), "\n") {
			line = strings.TrimSpace(line)
			if line == "" || isKratisPlatformPath(line) {
				continue
			}
			fullPath := filepath.Join(workspace, line)
			lineCount := 0
			//nolint:gosec // G304: reading workspace file discovered via git ls-files
			if data, err := os.ReadFile(fullPath); err == nil {
				trimmed := strings.TrimRight(string(data), "\r\n")
				if trimmed != "" {
					lineCount = len(strings.Split(trimmed, "\n"))
				}
			}
			totalAdds += lineCount
			filesMap[line] = GitChangeSetFile{
				Path:      line,
				Status:    GitDiffAdded,
				Additions: lineCount,
				Deletions: 0,
			}
		}
	}

	files := make([]GitChangeSetFile, 0, len(filesMap))
	for _, f := range filesMap {
		files = append(files, f)
	}
	sort.Slice(files, func(i, j int) bool {
		return files[i].Path < files[j].Path
	})

	hasher := sha256.New()
	for _, f := range files {
		_, _ = fmt.Fprintf(hasher, "%s:%s:%d:%d\n", f.Path, f.Status, f.Additions, f.Deletions)
	}
	digest := hex.EncodeToString(hasher.Sum(nil))

	return GitChangeSetStats{
		Additions: totalAdds,
		Deletions: totalDels,
		Files:     files,
		Digest:    digest,
	}, nil
}

// hasWorkspaceUncommittedChanges checks whether there are staged, unstaged, or untracked changes
// in the workspace (excluding .kratis).
func (c *Client) hasWorkspaceUncommittedChanges(workspace string, cmdEnv []string) bool {
	diffHead := exec.Command("git", "diff", "HEAD", "--quiet", "--", ":!.kratis") //nolint:gosec
	diffHead.Dir = workspace
	diffHead.Env = cmdEnv
	if diffHead.Run() != nil {
		return true
	}
	untrackedCmd := exec.Command("git", "ls-files", "--others", "--exclude-standard") //nolint:gosec
	untrackedCmd.Dir = workspace
	untrackedCmd.Env = cmdEnv
	out, err := untrackedCmd.Output()
	if err == nil {
		for _, line := range strings.Split(string(out), "\n") {
			line = strings.TrimSpace(line)
			if line != "" && !isKratisPlatformPath(line) {
				return true
			}
		}
	}
	return false
}

// countCommitsBetween returns the number of commits in range from..to.
func countCommitsBetween(workspace string, cmdEnv []string, fromCommit, toCommit string) int {
	if strings.TrimSpace(fromCommit) == "" || strings.TrimSpace(toCommit) == "" {
		return 0
	}
	cmd := exec.Command("git", "rev-list", "--count", fmt.Sprintf("%s..%s", fromCommit, toCommit)) //nolint:gosec
	cmd.Dir = workspace
	cmd.Env = cmdEnv
	out, err := cmd.Output()
	if err != nil {
		return 0
	}
	count, _ := strconv.Atoi(strings.TrimSpace(string(out)))
	return count
}

// ExecuteGitObservePublish inspects origin and local git state.
func (c *Client) ExecuteGitObservePublish(params GitObservePublishParams, reqID interface{}) {
	workspace := c.resolveWorkspace()
	cmdEnv := append(os.Environ(), "GIT_TERMINAL_PROMPT=0")

	c.reassertCredentialConfig()

	// 1. Fetch origin
	fetchCmd := exec.Command("git", "fetch", "origin") //nolint:gosec
	fetchCmd.Dir = workspace
	fetchCmd.Env = cmdEnv
	if out, err := fetchCmd.CombinedOutput(); err != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "git fetch origin failed", string(out))
		}
		return
	}

	// 2. Resolve default branch
	defaultBranch := originDefaultBranch(workspace)
	if defaultBranch == "" {
		defaultBranch = "main"
	}
	defaultRef := "origin/" + defaultBranch
	defaultShaCmd := exec.Command("git", "rev-parse", "--verify", defaultRef) //nolint:gosec
	defaultShaCmd.Dir = workspace
	defaultShaCmd.Env = cmdEnv
	defaultShaBytes, err := defaultShaCmd.Output()
	if err != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, fmt.Sprintf("unable to resolve default branch ref %s", defaultRef), err.Error())
		}
		return
	}
	defaultSha := strings.TrimSpace(string(defaultShaBytes))

	// 3. Execution base SHA
	execBaseSha := c.readPersistedExecutionBaseSha()
	if execBaseSha == "" {
		// Fallback to merge-base with default branch or root commit
		if mb, mbErr := mergeBaseWithHead(workspace, defaultRef); mbErr == nil {
			execBaseSha = mb
			c.persistExecutionBaseSha(mb)
		} else {
			execBaseSha = defaultSha
		}
	}

	// 4. Default is ancestor of HEAD
	ancCmd := exec.Command("git", "merge-base", "--is-ancestor", defaultSha, "HEAD") //nolint:gosec
	ancCmd.Dir = workspace
	ancCmd.Env = cmdEnv
	defaultIsAncestorOfHead := ancCmd.Run() == nil

	// 5. Incoming commits on the default branch
	incomingCommitCount := countCommitsBetween(workspace, cmdEnv, execBaseSha, defaultSha)

	// 6. Head branch SHA on remote
	headSha, headErr := c.remoteBranchHeadSha(workspace, cmdEnv, params.HeadBranch)
	if headErr != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Failed to inspect remote head branch", headErr.Error())
		}
		return
	}

	// 7. Publish delta: workspace relative to the target branch when it exists, otherwise the
	// execution base. The main diff view keeps using the execution base.
	publishBase := resolvePublishStatsBase(workspace, cmdEnv, execBaseSha, headSha)
	commitCount := countCommitsBetween(workspace, cmdEnv, publishBase, "HEAD")
	hasUncommittedChanges := c.hasWorkspaceUncommittedChanges(workspace, cmdEnv)
	stats, statsErr := c.computeChangeSetStats(workspace, cmdEnv, publishBase)
	if statsErr != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Failed to compute changeset stats", statsErr.Error())
		}
		return
	}

	if reqID != nil {
		c.sendSuccessResponse(reqID, GitObservePublishResult{
			DefaultBranch:           defaultBranch,
			DefaultSha:              defaultSha,
			ExecutionBaseSha:        execBaseSha,
			DefaultIsAncestorOfHead: defaultIsAncestorOfHead,
			IncomingCommitCount:     incomingCommitCount,
			CommitCount:             commitCount,
			HasUncommittedChanges:   hasUncommittedChanges,
			HeadSha:                 headSha,
			ChangeSet:               stats,
		})
	}
}

// ExecuteGitRebase replays the workspace history onto origin/<defaultBranch>.
func (c *Client) ExecuteGitRebase(params GitRebaseParams, reqID interface{}) {
	c.publishingMu.Lock()
	defer c.publishingMu.Unlock()

	workspace := c.resolveWorkspace()
	cmdEnv := append(os.Environ(), "GIT_TERMINAL_PROMPT=0")
	c.reassertCredentialConfig()

	// 1. Fetch origin
	fetchCmd := exec.Command("git", "fetch", "origin") //nolint:gosec
	fetchCmd.Dir = workspace
	fetchCmd.Env = cmdEnv
	if out, err := fetchCmd.CombinedOutput(); err != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "git fetch origin failed", string(out))
		}
		return
	}

	defaultBranch := strings.TrimSpace(params.DefaultBranch)
	if defaultBranch == "" {
		defaultBranch = originDefaultBranch(workspace)
		if defaultBranch == "" {
			defaultBranch = "main"
		}
	}
	defaultRef := "origin/" + defaultBranch
	defShaCmd := exec.Command("git", "rev-parse", "--verify", defaultRef) //nolint:gosec
	defShaCmd.Dir = workspace
	defShaCmd.Env = cmdEnv
	defShaBytes, err := defShaCmd.Output()
	if err != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Unable to resolve default branch ref", err.Error())
		}
		return
	}
	actualDefaultSha := strings.TrimSpace(string(defShaBytes))

	if params.ExpectedDefaultSha != "" && params.ExpectedDefaultSha != actualDefaultSha {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32001, "DEFAULT_BRANCH_MOVED", fmt.Sprintf("expected %s, found %s", params.ExpectedDefaultSha, actualDefaultSha))
		}
		return
	}

	// 2. Stage platform dir aside so .kratis is never touched by git stash or rebase
	stagedPlatformDir := ""
	platformDir := filepath.Join(workspace, ".kratis")
	if _, err := os.Stat(platformDir); err == nil {
		staged, stageErr := stagePlatformDir(workspace)
		if stageErr != nil {
			if reqID != nil {
				c.sendErrorResponse(reqID, -32000, "Failed to stage platform state", stageErr.Error())
			}
			return
		}
		stagedPlatformDir = staged
		defer func() { _ = restorePlatformDir(workspace, stagedPlatformDir) }()
	}

	// 3. Stash any uncommitted & untracked changes
	stashPushed := false
	stashCmd := exec.Command("git", "stash", "push", "--include-untracked", "-m", "kratis-rebase-stash") //nolint:gosec
	stashCmd.Dir = workspace
	stashCmd.Env = cmdEnv
	stashOut, stashErr := stashCmd.CombinedOutput()
	if stashErr == nil && !strings.Contains(string(stashOut), "No local changes to save") {
		stashPushed = true
	}

	// 4. Rebase onto defaultRef
	rebaseCmd := exec.Command("git", "rebase", defaultRef) //nolint:gosec
	rebaseCmd.Dir = workspace
	rebaseCmd.Env = cmdEnv
	if rebaseOut, err := rebaseCmd.CombinedOutput(); err != nil {
		log.Printf("git rebase %s failed: %v, out: %s", defaultRef, err, string(rebaseOut))
		abortCmd := exec.Command("git", "rebase", "--abort")
		abortCmd.Dir = workspace
		abortCmd.Env = cmdEnv
		_ = abortCmd.Run()
		if stashPushed {
			popCmd := exec.Command("git", "stash", "pop")
			popCmd.Dir = workspace
			popCmd.Env = cmdEnv
			_ = popCmd.Run()
		}
		if reqID != nil {
			c.sendErrorResponse(reqID, -32001, "REBASE_CONFLICT: "+defaultRef, string(rebaseOut))
		}
		return
	}

	// 5. Restore stash if we saved one
	if stashPushed {
		popCmd := exec.Command("git", "stash", "pop") //nolint:gosec
		popCmd.Dir = workspace
		popCmd.Env = cmdEnv
		if popOut, err := popCmd.CombinedOutput(); err != nil {
			log.Printf("git stash pop failed: %v, out: %s", err, string(popOut))
			if reqID != nil {
				c.sendErrorResponse(reqID, -32001, "STASH_CONFLICT", string(popOut))
			}
			return
		}
	}

	// 6. Update execution base SHA
	c.persistExecutionBaseSha(actualDefaultSha)

	// 7. Compute updated stats
	stats, _ := c.computeChangeSetStats(workspace, cmdEnv, actualDefaultSha)

	if reqID != nil {
		c.sendSuccessResponse(reqID, GitRebaseResult{
			NewBaseSha: actualDefaultSha,
			ChangeSet:  stats,
		})
	}
}

// ExecuteGitPublish commits, squashes (if requested), and pushes to the target branch.
func (c *Client) ExecuteGitPublish(params GitPushParams, reqID interface{}) {
	if !c.publishingMu.TryLock() {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32002, "PUBLISH_IN_PROGRESS", "A publish operation is already running")
		}
		return
	}
	defer c.publishingMu.Unlock()

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

	workspace := c.resolveWorkspace()
	cmdEnv := append(os.Environ(), "GIT_TERMINAL_PROMPT=0")
	c.reassertCredentialConfig()

	// 1. Fetch origin
	fetchCmd := exec.Command("git", "fetch", "origin") //nolint:gosec
	fetchCmd.Dir = workspace
	fetchCmd.Env = cmdEnv
	if out, err := fetchCmd.CombinedOutput(); err != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "git fetch origin failed", string(out))
		}
		return
	}

	// 2. Verify default branch
	defaultBranch := strings.TrimSpace(params.DefaultBranch)
	if defaultBranch == "" {
		defaultBranch = strings.TrimSpace(params.TargetBranch)
	}
	if defaultBranch == "" {
		defaultBranch = originDefaultBranch(workspace)
		if defaultBranch == "" {
			defaultBranch = "main"
		}
	}
	defaultRef := "origin/" + defaultBranch
	defShaCmd := exec.Command("git", "rev-parse", "--verify", defaultRef) //nolint:gosec
	defShaCmd.Dir = workspace
	defShaCmd.Env = cmdEnv
	defShaBytes, err := defShaCmd.Output()
	if err != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Unable to resolve default branch ref", err.Error())
		}
		return
	}
	actualDefaultSha := strings.TrimSpace(string(defShaBytes))

	if params.ExpectedDefaultSha != "" && params.ExpectedDefaultSha != actualDefaultSha {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32001, "DEFAULT_BRANCH_MOVED", fmt.Sprintf("expected %s, found %s", params.ExpectedDefaultSha, actualDefaultSha))
		}
		return
	}

	execBaseSha := c.readPersistedExecutionBaseSha()
	if execBaseSha != "" && execBaseSha != actualDefaultSha {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32001, "REBASE_REQUIRED", fmt.Sprintf("execution base %s is behind default %s", execBaseSha, actualDefaultSha))
		}
		return
	}

	// 3. Verify remote head lease if updating existing branch
	if !params.CreateBranch {
		actualHeadSha, headErr := c.remoteBranchHeadSha(workspace, cmdEnv, branchName)
		if headErr != nil {
			if reqID != nil {
				c.sendErrorResponse(reqID, -32000, "Failed to inspect remote head branch", headErr.Error())
			}
			return
		}
		if params.ExpectedHeadSha != "" && actualHeadSha != params.ExpectedHeadSha {
			if reqID != nil {
				c.sendErrorResponse(reqID, -32001, "HEAD_MOVED", fmt.Sprintf("expected %s, found %s", params.ExpectedHeadSha, actualHeadSha))
			}
			return
		}
	}

	// 4. Verify changeset digest if supplied
	if params.ExpectedChangeSetDigest != "" {
		baseForStats := execBaseSha
		if baseForStats == "" {
			baseForStats = actualDefaultSha
		}
		if remoteHeadSha, headErr := c.remoteBranchHeadSha(workspace, cmdEnv, branchName); headErr == nil {
			baseForStats = resolvePublishStatsBase(workspace, cmdEnv, baseForStats, remoteHeadSha)
		}
		stats, err := c.computeChangeSetStats(workspace, cmdEnv, baseForStats)
		if err == nil && stats.Digest != params.ExpectedChangeSetDigest {
			if reqID != nil {
				c.sendErrorResponse(reqID, -32001, "CHANGESET_CHANGED", fmt.Sprintf("expected digest %s, found %s", params.ExpectedChangeSetDigest, stats.Digest))
			}
			return
		}
	}

	// 5. Stage and commit uncommitted files
	if !c.stageAll(workspace, cmdEnv, reqID) {
		return
	}
	if c.hasStagedChanges(workspace, cmdEnv) {
		c.commitWithMessage(workspace, cmdEnv, commitMessage, reqID)
	}

	// 6. Switch to branchName
	checkoutCmd := exec.Command("git", "checkout", "-B", branchName) //nolint:gosec
	checkoutCmd.Dir = workspace
	checkoutCmd.Env = cmdEnv
	if out, err := checkoutCmd.CombinedOutput(); err != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Failed to create/switch branch", string(out))
		}
		return
	}

	// 7. Squash if requested
	if params.Squash {
		resetCmd := exec.Command("git", "reset", "--soft", defaultRef) //nolint:gosec
		resetCmd.Dir = workspace
		resetCmd.Env = cmdEnv
		if out, err := resetCmd.CombinedOutput(); err != nil {
			if reqID != nil {
				c.sendErrorResponse(reqID, -32000, "Failed to squash changes", string(out))
			}
			return
		}
		c.commitWithMessage(workspace, cmdEnv, commitMessage, reqID)
	}

	// 8. Push to remote origin
	pushArgs := []string{"push", "-u", "origin", branchName}
	switch {
	case params.Force:
		pushArgs = append(pushArgs, "--force-with-lease")
	case !params.CreateBranch && params.ExpectedHeadSha != "":
		pushArgs = append(pushArgs, fmt.Sprintf("--force-with-lease=refs/heads/%s:%s", branchName, params.ExpectedHeadSha))
	case !params.CreateBranch:
		pushArgs = append(pushArgs, "--force-with-lease")
	}
	pushCmd := exec.Command("git", pushArgs...) //nolint:gosec
	pushCmd.Dir = workspace
	pushCmd.Env = cmdEnv
	if pushOut, pushErr := pushCmd.CombinedOutput(); pushErr != nil {
		if reqID != nil {
			c.sendErrorResponse(reqID, -32000, "Failed to push branch to remote", string(pushOut))
		}
		return
	}

	// 9. Resolve new HEAD commit SHA
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
