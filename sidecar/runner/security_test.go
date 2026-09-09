package runner

import (
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestMain(m *testing.M) {
	// Disable enforceGitPathCheck by default for generic tests using mock proc directories
	enforceGitPathCheck = false
	os.Exit(m.Run())
}

func TestGetParentPID(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "mock-proc-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(tempDir) }()

	oldProcDir := GetProcDir()
	SetProcDir(tempDir)
	defer func() { SetProcDir(oldProcDir) }()

	// Case 1: Simple process
	pid := 12345
	ppid := 9999
	procPath := filepath.Join(tempDir, "12345")
	if err := os.MkdirAll(procPath, 0750); err != nil {
		t.Fatalf("failed to create mock proc dir: %v", err)
	}
	statContent := fmt.Sprintf("12345 (mock-process) S %d 123 0 0", ppid)
	if err := os.WriteFile(filepath.Join(procPath, "stat"), []byte(statContent), 0644); err != nil {
		t.Fatalf("failed to write mock stat: %v", err)
	}

	gotPPID, err := GetParentPID(pid)
	if err != nil {
		t.Errorf("GetParentPID failed: %v", err)
	}
	if gotPPID != ppid {
		t.Errorf("expected ppid %d, got %d", ppid, gotPPID)
	}

	// Case 2: Process comm containing spaces and parentheses
	pid2 := 12346
	ppid2 := 8888
	procPath2 := filepath.Join(tempDir, "12346")
	if err := os.MkdirAll(procPath2, 0750); err != nil {
		t.Fatalf("failed to create mock proc dir: %v", err)
	}
	statContent2 := fmt.Sprintf("12346 (mock (proc) name) S %d 123 0 0", ppid2)
	if err := os.WriteFile(filepath.Join(procPath2, "stat"), []byte(statContent2), 0644); err != nil {
		t.Fatalf("failed to write mock stat: %v", err)
	}

	gotPPID2, err := GetParentPID(pid2)
	if err != nil {
		t.Errorf("GetParentPID failed for complex comm: %v", err)
	}
	if gotPPID2 != ppid2 {
		t.Errorf("expected complex ppid %d, got %d", ppid2, gotPPID2)
	}
}

func TestVerifyProcessChain(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "mock-proc-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(tempDir) }()

	oldProcDir := GetProcDir()
	SetProcDir(tempDir)
	defer func() { SetProcDir(oldProcDir) }()

	// Set up a mock process tree:
	// 5000 (helper) -> parent 4000 (git-remote-https) -> grandparent 3000 (git push)
	helperPID := 5000

	// 1. Setup Helper stat (parent is 4000)
	helperPath := filepath.Join(tempDir, "5000")
	_ = os.MkdirAll(helperPath, 0755)
	_ = os.WriteFile(filepath.Join(helperPath, "stat"), []byte("5000 (helper) S 4000 123 0"), 0644)

	// 2. Setup Parent stat (parent is 3000) and exe link to git-remote-https
	parentPath := filepath.Join(tempDir, "4000")
	_ = os.MkdirAll(parentPath, 0755)
	_ = os.WriteFile(filepath.Join(parentPath, "stat"), []byte("4000 (git-remote-https) S 3000 123 0"), 0644)
	mockGitRemoteExe := filepath.Join(tempDir, "git-remote-https")
	_ = os.WriteFile(mockGitRemoteExe, []byte("binary-content"), 0755)
	_ = os.Symlink(mockGitRemoteExe, filepath.Join(parentPath, "exe"))

	// 3. Setup Grandparent stat (parent is 1) and exe link to git, cmdline containing push
	grandparentPath := filepath.Join(tempDir, "3000")
	_ = os.MkdirAll(grandparentPath, 0755)
	_ = os.WriteFile(filepath.Join(grandparentPath, "stat"), []byte("3000 (git) S 1 123 0"), 0644)
	mockGitExe := filepath.Join(tempDir, "git")
	_ = os.WriteFile(mockGitExe, []byte("git-binary-content"), 0755)
	_ = os.Symlink(mockGitExe, filepath.Join(grandparentPath, "exe"))

	// Test case 1: Valid git push
	_ = os.WriteFile(filepath.Join(grandparentPath, "cmdline"), []byte("git\x00push\x00"), 0644)
	ok, err := VerifyProcessChain(helperPID)
	if !ok || err != nil {
		t.Errorf("expected VerifyProcessChain to succeed for git push, got %v, err: %v", ok, err)
	}

	// Test case 1b: git remote-http wrapper — modern git spawns HTTP(S)
	// transports through a wrapper process (cmdline "git remote-http origin
	// <url>") before the main clone/fetch/push process; FindGitProcess stops
	// at this wrapper, so it must be accepted.
	_ = os.WriteFile(
		filepath.Join(grandparentPath, "cmdline"),
		[]byte("git\x00remote-http\x00origin\x00https://github.com/foo/bar.git\x00"), 0644)
	ok, err = VerifyProcessChain(helperPID)
	if !ok || err != nil {
		t.Errorf("expected VerifyProcessChain to succeed for git remote-http wrapper, got %v, err: %v", ok, err)
	}

	// Test case 1c: git remote-https wrapper
	_ = os.WriteFile(
		filepath.Join(grandparentPath, "cmdline"),
		[]byte("git\x00remote-https\x00origin\x00https://github.com/foo/bar.git\x00"), 0644)
	ok, err = VerifyProcessChain(helperPID)
	if !ok || err != nil {
		t.Errorf("expected VerifyProcessChain to succeed for git remote-https wrapper, got %v, err: %v", ok, err)
	}

	// Test case 2: Forbidden git credential subcommand
	_ = os.WriteFile(filepath.Join(grandparentPath, "cmdline"), []byte("git\x00credential\x00fill\x00"), 0644)
	ok, err = VerifyProcessChain(helperPID)
	if ok || err == nil {
		t.Errorf("expected VerifyProcessChain to fail for git credential, got %v", ok)
	}

	// Test case 3: Non-git parent process (e.g. bash)
	bashExe := filepath.Join(tempDir, "bash")
	_ = os.WriteFile(bashExe, []byte("bash-binary"), 0755)
	_ = os.Remove(filepath.Join(grandparentPath, "exe"))
	_ = os.Symlink(bashExe, filepath.Join(grandparentPath, "exe"))
	ok, err = VerifyProcessChain(helperPID)
	if ok || err == nil {
		t.Errorf("expected VerifyProcessChain to fail for non-git binary, got %v", ok)
	}
}

func TestCredentialServer_SocketLifecycle(t *testing.T) {
	// Start socket server in background
	pat := "super-secret-pat-123"
	err := StartCredentialServer(func() string {
		return pat
	})
	if err != nil {
		t.Fatalf("failed to start credential server: %v", err)
	}

	// Client connection test
	conn, err := net.Dial("unix", SocketPath)
	if err != nil {
		t.Fatalf("failed to dial credential server socket: %v", err)
	}
	defer func() { _ = conn.Close() }()

	// Since we are dialing directly from test, the peer PID is the test binary.
	// VerifyProcessChain will look up the parent of the test binary, which is not git.
	// So we expect the connection to be refused, with the denial reason written
	// back to the caller (git surfaces it as "warning: invalid credential line").
	done := make(chan struct{})
	var response string
	go func() {
		defer close(done)
		buf, err := io.ReadAll(conn)
		if err != nil {
			t.Errorf("read error: %v", err)
		}
		response = string(buf)
	}()

	// Send the credential request and half-close like git's helper does, so the
	// server's request drain completes instead of waiting for the deadline.
	if _, err := conn.Write([]byte("protocol=http\nhost=github.com\n\n")); err != nil {
		t.Fatalf("failed to write credential request: %v", err)
	}
	_ = conn.(*net.UnixConn).CloseWrite()

	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Error("timeout waiting for credential connection rejection")
	}

	if strings.Contains(response, "password=") {
		t.Errorf("expected no credentials to be served to raw test connection, got: %s", response)
	}
	if !strings.Contains(response, "kratis credential server") || !strings.Contains(response, "access denied") {
		t.Errorf("expected the denial reason to be returned to the caller, got: %q", response)
	}
}

