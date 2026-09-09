package rpc

import (
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"

	"kratis-connector/runner"
)

// ExecuteExec runs a shell command in the workspace, streams output via env.output,
// and returns exit code. When PersistEnv is true, a persisted environment file
// (stored outside the git workspace) is sourced before and rewritten after the
// command so subsequent steps inherit exports.
func (c *Client) ExecuteExec(params ExecParams, reqID interface{}) {
	if params.Command == "" {
		c.sendSuccessResponse(reqID, ExecResult{
			Status:   ExecFailed,
			ExitCode: 1,
			Error:    "command is required",
		})
		return
	}

	execID := params.ExecutionID
	if execID != "" {
		c.mu.Lock()
		c.currentExecutionID = execID
		c.mu.Unlock()
	} else {
		c.mu.Lock()
		execID = c.currentExecutionID
		c.mu.Unlock()
	}

	envFile := c.envFilePath()

	cmdStr := params.Command
	if params.PersistEnv {
		// Create env file once; subsequent execs source and rewrite it
		if _, err := os.Stat(envFile); os.IsNotExist(err) {
			initEnvCmd := fmt.Sprintf("mkdir -p %s && export -p > %s", filepath.Dir(envFile), envFile)
			c.mu.Lock()
			c.executor.Dir = c.workspace
			c.executor.Env = os.Environ()
			c.mu.Unlock()
			_, _ = c.executor.Execute(context.Background(), initEnvCmd, nil)
		}

		cmdStr = fmt.Sprintf(
			"set -a && . %s 2>/dev/null; set +a && %s && set -a && export -p > %s && set +a",
			envFile, params.Command, envFile,
		)
	}

	outputCallback := func(ol runner.OutputLine) {
		log.Printf("[Exec] [%s] %s", ol.Stream, ol.Line)
		_ = c.sendNotification("env.output", OutputParams{
			Line:        ol.Line,
			Stream:      OutputStream(ol.Stream),
			ExecutionID: execID,
		})
	}

	c.mu.Lock()
	c.executor.Dir = c.workspace
	c.executor.Env = os.Environ()
	c.mu.Unlock()

	exitCode, err := c.executor.Execute(context.Background(), cmdStr, outputCallback)

	var errStr string
	if err != nil {
		errStr = err.Error()
	}

	status := ExecCompleted
	if err != nil || exitCode != 0 {
		status = ExecFailed
	}

	c.sendSuccessResponse(reqID, ExecResult{
		Status:   status,
		ExitCode: exitCode,
		Error:    errStr,
	})
}
