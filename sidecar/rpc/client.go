package rpc

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"kratis-connector/acp"
	"kratis-connector/runner"
)

const (
	DefaultReconnectDelay    = 5 * time.Second
	DefaultHeartbeatInterval = 10 * time.Second
	DefaultPermissionTimeout = 15 * time.Minute

	DefaultPromptQuietPeriod = 10 * time.Second

	// productionStateDir is the root of the running sidecar's persistent state.
	// Everything the sidecar must keep across restarts lives below it.
	productionStateDir = "/kratis"
)

var defaultStateDir = productionStateDir

// maxOutboundFrameBytes bounds every JSON-RPC frame sent to the control plane.
// Tomcat rejects inbound text messages above its maxTextMessageBufferSize
// (16MB) with close code 1009, so frames stay well under it after JSON escaping.
const maxOutboundFrameBytes = 12 * 1024 * 1024

// maxOutputChunkBytes bounds one env.output frame. A single streamed "line"
// (up to the ACP scanner's 10MB token limit) is split into rune-aligned chunks
// so escaping can never push a frame past maxOutboundFrameBytes.
const maxOutputChunkBytes = 4 * 1024 * 1024

var errConnectionClosed = fmt.Errorf("connection closed before response")

// frameTooLargeError marks an outbound frame rejected by the size guard; it is
// reported to the control plane as env.sidecar_error so drops are not silent.
type frameTooLargeError struct {
	size int
}

func (e *frameTooLargeError) Error() string {
	return fmt.Sprintf(
		"frame too large: %d bytes exceeds the %d-byte limit; truncate oversized payloads at the source",
		e.size, maxOutboundFrameBytes)
}

type disconnectReport struct {
	code   int
	reason string
}

type ClientTimeouts struct {
	ReconnectDelay        time.Duration
	HeartbeatInterval     time.Duration
	PermissionTimeout     time.Duration
	PromptQuietPeriod     time.Duration
	DiffDebouncePeriod    time.Duration
	DiffRetryInitialDelay time.Duration
	DiffRetryMaxDelay     time.Duration
}

func DefaultClientTimeouts() ClientTimeouts {
	return ClientTimeouts{
		ReconnectDelay:        DefaultReconnectDelay,
		HeartbeatInterval:     DefaultHeartbeatInterval,
		PermissionTimeout:     DefaultPermissionTimeout,
		PromptQuietPeriod:     DefaultPromptQuietPeriod,
		DiffDebouncePeriod:    500 * time.Millisecond,
		DiffRetryInitialDelay: 500 * time.Millisecond,
		DiffRetryMaxDelay:     10 * time.Second,
	}
}

type Client struct {
	serverURL        string
	token            string
	workspace        string
	stateDir         string
	terminalLogMu    sync.Mutex
	executor         *runner.Executor
	sshAgentPID      string
	sshAuthSock      string
	gitHelperScript  string
	gitConfigGlobal  string
	credentialSocket string

	mu             sync.Mutex
	wsConn         *websocket.Conn
	writeMu        sync.Mutex
	pending        map[interface{}]chan *JsonRpcResponse
	nextID         uint64
	requestTimeout time.Duration

	// supervisor manages the ACP agent lifecycle
	supervisor *runner.AgentSupervisor

	// acpAgentCommand remembers the agent command used for the current ACP
	// session so env.acp_prompt with relaunch=true can spawn a fresh session
	// with the same command.
	acpAgentCommand string

	// acpAgentLogFiles remembers the diagnostic files tailed for the current
	// ACP session so a relaunch tails the same set.
	acpAgentLogFiles []string

	// terminalManager manages ACP terminal sessions
	terminalManager *acp.TerminalManager

	// Permission cancellation context
	permCtx    context.Context
	permCancel context.CancelFunc

	// Reconnect state tracking
	isReconnected bool
	// lastEventSequence is the env.register reconnect cursor; events carry no
	// sequence numbers yet, so it stays 0 until sequence tracking is added.
	lastEventSequence int64
	// pendingHitl holds channels waiting for an asynchronous env.hitl_resolved,
	// keyed by actionId so the notification can be matched without a request id.
	pendingHitl map[string]chan HitlActivityResult

	// Pending prompt completion (for reconnection recovery)
	pendingPromptComplete *AcpPromptCompleteParams

	// Disconnect (code/reason) detected by readLoop, flushed as env.sidecar_error
	// after the next successful re-registration; gated to reconnect cycles.
	pendingDisconnectReport *disconnectReport
	reconnectReportPending  bool

	// Current active execution ID
	currentExecutionID string

	// Checked-out target branch recorded from env.checkout; the diff base for
	// workspace change notifications emitted without an explicit branch.
	baseBranch string

	lastActivityNanos atomic.Int64

	// Timeouts for client operations
	Timeouts ClientTimeouts

	// Timeouts for supervisor (used when creating supervisors)
	supervisorTimeouts runner.SupervisorTimeouts

	// Diff sync state
	diffMu            sync.Mutex
	lastDiffHash      string
	diffDebounceTimer *time.Timer
	pendingDiff       *diffPushState
	pushingDiff       bool
	diffSeq           uint64

	// publishingMu ensures at most one publish operation runs per client
	publishingMu sync.Mutex

	// debug gates sidecar-internal diagnostics out of env.output (they always
	// remain in the Go log)
	debug bool
}