func TestVerifyProcessChain_MockProc(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "mock-proc-*")
	if err != nil {
		t.Fatalf("failed to create mock proc: %v", err)
	}
	defer func() { _ = os.RemoveAll(tmpDir) }()

	oldProcDir := GetProcDir()
	SetProcDir(tmpDir)
	defer func() { SetProcDir(oldProcDir) }()

	pid := 1000
	ppid := 999

	pidDir := filepath.Join(tmpDir, strconv.Itoa(pid))
	ppidDir := filepath.Join(tmpDir, strconv.Itoa(ppid))
	_ = os.MkdirAll(pidDir, 0755)
	_ = os.MkdirAll(ppidDir, 0755)

	statData := fmt.Sprintf("%d (helper) S %d 123 456", pid, ppid)
	_ = os.WriteFile(filepath.Join(pidDir, "stat"), []byte(statData), 0644)

	ppidStatData := fmt.Sprintf("%d (git) S 1 123 456", ppid)
	_ = os.WriteFile(filepath.Join(ppidDir, "stat"), []byte(ppidStatData), 0644)

	dummyGitPath := filepath.Join(tmpDir, "git")
	_ = os.WriteFile(dummyGitPath, []byte(""), 0755)
	_ = os.Symlink(dummyGitPath, filepath.Join(ppidDir, "exe"))

	cmdline := "git\x00clone\x00https://github.com/foo/bar\x00"
	_ = os.WriteFile(filepath.Join(ppidDir, "cmdline"), []byte(cmdline), 0644)

	ok, err := VerifyProcessChain(pid)
	if err != nil {
		t.Errorf("expected no error, got %v", err)
	}
	if !ok {
		t.Error("expected process chain verification to succeed")
	}

	cmdlineForbidden := "git\x00credential\x00get\x00"
	_ = os.WriteFile(filepath.Join(ppidDir, "cmdline"), []byte(cmdlineForbidden), 0644)
	ok, err = VerifyProcessChain(pid)
	if err == nil || ok {
		t.Error("expected verification to fail for forbidden subcommand")
	}

	cmdlineNoAllowed := "git\x00status\x00"
	_ = os.WriteFile(filepath.Join(ppidDir, "cmdline"), []byte(cmdlineNoAllowed), 0644)
	ok, err = VerifyProcessChain(pid)
	if err == nil || ok {
		t.Error("expected verification to fail for no allowed subcommand")
	}
}

