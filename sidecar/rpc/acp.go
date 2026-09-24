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
	if err := c.launchAcpAgentProcess(params.AgentCommand); err != nil {
		c.sendSuccessResponse(reqID, LaunchAcpAgentResult{
			Status: LaunchFailed,
			Error:  err.Error(),
		})
		return
	}

	sup := c.currentSupervisor()
	result := sup.LastLaunchResult()
	c.SendOutput(fmt.Sprintf("[ACP] Agent process started with PID: %d", sup.PID()), "stdout")

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

// launchAcpAgentProcess tears down any existing ACP agent, spawns a fresh
// agent subprocess for the given command, and performs the ACP handshake.
func (c *Client) launchAcpAgentProcess(agentCommand string) error {
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

	c.SendOutput(fmt.Sprintf("[ACP] Starting agent: %s", agentCommand), "stdout")
	c.SendOutput(fmt.Sprintf("[ACP] Working directory: %s", c.workspace), "stdout")

	// Source the persisted environment file before running the agent command
	agentCmd := fmt.Sprintf("set -a && . %s 2>/dev/null; set +a; %s", envFile, agentCommand)
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
	if _, err := sup.Launch(cmd); err != nil {
		sup.CleanupFailedLaunch(err.Error())
		c.mu.Lock()
		c.supervisor = nil
		c.mu.Unlock()
		return err
	}

	c.mu.Lock()
	c.acpAgentCommand = agentCommand
	c.mu.Unlock()
	return nil
}

// currentSupervisor returns the active supervisor snapshot.
func (c *Client) currentSupervisor() *runner.AgentSupervisor {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.supervisor
}

