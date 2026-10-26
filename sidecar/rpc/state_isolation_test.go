package rpc

import (
	"fmt"
	"os"
	"strings"
	"testing"
)

// TestStatePathsLiveUnderStateDir locks in the single-root rule: every file the
// sidecar persists must derive from stateDir, so redirecting one field isolates
// the whole client.
func TestStatePathsLiveUnderStateDir(t *testing.T) {
	client := newTestClient(t, t.TempDir())
	root := client.stateRoot()

	paths := map[string]string{
		"env file":           client.envFilePath(),
		"acp session id":     client.acpSessionIDPath(),
		"diff base branch":   client.diffBaseBranchPath(),
		"execution base sha": client.executionBaseShaPath(),
		"credential helper":  client.credentialHelperScript(),
		"git config":         client.statePath("gitconfig"),
		"terminal log":       client.terminalLogPath(),
	}

	for name, path := range paths {
		if !pathWithin(root, path) {
			t.Errorf("%s path %q is outside state root %q", name, path, root)
		}
		if strings.HasPrefix(path, productionStateDir+string(os.PathSeparator)) {
			t.Errorf("%s path %q leaks into the live sandbox state", name, path)
		}
	}
}

// TestClientWithoutStateDirFailsClosed proves a zero-value Client cannot fall
// back to the live sandbox state.
func TestClientWithoutStateDirFailsClosed(t *testing.T) {
	assertPanics(t, "no stateDir", func() {
		(&Client{}).envFilePath()
	})
}

// TestClientWithoutWorkspaceFailsClosed proves a zero-value Client cannot fall
// back to the live sandbox checkout.
func TestClientWithoutWorkspaceFailsClosed(t *testing.T) {
	assertPanics(t, "no workspace", func() {
		(&Client{}).resolveWorkspace()
	})
}

// TestNewClientUsesTestStateRoot proves the production constructor cannot leak
// the live state root in a test binary.
func TestNewClientUsesTestStateRoot(t *testing.T) {
	client := NewClient("ws://example.invalid", "token", t.TempDir())
	defer client.Close()

	if !pathWithin(os.TempDir(), client.stateRoot()) {
		t.Fatalf("NewClient state root %q is not inside the test temp dir %s", client.stateRoot(), os.TempDir())
	}
	if strings.HasPrefix(client.stateRoot(), productionStateDir) {
		t.Fatalf("NewClient state root %q leaks into %s", client.stateRoot(), productionStateDir)
	}
}

func assertPanics(t *testing.T, want string, fn func()) {
	t.Helper()
	defer func() {
		recovered := recover()
		if recovered == nil {
			t.Fatalf("expected panic containing %q", want)
		}
		if !strings.Contains(fmt.Sprint(recovered), want) {
			t.Fatalf("panic %q does not contain %q", fmt.Sprint(recovered), want)
		}
	}()
	fn()
}

// TestNoHardcodedSandboxPaths prevents production code from reintroducing
// ambient /kratis paths outside the single state-root constant.
func TestNoHardcodedSandboxPaths(t *testing.T) {
	scanGoFiles(t, func(name string, lineNo int, line string) {
		if strings.HasSuffix(name, "_test.go") {
			return
		}
		if !strings.Contains(line, `"/kratis`) || strings.Contains(line, "productionStateDir =") {
			return
		}
		t.Errorf("%s:%d hardcodes a live sandbox path: %s", name, lineNo, strings.TrimSpace(line))
	})
}

// TestNoTestClientUsesLivePaths prevents tests from opting back into the live
// sandbox state or checkout. Tests must inject t.TempDir() paths.
func TestNoTestClientUsesLivePaths(t *testing.T) {
	scanGoFiles(t, func(name string, lineNo int, line string) {
		if !strings.HasSuffix(name, "_test.go") {
			return
		}
		for _, field := range []string{"stateDir:", "workspace:"} {
			if strings.Contains(line, field+" productionStateDir") ||
				strings.Contains(line, field+` "/kratis`) {
				t.Errorf("%s:%d points a test Client at live sandbox state: %s",
					name, lineNo, strings.TrimSpace(line))
			}
		}
	})
}

func scanGoFiles(t *testing.T, visit func(name string, lineNo int, line string)) {
	t.Helper()
	entries, err := os.ReadDir(".")
	if err != nil {
		t.Fatalf("read package dir: %v", err)
	}
	for _, entry := range entries {
		name := entry.Name()
		if !strings.HasSuffix(name, ".go") {
			continue
		}
		data, err := os.ReadFile(name)
		if err != nil {
			t.Fatalf("read %s: %v", name, err)
		}
		for i, line := range strings.Split(string(data), "\n") {
			trimmed := strings.TrimSpace(line)
			if strings.HasPrefix(trimmed, "//") {
				continue
			}
			visit(name, i+1, line)
		}
	}
}