func NewClient(serverURL, token, workspace string) *Client {
	if workspace == "" {
		panic("rpc: NewClient requires an explicit workspace")
	}
	c := &Client{
		serverURL:          serverURL,
		token:              token,
		workspace:          workspace,
		stateDir:           defaultStateDir,
		executor:           runner.NewExecutor(),
		pending:            make(map[interface{}]chan *JsonRpcResponse),
		pendingHitl:        make(map[string]chan HitlActivityResult),
		nextID:             1,
		requestTimeout:     10 * time.Second,
		terminalManager:    acp.NewTerminalManager(workspace),
		Timeouts:           DefaultClientTimeouts(),
		supervisorTimeouts: runner.DefaultSupervisorTimeouts(),
	}
	c.permCtx, c.permCancel = context.WithCancel(context.Background())
	c.lastActivityNanos.Store(time.Now().UnixNano())
	// Start local process-verifying git credentials server. Tokens are resolved
	// on-demand from the control plane (never cached statically) so short-lived
	// credentials (e.g. GitHub App installation tokens) stay fresh for the whole
	// sandbox lifetime. The socket is process-private and bound once: later
	// reconnects and credential re-registrations keep serving the same path.
	socketPath, err := runner.StartCredentialServer(func() string {
		return c.fetchGitToken()
	})
	if err != nil {
		log.Printf("credential server failed to start: %v", err)
	} else {
		c.mu.Lock()
		c.credentialSocket = socketPath
		c.mu.Unlock()
	}
	return c
}

// fetchGitToken asks the control plane for a fresh token for the environment's
// current execution. It returns "" when the request fails, which makes git fall
// back to prompting (and ultimately fail without creds).
func (c *Client) fetchGitToken() string {
	resp, err := c.sendRequest("env.git_token", GitTokenParams{})
	if err != nil {
		log.Printf("fetchGitToken: request failed: %v", err)
		return ""
	}
	if resp.Error != nil {
		log.Printf("fetchGitToken: control plane error [%d]: %s", resp.Error.Code, resp.Error.Message)
		return ""
	}
	var result GitTokenResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		log.Printf("fetchGitToken: failed to parse result: %v", err)
		return ""
	}
	return result.Token
}

// SetDebug gates sidecar-internal [ACP][PERM]-style diagnostics out of
// env.output; they always remain in the Go log.
func (c *Client) SetDebug(debug bool) {
	c.debug = debug
}

// stateRoot returns this client's persistent state root. A Client is invalid
// until its dependencies are set, so a zero-value Client fails instead of
// silently falling back to the live sandbox's /kratis state. NewClient sets the
// root for the daemon; tests must inject a t.TempDir() root.
func (c *Client) stateRoot() string {
	if c.stateDir == "" {
		panic("rpc: Client has no stateDir; construct it with NewClient")
	}
	return c.stateDir
}

// statePath joins name onto the state root and rejects any escape from it.
func (c *Client) statePath(name ...string) string {
	root := c.stateRoot()
	path := filepath.Join(append([]string{root}, name...)...)
	if !pathWithin(root, path) {
		panic(fmt.Sprintf("rpc: state path %q escapes state root %q", path, root))
	}
	return path
}

func pathWithin(root, path string) bool {
	rootAbs, err := filepath.Abs(root)
	if err != nil {
		return false
	}
	pathAbs, err := filepath.Abs(path)
	if err != nil {
		return false
	}
	rel, err := filepath.Rel(rootAbs, pathAbs)
	if err != nil {
		return false
	}
	return rel == "." || (rel != ".." && !strings.HasPrefix(rel, ".."+string(os.PathSeparator)))
}

// envFilePath returns the location of the persisted environment file. It is
// kept outside the workspace so it never pollutes the git working tree.
func (c *Client) envFilePath() string {
	return c.statePath(".kratis-env")
}

// acpSessionIDPath returns the location of the persisted ACP session id. It sits
// in the state root (outside the workspace) so it survives a sandbox suspend
// snapshot and is never part of the git working tree. The sandbox is the single
// owner of this id: the control plane never sees it.
func (c *Client) acpSessionIDPath() string {
	return c.statePath("acp_session_id")
}

// readPersistedSessionID returns the stored ACP session id, or "" when none has
// been recorded yet (a first launch).
func (c *Client) readPersistedSessionID() string {
	data, err := os.ReadFile(c.acpSessionIDPath())
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}

// persistSessionID records the session id so a later agent process can re-attach
// to it via session/resume or session/load.
func (c *Client) persistSessionID(sessionID string) {
	if sessionID == "" {
		return
	}
	path := c.acpSessionIDPath()
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		log.Printf("[ACP] Failed to create session id directory %s: %v", filepath.Dir(path), err)
		return
	}
	if err := os.WriteFile(path, []byte(sessionID), 0o600); err != nil {
		log.Printf("[ACP] Failed to persist session id to %s: %v", path, err)
	}
}

// diffBaseBranchPath returns the location of the persisted diff base branch. It
// sits in the state root (outside the workspace) so it survives a sandbox
// suspend snapshot and is never part of the git working tree.
func (c *Client) diffBaseBranchPath() string {
	return c.statePath("diff_base_branch")
}

// executionBaseShaPath returns the location of the persisted execution base SHA.
// It sits in the state root (outside the workspace) so it survives a sandbox
// suspend snapshot and is never part of the git working tree.
func (c *Client) executionBaseShaPath() string {
	return c.statePath("execution_base_sha")
}

// readPersistedExecutionBaseSha returns the base commit SHA recorded at checkout or updated after rebase.
func (c *Client) readPersistedExecutionBaseSha() string {
	data, err := os.ReadFile(c.executionBaseShaPath())
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}