// ExecuteAcpPrompt acknowledges the request immediately and runs the turn on the
// request goroutine. The terminal outcome always arrives as env.acp_prompt_complete
// (or env.complete), so a turn survives a dropped request or control-plane restart.
func (c *Client) ExecuteAcpPrompt(params AcpPromptParams, reqID interface{}) {
	promptReceivedTime := time.Now()
	log.Printf("[ACP][TIMING] ExecuteAcpPrompt called at %v", promptReceivedTime)

	executionID := params.ExecutionID
	if executionID != "" {
		c.mu.Lock()
		c.currentExecutionID = executionID
		c.mu.Unlock()
	} else {
		c.mu.Lock()
		executionID = c.currentExecutionID
		c.mu.Unlock()
	}

	if params.Relaunch {
		if err := c.relaunchAgent(); err != nil {
			log.Printf("[ACP] Relaunch before prompt failed: %v", err)
			c.sendSuccessResponse(reqID, AcpPromptResult{
				Status: PromptFailed,
				Error:  fmt.Sprintf("agent relaunch failed: %v", err),
			})
			return
		}
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

	// Ack before running the turn so a dropped connection cannot strand it.
	c.sendSuccessResponse(reqID, AcpPromptResult{Status: PromptAccepted})

	promptSentTime := time.Now()
	log.Printf("[ACP][TIMING] Sending session/prompt at %v (delay since prompt received: %v)", promptSentTime, promptSentTime.Sub(promptReceivedTime))

	taskPrompt := params.TaskPrompt
	var result *runner.PromptResult
	var err error
	if params.IsSteering {
		taskPrompt = "[User provided additional information. Please consider this alongside all previous prompts]\n\n" + taskPrompt
		result, err = sup.Steer(taskPrompt, params.PromptID)
	} else {
		result, err = sup.Prompt(taskPrompt, params.PromptID)
	}
	promptResponseTime := time.Now()
	log.Printf("[ACP][TIMING] Received session/prompt response at %v (duration: %v)", promptResponseTime, promptResponseTime.Sub(promptSentTime))

	if err != nil {
		c.reportPromptFailure(session.SessionID, err, "", "")
		return
	}

	stopReason := StopReason(result.StopReason)
	promptErr := result.Error
	// A queued steering prompt returns immediately with no id and no completion; fall back to the
	// request's id. A queued chain returns the final turn's id.
	effectivePromptID := result.PromptID
	if effectivePromptID == "" {
		effectivePromptID = params.PromptID
	}

	// If normal prompt turn completed with end_turn, prompt the agent for quality verification and final commit.
	if !params.IsSteering && promptErr == nil && stopReason == StopReasonEndTurn {
		c.SendOutput("[ACP] Turn complete — prompting agent for quality verification and final commit", "stdout")
		wrapUpPrompt := "If you have completed the user's task, please: 1) check what quality and verification steps are required for this project (e.g. test suites, type checking, linting, builds) and run any that have not been run, 2) tidy any temporary or transient files, 3) generate a sensible commit message and commit all relevant changes if there are modifications."
		autoResult, autoErr := sup.Prompt(wrapUpPrompt, effectivePromptID)
		if autoErr == nil && autoResult != nil {
			if autoStop := StopReason(autoResult.StopReason); autoStop != "" && ValidStopReasons[autoStop] {
				stopReason = autoStop
			}
		}
	}

	if promptErr == nil && stopReason != "" && ValidStopReasons[stopReason] {
		// Some harnesses keep working after signalling end_turn; wait for quiet.
		c.waitForQuiet(c.Timeouts.PromptQuietPeriod)
		completionParams := AcpPromptCompleteParams{
			SessionID:   session.SessionID,
			StopReason:  stopReason,
			ExecutionID: executionID,
			PromptID:    effectivePromptID,
		}
		if err := c.sendNotification("env.acp_prompt_complete", completionParams); err != nil {
			// WebSocket is down — store for re-send after reconnection
			log.Printf("[ACP] Failed to send env.acp_prompt_complete (connection down), storing for re-send after reconnection")
			c.mu.Lock()
			c.pendingPromptComplete = &completionParams
			c.mu.Unlock()
		}
	} else if promptErr != nil || (stopReason != "" && !ValidStopReasons[stopReason]) {
		errMessage := ""
		if result != nil {
			errMessage = result.ErrMessage
		}
		c.reportPromptFailure(session.SessionID, promptErr, errMessage, stopReason)
	}
}

// reportPromptFailure reports a fatal turn failure as env.complete because the request was already
// acknowledged.
func (c *Client) reportPromptFailure(sessionID string, promptErr error, errMessage string, stopReason StopReason) {
	reason := "unknown error"
	switch {
	case errMessage != "":
		reason = truncateReason(errMessage)
	case promptErr != nil:
		reason = truncateReason(promptErr.Error())
	case stopReason != "":
		reason = truncateReason(fmt.Sprintf("invalid stop reason: %s", stopReason))
	}
	log.Printf("[ACP] Fatal prompt error for session %s: %s", sessionID, reason)
	// Echo the abort into the output stream so the UI activity log shows the
	// agent's own error text without requiring control-plane logs.
	c.SendOutput(fmt.Sprintf("[ACP] Fatal prompt error: %s", reason), "stderr")
	c.SendComplete(runner.CompletionInfo{
		ExitCode: 1,
		Reason:   reason,
	})
}

// relaunchAgent tears down the current ACP agent (if any) and launches a fresh
// session using the originally recorded agent command. No env.complete is
// reported for the teardown — the caller's prompt outcome drives the terminal
// state.
func (c *Client) relaunchAgent() error {
	c.mu.Lock()
	sup := c.supervisor
	agentCommand := c.acpAgentCommand
	c.mu.Unlock()

	if agentCommand == "" {
		return fmt.Errorf("no agent command recorded; cannot relaunch")
	}

	if sup != nil {
		log.Printf("[ACP] Relaunch: terminating existing agent session")
		if _, err := sup.TerminateQuietly(); err != nil {
			log.Printf("[ACP] Relaunch: terminate of existing agent: %v", err)
		}
		c.mu.Lock()
		c.supervisor = nil
		c.mu.Unlock()
	}

	if err := c.launchAcpAgentProcess(agentCommand); err != nil {
		return err
	}

	// Notify the control plane about the fresh session so the UI tracks the
	// new ACP session id.
	result := c.currentSupervisor().LastLaunchResult()
	c.mu.Lock()
	execID := c.currentExecutionID
	c.mu.Unlock()
	_ = c.sendNotification("env.acp_initialized", AcpInitializedParams{
		SessionID:    result.Session.SessionID,
		AgentName:    result.AgentName,
		AgentVersion: result.AgentVersion,
		ExecutionID:  execID,
		Relaunch:     true,
	})
	return nil
}

// truncateReason bounds an agent error message for the terminal reason string.
func truncateReason(msg string) string {
	const maxReasonRunes = 512
	runes := []rune(msg)
	if len(runes) <= maxReasonRunes {
		return msg
	}
	return string(runes[:maxReasonRunes]) + "…"
}

// ExecuteTerminate terminates the ACP agent process gracefully.
// Delegates to the supervisor for process lifecycle management.
func (c *Client) ExecuteTerminate(_ TerminateParams, reqID interface{}) {
	c.mu.Lock()
	sup := c.supervisor
	c.mu.Unlock()

	if sup == nil {
		c.SendOutput("[Terminate] No active ACP session found - nothing to terminate", "stdout")
		c.SendComplete(runner.CompletionInfo{ExitCode: 0, Reason: "terminated (no active agent session)"})
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
