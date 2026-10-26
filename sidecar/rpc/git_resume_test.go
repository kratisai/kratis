package rpc

import (
	"os"
	"path/filepath"
	"sort"
	"testing"
)

// resumeFixture is a clone shaped like a real sandbox checkout: the origin has several commits on
// main and a develop branch one commit ahead, and the workspace is cloned from develop so that
// origin/HEAD (main) differs from the target branch.
type resumeFixture struct {
	bareDir  string
	workDir  string
	rootSHA  string
	mainSHA  string
	devSHA   string
	cleanups []string
}

func newResumeFixture(t *testing.T) *resumeFixture {
	t.Helper()
	f := &resumeFixture{}
	mk := func() string {
		dir, err := os.MkdirTemp("", "kratis-resume-*")
		if err != nil {
			t.Fatalf("Failed to create temp dir: %v", err)
		}
		f.cleanups = append(f.cleanups, dir)
		return dir
	}
	t.Cleanup(func() {
		for _, dir := range f.cleanups {
			_ = os.RemoveAll(dir)
		}
	})

	f.bareDir = mk()
	runGitCmd(t, f.bareDir, "init", "--bare", "-b", "main")

	seed := mk()
	runGitCmd(t, seed, "clone", f.bareDir, ".")
	commit := func(file, content string) string {
		if err := os.WriteFile(filepath.Join(seed, file), []byte(content), 0600); err != nil {
			t.Fatalf("Failed to write %s: %v", file, err)
		}
		runGitCmd(t, seed, "add", file)
		runGitCmd(t, seed, "commit", "-m", "add "+file)
		return gitOutput(t, seed, "rev-parse", "HEAD")
	}
	runGitCmd(t, seed, "checkout", "-b", "main")
	f.rootSHA = commit("a.txt", "a\n")
	commit("b.txt", "b\n")
	f.mainSHA = commit("c.txt", "c\n")
	runGitCmd(t, seed, "push", "origin", "main")
	runGitCmd(t, seed, "checkout", "-b", "develop")
	f.devSHA = commit("d.txt", "d\n")
	runGitCmd(t, seed, "push", "origin", "develop")

	f.workDir = mk()
	runGitCmd(t, f.workDir, "clone", "--branch", "develop", f.bareDir, ".")
	return f
}

func sortedPaths(files []GitDiffManifestFile) []string {
	paths := make([]string, 0, len(files))
	for _, file := range files {
		paths = append(paths, file.Path)
	}
	sort.Strings(paths)
	return paths
}

// TestResumedConnector_ManifestListsOnlyLocalChanges reproduces a suspend/resume: checkout runs in
// the first connector process, the second process never sees env.checkout but shares the disk.
func TestResumedConnector_ManifestListsOnlyLocalChanges(t *testing.T) {
	f := newResumeFixture(t)

	first, _ := connectedGitClient(t, f.workDir)
	first.ExecuteCheckout(CheckoutParams{URL: f.bareDir, Branch: "develop", ExecutionID: "exec-1"}, "req-co")

	resumed, received := connectedGitClient(t, f.workDir)
	resumed.stateDir = first.stateDir
	resumed.mu.Lock()
	resumed.currentExecutionID = "exec-1"
	resumed.mu.Unlock()

	if err := os.WriteFile(filepath.Join(f.workDir, "local.txt"), []byte("local\n"), 0600); err != nil {
		t.Fatalf("Failed to write local file: %v", err)
	}
	resumed.TriggerDiffCheck()

	manifest := captureDiffManifest(t, received)
	got := sortedPaths(manifest.Files)
	if len(got) != 1 || got[0] != "local.txt" {
		t.Errorf("Expected only local.txt after resume, got %v", got)
	}
}

func TestResolveDiffBase_Matrix(t *testing.T) {
	f := newResumeFixture(t)

	newRepo, err := os.MkdirTemp("", "kratis-resume-new-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(newRepo) })
	runGitCmd(t, newRepo, "init", "-b", "main")
	runGitCmd(t, newRepo, "commit", "--allow-empty", "-m", "Initial commit")
	newRoot := gitOutput(t, newRepo, "rev-parse", "HEAD")

	noHead := newResumeFixture(t)
	runGitCmd(t, noHead.workDir, "remote", "set-head", "origin", "-d")

	tests := []struct {
		name      string
		workspace string
		branch    string
		want      string
	}{
		{"no origin is a new repository", newRepo, "", newRoot},
		{"recorded branch wins over origin/HEAD", f.workDir, "develop", f.devSHA},
		{"recorded default branch", f.workDir, "main", f.mainSHA},
		{"empty branch uses origin/HEAD", f.workDir, "", f.mainSHA},
		{"missing recorded branch falls back to root", f.workDir, "nope", f.rootSHA},
		{"empty branch without origin/HEAD falls back to root", noHead.workDir, "", noHead.rootSHA},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := resolveDiffBase(tc.workspace, tc.branch)
			if err != nil {
				t.Fatalf("resolveDiffBase failed: %v", err)
			}
			if got != tc.want {
				t.Errorf("Expected base %s, got %s", tc.want, got)
			}
		})
	}
}

func TestDiffBaseBranch_PersistedValueIsTrimmedAndBlankIsIgnored(t *testing.T) {
	client := &Client{stateDir: t.TempDir()}

	if got := client.diffBaseBranch(); got != "" {
		t.Errorf("Expected no base branch before any checkout, got %q", got)
	}

	if err := os.WriteFile(client.diffBaseBranchPath(), []byte(" \n"), 0600); err != nil {
		t.Fatalf("Failed to write blank base branch: %v", err)
	}
	if got := client.diffBaseBranch(); got != "" {
		t.Errorf("Expected a blank persisted branch to be ignored, got %q", got)
	}

	if err := os.WriteFile(client.diffBaseBranchPath(), []byte("develop\n"), 0600); err != nil {
		t.Fatalf("Failed to write base branch: %v", err)
	}
	if got := client.diffBaseBranch(); got != "develop" {
		t.Errorf("Expected trimmed persisted branch 'develop', got %q", got)
	}
}

func TestPersistBaseBranch_BlankKeepsExistingValue(t *testing.T) {
	client := &Client{stateDir: t.TempDir()}
	client.persistBaseBranch("develop")
	client.persistBaseBranch("  ")

	if got := client.readPersistedBaseBranch(); got != "develop" {
		t.Errorf("Expected blank persist to keep 'develop', got %q", got)
	}
}