// persistExecutionBaseSha records the execution base SHA so a connector restarted from
// a suspend snapshot can still resolve the base commit.
func (c *Client) persistExecutionBaseSha(sha string) {
	sha = strings.TrimSpace(sha)
	if sha == "" {
		return
	}
	path := c.executionBaseShaPath()
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		log.Printf("[GIT] Failed to create base sha directory %s: %v", filepath.Dir(path), err)
		return
	}
	if err := os.WriteFile(path, []byte(sha), 0o600); err != nil {
		log.Printf("[GIT] Failed to persist execution base sha to %s: %v", path, err)
	}
}

// readPersistedBaseBranch returns the branch recorded at checkout, or "" when no
// checkout has happened in this sandbox.
func (c *Client) readPersistedBaseBranch() string {
	data, err := os.ReadFile(c.diffBaseBranchPath())
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}

// persistBaseBranch records the checked-out branch so a connector restarted from
// a suspend snapshot - which never re-runs env.checkout - can still resolve the
// git-owned origin/<branch> base for workspace diff notifications.
func (c *Client) persistBaseBranch(branch string) {
	branch = strings.TrimSpace(branch)
	if branch == "" {
		return
	}
	path := c.diffBaseBranchPath()
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		log.Printf("[DIFF] Failed to create base branch directory %s: %v", filepath.Dir(path), err)
		return
	}
	if err := os.WriteFile(path, []byte(branch), 0o600); err != nil {
		log.Printf("[DIFF] Failed to persist diff base branch to %s: %v", path, err)
	}
}

// diffBaseBranch returns the branch the workspace diff is computed against. It
// prefers the in-memory branch recorded by env.checkout, then the value that
// checkout persisted to disk, so a restart without a checkout still has a base.
func (c *Client) diffBaseBranch() string {
	c.mu.Lock()
	branch := strings.TrimSpace(c.baseBranch)
	c.mu.Unlock()
	if branch != "" {
		return branch
	}

	persisted := c.readPersistedBaseBranch()
	if persisted == "" {
		return ""
	}
	c.mu.Lock()
	if strings.TrimSpace(c.baseBranch) == "" {
		c.baseBranch = persisted
	}
	branch = c.baseBranch
	c.mu.Unlock()
	return branch
}

// Start initiates the connection, registration, and heartbeat loop.
func (c *Client) Start(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		default:
			// Connect
			u, err := url.Parse(c.serverURL)
			if err != nil {
				log.Fatalf("Invalid server URL: %v", err)
			}

			log.Printf("Connecting to %s...", u.String())
			conn, _, err := websocket.DefaultDialer.Dial(u.String(), nil)
			if err != nil {
				log.Printf("Dial error: %v. Retrying in %v...", err, c.Timeouts.ReconnectDelay)
				select {
				case <-ctx.Done():
					return
				case <-time.After(c.Timeouts.ReconnectDelay):
					continue
				}
			}

			c.mu.Lock()
			c.wsConn = conn
			c.pending = make(map[interface{}]chan *JsonRpcResponse)
			c.mu.Unlock()

			// Start reading messages
			errChan := make(chan error, 1)
			go c.readLoop(errChan)

			// Register environment
			err = c.register()
			if err != nil {
				log.Printf("Registration failed: %v. Reconnecting...", err)
				c.closeConnection()
				select {
				case <-ctx.Done():
					return
				case <-time.After(c.Timeouts.ReconnectDelay):
					continue
				}
			}

			log.Printf("Successfully registered environment with control plane.")

			c.reportDisconnect()

			// Heartbeat ticker
			ticker := time.NewTicker(c.Timeouts.HeartbeatInterval)

			heartbeatErrChan := make(chan error, 1)
			go func() {
				for {
					select {
					case <-ticker.C:
						if err := c.heartbeat(); err != nil {
							heartbeatErrChan <- err
							return
						}
					case <-ctx.Done():
						return
					}
				}
			}()

			// Wait for error or cancel
			select {
			case <-ctx.Done():
				ticker.Stop()
				c.closeAll()
				return
			case err := <-errChan:
				ticker.Stop()
				log.Printf("Connection error: %v. Reconnecting...", err)
				c.mu.Lock()
				c.reconnectReportPending = true
				c.mu.Unlock()
				c.closeConnection()
			case err := <-heartbeatErrChan:
				ticker.Stop()
				log.Printf("Heartbeat error: %v. Reconnecting...", err)
				c.mu.Lock()
				c.reconnectReportPending = true
				c.mu.Unlock()
				c.closeConnection()
			}

			select {
			case <-ctx.Done():
				return
			case <-time.After(c.Timeouts.ReconnectDelay):
			}
		}
	}
}

func (c *Client) closeConnection() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.wsConn != nil {
		_ = c.wsConn.Close()
		c.wsConn = nil
	}
	c.failPendingLocked(errConnectionClosed)
	c.pending = make(map[interface{}]chan *JsonRpcResponse)
}

// failPendingLocked fails in-flight RPC waiters fast (synthetic error response)
// instead of leaving them to the request timeout after a disconnect.
func (c *Client) failPendingLocked(err error) {
	msg := err.Error()
	for id, ch := range c.pending {
		resp := &JsonRpcResponse{JsonRPC: "2.0", Error: &JsonRpcError{Code: -32000, Message: msg}, ID: id}
		select {
		case ch <- resp:
		default:
		}
	}
}

