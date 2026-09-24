# E2E ACP Agent Orchestration Sequence

This document describes the production orchestration flow and the end-to-end tests that validate the [ACP client lifecycle](acp-lifecycle.md).

## Components

| Component | Role |
|-----------|------|
| **Control Plane** | Java/Spring Boot API managing executions, environments, model providers, and HITL decisions. |
| **Sandbox Connector (`kratis-connector`)** | Go binary injected into the sandbox. Bridges `/ws/env` to ACP over agent stdio. |
| **Sandbox Container** | Isolated execution environment with `/kratis/workspace` and `/kratis/logs`. |
| **ACP Agent Interface** | Aider, Claude Code, Codex, Gemini, Goose, Mistral, OpenCode, OpenHands, Pi, or Qwen through its ACP interface. |
| **LiteLLM** | Model proxy that exchanges the transient execution key for protected provider access. |
| **WireMock** | Test-only mock model server returning matcher-defined responses. |
| **UI WebSocket Client** | Test fixture subscribing to execution output, activity, HITL, and completion events. |

> **Protocol Specification & Schemas**: The canonical source of truth for all sidecar <-> control-plane JSON-RPC 2.0 methods, parameters, and payload schemas is located in [`protocol/environment/openrpc.json`](../../protocol/environment/openrpc.json) and [`protocol/environment/schemas/`](../../protocol/environment/schemas/). All Java DTO records (`com.kratisai.controlplane.api.wsdto`) and Go sidecar structs (`sidecar/rpc/protocol.go`) are validated against these canonical specifications.

## Primary Sequence

```text
Test             Control Plane       Sandbox/Connector       ACP Agent        LiteLLM
 │                    │                      │                    │                │
 │ POST /executions   │                      │                    │                │
 │───────────────────>│                      │                    │                │
 │                    │ docker run           │                    │                │
 │                    │─────────────────────>│                    │                │
 │                    │ copy/start connector │                    │                │
 │                    │─────────────────────>│                    │                │
 │                    │<──── env.register ───│                    │                │
 │                    │ env.registerGitAuth  │                    │                │
 │                    │─────────────────────>│ deploy auth        │                │
 │                    │ env.checkout (existing repo) or env.exec   │                │
 │                    │    `git init` (new repo)                    │                │
 │                    │─────────────────────>│ prepare workspace  │                │
 │                    │ env.launch_acp_agent │                    │                │
 │                    │─────────────────────>│ setup and launch   │                │
 │                    │                      │───────────────────>│                │
 │                    │                      │ initialize         │                │
 │                    │                      │───────────────────>│                │
 │                    │                      │<────────── result ─│                │
 │                    │                      │ session/new        │                │
 │                    │                      │───────────────────>│                │
 │                    │                      │<────────── result ─│                │
 │                    │<─ env.acp_initialized│                    │                │
 │                    │ env.acp_prompt       │                    │                │
 │                    │ (promptId)           │                    │                │
 │                    │─────────────────────>│ session/prompt     │                │
 │                    │<── prompt accepted ──│───────────────────>│                │
 │                    │                      │                    │ LLM request    │
 │                    │                      │                    │───────────────>│
 │                    │                      │                    │<──── response ─│
 │                    │                      │<── session/update ─│                │
 │                    │<────── env.output ───│                    │                │
 │                    │                      │<── prompt result ──│                │
 │                    │                      │ quiet 10s (activity resets)          │
 │                    │<─ env.acp_prompt_complete (promptId)      │                │
 │ verify output      │                      │                    │                │
 │                    │ env.terminate        │                    │                │
 │                    │─────────────────────>│ cancel/close/exit  │                │
 │                    │<──── env.complete ───│                    │                │
```

## Phase Descriptions

### Phase 0: Test Setup

- PostgreSQL and LiteLLM run in Testcontainers.
- LiteLLM routes the configured Kratis model provider to WireMock.
- The connector WebSocket URL resolves to `/ws/env` from the sandbox network.
- The ACP harness under test is configured with the transient virtual key, model, and LiteLLM URL.

### Phase 1: Environment Provisioning

1. **POST `/executions`** — Create an execution with provider, harness, model, and canvas. The repository is resolved from the SPEC canvas (existing repository bound to the canvas, or a new-repository definition with a user-selected `credentialId`).
2. **Spawn sandbox** — Start the configured image with an idle process.
3. **Initialize workspace** — Create `/kratis/workspace` and `/kratis/logs`, copy `kratis-connector`, and start it with `--mode=sidecar`, `--server-url`, and the environment token.