func TestCredentialServer_SuccessFlow(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "mock-proc-*")
	if err != nil {
		t.Fatalf("failed to create mock proc: %v", err)
	}
	defer func() { _ = os.RemoveAll(tmpDir) }()

	oldProcDir := GetProcDir()
	SetProcDir(tmpDir)
	defer func() { SetProcDir(oldProcDir) }()

	myPID := os.Getpid()
	myPPID := 9999

	pidDir := filepath.Join(tmpDir, strconv.Itoa(myPID))
	ppidDir := filepath.Join(tmpDir, strconv.Itoa(myPPID))
	_ = os.MkdirAll(pidDir, 0755)
	_ = os.MkdirAll(ppidDir, 0755)

	statData := fmt.Sprintf("%d (test) S %d 123 456", myPID, myPPID)
	_ = os.WriteFile(filepath.Join(pidDir, "stat"), []byte(statData), 0644)

	ppidStatData := fmt.Sprintf("%d (git) S 1 123 456", myPPID)
	_ = os.WriteFile(filepath.Join(ppidDir, "stat"), []byte(ppidStatData), 0644)

	dummyGitPath := filepath.Join(tmpDir, "git")
	_ = os.WriteFile(dummyGitPath, []byte(""), 0755)
	_ = os.Symlink(dummyGitPath, filepath.Join(ppidDir, "exe"))

	cmdline := "git\x00clone\x00"
	_ = os.WriteFile(filepath.Join(ppidDir, "cmdline"), []byte(cmdline), 0644)

	err = StartCredentialServer(func() string {
		return "secret-token-123"
	})
	if err != nil {
		t.Fatalf("failed to start credential server: %v", err)
	}

	conn, err := net.Dial("unix", SocketPath)
	if err != nil {
		t.Fatalf("failed to dial: %v", err)
	}
	defer func() { _ = conn.Close() }()

	// Send the credential request and half-close like git's helper does; the
	// server drains it before responding.
	if _, err := conn.Write([]byte("protocol=http\nhost=github.com\n\n")); err != nil {
		t.Fatalf("failed to write credential request: %v", err)
	}
	_ = conn.(*net.UnixConn).CloseWrite()

	buf := make([]byte, 1024)
	n, err := conn.Read(buf)
	if err != nil {
		t.Fatalf("failed to read response: %v", err)
	}

	expected := "username=oauth2\npassword=secret-token-123\n"
	if string(buf[:n]) != expected {
		t.Errorf("expected response %q, got %q", expected, string(buf[:n]))
	}
}

