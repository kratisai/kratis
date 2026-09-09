package runner

import (
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"strings"
)

// StartSSHAgent starts a new ssh-agent instance and returns its socket path and PID.
func StartSSHAgent() (string, string, error) {
	cmd := exec.Command("ssh-agent", "-s")
	out, err := cmd.Output()
	if err != nil {
		return "", "", err
	}

	sockRegex := regexp.MustCompile(`SSH_AUTH_SOCK=([^;]+);`)
	pidRegex := regexp.MustCompile(`SSH_AGENT_PID=([0-9]+);`)

	sockMatch := sockRegex.FindStringSubmatch(string(out))
	pidMatch := pidRegex.FindStringSubmatch(string(out))

	if len(sockMatch) < 2 || len(pidMatch) < 2 {
		return "", "", fmt.Errorf("failed to parse ssh-agent output: %s", string(out))
	}

	return sockMatch[1], pidMatch[1], nil
}

// AddSSHKey adds the provided private key to the running ssh-agent.
func AddSSHKey(sock, privateKey string) error {
	cmd := exec.Command("ssh-add", "-")
	cmd.Env = append(os.Environ(), "SSH_AUTH_SOCK="+sock)

	stdin, err := cmd.StdinPipe()
	if err != nil {
		return err
	}

	if !strings.HasSuffix(privateKey, "\n") {
		privateKey += "\n"
	}

	go func() {
		defer func() { _ = stdin.Close() }()
		_, _ = stdin.Write([]byte(privateKey))
	}()

	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("ssh-add failed: %w, output: %s", err, string(out))
	}
	return nil
}