// cleanupCredentials tears down git credential state. It runs on shutdown and
// before credential re-registration, never on a WebSocket reconnect, so
// credentials survive connection drops.
func (c *Client) cleanupCredentials() {
	c.mu.Lock()
	sshAgentPID := c.sshAgentPID
	helperScript := c.gitHelperScript
	gitConfigGlobal := c.gitConfigGlobal
	c.sshAgentPID = ""
	c.sshAuthSock = ""
	c.gitHelperScript = ""
	c.gitConfigGlobal = ""
	c.mu.Unlock()

	if sshAgentPID != "" {
		cmd := exec.Command("kill", sshAgentPID) //nolint:gosec // G204: kills the ssh-agent helper this client spawned
		_ = cmd.Run()
	}
	if helperScript != "" {
		_ = os.Remove(helperScript)
	}
	if gitConfigGlobal != "" {
		_ = os.Remove(gitConfigGlobal)
	}
}

func (c *Client) closeAll() {
	c.mu.Lock()
	sup := c.supervisor
	c.supervisor = nil
	c.mu.Unlock()

	if sup != nil {
		log.Printf("[ACP] Closing supervisor session")
		if _, err := sup.Terminate(); err != nil {
			log.Printf("[ACP] Failed to terminate supervisor: %v", err)
		}
	}

	c.cleanupCredentials()
	c.closeConnection()
}

func (c *Client) Close() {
	c.closeAll()
}

func (c *Client) SendOutput(line string, stream string) {
	c.markActivity()
	c.mu.Lock()
	execID := c.currentExecutionID
	c.mu.Unlock()
	c.appendTerminalLog(stream, line)
	for _, chunk := range chunkString(line, maxOutputChunkBytes) {
		_ = c.sendNotification("env.output", OutputParams{Line: chunk, Stream: OutputStream(stream), ExecutionID: execID})
	}
}

// chunkString splits s into rune-aligned pieces of at most limit bytes each,
// so an oversized streamed line never becomes a single oversized frame.
func chunkString(s string, limit int) []string {
	if limit <= 0 || len(s) <= limit {
		return []string{s}
	}
	var chunks []string
	for len(s) > limit {
		cut := runeFloorBoundary(s, limit)
		chunks = append(chunks, s[:cut])
		s = s[cut:]
	}
	if s != "" {
		chunks = append(chunks, s)
	}
	return chunks
}

// runeFloorBoundary returns the largest rune-aligned byte offset <= n. When n
// falls inside a leading multi-byte rune, the next boundary (the rune's end) is
// returned, so callers always make progress on valid UTF-8.
func runeFloorBoundary(s string, n int) int {
	if n <= 0 {
		return 0
	}
	if n >= len(s) {
		return len(s)
	}
	last := 0
	for i := range s {
		if i > n {
			if last == 0 {
				return i
			}
			return last
		}
		last = i
	}
	if last == 0 {
		// n falls inside a single leading multi-byte rune; the first safe cut
		// is the rune's end.
		return len(s)
	}
	return last
}

func (c *Client) SendActivity(activity acp.Activity) {
	c.markActivity()
	c.mu.Lock()
	execID := c.currentExecutionID
	c.mu.Unlock()
	_ = c.sendNotification("env.activity", ActivityParams{
		ActivityType: ActivityType(activity.ActivityType),
		Description:  activity.Description,
		ExecutionID:  execID,
		ActionID:     activity.ActionID,
		Status:       ActivityStatus(activity.Status),
		Detail:       activity.Detail,
	})

	if activity.Status == acp.ActivityCompleted &&
		(activity.ActivityType == acp.ActivityTypeEdited || activity.ActivityType == acp.ActivityTypeCommand) {
		c.TriggerDiffCheck()
	}
}

func (c *Client) markActivity() {
	c.lastActivityNanos.Store(time.Now().UnixNano())
}

func (c *Client) lastActivityTime() time.Time {
	return time.Unix(0, c.lastActivityNanos.Load())
}

// waitForQuiet blocks until no activity has been observed for the full period; activity during the
// wait restarts the window.
func (c *Client) waitForQuiet(period time.Duration) {
	if period <= 0 {
		return
	}
	for {
		idle := time.Since(c.lastActivityTime())
		if idle >= period {
			return
		}
		time.Sleep(period - idle)
	}
}

// diffCheckAfterQuiet re-checks the workspace when activity was observed
// during the quiet wait, so writes that landed after the pre-quiet diff check
// still reach the diff before the turn boundary is reported.
func (c *Client) diffCheckAfterQuiet(activityNanosBefore int64) {
	if c.lastActivityNanos.Load() != activityNanosBefore {
		c.TriggerDiffCheck()
	}
}

func convertLocations(locations []acp.ToolLocation) []ActivityLocation {
	if len(locations) == 0 {
		return nil
	}
	converted := make([]ActivityLocation, 0, len(locations))
	for _, location := range locations {
		entry := ActivityLocation{Path: location.Path}
		if location.Line > 0 {
			line := location.Line
			entry.Line = &line
		}
		converted = append(converted, entry)
	}
	return converted
}

func (c *Client) SendComplete(info runner.CompletionInfo) {
	// Flush a terminal diff emit before env.complete so the persisted diff
	// snapshot always reflects the session's final workspace state, even if no
	// mid-run trigger saw the last writes.
	c.flushDiffCheck()

	c.mu.Lock()
	execID := c.currentExecutionID
	c.mu.Unlock()
	_ = c.sendNotification("env.complete", CompleteParams{
		ExitCode:    info.ExitCode,
		ExecutionID: execID,
		Reason:      info.Reason,
		Terminated:  info.Terminated,
	})
}

func (c *Client) PermissionCancelChan() <-chan struct{} {
	return c.permCtx.Done()
}

