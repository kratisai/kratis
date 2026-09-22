package runner

import (
	"context"
	"errors"
	"fmt"
	"kratis-connector/acp"
	"log"
	"os/exec"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

type AgentState int

const (
	StateUninitialized AgentState = iota
	StateInitializing
	StateReady
	StateSessionCreating
	StateSessionActive
	StatePrompting
	StateCancelling
	StateSessionClosing
	StateTerminating
	StateTerminated
	StateFailed
)

const (
	DefaultCancelDelay       = 1 * time.Second
	DefaultExitWait          = 1 * time.Second
	DefaultSigkillWait       = 3 * time.Second
	DefaultDrainWait         = 3 * time.Second
	DefaultSessionCloseWait  = 2 * time.Second
	DefaultRequestTimeout    = 15 * time.Second
	DefaultInitializeTimeout = 30 * time.Second
)

type SupervisorTimeouts struct {
	CancelDelay       time.Duration
	ExitWait          time.Duration
	SigkillWait       time.Duration
	DrainWait         time.Duration
	SessionCloseWait  time.Duration
	RequestTimeout    time.Duration
	InitializeTimeout time.Duration
}

func DefaultSupervisorTimeouts() SupervisorTimeouts {
	return SupervisorTimeouts{
		CancelDelay:       DefaultCancelDelay,
		ExitWait:          DefaultExitWait,
		SigkillWait:       DefaultSigkillWait,
		DrainWait:         DefaultDrainWait,
		SessionCloseWait:  DefaultSessionCloseWait,
		RequestTimeout:    DefaultRequestTimeout,
		InitializeTimeout: DefaultInitializeTimeout,
	}
}

func (s AgentState) String() string {
	switch s {
	case StateUninitialized:
		return "Uninitialized"
	case StateInitializing:
		return "Initializing"
	case StateReady:
		return "Ready"
	case StateSessionCreating:
		return "SessionCreating"
	case StateSessionActive:
		return "SessionActive"
	case StatePrompting:
		return "Prompting"
	case StateCancelling:
		return "Cancelling"
	case StateSessionClosing:
		return "SessionClosing"
	case StateTerminating:
		return "Terminating"
	case StateTerminated:
		return "Terminated"
	case StateFailed:
		return "Failed"
	default:
		return "Unknown"
	}
}

type AgentSupervisor struct {
	state     AgentState
	stateLock sync.RWMutex

	cmd        *exec.Cmd
	session    *acp.AcpSession
	transport  *acp.AcpTransport
	handler    *acp.Handler
	terminals  *acp.TerminalManager
	workspace  string
	eventSink  EventSink
	permCancel context.CancelFunc
	permCtx    context.Context
	permLock   sync.Mutex
	debug      bool

	promptMu       sync.Mutex
	queuedPrompts  []queuedPrompt
	promptInFlight bool

	exitDone        chan struct{}
	exitHandledDone chan struct{}
	exitErr         error
	terminating     atomic.Bool
	completeOnce    sync.Once

	Timeouts SupervisorTimeouts
}

type EventSink interface {
	SendOutput(line string, stream string)
	SendActivity(activity acp.Activity)
	SendComplete(exitCode int)
	RequestPermission(req acp.PermissionRequest) (selectedOptionID string, err error)
	CreateElicitation(req acp.ElicitationRequest) (acp.ElicitationResult, error)
	PermissionCancelChan() <-chan struct{}
	CancelPendingHitl()
}

func NewAgentSupervisor(workspace string, terminals *acp.TerminalManager, eventSink EventSink) *AgentSupervisor {
	ctx, cancel := context.WithCancel(context.Background())
	return &AgentSupervisor{
		state:      StateUninitialized,
		terminals:  terminals,
		workspace:  workspace,
		eventSink:  eventSink,
		permCtx:    ctx,
		permCancel: cancel,
		Timeouts:   DefaultSupervisorTimeouts(),
	}
}

func (s *AgentSupervisor) State() AgentState {
	s.stateLock.RLock()
	defer s.stateLock.RUnlock()
	return s.state
}

func (s *AgentSupervisor) setState(newState AgentState) {
	s.stateLock.Lock()
	defer s.stateLock.Unlock()
	oldState := s.state
	s.state = newState
	log.Printf("[Supervisor] State transition: %s -> %s", oldState, newState)
}

func (s *AgentSupervisor) Session() *acp.AcpSession {
	s.stateLock.RLock()
	defer s.stateLock.RUnlock()
	return s.session
}

func (s *AgentSupervisor) StartProcess(cmd *exec.Cmd) error {
	s.stateLock.Lock()
	if s.state != StateUninitialized {
		s.stateLock.Unlock()
		return fmt.Errorf("cannot start process in state %s", s.state)
	}
	s.state = StateInitializing
	s.stateLock.Unlock()

	s.cmd = cmd
	s.exitDone = make(chan struct{})
	s.exitHandledDone = make(chan struct{})

	if err := cmd.Start(); err != nil {
		s.setState(StateFailed)
		return fmt.Errorf("failed to start process: %w", err)
	}

	// Transition to Ready after process starts successfully
	s.setState(StateReady)

	go s.watchExit()
	return nil
}

func (s *AgentSupervisor) watchExit() {
	s.stateLock.RLock()
	cmd := s.cmd
	transport := s.transport
	s.stateLock.RUnlock()

	// Drain stdout/stderr before reaping with cmd.Wait(): Wait closes the
	// pipes, which would truncate any buffered output the transport reader
	// has not consumed yet (e.g. a session/new response written just before
	// the agent exits).
	if transport != nil {
		select {
		case <-transport.StdoutDone():
		case <-time.After(s.Timeouts.DrainWait):
		}
		select {
		case <-transport.StderrDone():
		case <-time.After(s.Timeouts.DrainWait):
		}
	}

	s.exitErr = cmd.Wait()
	close(s.exitDone)

	if !s.terminating.Load() {
		exitCode := 0
		if s.exitErr != nil {
			var exitError *exec.ExitError
			if errors.As(s.exitErr, &exitError) {
				exitCode = exitError.ExitCode()
			} else {
				exitCode = -1
			}
		}

		s.stateLock.Lock()
		s.session = nil
		s.stateLock.Unlock()

		s.completeOnce.Do(func() {
			if s.eventSink != nil {
				s.eventSink.SendComplete(exitCode)
			}
		})
	}

	s.setState(StateTerminated)
	close(s.exitHandledDone)
}

func (s *AgentSupervisor) setTransport(transport *acp.AcpTransport) {
	s.stateLock.Lock()
	defer s.stateLock.Unlock()
	s.transport = transport
}

func (s *AgentSupervisor) setSession(session *acp.AcpSession) {
	s.setState(StateSessionActive)
	s.stateLock.Lock()
	defer s.stateLock.Unlock()
	s.session = session
}

func (s *AgentSupervisor) SendOutput(line string, stream string) {
	s.eventSink.SendOutput(line, stream)
}

func (s *AgentSupervisor) SendActivity(activity acp.Activity) {
	s.eventSink.SendActivity(activity)
}

// SetDebug controls whether the ACP handler relays sidecar-internal
// diagnostics into env.output.
func (s *AgentSupervisor) SetDebug(debug bool) {
	s.debug = debug
}

func (s *AgentSupervisor) RequestPermission(req acp.PermissionRequest) (string, error) {
	return s.eventSink.RequestPermission(req)
}

func (s *AgentSupervisor) CreateElicitation(req acp.ElicitationRequest) (acp.ElicitationResult, error) {
	return s.eventSink.CreateElicitation(req)
}

func (s *AgentSupervisor) PermissionCancelChan() <-chan struct{} {
	return s.eventSink.PermissionCancelChan()
}

func (s *AgentSupervisor) CancelPermissions() {
	s.permLock.Lock()
	s.permCancel()
	s.permCtx, s.permCancel = context.WithCancel(context.Background())
	s.permLock.Unlock()
	// Also cancel the client's pending HITL requests
	if s.eventSink != nil {
		s.eventSink.CancelPendingHitl()
	}
}

func (s *AgentSupervisor) PermCtx() context.Context {
	return s.permCtx
}

func (s *AgentSupervisor) Terminate() (int, error) {
	s.stateLock.RLock()
	cmd := s.cmd
	transport := s.transport
	currentState := s.state
	s.stateLock.RUnlock()

	if cmd == nil || cmd.Process == nil {
		return 0, fmt.Errorf("no process to terminate")
	}

	s.terminating.Store(true)

	if currentState == StatePrompting || currentState == StateSessionActive {
		s.setState(StateCancelling)
	}

	s.CancelPermissions()

	s.stateLock.RLock()
	session := s.session
	s.stateLock.RUnlock()

	if session != nil && session.SessionID != "" && transport != nil {
		cancelNotif := map[string]interface{}{
			"jsonrpc": "2.0",
			"method":  "session/cancel",
			"params": map[string]interface{}{
				"sessionId": session.SessionID,
			},
		}
		_ = transport.WriteResponse(cancelNotif)
		time.Sleep(s.Timeouts.CancelDelay)
	}

	s.setState(StateTerminating)

	_ = s.CloseSession()

	if transport != nil {
		if err := transport.Close(); err != nil {
			log.Printf("[Supervisor] Failed to close transport: %v", err)
		}
	}

	select {
	case <-s.exitDone:
	case <-time.After(s.Timeouts.ExitWait):
		if err := syscall.Kill(-cmd.Process.Pid, syscall.SIGTERM); err != nil && err != syscall.ESRCH {
			return 0, fmt.Errorf("failed to terminate process group: %w", err)
		}
		select {
		case <-s.exitDone:
		case <-time.After(s.Timeouts.SigkillWait):
			if err := syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL); err != nil && err != syscall.ESRCH {
				return 0, fmt.Errorf("failed to kill process group: %w", err)
			}
			<-s.exitDone
		}
	}
	<-s.exitHandledDone

	// Wait for stdout/stderr readers to finish
	if transport != nil {
		select {
		case <-transport.StdoutDone():
		case <-time.After(s.Timeouts.DrainWait):
		}
		select {
		case <-transport.StderrDone():
		case <-time.After(s.Timeouts.DrainWait):
		}
	}

	exitCode := 0
	if s.exitErr != nil {
		var exitError *exec.ExitError
		if errors.As(s.exitErr, &exitError) {
			if status, ok := exitError.Sys().(syscall.WaitStatus); ok {
				if status.Signaled() {
					// Process was killed by a signal, exit code is 128 + signal number
					exitCode = 128 + int(status.Signal())
				} else {
					exitCode = status.ExitStatus()
				}
			} else {
				exitCode = exitError.ExitCode()
			}
		} else {
			exitCode = -1
		}
	}

	s.stateLock.Lock()
	s.session = nil
	s.stateLock.Unlock()

	s.completeOnce.Do(func() {
		if s.eventSink != nil {
			s.eventSink.SendComplete(exitCode)
		}
	})

	if s.terminals != nil {
		s.terminals.ReleaseAll()
	}

	return exitCode, nil
}

