package runner

import (
	"bufio"
	"context"
	"errors"
	"io"
	"os/exec"
	"sync"
	"syscall"
)

type Executor struct {
	Dir string
	Env []string
}

func NewExecutor() *Executor {
	return &Executor{}
}

type OutputLine struct {
	Line   string
	Stream string // "stdout" or "stderr"
}

// Execute runs the command string using 'sh -c' and streams stdout/stderr lines to outputCallback.
// It returns the command's exit code and any error that occurred during execution.
func (e *Executor) Execute(ctx context.Context, cmdStr string, outputCallback func(OutputLine)) (int, error) {
	//nolint:gosec // G204: executing agent-requested commands via sh -c is the runner's purpose
	cmd := exec.CommandContext(ctx, "sh", "-c", cmdStr)
	if e.Dir != "" {
		cmd.Dir = e.Dir
	}
	if len(e.Env) > 0 {
		cmd.Env = e.Env
	}

	stdoutPipe, err := cmd.StdoutPipe()
	if err != nil {
		return -1, err
	}

	stderrPipe, err := cmd.StderrPipe()
	if err != nil {
		return -1, err
	}

	var wg sync.WaitGroup
	wg.Add(2)

	go readStream(stdoutPipe, "stdout", outputCallback, &wg)
	go readStream(stderrPipe, "stderr", outputCallback, &wg)

	if err := cmd.Start(); err != nil {
		return -1, err
	}

	wg.Wait()

	runErr := cmd.Wait()
	exitCode := 0
	if runErr != nil {
		var exitError *exec.ExitError
		if errors.As(runErr, &exitError) {
			if status, ok := exitError.Sys().(syscall.WaitStatus); ok {
				exitCode = status.ExitStatus()
			} else {
				exitCode = exitError.ExitCode()
			}
			runErr = nil // It finished executing, just with a non-zero exit code
		}
	}

	return exitCode, runErr
}

func readStream(reader io.ReadCloser, streamName string, callback func(OutputLine), wg *sync.WaitGroup) {
	defer wg.Done()
	scanner := bufio.NewScanner(reader)
	for scanner.Scan() {
		line := scanner.Text()
		outputLine := OutputLine{
			Line:   line,
			Stream: streamName,
		}
		if callback != nil {
			callback(outputLine)
		}
	}
}