func TestGetParentPID_EdgeCases(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "mock-proc-*")
	if err != nil {
		t.Fatalf("failed to create mock proc: %v", err)
	}
	defer func() { _ = os.RemoveAll(tmpDir) }()

	oldProcDir := GetProcDir()
	SetProcDir(tmpDir)
	defer func() { SetProcDir(oldProcDir) }()

	_ = os.MkdirAll(filepath.Join(tmpDir, "101"), 0755)
	_ = os.WriteFile(filepath.Join(tmpDir, "101", "stat"), []byte("101 helper S 100"), 0644)
	_, err = GetParentPID(101)
	if err == nil || !strings.Contains(err.Error(), "malformed stat file") {
		t.Errorf("expected malformed error, got %v", err)
	}

	_ = os.MkdirAll(filepath.Join(tmpDir, "102"), 0755)
	_ = os.WriteFile(filepath.Join(tmpDir, "102", "stat"), []byte("102 (helper)"), 0644)
	_, err = GetParentPID(102)
	if err == nil || !strings.Contains(err.Error(), "malformed stat file") {
		t.Errorf("expected malformed error, got %v", err)
	}

	_ = os.MkdirAll(filepath.Join(tmpDir, "103"), 0755)
	_ = os.WriteFile(filepath.Join(tmpDir, "103", "stat"), []byte("103 (helper) S"), 0644)
	_, err = GetParentPID(103)
	if err == nil || !strings.Contains(err.Error(), "insufficient fields") {
		t.Errorf("expected insufficient fields error, got %v", err)
	}

	_ = os.MkdirAll(filepath.Join(tmpDir, "104"), 0755)
	_ = os.WriteFile(filepath.Join(tmpDir, "104", "stat"), []byte("104 (helper) S abc 123"), 0644)
	_, err = GetParentPID(104)
	if err == nil || !strings.Contains(err.Error(), "invalid ppid") {
		t.Errorf("expected invalid ppid error, got %v", err)
	}
}

func TestFindGitProcess_MissingCmdline(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "mock-proc-*")
	if err != nil {
		t.Fatalf("failed to create mock proc: %v", err)
	}
	defer func() { _ = os.RemoveAll(tmpDir) }()

	oldProcDir := GetProcDir()
	SetProcDir(tmpDir)
	defer func() { SetProcDir(oldProcDir) }()

	pid := 1000
	ppid := 999

	pidDir := filepath.Join(tmpDir, strconv.Itoa(pid))
	ppidDir := filepath.Join(tmpDir, strconv.Itoa(ppid))
	_ = os.MkdirAll(pidDir, 0755)
	_ = os.MkdirAll(ppidDir, 0755)

	statData := fmt.Sprintf("%d (helper) S %d 123 456", pid, ppid)
	_ = os.WriteFile(filepath.Join(pidDir, "stat"), []byte(statData), 0644)

	ppidStatData := fmt.Sprintf("%d (git) S 1 123 456", ppid)
	_ = os.WriteFile(filepath.Join(ppidDir, "stat"), []byte(ppidStatData), 0644)

	dummyGitPath := filepath.Join(tmpDir, "git")
	_ = os.WriteFile(dummyGitPath, []byte(""), 0755)
	_ = os.Symlink(dummyGitPath, filepath.Join(ppidDir, "exe"))

	_, _, _, err = FindGitProcess(pid)
	if err == nil {
		t.Error("expected error due to missing cmdline, got nil")
	}
}