func (s *AgentSupervisor) ReleaseTerminals() {
	if s.terminals != nil {
		s.terminals.ReleaseAll()
	}
}

func (s *AgentSupervisor) SetStatePrompting() error {
	s.stateLock.Lock()
	defer s.stateLock.Unlock()
	if s.state != StateSessionActive {
		return fmt.Errorf("cannot transition to Prompting from state %s", s.state)
	}
	oldState := s.state
	s.state = StatePrompting
	log.Printf("[Supervisor] State transition: %s -> %s", oldState, StatePrompting)
	return nil
}

func (s *AgentSupervisor) SetStateSessionActive() error {
	s.stateLock.Lock()
	defer s.stateLock.Unlock()
	if s.state != StatePrompting {
		return fmt.Errorf("cannot transition to SessionActive from state %s", s.state)
	}
	oldState := s.state
	s.state = StateSessionActive
	log.Printf("[Supervisor] State transition: %s -> %s", oldState, StateSessionActive)
	return nil
}

func (s *AgentSupervisor) SetStateCancelling() error {
	s.stateLock.Lock()
	defer s.stateLock.Unlock()
	if s.state != StatePrompting {
		return fmt.Errorf("cannot transition to Cancelling from state %s", s.state)
	}
	oldState := s.state
	s.state = StateCancelling
	log.Printf("[Supervisor] State transition: %s -> %s", oldState, StateCancelling)
	return nil
}

