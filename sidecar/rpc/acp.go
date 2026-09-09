package rpc

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"

	"kratis-connector/runner"
)

// ExecuteLaunchAcpAgent spawns the ACP agent subprocess and performs the ACP
// JSON-RPC handshake (initialize + session/new). Setup commands are executed
// separately via env.exec before this method is called.
func (c *Client) ExecuteLaunchAcpAgent(params LaunchAcpAgentParams, reqID interface{}) {
	if params.ExecutionID != "" {
		c.mu.Lock()
		c.currentExecutionID = params.ExecutionID
		c.mu.Unlock()
	}
	envFile := c.envFilePath()

	// Ensure env file exists so sourcing is a no-op when no prior env.exec ran
	if _, err := os.Stat(envFile); os.IsNotExist(err) {
		initEnvCmd := fmt.Sprintf("mkdir -p %s && export -p > %s", filepath.Dir(envFile), envFile)
		c.mu.Lock()
		c.executor.Dir = c.workspace
		c.executor.Env = os.Environ()
		c.mu.Unlock()
		_, _ = c.executor.Execute(context.Background(), initEnvCmd, nil)
	}

	c.SendOutput(fmt.Sprintf("[ACP] Starting agent: %s", params.AgentCommand), "stdout")
	c.SendOutput(fmt.Sprintf("[ACP] Working directory: %s", c.workspace), "stdout")

	// Source the persisted environment file before running the agent command
	agentCmd := fmt.Sprintf("set -a && . %s 2>/dev/null; set +a; %s", envFile, params.AgentCommand)
	cmd := exec.CommandContext(context.Background(), "sh", "-c", agentCmd) //nolint:gosec // G204: launches the ACP agent binary via shell
	c.mu.Lock()
	cmd.Dir = c.workspace
	c.mu.Unlock()
	cmd.Env = os.Environ()
	// Start in its own process group so we can signal the entire group (including child processes)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	// Create supervisor with client as event sink
	sup := runner.NewAgentSupervisor(c.workspace, c.terminalManager, c)
	sup.SetDebug(c.debug)
	sup.Timeouts = c.supervisorTimeouts
	c.mu.Lock()
	c.supervisor = sup
	c.mu.Unlock()

	// Launch agent (creates transport, starts process, performs handshake)
	result, err := sup.Launch(cmd)
	if err != nil {
		c.sendSuccessResponse(reqID, LaunchAcpAgentResult{
			Status: LaunchFailed,
			Error:  err.Error(),
		})
		return
	}

	c.SendOutput(fmt.Sprintf("[ACP] Agent process started with PID: %d", cmd.Process.Pid), "stdout")

	// Send env.acp_initialized notification (ACP-agnostic payload)
	c.mu.Lock()
	execID := c.currentExecutionID
	c.mu.Unlock()
	_ = c.sendNotification("env.acp_initialized", AcpInitializedParams{
		SessionID:    result.Session.SessionID,
		AgentName:    result.AgentName,
		AgentVersion: result.AgentVersion,
		ExecutionID:  execID,
	})
	c.sendSuccessResponse(reqID, LaunchAcpAgentResult{
		Status:       LaunchLaunched,
		SessionID:    result.Session.SessionID,
		AgentName:    result.AgentName,
		AgentVersion: result.AgentVersion,
	})
}