func TestVerifyProcessChain_ParentNotGit(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "mock-proc-*")
	if err != nil {
		t.Fatalf("failed to create mock proc: %v", err)
	}
	defer func() { _ = os.RemoveAll(tmpDir) }()

	oldProcDir := GetProcDir()
	SetProcDir(tmpDir)
	defer func() { SetProcDir(oldProcDir) }()

	pid := 1000
	ppid := 999

	pidDir := filepath.Join(tmpDir, strconv.Itoa(pid))
	ppidDir := filepath.Join(tmpDir, strconv.Itoa(ppid))
	_ = os.MkdirAll(pidDir, 0755)
	_ = os.MkdirAll(ppidDir, 0755)

	statData := fmt.Sprintf("%d (helper) S %d 123 456", pid, ppid)
	_ = os.WriteFile(filepath.Join(pidDir, "stat"), []byte(statData), 0644)

	ppidStatData := fmt.Sprintf("%d (not-git) S 1 123 456", ppid)
	_ = os.WriteFile(filepath.Join(ppidDir, "stat"), []byte(ppidStatData), 0644)

	dummyGitPath := filepath.Join(tmpDir, "git-helper-not-main")
	_ = os.WriteFile(dummyGitPath, []byte(""), 0755)
	_ = os.Symlink(dummyGitPath, filepath.Join(ppidDir, "exe"))

	cmdline := "git-helper-not-main\x00clone\x00"
	_ = os.WriteFile(filepath.Join(ppidDir, "cmdline"), []byte(cmdline), 0644)

	ok, err := VerifyProcessChain(pid)
	if err == nil || ok {
		t.Error("expected VerifyProcessChain to fail because parent is not main git binary")
	}
}

func TestVerifyProcessChain_PathEnforcement(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "mock-proc-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(tempDir) }()

	oldProcDir := GetProcDir()
	SetProcDir(tempDir)
	defer func() { SetProcDir(oldProcDir) }()

	oldEnforce := enforceGitPathCheck
	enforceGitPathCheck = true
	defer func() { enforceGitPathCheck = oldEnforce }()

	helperPID := 5000
	helperPath := filepath.Join(tempDir, "5000")
	_ = os.MkdirAll(helperPath, 0755)
	_ = os.WriteFile(filepath.Join(helperPath, "stat"), []byte("5000 (helper) S 4000 123 0"), 0644)

	parentPath := filepath.Join(tempDir, "4000")
	_ = os.MkdirAll(parentPath, 0755)
	_ = os.WriteFile(filepath.Join(parentPath, "stat"), []byte("4000 (git) S 1 123 0"), 0644)
	_ = os.WriteFile(filepath.Join(parentPath, "cmdline"), []byte("git\x00clone\x00"), 0644)

	// Case 1: Untrusted path (e.g. /tmp/mock-proc-*/git)
	untrustedGitExe := filepath.Join(tempDir, "git")
	_ = os.WriteFile(untrustedGitExe, []byte(""), 0755)
	_ = os.Symlink(untrustedGitExe, filepath.Join(parentPath, "exe"))

	ok, err := VerifyProcessChain(helperPID)
	if ok || err == nil || !strings.Contains(err.Error(), "git binary from untrusted path") {
		t.Errorf("expected untrusted path check to fail, got ok=%v, err=%v", ok, err)
	}

	// Case 2: Trusted path (e.g. /usr/bin/git)
	_ = os.Remove(filepath.Join(parentPath, "exe"))
	_ = os.Symlink("/usr/bin/git", filepath.Join(parentPath, "exe"))

	ok, err = VerifyProcessChain(helperPID)
	if !ok || err != nil {
		t.Errorf("expected trusted path check to pass, got ok=%v, err=%v", ok, err)
	}
}

// TestGetPeerPID_RealSocket tests GetPeerPID with a real UNIX domain socket connection.
func TestGetPeerPID_RealSocket(t *testing.T) {
	// Create a temporary socket
	tmpDir, err := os.MkdirTemp("", "peer-pid-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer func() { _ = os.RemoveAll(tmpDir) }()

	sockPath := filepath.Join(tmpDir, "test.sock")
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: sockPath, Net: "unix"})
	if err != nil {
		t.Fatalf("failed to listen: %v", err)
	}
	defer func() { _ = listener.Close() }()

	// Accept connections in background
	connCh := make(chan *net.UnixConn, 1)
	go func() {
		conn, err := listener.AcceptUnix()
		if err != nil {
			return
		}
		connCh <- conn
	}()

	// Connect from client side
	clientConn, err := net.Dial("unix", sockPath)
	if err != nil {
		t.Fatalf("failed to dial: %v", err)
	}
	defer func() { _ = clientConn.Close() }()

	// Accept the connection on server side
	serverConn := <-connCh
	defer func() { _ = serverConn.Close() }()

	// Get peer PID
	pid, err := GetPeerPID(serverConn)
	if err != nil {
		t.Fatalf("GetPeerPID failed: %v", err)
	}

	// The peer PID should be the current process (since we're connecting locally)
	if pid != os.Getpid() {
		t.Errorf("expected peer PID %d (self), got %d", os.Getpid(), pid)
	}
}