func (s *AgentSupervisor) CloseSession() error {
	s.stateLock.RLock()
	session := s.session
	transport := s.transport
	s.stateLock.RUnlock()

	if session == nil || session.SessionID == "" || transport == nil {
		return nil
	}

	s.setState(StateSessionClosing)

	params := map[string]interface{}{
		"sessionId": session.SessionID,
	}

	type result struct {
		resp map[string]interface{}
		err  error
	}

	done := make(chan result, 1)

	go func() {
		resp, err := transport.SendRequest("session/close", params, s.Timeouts.RequestTimeout)
		done <- result{resp, err}
	}()

	select {
	case res := <-done:
		if res.err != nil {
			log.Printf("[Supervisor] session/close failed: %v", res.err)
			return res.err
		}
		log.Printf("[Supervisor] session/close succeeded for session %s", session.SessionID)
		s.setState(StateReady)
		return nil
	case <-time.After(s.Timeouts.SessionCloseWait):
		log.Printf("[Supervisor] session/close timed out for session %s", session.SessionID)
		return fmt.Errorf("session/close timed out")
	}
}

type LaunchResult struct {
	Session      *acp.AcpSession
	AgentName    string
	AgentVersion string
}

func (s *AgentSupervisor) Launch(cmd *exec.Cmd) (*LaunchResult, error) {
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, fmt.Errorf("failed to create stdin pipe: %w", err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, fmt.Errorf("failed to create stdout pipe: %w", err)
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, fmt.Errorf("failed to create stderr pipe: %w", err)
	}

	handler := acp.NewHandler(s.eventSink, s.terminals, s.workspace)
	handler.SetDebug(s.debug)
	s.handler = handler

	transport := acp.NewAcpTransport(
		stdin, stdout, stderr,
		nil,
		handler.OutputHandler(),
	)
	s.transport = transport

	transport.SetOnNotification(handler.NotificationHandler(transport))

	if err := s.StartProcess(cmd); err != nil {
		return nil, err
	}

	transport.Start()

	s.setState(StateSessionCreating)

	s.eventSink.SendOutput("[ACP] Starting ACP handshake...", "stdout")
	s.eventSink.SendOutput("[ACP] Sending initialize request...", "stdout")

	initReq := acp.NewInitializeRequest()
	resp, err := transport.SendRequest("initialize", initReq, s.Timeouts.InitializeTimeout)
	if err != nil {
		s.cleanupFailedLaunch(fmt.Sprintf("ACP initialize failed: %v", err))
		return nil, fmt.Errorf("ACP initialize failed: %w", err)
	}
	if resp["error"] != nil {
		s.cleanupFailedLaunch(fmt.Sprintf("ACP initialize error: %v", resp["error"]))
		return nil, fmt.Errorf("ACP initialize error: %v", resp["error"])
	}

	initResp, agentName, agentVersion, err := acp.ParseInitializeResponse(resp)
	if err != nil {
		s.cleanupFailedLaunch(fmt.Sprintf("Failed to parse initialize response: %v", err))
		return nil, fmt.Errorf("failed to parse initialize response: %w", err)
	}

	agreedVersion, err := acp.NegotiateVersion(acp.AcpProtocolVersion, initResp.ProtocolVersion)
	if err != nil {
		s.cleanupFailedLaunch(err.Error())
		return nil, err
	}

	s.eventSink.SendOutput(fmt.Sprintf("[ACP] Initialize successful (protocol v%d)", agreedVersion), "stdout")

	if agentName != "" {
		titleStr := ""
		if initResp.AgentInfo.Title != "" {
			titleStr = fmt.Sprintf(" (%s)", initResp.AgentInfo.Title)
		}
		s.eventSink.SendOutput(fmt.Sprintf("[ACP] Connected to %s%s v%s", agentName, titleStr, agentVersion), "stdout")
	}

	s.eventSink.SendOutput("[ACP] Sending session/new request...", "stdout")

	sessionNewReq := acp.AcpSessionNewRequest{
		Cwd:        s.workspace,
		McpServers: []interface{}{},
	}
	resp, err = transport.SendRequest("session/new", sessionNewReq, s.Timeouts.InitializeTimeout)
	if err != nil {
		s.cleanupFailedLaunch(fmt.Sprintf("ACP session/new failed: %v", err))
		return nil, fmt.Errorf("ACP session/new failed: %w", err)
	}
	if resp["error"] != nil {
		s.cleanupFailedLaunch(fmt.Sprintf("ACP session/new error: %v", resp["error"]))
		return nil, fmt.Errorf("ACP session/new error: %v", resp["error"])
	}

	s.eventSink.SendOutput("[ACP] session/new successful", "stdout")
	log.Printf("[ACP][TIMING] session/new completed at %v", time.Now())

	var sessionID string
	if result, ok := resp["result"].(map[string]interface{}); ok {
		if sID, ok := result["sessionId"].(string); ok {
			sessionID = sID
		}
	}

	session := &acp.AcpSession{
		SessionID:         sessionID,
		AgentName:         agentName,
		AgentVersion:      agentVersion,
		ProtocolVersion:   agreedVersion,
		AgentTitle:        initResp.AgentInfo.Title,
		AgentCapabilities: initResp.AgentCapabilities,
		AuthMethods:       initResp.AuthMethods,
		Transport:         transport,
	}

	s.stateLock.Lock()
	s.session = session
	s.state = StateSessionActive
	s.stateLock.Unlock()
	log.Printf("[Supervisor] State transition: %s -> %s", StateSessionCreating, StateSessionActive)

	select {
	case <-s.exitDone:
		s.stateLock.Lock()
		s.session = nil
		s.stateLock.Unlock()
		log.Printf("[Supervisor] Process exited during handshake, cleared session")
	default:
	}

	return &LaunchResult{
		Session:      session,
		AgentName:    agentName,
		AgentVersion: agentVersion,
	}, nil
}