// ExecuteAcpPrompt sends a session/prompt to an existing ACP session and streams output.
// It does NOT close stdin or wait for process exit.
func (c *Client) ExecuteAcpPrompt(params AcpPromptParams, reqID interface{}) {
	promptReceivedTime := time.Now()
	log.Printf("[ACP][TIMING] ExecuteAcpPrompt called at %v", promptReceivedTime)

	if params.ExecutionID != "" {
		c.mu.Lock()
		c.currentExecutionID = params.ExecutionID
		c.mu.Unlock()
	}

	c.mu.Lock()
	sup := c.supervisor
	c.mu.Unlock()

	if sup == nil {
		c.sendSuccessResponse(reqID, AcpPromptResult{
			Status: PromptFailed,
			Error:  "no active ACP session found",
		})
		return
	}

	session := sup.Session()
	if session == nil {
		c.sendSuccessResponse(reqID, AcpPromptResult{
			Status: PromptFailed,
			Error:  "no active ACP session found",
		})
		return
	}

	c.SendOutput(fmt.Sprintf("[ACP] Sending session/prompt (task length: %d chars)", len(params.TaskPrompt)), "stdout")

	promptPreview := params.TaskPrompt
	if len(promptPreview) > 200 {
		promptPreview = promptPreview[:200] + "..."
	}
	log.Printf("[ACP][PROMPT] Sending prompt: %s", promptPreview)

	promptSentTime := time.Now()
	log.Printf("[ACP][TIMING] Sending session/prompt at %v (delay since prompt received: %v)", promptSentTime, promptSentTime.Sub(promptReceivedTime))

	taskPrompt := params.TaskPrompt
	var result *runner.PromptResult
	var err error
	if params.IsSteering {
		taskPrompt = "[User provided additional information. Please consider this alongside all previous prompts]\n\n" + taskPrompt
		result, err = sup.Steer(taskPrompt)
	} else {
		result, err = sup.Prompt(taskPrompt)
	}
	promptResponseTime := time.Now()
	log.Printf("[ACP][TIMING] Received session/prompt response at %v (duration: %v)", promptResponseTime, promptResponseTime.Sub(promptSentTime))

	if err != nil {
		c.sendSuccessResponse(reqID, AcpPromptResult{
			Status: PromptFailed,
			Error:  err.Error(),
		})
		return
	}

	stopReason := StopReason(result.StopReason)
	promptErr := result.Error

	// If normal prompt turn completed with end_turn, prompt the agent for quality verification and final commit.
	if !params.IsSteering && promptErr == nil && stopReason == StopReasonEndTurn {
		c.SendOutput("[ACP] Turn complete — prompting agent for quality verification and final commit", "stdout")
		wrapUpPrompt := "If you have completed the user's task, please: 1) check what quality and verification steps are required for this project (e.g. test suites, type checking, linting, builds) and run any that have not been run, 2) tidy any temporary or transient files, 3) generate a sensible commit message and commit all relevant changes if there are modifications."
		autoResult, autoErr := sup.Prompt(wrapUpPrompt)
		if autoErr == nil && autoResult != nil {
			if autoStop := StopReason(autoResult.StopReason); autoStop != "" && ValidStopReasons[autoStop] {
				stopReason = autoStop
			}
		}
	}

	if promptErr == nil && stopReason != "" && ValidStopReasons[stopReason] {
		c.mu.Lock()
		execID := c.currentExecutionID
		c.mu.Unlock()
		completionParams := AcpPromptCompleteParams{
			SessionID:   session.SessionID,
			StopReason:  stopReason,
			ExecutionID: execID,
		}
		err := c.sendNotification("env.acp_prompt_complete", completionParams)
		if err != nil {
			// WebSocket is down — store for re-send after reconnection
			log.Printf("[ACP] Failed to send env.acp_prompt_complete (connection down), storing for re-send after reconnection")
			c.mu.Lock()
			c.pendingPromptComplete = &completionParams
			c.mu.Unlock()
		}
	} else if promptErr != nil || (stopReason != "" && !ValidStopReasons[stopReason]) {
		errorMsg := "unknown error"
		if promptErr != nil {
			errorMsg = promptErr.Error()
		} else if stopReason != "" {
			errorMsg = fmt.Sprintf("invalid stop reason: %s", stopReason)
		}
		log.Printf("[ACP] Fatal prompt error for session %s: %s", session.SessionID, errorMsg)
		c.SendComplete(1)
	}

	var errStr string
	if promptErr != nil {
		errStr = promptErr.Error()
	}

	status := PromptCompleted
	if promptErr != nil {
		status = PromptFailed
	}

	c.sendSuccessResponse(reqID, AcpPromptResult{
		Status:     status,
		StopReason: stopReason,
		Error:      errStr,
	})
}

// ExecuteTerminate terminates the ACP agent process gracefully.
// Delegates to the supervisor for process lifecycle management.
func (c *Client) ExecuteTerminate(_ TerminateParams, reqID interface{}) {
	c.mu.Lock()
	sup := c.supervisor
	c.mu.Unlock()

	if sup == nil {
		c.SendOutput("[Terminate] No active ACP session found - nothing to terminate", "stdout")
		c.SendComplete(0)
		c.sendSuccessResponse(reqID, TerminateResult{Status: TerminateCompleted, ExitCode: 0})
		return
	}

	c.SendOutput("[Terminate] Delegating to supervisor", "stdout")

	exitCode, err := sup.Terminate()
	if err != nil {
		c.SendOutput(fmt.Sprintf("[Terminate] Error: %v", err), "stderr")
	}

	// Don't clear supervisor here - let it be cleared in closeAll() or when process exits
	c.sendSuccessResponse(reqID, TerminateResult{Status: TerminateTerminated, ExitCode: exitCode})
}