### Phase 2: Connector Registration and Launch

4. **`env.register`** — Connector authenticates to `/ws/env`.
5. **Connected** — Control Plane persists the environment status and dispatches the execution.
6. **`env.registerGitAuth`** — Control Plane deploys the credential process-wide (single auth mechanism; the connector exports `GIT_CONFIG_GLOBAL` / `SSH_AUTH_SOCK` + `GIT_SSH_COMMAND` into its environment). The generated global gitconfig also pins the git author identity (`user.name`/`user.email`, with the email hostname derived from the control-plane host) so later commits (publish/PR) never fail with "Author identity unknown".
7. **Workspace preparation** — Existing-repository canvases: `env.checkout` clones/fetches the canvas-associated repository. New-repository canvases: `env.exec` runs `rm -rf ./* ./.git; git init && git -c user.name="Kratis" -c user.email="kratis@<control-plane-host>" commit --allow-empty -m "Initial commit"`; both paths then pass `git rev-parse --verify HEAD` verification before launch.
8. **`env.exec` (N×)** then **`env.launch_acp_agent`** — Control Plane runs each harness setup command via `env.exec` (with env persistence), then launches the ACP agent (handshake only).
9. **Setup and launch** — Connector installs the current harness dependencies and starts the ACP agent in `/kratis/workspace`.

### Phase 3: ACP Initialization

8. **`initialize`** — Connector sends the protocol version, client capabilities, and client information.
9. **Initialize result** — Connector validates the returned version and stores agent capabilities.
10. **`session/new`** — Connector sends `/kratis/workspace` as `cwd` and the configured MCP servers.
11. **`env.acp_initialized`** — Connector reports the established session and agent information.
12. **`env.acp_prompt`** — Control Plane supplies the task prompt and a `promptId`; connector acknowledges with `status: accepted` and then sends `session/prompt`.

Failure of `initialize` or `session/new` fails Kratis agent initialization.

### Phase 4: Agent Execution

13. Agent calls the configured model through LiteLLM and WireMock.
14. Agent executes tools, calls advertised client operations when needed, and reports `session/update` events.
15. Connector translates updates into `env.output` or structured activity events. Terminal tool updates with `status: completed` are streamed as `stdout`; updates with `status: failed` are streamed as `stderr`. The extractor supports both the generic `rawOutput.output` field and Codex's `rawOutput.formatted_output` field.
16. Agent returns the prompt response.
17. Connector waits for a 10s quiet window, then sends `env.acp_prompt_complete` with the ACP `stopReason` and the echoed `promptId`. The completion is also recorded as an activity on the control plane so each turn boundary is visible in the activity log.

Normal successful fixtures expect `end_turn`. Other valid reasons are `max_tokens`, `max_turn_requests`, `refusal`, and `cancelled`.

Codex ACP supplies a sandbox policy on each turn, overriding `sandbox_mode` from `$HOME/.codex/config.toml`. Kratis therefore launches Codex ACP with `INITIAL_AGENT_MODE=agent-full-access`. This prevents Codex from nesting bubblewrap inside the Kratis-provided container sandbox.

### Phase 5: HITL Permission Bridge

HITL-specific tests insert this branch while the prompt is active:

```text
ACP Agent          Connector           Control Plane          UI Client
   │ request_permission │                    │                    │
   │  + options[]       │                    │                    │
   │───────────────────>│ permission request │                    │
   │                    │ + options/title/   │                    │
   │                    │   kind/diff        │                    │
   │                    │───────────────────>│                    │
   │                    │                    │<── option select ──│
   │                    │                    │    or cancel       │
   │                    │<── selectedOptionId│                    │
   │<── correlated result                    │                    │
   │   (outcome.selected)                    │                    │
```

The execution remains `RUNNING` while the request is unresolved. The control plane returns the
`optionId` the user selected (allow or reject kind); the connector translates it into
`outcome.selected{optionId}` per ACP. A real cancel (dismissal/timeout/termination) is the only
path that returns `outcome.cancelled` — rejection is no longer collapsed into cancellation.