func (s *AgentSupervisor) cleanupFailedLaunch(errMsg string) {
	s.eventSink.SendOutput(fmt.Sprintf("[ACP] Launch failed: %s — terminating agent", errMsg), "stderr")
	if s.transport != nil {
		_ = s.transport.Close()
	}
	if s.cmd != nil && s.cmd.Process != nil {
		_, _ = s.Terminate()
	}
}

type queuedPrompt struct {
	prompt     string
	isSteering bool
}

type PromptResult struct {
	StopReason string
	Error      error
}

// Prompt sends a session/prompt turn. A prompt arriving while another turn is
// already in flight is queued and delivered as a follow-up turn once the current
// turn completes.
func (s *AgentSupervisor) Prompt(taskPrompt string) (*PromptResult, error) {
	return s.submitPrompt(taskPrompt, false)
}

// Steer interrupts the in-flight turn (via session/cancel) and queues the
// steering prompt to be delivered as a follow-up session/prompt turn. If no
// turn is in flight, the steering prompt is sent directly.
func (s *AgentSupervisor) Steer(taskPrompt string) (*PromptResult, error) {
	return s.submitPrompt(taskPrompt, true)
}

func (s *AgentSupervisor) submitPrompt(taskPrompt string, isSteering bool) (*PromptResult, error) {
	session := s.Session()
	if session == nil {
		return nil, fmt.Errorf("no active session")
	}

	s.promptMu.Lock()
	if s.promptInFlight {
		s.queuedPrompts = append(s.queuedPrompts, queuedPrompt{
			prompt:     taskPrompt,
			isSteering: isSteering,
		})
		s.promptMu.Unlock()
		if isSteering {
			// Interrupt the active turn so the steering is delivered promptly
			// rather than after the current turn completes naturally.
			if err := s.cancelSession(session, "user_interrupted"); err != nil {
				log.Printf("[Supervisor] Warning: failed to cancel in-flight turn for steering: %v", err)
			}
		}
		return &PromptResult{}, nil
	}
	s.promptInFlight = true
	s.promptMu.Unlock()

	result, err := s.runPromptTurn(session, taskPrompt, isSteering)

	for {
		s.promptMu.Lock()
		if len(s.queuedPrompts) == 0 {
			s.promptInFlight = false
			s.promptMu.Unlock()
			break
		}
		next := s.queuedPrompts[0]
		s.queuedPrompts = s.queuedPrompts[1:]
		s.promptMu.Unlock()

		turnResult, turnErr := s.runPromptTurn(session, next.prompt, next.isSteering)
		switch {
		case turnErr != nil:
			log.Printf("[Supervisor] Warning: queued steering prompt failed: %v", turnErr)
		case turnResult != nil && turnResult.Error != nil:
			log.Printf("[Supervisor] Warning: queued steering prompt failed: %v", turnResult.Error)
		case turnResult != nil:
			// Track the final follow-up turn's stop reason so the caller reports
			// the true terminal state (e.g. end_turn) rather than the interrupted
			// turn's cancelled marker.
			result = turnResult
		}
	}

	return result, err
}

