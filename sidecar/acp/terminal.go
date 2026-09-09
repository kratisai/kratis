package acp

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"sync"
	"syscall"
	"time"
)

type Terminal struct {
	ID              string
	Cmd             *exec.Cmd
	Stdin           *bytes.Buffer
	Stdout          *bytes.Buffer
	Stderr          *bytes.Buffer
	ExitStatus      *TerminalExitStatus
	OutputForwarded bool
	Done            chan struct{}
	relayDone       chan struct{}
	// stdoutW/stderrW are the io.Pipe writers fed by the process; closing
	// them on release unblocks the copier goroutines and os/exec's copy
	// goroutines even when a descendant still holds the fds.
	stdoutW       io.WriteCloser
	stderrW       io.WriteCloser
	stdoutEmitted int
	stderrEmitted int
	mu            sync.RWMutex
}

type TerminalExitStatus struct {
	ExitCode *int    `json:"exitCode,omitempty"`
	Signal   *string `json:"signal,omitempty"`
}

type TerminalManager struct {
	terminals  map[string]*Terminal
	mu         sync.RWMutex
	nextID     int
	workspace  string
	outputSink func(line, stream string)
}

func NewTerminalManager(workspace string) *TerminalManager {
	return &TerminalManager{
		terminals: make(map[string]*Terminal),
		workspace: workspace,
	}
}

func (tm *TerminalManager) SetOutputSink(sink func(line, stream string)) {
	tm.mu.Lock()
	defer tm.mu.Unlock()
	tm.outputSink = sink
}

func (tm *TerminalManager) outputSinkSnapshot() func(string, string) {
	tm.mu.RLock()
	defer tm.mu.RUnlock()
	return tm.outputSink
}

func (tm *TerminalManager) CreateTerminal(command string, cwd string) (*Terminal, error) {
	tm.mu.Lock()
	tm.nextID++
	id := fmt.Sprintf("term_%d", tm.nextID)
	tm.mu.Unlock()

	if cwd == "" {
		cwd = tm.workspace
	}

	cmd := exec.Command("sh", "-c", command) //nolint:gosec // G204: agent-run terminal command executes arbitrary shell
	cmd.Dir = cwd
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	stdin := &bytes.Buffer{}
	stdout := &bytes.Buffer{}
	stderr := &bytes.Buffer{}
	stdoutR, stdoutW := io.Pipe()
	stderrR, stderrW := io.Pipe()
	cmd.Stdin = stdin
	cmd.Stdout = stdoutW
	cmd.Stderr = stderrW

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("failed to start command: %w", err)
	}

	term := &Terminal{
		ID:        id,
		Cmd:       cmd,
		Stdin:     stdin,
		Stdout:    stdout,
		Stderr:    stderr,
		Done:      make(chan struct{}),
		relayDone: make(chan struct{}),
		stdoutW:   stdoutW,
		stderrW:   stderrW,
	}

	tm.mu.Lock()
	tm.terminals[id] = term
	tm.mu.Unlock()

	var relayWg sync.WaitGroup
	relayWg.Add(2)
	go tm.relayStream(term, stdoutR, "stdout", &relayWg)
	go tm.relayStream(term, stderrR, "stderr", &relayWg)
	go func() {
		relayWg.Wait()
		close(term.relayDone)
	}()

	go func() {
		err := cmd.Wait()
		_ = stdoutW.Close()
		_ = stderrW.Close()
		term.mu.Lock()
		if err != nil {
			var exitErr *exec.ExitError
			if errors.As(err, &exitErr) {
				if status, ok := exitErr.Sys().(syscall.WaitStatus); ok {
					if status.Signaled() {
						sig := status.Signal().String()
						term.ExitStatus = &TerminalExitStatus{Signal: &sig}
					} else {
						code := status.ExitStatus()
						term.ExitStatus = &TerminalExitStatus{ExitCode: &code}
					}
				}
			}
		} else {
			code := 0
			term.ExitStatus = &TerminalExitStatus{ExitCode: &code}
		}
		term.mu.Unlock()
		close(term.Done)
	}()

	return term, nil
}

// relayStream copies one output pipe into the terminal's buffer and, when a
// sink is installed, relays each line live. The buffer is written under the
// terminal lock so GetOutput is safe to call while the command runs.
func (tm *TerminalManager) relayStream(term *Terminal, r io.Reader, stream string, wg *sync.WaitGroup) {
	defer wg.Done()
	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 0, 64*1024), maxScanTokenSize)
	for scanner.Scan() {
		line := scanner.Text()
		sink := tm.outputSinkSnapshot()
		term.mu.Lock()
		if stream == "stdout" {
			term.Stdout.WriteString(line)
			term.Stdout.WriteString("\n")
			term.stdoutEmitted += len(line) + 1
		} else {
			term.Stderr.WriteString(line)
			term.Stderr.WriteString("\n")
			term.stderrEmitted += len(line) + 1
		}
		term.mu.Unlock()
		if sink != nil {
			sink(line, stream)
		}
	}
}

func (tm *TerminalManager) GetTerminal(id string) (*Terminal, bool) {
	tm.mu.RLock()
	defer tm.mu.RUnlock()
	term, ok := tm.terminals[id]
	return term, ok
}

// releaseLocked kills the terminal's process group and closes its output
// pipes so every reader goroutine unblocks even when a descendant of the
// command still holds the original fds.
func (tm *TerminalManager) releaseLocked(id string) {
	term, ok := tm.terminals[id]
	if !ok {
		return
	}
	_ = term.stdoutW.Close()
	_ = term.stderrW.Close()
	if term.Cmd.Process != nil {
		_ = syscall.Kill(-term.Cmd.Process.Pid, syscall.SIGKILL)
		select {
		case <-term.Done:
		case <-time.After(3 * time.Second):
		}
	}
	delete(tm.terminals, id)
}

func (tm *TerminalManager) ReleaseTerminal(id string) {
	tm.mu.Lock()
	defer tm.mu.Unlock()
	tm.releaseLocked(id)
}

func (tm *TerminalManager) KillTerminal(id string) error {
	tm.mu.RLock()
	term, ok := tm.terminals[id]
	tm.mu.RUnlock()
	if !ok {
		return fmt.Errorf("terminal not found: %s", id)
	}
	if term.Cmd.Process != nil {
		return syscall.Kill(-term.Cmd.Process.Pid, syscall.SIGKILL)
	}
	return nil
}

func (tm *TerminalManager) WaitForExit(id string, timeout time.Duration) (*TerminalExitStatus, error) {
	term, ok := tm.GetTerminal(id)
	if !ok {
		return nil, fmt.Errorf("terminal not found: %s", id)
	}

	select {
	case <-term.Done:
		// The copier goroutines may still be draining the pipes.
		<-term.relayDone
		term.mu.RLock()
		defer term.mu.RUnlock()
		return term.ExitStatus, nil
	case <-time.After(timeout):
		return nil, fmt.Errorf("timeout waiting for terminal exit")
	}
}

func (tm *TerminalManager) GetOutput(id string) (string, string, bool) {
	term, ok := tm.GetTerminal(id)
	if !ok {
		return "", "", false
	}
	term.mu.RLock()
	defer term.mu.RUnlock()
	return term.Stdout.String(), term.Stderr.String(), term.ExitStatus != nil
}

func (tm *TerminalManager) ReleaseAll() {
	tm.mu.Lock()
	defer tm.mu.Unlock()
	for id := range tm.terminals {
		tm.releaseLocked(id)
	}
}