// CancelPendingHitl drains and cancels every HITL waiter (approvals and questions).
func (c *Client) CancelPendingHitl() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.permCancel != nil {
		c.permCancel()
	}
	c.permCtx, c.permCancel = context.WithCancel(context.Background())
	for actionID, ch := range c.pendingHitl {
		select {
		case ch <- HitlActivityResult{Response: HitlCancelled}:
		default:
		}
		delete(c.pendingHitl, actionID)
	}
}

// resolveHitl delivers an asynchronous env.hitl_resolved to the matching waiter.
func (c *Client) resolveHitl(params HitlResolvedParams) {
	c.mu.Lock()
	ch, ok := c.pendingHitl[params.ActionID]
	if ok {
		delete(c.pendingHitl, params.ActionID)
	}
	c.mu.Unlock()
	if !ok {
		log.Printf("[HITL] env.hitl_resolved for unknown actionId %q (already resolved or timed out)", params.ActionID)
		return
	}
	result := HitlActivityResult{
		Response: params.Response,
		OptionID: params.OptionID,
		Content:  params.Content,
	}
	select {
	case ch <- result:
	default:
		log.Printf("[HITL] dropping env.hitl_resolved for actionId %q: waiter channel full", params.ActionID)
	}
}

func (c *Client) readLoop(errChan chan<- error) {
	for {
		c.mu.Lock()
		conn := c.wsConn
		c.mu.Unlock()
		if conn == nil {
			return
		}

		_, message, err := conn.ReadMessage()
		if err != nil {
			var closeErr *websocket.CloseError
			if errors.As(err, &closeErr) {
				log.Printf("[WS] Control plane closed the connection unexpectedly: code=%d reason=%q", closeErr.Code, closeErr.Text)
				if closeErr.Code == websocket.CloseMessageTooBig {
					log.Printf("[WS] Close 1009 (message too big): an outbound frame exceeded the control plane's WebSocket text-buffer limit; oversized payloads must be truncated at the source")
				}
				c.mu.Lock()
				c.pendingDisconnectReport = &disconnectReport{code: closeErr.Code, reason: closeErr.Text}
				c.mu.Unlock()
			}
			errChan <- err
			return
		}

		var raw map[string]interface{}
		if err := json.Unmarshal(message, &raw); err != nil {
			log.Printf("Failed to unmarshal raw JSON-RPC: %v", err)
			continue
		}

		if _, isMethod := raw["method"]; isMethod {
			var req JsonRpcRequest
			if err := json.Unmarshal(message, &req); err != nil {
				log.Printf("Failed to parse request: %v", err)
				continue
			}
			go c.handleServerRequest(req)
		} else {
			var resp JsonRpcResponse
			if err := json.Unmarshal(message, &resp); err != nil {
				log.Printf("Failed to parse response: %v", err)
				continue
			}
			c.mu.Lock()
			var lookupKey = resp.ID
			if f, ok := resp.ID.(float64); ok {
				lookupKey = uint64(f)
			}
			if ch, ok := c.pending[lookupKey]; ok {
				log.Printf("[WS] readLoop: matched response to pending request id=%v (result len=%d, hasError=%v)", lookupKey, len(resp.Result), resp.Error != nil)
				// Send the response to the channel. Do NOT delete the pending entry here —
				// some callers (e.g. RequestPermission) expect multiple responses on the same
				// channel (e.g. approved:false followed by approved:true). Callers are
				// responsible for cleaning up their pending entries.
				select {
				case ch <- &resp:
				default:
					// Channel is full — this can happen if the caller has already moved on
					// (e.g. timeout). Log and clean up the stale entry.
					log.Printf("[WS] readLoop: channel full for request id=%v, dropping response and cleaning up", lookupKey)
					delete(c.pending, lookupKey)
				}
			} else {
				log.Printf("[Permission] readLoop: no pending request found for response id=%v", lookupKey)
			}
			c.mu.Unlock()
		}
	}
}

func (c *Client) writeJSON(v interface{}) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	c.mu.Lock()
	conn := c.wsConn
	c.mu.Unlock()
	if conn == nil {
		return fmt.Errorf("connection closed")
	}
	raw, err := json.Marshal(v)
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}
	if len(raw) > maxOutboundFrameBytes {
		return &frameTooLargeError{size: len(raw)}
	}
	return conn.WriteMessage(websocket.TextMessage, raw)
}

// sendSidecarError reports a connector-side failure to the control plane
// (best effort; the connection may be gone).
func (c *Client) sendSidecarError(kind SidecarErrorKind, message string, closeCode *int, closeReason string) {
	c.mu.Lock()
	execID := c.currentExecutionID
	c.mu.Unlock()
	if err := c.sendNotification("env.sidecar_error", SidecarErrorParams{
		Kind:        kind,
		Message:     message,
		ExecutionID: execID,
		CloseCode:   closeCode,
		CloseReason: closeReason,
	}); err != nil {
		log.Printf("[WS] Failed to report sidecar error kind=%s: %v", kind, err)
	}
}

// reportDisconnect sends a disconnect captured by readLoop to the control plane
// as env.sidecar_error after a successful re-registration. No-op outside a
// reconnect cycle.
func (c *Client) reportDisconnect() {
	c.mu.Lock()
	if !c.reconnectReportPending {
		c.mu.Unlock()
		return
	}
	report := c.pendingDisconnectReport
	c.reconnectReportPending = false
	c.pendingDisconnectReport = nil
	c.mu.Unlock()
	if report == nil {
		return
	}
	reason := report.reason
	if reason == "" {
		reason = "no reason given by control plane"
	}
	c.sendSidecarError(
		SidecarErrorConnectionClosed,
		fmt.Sprintf("control plane closed the connection unexpectedly (code=%d, reason=%q); reconnected", report.code, reason),
		&report.code, report.reason)
}

