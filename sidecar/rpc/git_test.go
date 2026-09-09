package rpc

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestExecuteCheckout_LocalGitRepo(t *testing.T) {
	srcDir, err := os.MkdirTemp("", "kratis-git-src-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(srcDir) }()

	if err := exec.Command("git", "init", "-b", "main", srcDir).Run(); err != nil {
		t.Fatalf("failed to init git: %v", err)
	}
	if err := exec.Command("git", "-C", srcDir, "config", "user.name", "Test User").Run(); err != nil {
		t.Fatalf("failed to config git name: %v", err)
	}
	if err := exec.Command("git", "-C", srcDir, "config", "user.email", "test@example.com").Run(); err != nil {
		t.Fatalf("failed to config git email: %v", err)
	}

	testFile := srcDir + "/hello.txt"
	if err := os.WriteFile(testFile, []byte("hello world"), 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "add", ".").Run()
	_ = exec.Command("git", "-C", srcDir, "commit", "-m", "initial commit").Run()

	cmd := exec.Command("git", "-C", srcDir, "rev-parse", "HEAD")
	expectedHashBytes, _ := cmd.Output()
	expectedHash := strings.TrimSpace(string(expectedHashBytes))

	destDir, err := os.MkdirTemp("", "kratis-git-dest-*")
	if err != nil {
		t.Fatalf("failed to create temp dest dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(destDir) }()

	var gotComplete atomic.Bool
	var completeParams CheckoutCompleteParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.checkout_complete" {
				var p CheckoutCompleteParams
				rawBytes, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(rawBytes, &p)
				completeParams = p
				gotComplete.Store(true)
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", destDir)
	c.wsConn = connectClient(t, wsURL(srv), "tok").wsConn

	params := CheckoutParams{
		URL:    "file://" + srcDir,
		Branch: "main",
	}

	c.ExecuteCheckout(params, nil)
	time.Sleep(300 * time.Millisecond)

	if !gotComplete.Load() {
		t.Error("env.checkout_complete was never sent")
	}

	if completeParams.Status != CheckoutSuccess {
		t.Errorf("expected checkout status 'success', got %s: %s", completeParams.Status, completeParams.Error)
	}

	if completeParams.CommitHash != expectedHash {
		t.Errorf("expected commit hash %s, got %s", expectedHash, completeParams.CommitHash)
	}

	c.Close()
}

func TestExecuteCheckout_Failed(t *testing.T) {
	var gotComplete atomic.Bool
	var completeParams CheckoutCompleteParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.checkout_complete" {
				var p CheckoutCompleteParams
				rawBytes, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(rawBytes, &p)
				completeParams = p
				gotComplete.Store(true)
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", "/invalid/workspace/dir/that/does/not/exist/nonexistent-workspace")
	c.wsConn = connectClient(t, wsURL(srv), "tok").wsConn

	params := CheckoutParams{
		URL:    "file:///invalid/nonexistent-path-abc-123",
		Branch: "main",
	}

	c.ExecuteCheckout(params, nil)
	time.Sleep(300 * time.Millisecond)

	if !gotComplete.Load() {
		t.Error("env.checkout_complete was never sent")
	}

	if completeParams.Status != CheckoutFailed {
		t.Errorf("expected checkout status 'failed', got %s", completeParams.Status)
	}

	if completeParams.Error == "" {
		t.Error("expected error message in failure status, got empty string")
	}

	c.Close()
}

func TestExecuteCheckout_ExistingGitRepo_WithChanges(t *testing.T) {
	srcDir, err := os.MkdirTemp("", "kratis-git-src-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(srcDir) }()

	if err := exec.Command("git", "init", "-b", "main", srcDir).Run(); err != nil {
		t.Fatalf("failed to init git: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "config", "user.name", "Test User").Run()
	_ = exec.Command("git", "-C", srcDir, "config", "user.email", "test@example.com").Run()

	testFile := filepath.Join(srcDir, "hello.txt")
	if err := os.WriteFile(testFile, []byte("hello world"), 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "add", ".").Run()
	_ = exec.Command("git", "-C", srcDir, "commit", "-m", "initial commit").Run()

	destDir, err := os.MkdirTemp("", "kratis-git-dest-*")
	if err != nil {
		t.Fatalf("failed to create temp dest dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(destDir) }()

	// Clone the repo first
	if err := exec.Command("git", "clone", "file://"+srcDir, destDir).Run(); err != nil {
		t.Fatalf("failed to clone git: %v", err)
	}

	// Write a modified file to destDir to make the working tree dirty
	if err := os.WriteFile(filepath.Join(destDir, "hello.txt"), []byte("dirty changes"), 0644); err != nil {
		t.Fatalf("failed to write dirty changes: %v", err)
	}

	var gotComplete atomic.Bool
	var completeParams CheckoutCompleteParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.checkout_complete" {
				var p CheckoutCompleteParams
				rawBytes, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(rawBytes, &p)
				completeParams = p
				gotComplete.Store(true)
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", destDir)
	c.wsConn = connectClient(t, wsURL(srv), "tok").wsConn

	params := CheckoutParams{
		URL:    "file://" + srcDir,
		Branch: "main",
	}

	c.ExecuteCheckout(params, nil)
	time.Sleep(300 * time.Millisecond)

	if !gotComplete.Load() {
		t.Error("env.checkout_complete was never sent")
	}

	if completeParams.Status != CheckoutFailed {
		t.Errorf("expected checkout status 'failed', got %s", completeParams.Status)
	}

	if !strings.Contains(completeParams.Error, "uncommitted changes") {
		t.Errorf("expected error message to contain 'uncommitted changes', got: %s", completeParams.Error)
	}

	c.Close()
}

func TestExecuteCheckout_ExistingGitRepo_MismatchOrigin(t *testing.T) {
	srcDir, err := os.MkdirTemp("", "kratis-git-src-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(srcDir) }()

	if err := exec.Command("git", "init", "-b", "main", srcDir).Run(); err != nil {
		t.Fatalf("failed to init git: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "config", "user.name", "Test User").Run()
	_ = exec.Command("git", "-C", srcDir, "config", "user.email", "test@example.com").Run()

	testFile := filepath.Join(srcDir, "hello.txt")
	if err := os.WriteFile(testFile, []byte("hello world"), 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "add", ".").Run()
	_ = exec.Command("git", "-C", srcDir, "commit", "-m", "initial commit").Run()

	destDir, err := os.MkdirTemp("", "kratis-git-dest-*")
	if err != nil {
		t.Fatalf("failed to create temp dest dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(destDir) }()

	// Clone a mismatched repository URL first (e.g. clone srcDir) but we will try checking out a different path
	if err := exec.Command("git", "clone", "file://"+srcDir, destDir).Run(); err != nil {
		t.Fatalf("failed to clone git: %v", err)
	}

	var gotComplete atomic.Bool
	var completeParams CheckoutCompleteParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.checkout_complete" {
				var p CheckoutCompleteParams
				rawBytes, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(rawBytes, &p)
				completeParams = p
				gotComplete.Store(true)
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", destDir)
	c.wsConn = connectClient(t, wsURL(srv), "tok").wsConn

	params := CheckoutParams{
		URL:    "file:///some/other/mismatched/path",
		Branch: "main",
	}

	c.ExecuteCheckout(params, nil)
	time.Sleep(300 * time.Millisecond)

	if !gotComplete.Load() {
		t.Error("env.checkout_complete was never sent")
	}

	if completeParams.Status != CheckoutFailed {
		t.Errorf("expected checkout status 'failed', got %s", completeParams.Status)
	}

	if !strings.Contains(completeParams.Error, "does not match target URL") {
		t.Errorf("expected error message to contain 'does not match target URL', got: %s", completeParams.Error)
	}

	c.Close()
}

func TestExecuteCheckout_ExistingGitRepo_Success(t *testing.T) {
	srcDir, err := os.MkdirTemp("", "kratis-git-src-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(srcDir) }()

	if err := exec.Command("git", "init", "-b", "main", srcDir).Run(); err != nil {
		t.Fatalf("failed to init git: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "config", "user.name", "Test User").Run()
	_ = exec.Command("git", "-C", srcDir, "config", "user.email", "test@example.com").Run()

	testFile := filepath.Join(srcDir, "hello.txt")
	if err := os.WriteFile(testFile, []byte("hello world"), 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "add", ".").Run()
	_ = exec.Command("git", "-C", srcDir, "commit", "-m", "initial commit").Run()

	destDir, err := os.MkdirTemp("", "kratis-git-dest-*")
	if err != nil {
		t.Fatalf("failed to create temp dest dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(destDir) }()

	// Clone the repo first
	if err := exec.Command("git", "clone", "file://"+srcDir, destDir).Run(); err != nil {
		t.Fatalf("failed to clone git: %v", err)
	}

	var gotComplete atomic.Bool
	var completeParams CheckoutCompleteParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.checkout_complete" {
				var p CheckoutCompleteParams
				rawBytes, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(rawBytes, &p)
				completeParams = p
				gotComplete.Store(true)
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", destDir)
	c.wsConn = connectClient(t, wsURL(srv), "tok").wsConn

	params := CheckoutParams{
		URL:    "file://" + srcDir,
		Branch: "main",
	}

	c.ExecuteCheckout(params, nil)
	time.Sleep(300 * time.Millisecond)

	if !gotComplete.Load() {
		t.Error("env.checkout_complete was never sent")
	}

	if completeParams.Status != CheckoutSuccess {
		t.Errorf("expected checkout status 'success', got %s: %s", completeParams.Status, completeParams.Error)
	}

	c.Close()
}

func TestGitUtilities(t *testing.T) {
	tests := []struct {
		urlA     string
		urlB     string
		expected bool
	}{
		{"git@github.com:foo/bar.git", "https://github.com/foo/bar", true},
		{"https://github.com/foo/bar.git/", "git@github.com:foo/bar", true},
		{"git@github.com:foo/bar.git", "git@github.com:foo/baz.git", false},
		{"https://github.com/foo/bar", "https://gitlab.com/foo/bar", false},
	}
	for _, tc := range tests {
		if got := urlsMatch(tc.urlA, tc.urlB); got != tc.expected {
			t.Errorf("urlsMatch(%q, %q) = %t; want %t", tc.urlA, tc.urlB, got, tc.expected)
		}
	}

	tmp, err := os.MkdirTemp("", "git-util-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(tmp) }()

	empty, err := isDirEmpty(tmp)
	if err != nil || !empty {
		t.Errorf("expected empty dir, got empty=%t, err=%v", empty, err)
	}

	if err := os.WriteFile(filepath.Join(tmp, "a.txt"), []byte("hello"), 0644); err != nil {
		t.Fatalf("failed to write file: %v", err)
	}
	empty, err = isDirEmpty(tmp)
	if err != nil || empty {
		t.Errorf("expected non-empty dir, got empty=%t, err=%v", empty, err)
	}

	_, err = isDirEmpty("/nonexistent-path-dir-12345")
	if err == nil {
		t.Error("expected error for non-existent dir, got nil")
	}
}

func TestExecuteRegisterGitAuth(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")
	c.credentialsDir = t.TempDir()
	paramsPAT := RegisterGitAuthParams{
		CredentialType: "GIT_PAT",
	}
	c.ExecuteRegisterGitAuth(paramsPAT, nil)

	helperScript := filepath.Join(c.credentialsDir, "git-credentials-helper.sh")
	if _, err := os.Stat(helperScript); err != nil {
		t.Errorf("expected helper script to be created at %s", helperScript)
	}
	defer func() { _ = os.Remove(helperScript) }()

	paramsSSH := RegisterGitAuthParams{
		CredentialType: "SSH_KEY",
		PrivateKey:     "invalid-private-key",
	}
	c.ExecuteRegisterGitAuth(paramsSSH, nil)
}

// TestExecuteRegisterGitAuth_ProcessWideEnv verifies registerGitAuth applies auth
// process-wide via env vars, and re-registration clears the previous state.
func TestExecuteRegisterGitAuth_ProcessWideEnv(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")
	c.credentialsDir = t.TempDir()
	defer c.Close()

	cleanupEnv := func() {
		_ = os.Unsetenv("SSH_AUTH_SOCK")
		_ = os.Unsetenv("GIT_SSH_COMMAND")
		_ = os.Unsetenv("GIT_CONFIG_GLOBAL")
	}
	defer cleanupEnv()

	// PAT registration deploys a generated global gitconfig (credential helper +
	// git author identity) and sets GIT_CONFIG_GLOBAL
	c.ExecuteRegisterGitAuth(RegisterGitAuthParams{
		CredentialType: "PAT",
		UserName:       "Kratis",
		UserEmail:      "kratis@control-plane-host",
	}, nil)
	if os.Getenv("GIT_CONFIG_GLOBAL") == "" {
		t.Error("expected GIT_CONFIG_GLOBAL to be set after PAT registration")
	}
	gitConfig := os.Getenv("GIT_CONFIG_GLOBAL")
	if _, err := os.Stat(gitConfig); err != nil { //nolint:gosec // G703: test temp gitconfig path
		t.Errorf("expected generated gitconfig to exist at %s: %v", gitConfig, err)
	}
	defer func() { _ = os.Remove(gitConfig) }() //nolint:gosec // G703: test temp gitconfig path
	patContent, err := os.ReadFile(gitConfig)   //nolint:gosec // G304: test reads the generated gitconfig
	if err != nil {
		t.Fatalf("failed to read PAT gitconfig: %v", err)
	}
	patContentStr := string(patContent)
	if !strings.Contains(patContentStr, "[user]") {
		t.Errorf("expected PAT gitconfig to contain a [user] section, got:\n%s", patContentStr)
	}
	if !strings.Contains(patContentStr, "name = Kratis") {
		t.Errorf("expected PAT gitconfig to preserve user.name, got:\n%s", patContentStr)
	}
	if !strings.Contains(patContentStr, "email = kratis@control-plane-host") {
		t.Errorf("expected PAT gitconfig to preserve user.email, got:\n%s", patContentStr)
	}
	if !strings.Contains(patContentStr, "[credential]") {
		t.Errorf("expected PAT gitconfig to contain the credential helper, got:\n%s", patContentStr)
	}

	// SSH registration clears the PAT helper/gitconfig and env, and sets
	// SSH_AUTH_SOCK/GIT_SSH_COMMAND. A gitconfig with the git author identity is
	// still persisted so later commits (publish) have an identity.
	keyPath, err := generateSSHTestKey(t)
	if err != nil {
		t.Fatalf("failed to generate SSH key: %v", err)
	}
	privateKey, err := os.ReadFile(keyPath) //nolint:gosec // G304: test fixture reads the generated SSH key
	if err != nil {
		t.Fatalf("failed to read SSH key: %v", err)
	}
	c.ExecuteRegisterGitAuth(RegisterGitAuthParams{
		CredentialType: "SSH_KEY",
		PrivateKey:     string(privateKey),
		UserName:       "Alice Engineer",
		UserEmail:      "alice@example.com",
	}, nil)
	if os.Getenv("SSH_AUTH_SOCK") == "" {
		t.Error("expected SSH_AUTH_SOCK to be set after SSH registration")
	}
	if os.Getenv("GIT_SSH_COMMAND") != "ssh -o StrictHostKeyChecking=no" {
		t.Errorf("expected GIT_SSH_COMMAND to be 'ssh -o StrictHostKeyChecking=no', got %q", os.Getenv("GIT_SSH_COMMAND"))
	}
	if os.Getenv("GIT_CONFIG_GLOBAL") == "" {
		t.Error("expected GIT_CONFIG_GLOBAL to be set after SSH registration (git identity persists)")
	}
	sshConfig := os.Getenv("GIT_CONFIG_GLOBAL")
	content, err := os.ReadFile(sshConfig) //nolint:gosec // G304: test reads the generated gitconfig
	if err != nil {
		t.Fatalf("failed to read SSH gitconfig: %v", err)
	}
	sshContent := string(content)
	if !strings.Contains(sshContent, "[user]") {
		t.Errorf("expected SSH gitconfig to contain a [user] section, got:\n%s", sshContent)
	}
	if !strings.Contains(sshContent, "name = Alice Engineer") {
		t.Errorf("expected SSH gitconfig to preserve user.name, got:\n%s", sshContent)
	}
	if !strings.Contains(sshContent, "email = alice@example.com") {
		t.Errorf("expected SSH gitconfig to preserve user.email, got:\n%s", sshContent)
	}
	if strings.Contains(sshContent, "[credential]") {
		t.Errorf("expected SSH gitconfig to have no credential helper, got:\n%s", sshContent)
	}
}

func generateSSHTestKey(t *testing.T) (string, error) {
	t.Helper()
	keyPath := filepath.Join(t.TempDir(), "id_ed25519")
	cmd := exec.Command("ssh-keygen", "-t", "ed25519", "-N", "", "-f", keyPath)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("ssh-keygen failed: %w, output: %s", err, string(out))
	}
	return keyPath, nil
}

// TestExecuteRegisterGitAuth_ControlPlaneVocabulary verifies the sidecar accepts the credential
// types emitted by the control-plane (PAT / GITHUB_APP / SSH_KEY) rather than only the legacy
// GIT_PAT / GITHUB values.
func TestExecuteRegisterGitAuth_ControlPlaneVocabulary(t *testing.T) {
	for _, credentialType := range []CredentialType{CredentialTypePAT, CredentialTypeGitHubApp} {
		c := NewClient("ws://localhost:12345", "tok", "", "")
		c.credentialsDir = t.TempDir()
		params := RegisterGitAuthParams{
			CredentialType: credentialType,
		}
		c.ExecuteRegisterGitAuth(params, nil)
		helperScript := filepath.Join(c.credentialsDir, "git-credentials-helper.sh")
		if _, err := os.Stat(helperScript); err != nil {
			t.Errorf("credentialType %s: expected helper script to be created at %s", credentialType, helperScript)
		}
	}

	c := NewClient("ws://localhost:12345", "tok", "", "")
	c.credentialsDir = t.TempDir()
	c.ExecuteRegisterGitAuth(RegisterGitAuthParams{
		CredentialType: CredentialTypeSSHKey,
		PrivateKey:     "invalid-private-key",
	}, nil)
}

// TestExecuteRegisterGitAuth_RejectsUnsupportedType verifies the sidecar no longer silently accepts
// unknown credential types (regression guard for the PAT/GIT_PAT vocabulary drift).
func TestExecuteRegisterGitAuth_RejectsUnsupportedType(t *testing.T) {
	var (
		mu           sync.Mutex
		responseMsg  []byte
		responseChan = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.ID != nil && req.Method == "" {
				mu.Lock()
				if responseMsg == nil {
					responseMsg = msg
					select {
					case <-responseChan:
					default:
						close(responseChan)
					}
				}
				mu.Unlock()
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	c.handleServerRequest(JsonRpcRequest{
		Method: "env.registerGitAuth",
		Params: map[string]interface{}{
			"credentialType": "BOGUS",
			"token":          "should-not-be-stored",
		},
		ID: uint64(703),
	})

	select {
	case <-responseChan:
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for registerGitAuth error response")
	}

	mu.Lock()
	respBytes := responseMsg
	mu.Unlock()

	var resp JsonRpcResponse
	if err := json.Unmarshal(respBytes, &resp); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}
	if resp.Error == nil {
		t.Fatalf("expected an error response for unsupported credentialType, got: %s", string(respBytes))
	}

	c.mu.Lock()
	helper := c.gitHelperScript
	c.mu.Unlock()
	if helper != "" {
		t.Errorf("expected gitHelperScript to remain empty, got '%s'", helper)
	}

	c.Close()
}

func TestExecuteCheckout_UncommittedChanges(t *testing.T) {
	srcDir, err := os.MkdirTemp("", "git-src-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(srcDir) }()

	if err := exec.Command("git", "init", srcDir).Run(); err != nil {
		t.Fatalf("failed to init git: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "config", "user.email", "test@example.com").Run()
	_ = exec.Command("git", "-C", srcDir, "config", "user.name", "Test").Run()

	if err := os.WriteFile(filepath.Join(srcDir, "file.txt"), []byte("initial"), 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "add", ".").Run()
	_ = exec.Command("git", "-C", srcDir, "commit", "-m", "initial").Run()

	if err := os.WriteFile(filepath.Join(srcDir, "file.txt"), []byte("modified"), 0644); err != nil {
		t.Fatalf("failed to write modified file: %v", err)
	}

	var gotComplete atomic.Bool
	var completeParams CheckoutCompleteParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.checkout_complete" {
				var p CheckoutCompleteParams
				rawBytes, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(rawBytes, &p)
				completeParams = p
				gotComplete.Store(true)
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", srcDir)
	c.wsConn = connectClient(t, wsURL(srv), "tok").wsConn
	errChan := make(chan error, 1)
	go c.readLoop(errChan)
	defer c.Close()

	c.ExecuteCheckout(CheckoutParams{URL: "file://" + srcDir, Branch: "main"}, nil)
	time.Sleep(300 * time.Millisecond)

	if !gotComplete.Load() {
		t.Error("env.checkout_complete was never sent")
	}

	if completeParams.Status != CheckoutFailed || !strings.Contains(completeParams.Error, "uncommitted changes") {
		t.Errorf("expected failed status due to uncommitted changes, got status=%q, error=%q", completeParams.Status, completeParams.Error)
	}
}

func TestExecuteCheckout_EdgeCases(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "git-dest-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(tmpDir) }()
	if err := os.WriteFile(filepath.Join(tmpDir, "file.txt"), []byte("data"), 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}

	var gotComplete atomic.Bool
	var completeParams CheckoutCompleteParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.checkout_complete" {
				var p CheckoutCompleteParams
				rawBytes, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(rawBytes, &p)
				completeParams = p
				gotComplete.Store(true)
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", tmpDir)
	c.wsConn = connectClient(t, wsURL(srv), "tok").wsConn
	errChan := make(chan error, 1)
	go c.readLoop(errChan)
	defer c.Close()

	c.ExecuteCheckout(CheckoutParams{URL: "https://github.com/foo/bar", Branch: "main"}, nil)
	time.Sleep(300 * time.Millisecond)

	if !gotComplete.Load() || completeParams.Status != CheckoutFailed || !strings.Contains(completeParams.Error, "is populated") {
		t.Errorf("expected checkout to fail on populated non-git directory, got: %s", completeParams.Error)
	}

	gitDir, err := os.MkdirTemp("", "git-dest-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(gitDir) }()

	if err := exec.Command("git", "init", gitDir).Run(); err != nil {
		t.Fatalf("failed to init git: %v", err)
	}
	_ = exec.Command("git", "-C", gitDir, "remote", "add", "origin", "https://github.com/foo/bar").Run()

	gotComplete.Store(false)
	cMismatch := NewClient(wsURL(srv), "tok", "", gitDir)
	cMismatch.wsConn = connectClient(t, wsURL(srv), "tok").wsConn
	errChan2 := make(chan error, 1)
	go cMismatch.readLoop(errChan2)
	defer cMismatch.Close()

	cMismatch.ExecuteCheckout(CheckoutParams{URL: "https://github.com/mismatch/repo", Branch: "main"}, nil)
	time.Sleep(300 * time.Millisecond)

	if !gotComplete.Load() || completeParams.Status != CheckoutFailed || !strings.Contains(completeParams.Error, "does not match target") {
		t.Errorf("expected checkout to fail on mismatched URL, got: %s", completeParams.Error)
	}
}

// TestExecuteCheckout_EmptyDirectory tests checkout when the workspace directory
// exists but is empty (not a git repo). It should attempt to clone into it.
func TestExecuteCheckout_EmptyDirectory(t *testing.T) {
	srcDir, err := os.MkdirTemp("", "kratis-git-src-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(srcDir) }()

	if err := exec.Command("git", "init", "-b", "main", srcDir).Run(); err != nil {
		t.Fatalf("failed to init git: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "config", "user.name", "Test User").Run()
	_ = exec.Command("git", "-C", srcDir, "config", "user.email", "test@example.com").Run()
	testFile := srcDir + "/hello.txt"
	if err := os.WriteFile(testFile, []byte("hello world"), 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "add", ".").Run()
	_ = exec.Command("git", "-C", srcDir, "commit", "-m", "initial commit").Run()

	// Create an empty destination directory
	destDir, err := os.MkdirTemp("", "kratis-git-empty-dest-*")
	if err != nil {
		t.Fatalf("failed to create temp dest dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(destDir) }()

	var gotComplete atomic.Bool
	var completeParams CheckoutCompleteParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.checkout_complete" {
				var p CheckoutCompleteParams
				rawBytes, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(rawBytes, &p)
				completeParams = p
				gotComplete.Store(true)
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", destDir)
	c.wsConn = connectClient(t, wsURL(srv), "tok").wsConn
	errChan := make(chan error, 1)
	go c.readLoop(errChan)
	defer c.Close()

	params := CheckoutParams{
		URL:    "file://" + srcDir,
		Branch: "main",
	}

	c.ExecuteCheckout(params, nil)
	time.Sleep(500 * time.Millisecond)

	if !gotComplete.Load() {
		t.Error("env.checkout_complete was never sent")
	}

	if completeParams.Status != CheckoutSuccess {
		t.Errorf("expected checkout status 'success' for empty directory clone, got %s: %s", completeParams.Status, completeParams.Error)
	}
}

// TestCleanGitURL_EdgeCases tests cleanGitURL with various URL formats including
// the user@host:path format (non-git@).
func TestCleanGitURL_EdgeCases(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		// Standard HTTPS
		{"https://github.com/foo/bar.git", "github.com/foo/bar"},
		// Standard SSH
		{"git@github.com:foo/bar.git", "github.com/foo/bar"},
		// Non-git@ user@host format
		{"user@host.example.com:path/to/repo.git", "host.example.com/path/to/repo"},
		// With trailing slashes
		{"https://github.com/foo/bar/", "github.com/foo/bar"},
		// With protocol and trailing .git
		{"https://gitlab.com/org/project.git/", "gitlab.com/org/project"},
		// Plain host:path (no protocol, no @)
		{"github.com:foo/bar", "github.com/foo/bar"},
		// Whitespace trimming
		{"  https://github.com/foo/bar.git  ", "github.com/foo/bar"},
	}

	for _, tc := range tests {
		got := cleanGitURL(tc.input)
		if got != tc.expected {
			t.Errorf("cleanGitURL(%q) = %q; want %q", tc.input, got, tc.expected)
		}
	}
}

// TestExecuteRegisterGitAuth_CleanupPrevious tests that ExecuteRegisterGitAuth
// cleans up previous SSH agent and helper script before setting new credentials.
func TestExecuteRegisterGitAuth_CleanupPrevious(t *testing.T) {
	c := NewClient("ws://localhost:12345", "tok", "", "")
	c.credentialsDir = t.TempDir()
	defer c.Close()

	// Set up a fake previous SSH agent PID (a real process we can verify gets killed)
	dummyCmd := exec.Command("sleep", "30")
	if err := dummyCmd.Start(); err != nil {
		t.Fatalf("failed to start dummy process: %v", err)
	}
	c.mu.Lock()
	c.sshAgentPID = fmt.Sprintf("%d", dummyCmd.Process.Pid)
	c.sshAuthSock = "/tmp/fake-ssh-sock"
	c.mu.Unlock()

	// Create a fake helper script at a DIFFERENT path than the one ExecuteRegisterGitAuth will create
	oldHelperScript := fmt.Sprintf("/tmp/kratis-git-credentials-helper-old-%d.sh", os.Getpid())
	_ = os.WriteFile(oldHelperScript, []byte("#!/bin/sh\necho test"), 0700)
	c.mu.Lock()
	c.gitHelperScript = oldHelperScript
	c.mu.Unlock()

	// Create a fake previous gitconfig and set the process-wide env to mirror deployed state
	oldGitConfig := fmt.Sprintf("/tmp/kratis-gitconfig-old-%d", os.Getpid())
	_ = os.WriteFile(oldGitConfig, []byte("[credential]\n\thelper = old\n"), 0600)
	c.mu.Lock()
	c.gitConfigGlobal = oldGitConfig
	c.mu.Unlock()
	_ = os.Setenv("GIT_CONFIG_GLOBAL", oldGitConfig)
	defer func() { _ = os.Unsetenv("GIT_CONFIG_GLOBAL") }()

	// Now register new GIT_PAT credentials - this should clean up the previous state
	params := RegisterGitAuthParams{
		CredentialType: "GIT_PAT",
	}
	c.ExecuteRegisterGitAuth(params, nil)

	// Verify the dummy process was killed
	err := dummyCmd.Wait()
	if err == nil {
		t.Error("expected previous SSH agent process to be killed")
	}

	// Verify the old helper script was removed
	if _, statErr := os.Stat(oldHelperScript); !os.IsNotExist(statErr) {
		t.Error("expected old helper script to be removed")
		_ = os.Remove(oldHelperScript)
	}

	// Verify the old gitconfig was removed and the env points at the new one
	if _, statErr := os.Stat(oldGitConfig); !os.IsNotExist(statErr) {
		t.Error("expected old gitconfig to be removed")
		_ = os.Remove(oldGitConfig)
	}
	if os.Getenv("GIT_CONFIG_GLOBAL") == oldGitConfig {
		t.Error("expected GIT_CONFIG_GLOBAL to point at the new gitconfig")
	}

	// Verify the new helper script was configured
	c.mu.Lock()
	newHelper := c.gitHelperScript
	c.mu.Unlock()
	if newHelper == "" {
		t.Error("expected new helper script to be configured")
	}
}

// TestExecuteCheckout_WithPAT tests checkout when gitPAT is configured,
// exercising the credential helper configuration paths.
func TestExecuteCheckout_WithPAT(t *testing.T) {
	srcDir, err := os.MkdirTemp("", "kratis-git-src-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(srcDir) }()

	if err := exec.Command("git", "init", "-b", "main", srcDir).Run(); err != nil {
		t.Fatalf("failed to init git: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "config", "user.name", "Test User").Run()
	_ = exec.Command("git", "-C", srcDir, "config", "user.email", "test@example.com").Run()
	testFile := srcDir + "/hello.txt"
	if err := os.WriteFile(testFile, []byte("hello world"), 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "add", ".").Run()
	_ = exec.Command("git", "-C", srcDir, "commit", "-m", "initial commit").Run()

	destDir, err := os.MkdirTemp("", "kratis-git-dest-*")
	if err != nil {
		t.Fatalf("failed to create temp dest dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(destDir) }()

	var gotComplete atomic.Bool
	var completeParams CheckoutCompleteParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.checkout_complete" {
				var p CheckoutCompleteParams
				rawBytes, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(rawBytes, &p)
				completeParams = p
				gotComplete.Store(true)
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", destDir)
	c.credentialsDir = t.TempDir()
	c.wsConn = connectClient(t, wsURL(srv), "tok").wsConn
	errChan := make(chan error, 1)
	go c.readLoop(errChan)
	defer c.Close()

	// Deploy auth process-wide via the single mechanism (registerGitAuth); checkout itself
	// must be auth-agnostic.
	c.ExecuteRegisterGitAuth(RegisterGitAuthParams{CredentialType: "GIT_PAT"}, nil)
	defer func() { _ = os.Unsetenv("GIT_CONFIG_GLOBAL") }()
	if os.Getenv("GIT_CONFIG_GLOBAL") == "" {
		t.Fatal("expected GIT_CONFIG_GLOBAL to be set after registerGitAuth")
	}

	params := CheckoutParams{
		URL:    "file://" + srcDir,
		Branch: "main",
	}

	c.ExecuteCheckout(params, nil)
	time.Sleep(500 * time.Millisecond)

	if !gotComplete.Load() {
		t.Error("env.checkout_complete was never sent")
	}

	// The clone should succeed even with PAT configured (file:// doesn't use credentials)
	if completeParams.Status != CheckoutSuccess {
		t.Errorf("expected checkout status 'success', got %s: %s", completeParams.Status, completeParams.Error)
	}

	// Checkout must not configure a local credential helper; auth is inherited process-wide
	helperOut, _ := exec.Command("git", "-C", destDir, "config", "--local", "--get", "credential.helper").CombinedOutput()
	if strings.TrimSpace(string(helperOut)) != "" {
		t.Errorf("expected no local credential.helper after checkout, got %q", strings.TrimSpace(string(helperOut)))
	}
}

// TestExecuteCheckout_ExistingGitRepo_WithPAT tests checkout on an existing git repo
// when gitPAT is configured, exercising the credential helper configuration path.
func TestExecuteCheckout_ExistingGitRepo_WithPAT(t *testing.T) {
	srcDir, err := os.MkdirTemp("", "kratis-git-src-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(srcDir) }()

	if err := exec.Command("git", "init", "-b", "main", srcDir).Run(); err != nil {
		t.Fatalf("failed to init git: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "config", "user.name", "Test User").Run()
	_ = exec.Command("git", "-C", srcDir, "config", "user.email", "test@example.com").Run()
	testFile := filepath.Join(srcDir, "hello.txt")
	if err := os.WriteFile(testFile, []byte("hello world"), 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "add", ".").Run()
	_ = exec.Command("git", "-C", srcDir, "commit", "-m", "initial commit").Run()

	destDir, err := os.MkdirTemp("", "kratis-git-dest-*")
	if err != nil {
		t.Fatalf("failed to create temp dest dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(destDir) }()

	if err := exec.Command("git", "clone", "file://"+srcDir, destDir).Run(); err != nil {
		t.Fatalf("failed to clone git: %v", err)
	}

	var gotComplete atomic.Bool
	var completeParams CheckoutCompleteParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.checkout_complete" {
				var p CheckoutCompleteParams
				rawBytes, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(rawBytes, &p)
				completeParams = p
				gotComplete.Store(true)
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", destDir)
	c.credentialsDir = t.TempDir()
	c.wsConn = connectClient(t, wsURL(srv), "tok").wsConn
	errChan := make(chan error, 1)
	go c.readLoop(errChan)
	defer c.Close()

	// Deploy auth process-wide via the single mechanism (registerGitAuth); checkout itself
	// must be auth-agnostic.
	c.ExecuteRegisterGitAuth(RegisterGitAuthParams{CredentialType: "GIT_PAT"}, nil)
	defer func() { _ = os.Unsetenv("GIT_CONFIG_GLOBAL") }()
	if os.Getenv("GIT_CONFIG_GLOBAL") == "" {
		t.Fatal("expected GIT_CONFIG_GLOBAL to be set after registerGitAuth")
	}

	params := CheckoutParams{
		URL:    "file://" + srcDir,
		Branch: "main",
	}

	c.ExecuteCheckout(params, nil)
	time.Sleep(500 * time.Millisecond)

	if !gotComplete.Load() {
		t.Error("env.checkout_complete was never sent")
	}

	if completeParams.Status != CheckoutSuccess {
		t.Errorf("expected checkout status 'success', got %s: %s", completeParams.Status, completeParams.Error)
	}

	// Checkout must not configure a local credential helper; auth is inherited process-wide
	helperOut, _ := exec.Command("git", "-C", destDir, "config", "--local", "--get", "credential.helper").CombinedOutput()
	if strings.TrimSpace(string(helperOut)) != "" {
		t.Errorf("expected no local credential.helper after checkout, got %q", strings.TrimSpace(string(helperOut)))
	}
}

// TestExecuteCheckout_DefaultBranch tests checkout when no branch is specified,
// exercising the default "main" branch path.
func TestExecuteCheckout_DefaultBranch(t *testing.T) {
	srcDir, err := os.MkdirTemp("", "kratis-git-src-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(srcDir) }()

	if err := exec.Command("git", "init", "-b", "main", srcDir).Run(); err != nil {
		t.Fatalf("failed to init git: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "config", "user.name", "Test User").Run()
	_ = exec.Command("git", "-C", srcDir, "config", "user.email", "test@example.com").Run()
	testFile := srcDir + "/hello.txt"
	if err := os.WriteFile(testFile, []byte("hello world"), 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "add", ".").Run()
	_ = exec.Command("git", "-C", srcDir, "commit", "-m", "initial commit").Run()

	destDir, err := os.MkdirTemp("", "kratis-git-dest-*")
	if err != nil {
		t.Fatalf("failed to create temp dest dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(destDir) }()

	var gotComplete atomic.Bool
	var completeParams CheckoutCompleteParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.checkout_complete" {
				var p CheckoutCompleteParams
				rawBytes, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(rawBytes, &p)
				completeParams = p
				gotComplete.Store(true)
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", destDir)
	c.wsConn = connectClient(t, wsURL(srv), "tok").wsConn
	errChan := make(chan error, 1)
	go c.readLoop(errChan)
	defer c.Close()

	// No branch specified - should default to "main"
	params := CheckoutParams{
		URL: "file://" + srcDir,
	}

	c.ExecuteCheckout(params, nil)
	time.Sleep(500 * time.Millisecond)

	if !gotComplete.Load() {
		t.Error("env.checkout_complete was never sent")
	}

	if completeParams.Status != CheckoutSuccess {
		t.Errorf("expected checkout status 'success', got %s: %s", completeParams.Status, completeParams.Error)
	}
}

// TestExecuteCheckout_WithEnvVars tests that checkout succeeds when caller-provided
// environment variables (e.g. GIT_TERMINAL_PROMPT, SSH_ASKPASS) are passed via
// CheckoutParams.Env and merged into the git command environment.
func TestExecuteCheckout_WithEnvVars(t *testing.T) {
	srcDir, err := os.MkdirTemp("", "kratis-git-src-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(srcDir) }()

	if err := exec.Command("git", "init", "-b", "main", srcDir).Run(); err != nil {
		t.Fatalf("failed to init git: %v", err)
	}
	if err := exec.Command("git", "-C", srcDir, "config", "user.name", "Test User").Run(); err != nil {
		t.Fatalf("failed to config git name: %v", err)
	}
	if err := exec.Command("git", "-C", srcDir, "config", "user.email", "test@example.com").Run(); err != nil {
		t.Fatalf("failed to config git email: %v", err)
	}

	testFile := srcDir + "/hello.txt"
	if err := os.WriteFile(testFile, []byte("hello world"), 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "add", ".").Run()
	_ = exec.Command("git", "-C", srcDir, "commit", "-m", "initial commit").Run()

	destDir, err := os.MkdirTemp("", "kratis-git-dest-*")
	if err != nil {
		t.Fatalf("failed to create temp dest dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(destDir) }()

	var gotComplete atomic.Bool
	var completeParams CheckoutCompleteParams

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.checkout_complete" {
				var p CheckoutCompleteParams
				rawBytes, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(rawBytes, &p)
				completeParams = p
				gotComplete.Store(true)
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", destDir)
	c.wsConn = connectClient(t, wsURL(srv), "tok").wsConn

	// Pass headless git environment variables as the control plane would
	params := CheckoutParams{
		URL:    "file://" + srcDir,
		Branch: "main",
		Env: map[string]string{
			"GIT_TERMINAL_PROMPT": "0",
			"SSH_ASKPASS":         "",
		},
	}

	c.ExecuteCheckout(params, nil)
	time.Sleep(500 * time.Millisecond)

	if !gotComplete.Load() {
		t.Error("env.checkout_complete was never sent")
	}

	if completeParams.Status != CheckoutSuccess {
		t.Errorf("expected checkout status 'success', got %s: %s", completeParams.Status, completeParams.Error)
	}

	// Verify the file was actually cloned
	if _, err := os.Stat(destDir + "/hello.txt"); err != nil {
		t.Errorf("expected cloned file hello.txt to exist: %v", err)
	}

	c.Close()
}