// TestHandleCredentialConnection_EmptyPAT tests that handleCredentialConnection
// returns without sending credentials when the PAT is empty.
func TestHandleCredentialConnection_EmptyPAT(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "mock-proc-*")
	if err != nil {
		t.Fatalf("failed to create mock proc: %v", err)
	}
	defer func() { _ = os.RemoveAll(tmpDir) }()

	oldProcDir := GetProcDir()
	SetProcDir(tmpDir)
	defer func() { SetProcDir(oldProcDir) }()

	myPID := os.Getpid()
	myPPID := 9999

	pidDir := filepath.Join(tmpDir, strconv.Itoa(myPID))
	ppidDir := filepath.Join(tmpDir, strconv.Itoa(myPPID))
	_ = os.MkdirAll(pidDir, 0755)
	_ = os.MkdirAll(ppidDir, 0755)

	statData := fmt.Sprintf("%d (test) S %d 123 456", myPID, myPPID)
	_ = os.WriteFile(filepath.Join(pidDir, "stat"), []byte(statData), 0644)

	ppidStatData := fmt.Sprintf("%d (git) S 1 123 456", myPPID)
	_ = os.WriteFile(filepath.Join(ppidDir, "stat"), []byte(ppidStatData), 0644)

	dummyGitPath := filepath.Join(tmpDir, "git")
	_ = os.WriteFile(dummyGitPath, []byte(""), 0755)
	_ = os.Symlink(dummyGitPath, filepath.Join(ppidDir, "exe"))

	cmdline := "git\x00clone\x00"
	_ = os.WriteFile(filepath.Join(ppidDir, "cmdline"), []byte(cmdline), 0644)

	// Start credential server with empty PAT provider
	err = StartCredentialServer(func() string {
		return "" // Empty PAT
	})
	if err != nil {
		t.Fatalf("failed to start credential server: %v", err)
	}

	conn, err := net.Dial("unix", SocketPath)
	if err != nil {
		t.Fatalf("failed to dial: %v", err)
	}
	defer func() { _ = conn.Close() }()

	// Send the credential request and half-close like git's helper does.
	if _, err := conn.Write([]byte("protocol=http\nhost=github.com\n\n")); err != nil {
		t.Fatalf("failed to write credential request: %v", err)
	}
	_ = conn.(*net.UnixConn).CloseWrite()

	// Set a read deadline so we don't hang
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))

	buf := make([]byte, 1024)
	n, err := conn.Read(buf)
	if err != nil {
		t.Fatalf("expected a denial reason to be returned when the PAT is empty, got read error: %v", err)
	}
	response := string(buf[:n])
	if strings.Contains(response, "password=") {
		t.Errorf("expected no credentials when PAT is empty, got: %s", response)
	}
	if !strings.Contains(response, "no git credentials registered") {
		t.Errorf("expected the missing-credentials reason to be returned, got: %q", response)
	}
}

// TestStartCredentialServer_SocketPermissions tests that the credential server
// creates the socket file with restricted permissions (0600).
func TestStartCredentialServer_SocketPermissions(t *testing.T) {
	// Remove any existing socket
	_ = os.Remove(SocketPath)

	err := StartCredentialServer(func() string { return "test" })
	if err != nil {
		t.Fatalf("failed to start credential server: %v", err)
	}

	// Check socket file permissions
	info, err := os.Stat(SocketPath)
	if err != nil {
		t.Fatalf("failed to stat socket: %v", err)
	}

	perm := info.Mode().Perm()
	if perm != 0600 {
		t.Errorf("expected socket permissions 0600, got %04o", perm)
	}

	// Clean up
	_ = os.Remove(SocketPath)
}