func (c *Client) sendRequest(method string, params interface{}) (*JsonRpcResponse, error) {
	c.mu.Lock()
	id := c.nextID
	c.nextID++
	ch := make(chan *JsonRpcResponse, 1)
	c.pending[id] = ch
	c.mu.Unlock()

	req := JsonRpcRequest{
		JsonRPC: "2.0",
		Method:  method,
		Params:  params,
		ID:      id,
	}

	err := c.writeJSON(req)
	if err != nil {
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		var fte *frameTooLargeError
		if errors.As(err, &fte) {
			c.sendSidecarError(
				SidecarErrorFrameDropped,
				fmt.Sprintf("dropped %s request: %d bytes exceeds the %d-byte limit", method, fte.size, maxOutboundFrameBytes),
				nil, "")
		}
		return nil, err
	}

	select {
	case resp := <-ch:
		// Clean up the pending entry after receiving the response.
		// The readLoop no longer deletes entries — callers are responsible for cleanup.
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		return resp, nil
	case <-time.After(c.requestTimeout):
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		return nil, fmt.Errorf("request timeout for method %s", method)
	}
}

func (c *Client) sendNotification(method string, params interface{}) error {
	req := JsonRpcRequest{
		JsonRPC: "2.0",
		Method:  method,
		Params:  params,
	}

	err := c.writeJSON(req)
	if err != nil {
		log.Printf("[WS] sendNotification %s failed: %v", method, err)
		var fte *frameTooLargeError
		if errors.As(err, &fte) {
			c.sendSidecarError(
				SidecarErrorFrameDropped,
				fmt.Sprintf("dropped %s notification: %d bytes exceeds the %d-byte limit", method, fte.size, maxOutboundFrameBytes),
				nil, "")
		}
	}
	return err
}

func (c *Client) register() error {
	c.mu.Lock()
	isReconnect := c.isReconnected
	c.isReconnected = true

	pendingHitlIDs := make([]string, 0, len(c.pendingHitl))
	for actionID := range c.pendingHitl {
		pendingHitlIDs = append(pendingHitlIDs, actionID)
	}
	sort.Strings(pendingHitlIDs)
	params := RegisterParams{
		Token:             c.token,
		IsReconnect:       isReconnect,
		LastEventSequence: c.lastEventSequence,
		PendingHitlIDs:    pendingHitlIDs,
		ProtocolVersion:   1,
		ConnectorVersion:  ConnectorVersion(),
	}

	if c.supervisor != nil {
		session := c.supervisor.Session()
		if session != nil {
			params.ActiveAcpSessionID = session.SessionID
			params.HasActiveAgent = true
		}
	}
	c.mu.Unlock()

	resp, err := c.sendRequest("env.register", params)
	if err != nil {
		return err
	}
	if resp.Error != nil {
		return fmt.Errorf("register error [%d]: %s", resp.Error.Code, resp.Error.Message)
	}

	// Re-send any pending prompt completion that failed during disconnection
	c.mu.Lock()
	pendingCompletion := c.pendingPromptComplete
	c.pendingPromptComplete = nil
	c.mu.Unlock()

	if pendingCompletion != nil {
		log.Printf("[ACP] Re-sending pending env.acp_prompt_complete after reconnection (stopReason=%s)", pendingCompletion.StopReason)
		if err := c.sendNotification("env.acp_prompt_complete", *pendingCompletion); err != nil {
			log.Printf("[ACP] Failed to re-send env.acp_prompt_complete after reconnection: %v", err)
			// Store it back for next reconnection attempt
			c.mu.Lock()
			c.pendingPromptComplete = pendingCompletion
			c.mu.Unlock()
		}
	}

	// Re-push the current diff state so the control-plane copy converges even
	// when a previous env.diff_manifest push was lost during disconnection. The
	// control plane treats the push as an idempotent upsert, so an unchanged
	// workspace just refreshes the same snapshot.
	c.flushDiffOnRegister()

	return nil
}

func (c *Client) heartbeat() error {
	resp, err := c.sendRequest("env.heartbeat", HeartbeatParams{})
	if err != nil {
		return err
	}
	if resp.Error != nil {
		return fmt.Errorf("heartbeat error [%d]: %s", resp.Error.Code, resp.Error.Message)
	}
	return nil
}