// cancelSession sends a session/cancel notification to interrupt the active turn.
// The reason is a non-standard extension tolerated by agents that surface it;
// the notification remains a valid cancel even when the agent ignores the field.
func (s *AgentSupervisor) cancelSession(session *acp.AcpSession, reason string) error {
	transport := s.transport
	if session == nil || session.SessionID == "" || transport == nil {
		return fmt.Errorf("no active session to cancel")
	}

	params := map[string]interface{}{
		"sessionId": session.SessionID,
	}
	if reason != "" {
		params["reason"] = reason
	}
	cancelNotif := map[string]interface{}{
		"jsonrpc": "2.0",
		"method":  "session/cancel",
		"params":  params,
	}
	return transport.WriteResponse(cancelNotif)
}

func (s *AgentSupervisor) runPromptTurn(session *acp.AcpSession, taskPrompt string, isSteering bool) (*PromptResult, error) {
	if err := s.SetStatePrompting(); err != nil {
		return nil, err
	}

	if isSteering && s.eventSink != nil {
		desc := taskPrompt
		const maxLen = 120
		if len(desc) > maxLen {
			desc = desc[:maxLen] + "..."
		}
		desc = "User provided steering guidance: " + desc
		actionID := fmt.Sprintf("steering-%d", time.Now().UnixNano())
		s.eventSink.SendActivity(acp.Activity{
			ActivityType: acp.ActivityTypeMessage,
			Description:  desc,
			ActionID:     actionID,
			Status:       acp.ActivityCompleted,
			Detail: acp.ActivityDetail{
				Role:      "user",
				MessageID: actionID,
			},
		})
	}

	promptBlocks := []interface{}{
		map[string]interface{}{
			"type": "text",
			"text": taskPrompt,
		},
	}

	promptReq := acp.AcpSessionPromptRequest{
		SessionID: session.SessionID,
		Prompt:    promptBlocks,
	}

	// session/prompt has no fixed deadline: the agent may legitimately work for
	// an arbitrarily long time (tool calls, multi-turn LLM loops, etc.) and reports
	// progress via session/update notifications rather than by responding quickly.
	// We only fail if the agent process itself dies (stdout closes) before responding.
	resp, err := session.Transport.SendRequestUntilClosed("session/prompt", promptReq)

	if setErr := s.SetStateSessionActive(); setErr != nil {
		log.Printf("[Supervisor] Warning: failed to set state to SessionActive: %v", setErr)
	}

	if s.handler != nil {
		s.handler.CloseChunkRun("prompt turn ended")
	}

	if err != nil {
		return &PromptResult{Error: fmt.Errorf("ACP session/prompt failed: %w", err)}, nil
	}

	if resp["error"] != nil {
		return &PromptResult{Error: fmt.Errorf("ACP session/prompt error: %v", resp["error"])}, nil
	}

	var stopReason string
	if result, ok := resp["result"].(map[string]interface{}); ok {
		if sr, ok := result["stopReason"].(string); ok {
			stopReason = sr
		}
	}

	return &PromptResult{StopReason: stopReason}, nil
}