On timeout the control plane unblocks the agent with `outcome.cancelled` and then dispatches a
system steering turn (`SandboxExecutionService.dispatchSystemSteering`) telling the agent that no
response was received and that this is not a rejection, so it continues the task without the
blocked action. A HITL resolution may also carry an optional `feedback` note, which is delivered
the same way after the sidecar reply.

The control plane owns permission policy:

- `ShellCommandSplitter` splits composite commands into root commands at `&&`, `||`, `;`, `|`,
  and newlines. `HitlRuleService.autoResolve` evaluates team rules per segment. Any denied
  segment denies the whole command. Auto-approve requires a fully trusted parse in which every
  segment is covered by an allow rule. Otherwise the request goes to HITL.
- The splitter marks hidden-command constructs as untrusted (`$(...)`, backticks, heredocs,
  subshells). It also marks segments with env-assignment prefixes, expansions, or redirections.
  Untrusted segments never auto-approve.
- Rules carry a match type. `EXACT` and `PREFIX_WILD` match command text. `TOOL_KIND` matches
  the ACP tool kind of the request (`read`, `edit`, `write`, `delete`, `move`, `search`,
  `execute`, `think`, `fetch`, `switch_mode`, `other`). Non-command kinds (e.g. an agent edit
  tool) are governed exclusively by `TOOL_KIND` rules, because their command text is only a
  synthesized description. A `TOOL_KIND` rule on `execute` resolves every command request.
- The control plane strips agent-offered `allow_always` and `reject_always` options when a
  once-variant exists. Persistent memory is exclusively team HITL rules. Auto-approve picks
  `allow_once` first, so a rule match never seeds agent-side session memory.
- The `execution_hitl_required` payload carries `commandSegments` (`text`, `suggestedRoot`,
  `ruleType`). Command requests get one segment per root command with `ruleType=PREFIX_WILD`.
  Non-command requests get a single segment naming the tool kind with `ruleType=TOOL_KIND`.
  The UI uses them in the "Remember choices" panel. Resolving with `rules` persists the ticked
  or crossed roots as ALLOW or DENY rules of the segment's type.

### Phase 6: Verification and Cleanup

18. Verify expected files under `/kratis/workspace`, streamed output, activity, and WireMock matches.
19. Send `env.terminate`.
20. Connector cancels an active prompt, answers pending permissions as cancelled, closes the ACP session when supported, closes stdin, and escalates process termination if necessary.
21. Connector drains output and sends `env.complete`.
22. Control Plane revokes the transient LiteLLM key and removes the sandbox.

## Core Assertions

| Phase | Assertion |
|-------|-----------|
| Registration | Environment reaches `CONNECTED`. |
| Initialization | `env.acp_initialized` contains a session ID after `initialize` and `session/new`. |
| Prompt | Normal completion returns exactly `end_turn`; every reason belongs to the ACP enum. |
| Output | Expected files, output, activity, and model requests are observed. |
| HITL | Any selected option resumes the agent with `outcome.selected{optionId}` (allow or reject kind); only dismissal/timeout yields `outcome.cancelled`. Timeout additionally dispatches a "no response" steering turn, and the execution returns to `RUNNING`. |
| Cancellation | Pending permission receives cancelled outcome and prompt returns `cancelled`. |
| Reconnect | Agent process and ACP session survive `/ws/env` interruption; registration and event delivery resume within the lease. |
| Cleanup | `env.complete` is received, the virtual key is revoked, and the sandbox is removed. |

## Known race conditions in the ACP-init fan-out (CI-only timeouts)

CI-only `*ExecutionRealTest` timeouts (30–40% of builds, different harness each time, cleared by
rerun) were logical races in the control-plane fan-out path, fixed as follows:

1. **Slow web client back-pressured the sidecar.** `env.output` fan-out ran on the single env
   WebSocket thread; a slow fixture dropped the `execution_acp_initialized` broadcast. **Fix:** a
   dispatcher reads the socket on the WebSocket thread and enqueues handler work per connection over
   `envMessageExecutor`; fan-out is per web-client session over `clientBroadcastExecutor`.
2. **`env.acp_initialized` was all-or-nothing.** A throw in prompt dispatch rolled back the ACP-init
   broadcast and left the execution `RUNNING`. **Fix:** `dispatchAcpPrompt` never throws synchronously.