func (c *Client) handleServerRequest(req JsonRpcRequest) {
	switch req.Method {
	case "env.hitl_resolved":
		var params HitlResolvedParams
		rawBytes, err := json.Marshal(req.Params)
		if err != nil {
			log.Printf("[HITL] failed to decode env.hitl_resolved params: %v", err)
			return
		}
		if err := json.Unmarshal(rawBytes, &params); err != nil {
			log.Printf("[HITL] failed to decode env.hitl_resolved params: %v", err)
			return
		}
		c.resolveHitl(params)

	case "env.exec":
		var params ExecParams
		rawBytes, err := json.Marshal(req.Params)
		if err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		if err := json.Unmarshal(rawBytes, &params); err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}

		go c.ExecuteExec(params, req.ID)

	case "env.registerGitAuth":
		var params RegisterGitAuthParams
		rawBytes, err := json.Marshal(req.Params)
		if err != nil {
			if req.ID != nil {
				c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			}
			return
		}
		if err := json.Unmarshal(rawBytes, &params); err != nil {
			if req.ID != nil {
				c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			}
			return
		}

		go c.ExecuteRegisterGitAuth(params, req.ID)

	case "env.registerGitIdentity":
		var params RegisterGitIdentityParams
		rawBytes, err := json.Marshal(req.Params)
		if err != nil {
			if req.ID != nil {
				c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			}
			return
		}
		if err := json.Unmarshal(rawBytes, &params); err != nil {
			if req.ID != nil {
				c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			}
			return
		}

		go c.ExecuteRegisterGitIdentity(params, req.ID)

	case "env.checkout":
		var params CheckoutParams
		rawBytes, err := json.Marshal(req.Params)
		if err != nil {
			if req.ID != nil {
				c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			}
			return
		}
		if err := json.Unmarshal(rawBytes, &params); err != nil {
			if req.ID != nil {
				c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			}
			return
		}

		go c.ExecuteCheckout(params, req.ID)
	case "env.launch_acp_agent":
		var params LaunchAcpAgentParams
		rawBytes, err := json.Marshal(req.Params)
		if err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		if err := json.Unmarshal(rawBytes, &params); err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}

		go c.ExecuteLaunchAcpAgent(params, req.ID)

	case "env.acp_prompt":
		var params AcpPromptParams
		rawBytes, err := json.Marshal(req.Params)
		if err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		if err := json.Unmarshal(rawBytes, &params); err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}

		go c.ExecuteAcpPrompt(params, req.ID)

	case "env.cancel_agent":
		var params CancelAgentParams
		rawBytes, err := json.Marshal(req.Params)
		if err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		if err := json.Unmarshal(rawBytes, &params); err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}

		go c.ExecuteCancelAgent(params, req.ID)

	case "env.read_file_slice":
		var params ReadFileSliceParams
		rawBytes, err := json.Marshal(req.Params)
		if err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		if err := json.Unmarshal(rawBytes, &params); err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		go c.ExecuteReadFileSlice(params, req.ID)

	case "env.get_logs":
		var params GetLogsParams
		rawBytes, err := json.Marshal(req.Params)
		if err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		if err := json.Unmarshal(rawBytes, &params); err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		go c.ExecuteGetLogs(params, req.ID)

	case "env.git_push":
		var params GitPushParams
		rawBytes, err := json.Marshal(req.Params)
		if err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		if err := json.Unmarshal(rawBytes, &params); err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		go c.ExecuteGitPublish(params, req.ID)

	case "env.git_observe_publish":
		var params GitObservePublishParams
		if req.Params != nil {
			rawBytes, err := json.Marshal(req.Params)
			if err != nil {
				c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
				return
			}
			if err := json.Unmarshal(rawBytes, &params); err != nil {
				c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
				return
			}
		}
		go c.ExecuteGitObservePublish(params, req.ID)

	case "env.git_rebase":
		var params GitRebaseParams
		rawBytes, err := json.Marshal(req.Params)
		if err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		if err := json.Unmarshal(rawBytes, &params); err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		go c.ExecuteGitRebase(params, req.ID)

	case "env.git_set_remote":
		var params GitSetRemoteParams
		rawBytes, err := json.Marshal(req.Params)
		if err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		if err := json.Unmarshal(rawBytes, &params); err != nil {
			c.sendErrorResponse(req.ID, -32602, "Invalid parameters", err.Error())
			return
		}
		go c.ExecuteGitSetRemote(params, req.ID)

	default:
		c.sendErrorResponse(req.ID, -32601, "Method not found", fmt.Sprintf("Unsupported method: %s", req.Method))
	}
}

func (c *Client) sendErrorResponse(id interface{}, code int, message string, data interface{}) {
	if id == nil {
		return
	}
	resp := JsonRpcResponse{
		JsonRPC: "2.0",
		Error: &JsonRpcError{
			Code:    code,
			Message: message,
			Data:    data,
		},
		ID: id,
	}
	if err := c.writeJSON(resp); err != nil {
		log.Printf("[WS] sendErrorResponse id=%v failed: %v", id, err)
	}
}

func (c *Client) sendSuccessResponse(id interface{}, result interface{}) {
	if id == nil {
		return
	}
	raw, err := json.Marshal(result)
	if err != nil {
		log.Printf("Failed to marshal response result: %v", err)
		return
	}
	resp := JsonRpcResponse{
		JsonRPC: "2.0",
		Result:  raw,
		ID:      id,
	}
	if err := c.writeJSON(resp); err != nil {
		log.Printf("[WS] sendSuccessResponse id=%v failed: %v", id, err)
	}
}

// RequestPermission forwards an ACP permission request to the control plane as
// a HITL approval and blocks until the user resolves it or it is
// cancelled/timed out. The returned optionId is the user's selection; empty
// means the request was cancelled.
func (c *Client) RequestPermission(req acp.PermissionRequest) (string, error) {
	message := req.Title
	if message == "" {
		message = req.Command
	}
	actionID := req.ActionID
	if actionID == "" {
		// Some agents omit the required toolCallId; synthesise an id so the
		// request still resolves, and report it loudly.
		actionID = newHitlID()
		msg := fmt.Sprintf("[WARN] ACP violation: session/request_permission without toolCallId (command=%q title=%q) — synthesised actionId=%q, approval will not attach to a tool activity",
			req.Command, req.Title, actionID)
		log.Printf("%s", msg)
		c.SendOutput(msg, "stderr")
	}
	c.mu.Lock()
	execID := c.currentExecutionID
	c.mu.Unlock()
	result, err := c.requestHitl(HitlActivityParams{
		ActionID:    actionID,
		Message:     message,
		Kind:        HitlApproval,
		ExecutionID: execID,
		Command:     req.Command,
		Title:       req.Title,
		ToolKind:    req.Kind,
		Options:     convertPermissionOptions(req.Options),
		Diff:        req.Diff,
		Locations:   convertLocations(req.Locations),
	})
	if err != nil {
		return "", err
	}
	switch result.Response {
	case HitlApproved:
		return result.OptionID, nil
	case HitlDeclined, HitlCancelled:
		return "", fmt.Errorf("permission request cancelled")
	default:
		return "", nil
	}
}

