package rpc

import (
	"bytes"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// startGitHttpBackend serves a git repository over smart HTTP with HTTP Basic
// authentication by delegating to `git http-backend`. It counts how many
// requests arrived without credentials (401 challenges) and how many arrived
// with the expected password, so tests can prove that git actually obtained
// credentials from the Kratis credential helper.
func startGitHttpBackend(t *testing.T, projectRoot, password string) (string, *atomic.Int32, *atomic.Int32) {
	t.Helper()
	var unauthorized, authorized atomic.Int32

	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		_, gotPassword, ok := r.BasicAuth()
		if !ok || gotPassword != password {
			unauthorized.Add(1)
			w.Header().Set("WWW-Authenticate", `Basic realm="kratis-test"`)
			http.Error(w, "authentication required", http.StatusUnauthorized)
			return
		}
		authorized.Add(1)
		serveGitHttpBackend(w, r, projectRoot)
	})

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to listen: %v", err)
	}
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	go func() { _ = srv.Serve(ln) }()
	t.Cleanup(func() { _ = srv.Close() })

	return "http://" + ln.Addr().String(), &unauthorized, &authorized
}

func serveGitHttpBackend(w http.ResponseWriter, r *http.Request, projectRoot string) {
	cmd := exec.Command("git", "http-backend")
	cmd.Env = append(
		os.Environ(),
		"GIT_PROJECT_ROOT="+projectRoot,
		"GIT_HTTP_EXPORT_ALL=1",
		"PATH_INFO="+r.URL.Path,
		"REQUEST_METHOD="+r.Method,
		"QUERY_STRING="+r.URL.RawQuery,
		"CONTENT_TYPE="+r.Header.Get("Content-Type"),
	)
	if r.Method == http.MethodPost {
		cmd.Stdin = r.Body
	}
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		http.Error(w, "git http-backend failed: "+stderr.String(), http.StatusInternalServerError)
		return
	}

	out := stdout.Bytes()
	headerEnd := bytes.Index(out, []byte("\r\n\r\n"))
	if headerEnd < 0 {
		http.Error(w, "malformed git http-backend response", http.StatusInternalServerError)
		return
	}

	status := http.StatusOK
	for _, line := range strings.Split(string(out[:headerEnd]), "\r\n") {
		name, value, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		name = strings.TrimSpace(name)
		value = strings.TrimSpace(value)
		if strings.EqualFold(name, "Status") {
			if code, err := strconv.Atoi(strings.Fields(value)[0]); err == nil {
				status = code
			}
			continue
		}
		if strings.EqualFold(name, "Content-Length") {
			continue
		}
		w.Header().Add(name, value)
	}
	w.WriteHeader(status)
	_, _ = w.Write(out[headerEnd+4:])
}

// TestExecuteCheckout_RealHttpsClone_WithCredentialHelper validates the full
// production credential flow: registerGitAuth deploys the credential helper
// process-wide, then a real `git clone` over HTTP(S) invokes the helper, which
// must fetch the token from the connector's credential socket and authenticate.
func TestExecuteCheckout_RealHttpsClone_WithCredentialHelper(t *testing.T) {
	const token = "test-pat-token"

	srcDir, err := os.MkdirTemp("", "kratis-https-src-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(srcDir) }()

	if err := exec.Command("git", "init", "-b", "main", srcDir).Run(); err != nil {
		t.Fatalf("failed to init git: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "config", "user.name", "Test User").Run()
	_ = exec.Command("git", "-C", srcDir, "config", "user.email", "test@example.com").Run()
	if err := os.WriteFile(filepath.Join(srcDir, "hello.txt"), []byte("hello over https"), 0644); err != nil {
		t.Fatalf("failed to write file: %v", err)
	}
	_ = exec.Command("git", "-C", srcDir, "add", ".").Run()
	if err := exec.Command("git", "-C", srcDir, "commit", "-m", "initial commit").Run(); err != nil {
		t.Fatalf("failed to commit: %v", err)
	}
	commitHash := strings.TrimSpace(runGitOutput(t, srcDir, "rev-parse", "HEAD"))

	parentDir, err := os.MkdirTemp("", "kratis-https-server-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(parentDir) }()
	bareRepo := filepath.Join(parentDir, "repo.git")
	if err := exec.Command("git", "clone", "--bare", srcDir, bareRepo).Run(); err != nil {
		t.Fatalf("failed to create bare repo: %v", err)
	}

	serverURL, unauthorized, authorized := startGitHttpBackend(t, parentDir, token)
	repoURL := serverURL + "/repo.git"

	destDir, err := os.MkdirTemp("", "kratis-https-dest-*")
	if err != nil {
		t.Fatalf("failed to create dest dir: %v", err)
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
			if req.Method == "env.git_token" {
				resultBytes, _ := json.Marshal(GitTokenResult{Token: token})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: resultBytes, ID: req.ID})
				continue
			}
			if req.Method == "env.checkout_complete" {
				rawBytes, _ := json.Marshal(req.Params)
				_ = json.Unmarshal(rawBytes, &completeParams)
				gotComplete.Store(true)
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", destDir)
	// Dial directly instead of using connectClient, which creates a second
	// client that would re-bind the credential socket with an empty PAT.
	wsConn, _, err := websocket.DefaultDialer.Dial(wsURL(srv), nil)
	if err != nil {
		t.Fatalf("direct dial failed: %v", err)
	}
	c.mu.Lock()
	c.wsConn = wsConn
	c.credentialsDir = t.TempDir()
	c.mu.Unlock()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)
	defer c.Close()

	c.ExecuteRegisterGitAuth(RegisterGitAuthParams{CredentialType: "GIT_PAT"}, nil)
	defer func() { _ = os.Unsetenv("GIT_CONFIG_GLOBAL") }()
	if os.Getenv("GIT_CONFIG_GLOBAL") == "" {
		t.Fatal("expected GIT_CONFIG_GLOBAL to be set after registerGitAuth")
	}

	c.ExecuteCheckout(CheckoutParams{
		URL:    repoURL,
		Branch: "main",
		Env:    map[string]string{"GIT_TERMINAL_PROMPT": "0", "SSH_ASKPASS": ""},
	}, nil)

	deadline := time.Now().Add(15 * time.Second)
	for !gotComplete.Load() {
		if time.Now().After(deadline) {
			t.Fatal("timed out waiting for env.checkout_complete")
		}
		time.Sleep(50 * time.Millisecond)
	}

	if completeParams.Status != CheckoutSuccess {
		t.Fatalf("expected checkout status 'success', got %s: %s", completeParams.Status, completeParams.Error)
	}
	if completeParams.CommitHash != commitHash {
		t.Errorf("expected commit hash %s, got %s", commitHash, completeParams.CommitHash)
	}
	if _, err := os.Stat(filepath.Join(destDir, "hello.txt")); err != nil {
		t.Errorf("expected cloned file hello.txt in %s: %v", destDir, err)
	}

	if unauthorized.Load() == 0 {
		t.Error("expected at least one unauthenticated request (401 challenge) before credentials were supplied")
	}
	if authorized.Load() == 0 {
		t.Error("expected at least one authenticated request; git never presented the credentials from the helper")
	}
}

func runGitOutput(t *testing.T, dir string, args ...string) string {
	t.Helper()
	out, err := exec.Command("git", append([]string{"-C", dir}, args...)...).Output()
	if err != nil {
		t.Fatalf("git %v failed: %v", args, err)
	}
	return string(out)
}
