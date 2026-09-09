package runner

import (
	"bytes"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

const SocketPath = "/tmp/kratis-git-credentials.sock"

// GetPeerPID retrieves the PID of the peer connecting to a UNIX domain socket.
func GetPeerPID(conn *net.UnixConn) (int, error) {
	rawConn, err := conn.SyscallConn()
	if err != nil {
		return 0, err
	}
	var cred *syscall.Ucred
	var sysErr error
	err = rawConn.Control(func(fd uintptr) {
		cred, sysErr = syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
	})
	if err != nil {
		return 0, err
	}
	if sysErr != nil {
		return 0, sysErr
	}
	return int(cred.Pid), nil
}

var procDir atomic.Value
var enforceGitPathCheck = true

func init() {
	procDir.Store("/proc")
}

func GetProcDir() string {
	return procDir.Load().(string)
}

func SetProcDir(dir string) {
	procDir.Store(dir)
}

func SetEnforceGitPathCheck(enabled bool) {
	enforceGitPathCheck = enabled
}

// GetParentPID reads procDir/<pid>/stat to find the parent process ID.
func GetParentPID(pid int) (int, error) {
	statPath := filepath.Join(GetProcDir(), strconv.Itoa(pid), "stat")
	//nolint:gosec // G304: reading /proc/<pid>/stat of sandbox processes for parent detection
	data, err := os.ReadFile(statPath)
	if err != nil {
		return 0, err
	}
	// Format is: PID (comm) State PPID ...
	// Find last closing parenthesis to skip comm which can contain spaces/parentheses
	lastParen := bytes.LastIndexByte(data, ')')
	if lastParen == -1 || lastParen+2 >= len(data) {
		return 0, fmt.Errorf("malformed stat file for pid %d", pid)
	}
	fields := strings.Fields(string(data[lastParen+2:]))
	if len(fields) < 2 {
		return 0, fmt.Errorf("insufficient fields in stat for pid %d", pid)
	}
	ppid, err := strconv.Atoi(fields[1])
	if err != nil {
		return 0, fmt.Errorf("invalid ppid for pid %d: %w", pid, err)
	}
	return ppid, nil
}

// FindGitProcess walks up the process tree starting from pid to find the first process
// whose executable name starts with "git". It returns its PID, executable base name, and command line arguments.
func FindGitProcess(pid int) (int, string, []string, error) {
	currentPID := pid
	for i := 0; i < 10; i++ { // Limit traversal to 10 parents to prevent infinite loops
		ppid, err := GetParentPID(currentPID)
		if err != nil || ppid <= 1 {
			break
		}
		exeLink := filepath.Join(GetProcDir(), strconv.Itoa(ppid), "exe")
		target, err := os.Readlink(exeLink)
		if err != nil {
			currentPID = ppid
			continue
		}
		baseExe := filepath.Base(target)
		if strings.HasPrefix(baseExe, "git") {
			// Read cmdline
			cmdlinePath := filepath.Join(GetProcDir(), strconv.Itoa(ppid), "cmdline")
			//nolint:gosec // G304: reading /proc/<pid>/cmdline of sandbox processes for git-command detection
			cmdlineBytes, err := os.ReadFile(cmdlinePath)
			if err == nil {
				args := strings.Split(string(cmdlineBytes), "\x00")
				// If it's a remote helper (like git-remote-https), continue walking up to find the main git process
				if baseExe == "git" {
					// Ensure target is a trusted system binary path unless we are in testing
					if enforceGitPathCheck {
						trusted := false
						for _, prefix := range []string{"/usr/bin/", "/bin/", "/usr/local/bin/", "/usr/libexec/git-core/", "/usr/lib/git-core/"} {
							if strings.HasPrefix(target, prefix) {
								trusted = true
								break
							}
						}
						if !trusted {
							return 0, "", nil, fmt.Errorf("git binary from untrusted path: %s", target)
						}
					}
					return ppid, baseExe, args, nil
				}
			}
		}
		currentPID = ppid
	}
	return 0, "", nil, fmt.Errorf("git process not found in parent chain")
}

// VerifyProcessChain checks if the process tree calling the credential helper is a valid git command.
func VerifyProcessChain(pid int) (bool, error) {
	_, baseExe, args, err := FindGitProcess(pid)
	if err != nil {
		return false, err
	}

	if baseExe != "git" {
		return false, fmt.Errorf("parent process %s is not main git binary", baseExe)
	}

	// Allowed git subcommands that legitimately request credentials
	allowedSubcmds := map[string]bool{
		"clone":        true,
		"fetch":        true,
		"push":         true,
		"pull":         true,
		"ls-remote":    true,
		"remote-http":  true,
		"remote-https": true,
	}

	// Look for allowed subcommands and forbidden words
	hasAllowedSubcmd := false
	for _, arg := range args {
		if arg == "credential" || arg == "config" {
			return false, fmt.Errorf("forbidden git subcommand/parameter '%s'", arg)
		}
		if allowedSubcmds[arg] {
			hasAllowedSubcmd = true
		}
	}

	if !hasAllowedSubcmd {
		return false, fmt.Errorf("no allowed git subcommand found in arguments")
	}

	return true, nil
}

// StartCredentialServer starts a UNIX domain socket server that serves the git credentials.
func StartCredentialServer(gitPATProvider func() string) error {
	_ = os.Remove(SocketPath)

	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: SocketPath, Net: "unix"})
	if err != nil {
		return err
	}

	// Restrict permissions on the socket file so only the owner can access it
	_ = os.Chmod(SocketPath, 0600)

	go func() {
		defer func() { _ = listener.Close() }()
		for {
			conn, err := listener.AcceptUnix()
			if err != nil {
				return
			}
			go handleCredentialConnection(conn, gitPATProvider)
		}
	}()

	return nil
}

func handleCredentialConnection(conn *net.UnixConn, gitPATProvider func() string) {
	defer func() { _ = conn.Close() }()

	pid, err := GetPeerPID(conn)
	if err != nil {
		deny(conn, "failed to get peer PID: "+err.Error())
		return
	}

	ok, err := VerifyProcessChain(pid)
	if !ok || err != nil {
		deny(conn, fmt.Sprintf("access denied for PID %d: %v", pid, err))
		return
	}

	drainCredentialRequest(conn)

	pat := gitPATProvider()
	if pat == "" {
		deny(conn, "no git credentials registered for this session")
		return
	}

	response := fmt.Sprintf("username=oauth2\npassword=%s\n", pat)
	_, _ = conn.Write([]byte(response))
}

// drainCredentialRequest consumes the caller's request so a later response is
// not lost (see handleCredentialConnection). The deadline bounds the read so a
// caller that never sends a request cannot hang the server goroutine.
func drainCredentialRequest(conn *net.UnixConn) {
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, _ = io.Copy(io.Discard, conn)
}

// deny reports a credential request failure both to the sidecar log and back
// to the caller.
func deny(conn *net.UnixConn, reason string) {
	log.Printf("Credential server: %s", reason)
	drainCredentialRequest(conn)
	_, _ = conn.Write([]byte("kratis credential server: " + reason + "\n"))
}