// newHitlID returns a random correlation id for HITL requests that carry no
// agent-side toolCallId.
func newHitlID() string {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("hitl-%d", time.Now().UnixNano())
	}
	return "hitl-" + hex.EncodeToString(b)
}

// CreateElicitation forwards an ACP elicitation/create request to the control
// plane as a HITL question and blocks until the user answers or it is
// cancelled/timed out.
func (c *Client) CreateElicitation(req acp.ElicitationRequest) (acp.ElicitationResult, error) {
	c.mu.Lock()
	execID := c.currentExecutionID
	c.mu.Unlock()
	result, err := c.requestHitl(HitlActivityParams{
		ActionID:    req.ElicitationID,
		Message:     req.Message,
		Kind:        HitlQuestion,
		ExecutionID: execID,
		Form:        req.RequestedSchema,
	})
	if err != nil {
		return acp.ElicitationResult{}, err
	}
	switch result.Response {
	case HitlAnswered:
		return acp.ElicitationResult{Action: "accept", Content: result.Content}, nil
	case HitlDeclined:
		return acp.ElicitationResult{Action: "decline"}, nil
	default:
		return acp.ElicitationResult{Action: "cancel"}, nil
	}
}

// requestHitl sends one env.hitl_activity to the control plane and waits for
// either the immediate acknowledgement result (auto-resolved requests) or the
// asynchronous env.hitl_resolved notification. A dropped connection does not
// abort the wait: re-registration replays the decision.
func (c *Client) requestHitl(req HitlActivityParams) (HitlActivityResult, error) {
	c.mu.Lock()
	id := c.nextID
	c.nextID++
	rpcCh := make(chan *JsonRpcResponse, 2)
	actionCh := make(chan HitlActivityResult, 2)
	permCtx := c.permCtx
	c.pending[id] = rpcCh
	c.pendingHitl[req.ActionID] = actionCh
	c.mu.Unlock()

	cleanup := func() {
		c.mu.Lock()
		delete(c.pending, id)
		if current, ok := c.pendingHitl[req.ActionID]; ok && current == actionCh {
			delete(c.pendingHitl, req.ActionID)
		}
		c.mu.Unlock()
	}

	hitlDebug := func(format string, args ...interface{}) {
		msg := fmt.Sprintf(format, args...)
		log.Printf("%s", msg)
		if c.debug {
			c.SendOutput(msg, "stdout")
		}
	}

	reqMsg := JsonRpcRequest{
		JsonRPC: "2.0",
		Method:  "env.hitl_activity",
		Params:  req,
		ID:      id,
	}

	hitlDebug("[HITL] requestHitl: sending env.hitl_activity to control plane, id=%d, kind=%q, actionId=%q", id, req.Kind, req.ActionID)
	if err := c.writeJSON(reqMsg); err != nil {
		hitlDebug("[HITL] requestHitl: failed to send request to control plane: %v", err)
		cleanup()
		return HitlActivityResult{}, err
	}

	hitlDebug("[HITL] requestHitl: request sent, waiting for decision (id=%d)", id)

	timer := time.NewTimer(c.Timeouts.PermissionTimeout)
	defer timer.Stop()
	for {
		select {
		case res := <-actionCh:
			cleanup()
			hitlDebug("[HITL] requestHitl: decision received (id=%d, response=%q)", id, res.Response)
			return res, nil
		case resp := <-rpcCh:
			if resp.Error != nil {
				// A reconnect tears down the RPC waiter. Keep the action channel open so the
				// re-registration replay can still deliver the decision.
				if resp.Error.Code == -32000 && strings.Contains(strings.ToLower(resp.Error.Message), "connection") {
					hitlDebug("[HITL] requestHitl: connection dropped (id=%d); waiting for reconnect replay", id)
					continue
				}
				hitlDebug("[HITL] requestHitl: response contains error [%d]: %s", resp.Error.Code, resp.Error.Message)
				cleanup()
				return HitlActivityResult{}, fmt.Errorf("hitl request error [%d]: %s", resp.Error.Code, resp.Error.Message)
			}
			var res HitlActivityResult
			if err := json.Unmarshal(resp.Result, &res); err != nil {
				hitlDebug("[HITL] requestHitl: failed to parse result: %v, raw=%s", err, string(resp.Result))
				cleanup()
				return HitlActivityResult{}, fmt.Errorf("failed to parse hitl result: %w", err)
			}
			cleanup()
			hitlDebug("[HITL] requestHitl: decision received (id=%d, response=%q)", id, res.Response)
			return res, nil
		case <-permCtx.Done():
			hitlDebug("[HITL] requestHitl: CANCELLED (id=%d)", id)
			cleanup()
			return HitlActivityResult{}, fmt.Errorf("hitl request cancelled")
		case <-timer.C:
			hitlDebug("[HITL] requestHitl: TIMEOUT waiting for HITL response (id=%d)", id)
			cleanup()
			return HitlActivityResult{}, fmt.Errorf("timeout waiting for hitl response")
		}
	}
}

// convertPermissionOptions maps the acp option set onto the rpc wire type.
func convertPermissionOptions(options []acp.PermissionOption) []PermissionOption {
	out := make([]PermissionOption, 0, len(options))
	for _, opt := range options {
		out = append(out, PermissionOption{OptionID: opt.OptionID, Name: opt.Name, Kind: opt.Kind})
	}
	return out
}
