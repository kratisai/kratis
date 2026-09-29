package runner

import (
	"os/exec"
	"testing"
)

func TestSSH(t *testing.T) {
	_, err := exec.LookPath("ssh-agent")
	if err != nil {
		t.Skip("ssh-agent not found in PATH, skipping")
	}
	sock, pid, err := StartSSHAgent()
	if err != nil {
		t.Fatalf("failed to start ssh-agent: %v", err)
	}
	defer func() {
		_ = exec.Command("kill", pid).Run()
	}()
	if sock == "" || pid == "" {
		t.Errorf("expected non-empty sock and pid, got sock=%q, pid=%q", sock, pid)
	}

	_, err = exec.LookPath("ssh-add")
	if err != nil {
		t.Skip("ssh-add not found in PATH, skipping")
	}

	dummyKey := `-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtcn
NhAAAAAwEAAQAAAgEA0z8b66uV1rN+xZq/MvE4Y9B/yYx
-----END OPENSSH PRIVATE KEY-----`
	_ = AddSSHKey(sock, dummyKey)
}

func TestSSH_UnhappyPaths(t *testing.T) {
	err := AddSSHKey("/invalid/nonexistent/sock/path", "dummy-key")
	if err == nil {
		t.Error("expected error with invalid socket path, got nil")
	}
}