3. **Failed prompt dispatch silently stalled.** **Fix:** a `status: failed` acknowledgement marks the
   execution `FAILED` (unless already terminal). Dispatch is now fire-and-ack, so a dropped
   connection, request timeout, or control-plane restart is non-fatal: the turn runs in the sidecar
   and its terminal outcome arrives as `env.acp_prompt_complete` (or `env.complete`).
4. **Diagnostic:** waits also accept a persisted `stopReason` and log execution state on timeout.
   `stopReason` set with `acpInitialized` null ⇒ broadcast lost; all null ⇒ triage `sidecar.log`.

## Host Docker Hygiene (local / CI agent machines)

E2E harnesses spawn real containers on the host Docker daemon (`kratis-e2e-test`, `kratis-runner-base`) in addition to reusable Testcontainers (Postgres, LiteLLM). They are critical path tests and must stay green. Timeouts or intermittent agent failures after a killed suite almost always mean **leftover containers or volumes**, not test nondeterminism.

### What owns cleanup

| Resource | Owner | When it runs |
|----------|--------|--------------|
| Sandbox container (`spawnedContainerId`) | `AbstractAgentExecutionRealTest` teardown → `LocalDockerSandboxProvider.terminateSandbox` | `@AfterEach` — **only if the JVM exits the test normally** |
| Testcontainers Postgres / LiteLLM | Testcontainers Ryuk | JVM exit (clean process death) |
| Anonymous volumes from sandboxes | Host `docker volume prune` (manual) | Not automatic |

A hard kill of Maven (`Ctrl-C`, OOM, IDE stop) skips `@AfterEach` and can leave both sandboxes and volumes behind. Ryuk also does not run if the JVM never shuts down cleanly.

### Symptoms → cause

| Symptom | Likely cause |
|---------|----------------|
| `ConditionTimeoutException` on `CONNECTED`, ACP init, or `stopReason` | Orphaned sandbox / port / network contention from a prior run |
| Different harnesses fail on successive full-suite runs | Accumulated host Docker debris, not agent-specific logic |
| `docker events` shows `container kill ... signal=9` + `die (exitCode=137)` + `destroy` ~30-35s after spawn, then `CloseStatus[code=1006]` | **Cross-instance interference.** Sandboxes carry `kratis.instance.id`; current instances ignore others' containers. This now means a legacy instance (pre-scoping, filter `kratis.managed=true`) or two instances sharing `kratis.instance.id`. Tests use `kratis-test`; production must not. |
| `No such container` during file verification | Sandbox already removed or never started cleanly |
| Multi-GB disk growth under Docker | Dangling volumes from terminated sandboxes |

> **Never label `*ExecutionRealTest` failures "flaky".** They are deterministic, environment-sensitive integration tests. Any failure — including in isolation after cleanup — means the host environment is broken (conflicting control-plane instance, leftover state, LiteLLM pollution). Diagnose with `docker events` + `ss -tlnp | grep 8080` + the reset procedure before touching test or production code.

### Reset procedure

```bash
# Exited leftovers
docker ps -a --filter "status=exited" -q | xargs -r docker rm -f

# Any remaining kratis E2E sandboxes
docker ps -a --format '{{.ID}} {{.Image}} {{.Names}}' | grep -E 'kratis-e2e|kratis-runner' \
  | awk '{print $1}' | xargs -r docker rm -f

# Volumes left by removed containers
docker volume prune -f

# Only if reusable Postgres/LiteLLM themselves are dirty (schema / model table)
docker ps --filter "label=org.testcontainers.session.reusable=true" -q | xargs -r docker rm -f

# Stop any conflicting production-profile control plane whose zombie collector kills test sandboxes
ss -tlnp | grep 8080   # if a dev server is listening, it is inert unless running legacy code
                       # (pre-instance-scoping) or configured with kratis.instance.id=kratis-test;
                       # otherwise no action is needed — instance-scoped labels isolate it.
```

Confirm with a single harness before the full suite:

```bash
cd control-plane && ./mvnw test -Dtest=AiderExecutionRealTest
cd control-plane && ./mvnw test   # full suite including all *ExecutionRealTest
```

Operational detail and LiteLLM model-table pollution live in [`docs/skills/control-plane-testing.md`](../skills/control-plane-testing.md) under **Testcontainer Reuse and Restart** / **E2E Docker / sandbox stale state**.
