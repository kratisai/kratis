# Sandbox Provisioning

A sandbox is an isolated execution environment that runs a single `kratis-connector`
process. The control plane provisions the sandbox, then drives everything else over the
connector's `/ws/env` WebSocket using the `env.*` methods in
[`protocol/environment/openrpc.yaml`](../../protocol/environment/openrpc.yaml). The
connector, in turn, supervises one ACP agent (see [`acp-lifecycle.md`](acp-lifecycle.md)).

## Provider model

The control plane talks to sandboxes only through the
[`SandboxProvider`](../../control-plane/src/main/java/com/kratisai/controlplane/service/SandboxProvider.java)
interface. Every method takes or returns an environment id. The provider owns all Docker
detail: container, network and image names, labels, and ownership checks. Callers never see
them. The interface has these operations:

- `spawnSandbox`, `initializeWorkspace`, `resume`: start a runtime.
- `isSandboxRunning`, `isRetained`: report state.
- `suspend`: snapshot the runtime, then remove compute.
- `terminateSandbox`: remove compute only. Idempotent. Throws if any compute remains.
- `destroy`: `terminateSandbox`, then remove the snapshot.
- `removeSnapshot`: remove the snapshot if this instance owns it.
- `listActiveEnvIds`: environments with compute. `listRetainedEnvIds`: environments with a
  retained snapshot, whatever their row status.
- `captureSidecarLogs`, plus a provider `type`.

Beans implement the interface and are resolved by type in
[`SandboxOrchestratorService`](../../control-plane/src/main/java/com/kratisai/controlplane/service/SandboxOrchestratorService.java).
`ExecutionProviderType` currently has one value, `DOCKER`. Teams name providers in the
`environment_providers` table (`EnvironmentProvider`), each with an optional
`dockerImage`.

Every infrastructure handle — container ids, resource names, snapshot images — stays
inside the provider. The control plane stores no container identity: the provider
resolves resources from the environment id (deterministic names, with container labels as
the discovery mechanism), which is what lets lifecycle reconciliation survive control
plane restarts.

This split is the extension point for new providers: a future provider is a new
`SandboxProvider` bean with a new `ExecutionProviderType`. Everything below under
"Provisioning contract" and "Security boundary" is provider-agnostic.

## Provisioning contract

[`SandboxProvisioningService`](../../control-plane/src/main/java/com/kratisai/controlplane/service/SandboxProvisioningService.java)
drives every sandbox the same way, independent of provider:

1. Resolve the provider by type; `spawnSandbox` creates the runtime resources for the
   environment.
2. `initializeWorkspace` injects and starts the connector with the environment's
   registration token.
3. The connector connects to `/ws/env` and registers (`env.register`).
4. Register the git author identity (`env.registerGitIdentity`) — always, with or
   without a credential. The connector persists `user.name`/`user.email` to
   `$HOME/.gitconfig`, so every git call in the sandbox sees it and it survives
   connector restarts.
5. Deploy Git auth when the repository carries a credential (`env.registerGitAuth`)
   — PAT credential-helper or in-memory `ssh-agent`, exported process-wide. The
   generated credential gitconfig includes `$HOME/.gitconfig`, so the identity
   stays visible while `GIT_CONFIG_GLOBAL` is active.
6. Prepare the workspace: `env.checkout` for an existing repository, or `env.exec`
   `git init` for a new repository; both verify `git rev-parse --verify HEAD`.
7. Write the canonical task file (`env.exec` chunked base64 to
   `workspace/.kratis/ACTIVE_TASK.md`, git-ignored).
8. Run harness setup commands (`env.exec` ×N).
9. Launch the ACP agent (`env.launch_acp_agent`).

Steps 3–8 are connector-side behaviour; see [`acp-lifecycle.md`](acp-lifecycle.md) and
[`e2e-agent-orchestration.md`](e2e-agent-orchestration.md) for the full sequence.

## Security boundary

These invariants hold for every provider:

- The image/environment carries no credentials at launch.
- All sensitive material (Git credentials, the LiteLLM virtual key, the connector
  token) is delivered after the connector's WebSocket handshake.
- Git credentials live only in connector memory (credential-helper socket /
  `ssh-agent`), never on the sandbox filesystem or in the agent's environment.
- The agent reaches the model provider only through a session-scoped LiteLLM virtual
  key — real provider keys never enter the sandbox.
- The sandbox has no path to the host Docker daemon or the host filesystem.

## LiteLLM gateway

The control plane registers models at runtime and mints a transient virtual key per
execution (scoped to the execution's models and duration). The connector injects
`${LLM_BASE_URL}`, `${VIRTUAL_KEY}`, and `${LLM_MODEL}` into the harness. LiteLLM records
usage and spend in PostgreSQL (Redis-free) and the key is revoked on termination.

Each key lives inside a `sandbox_execution_usage` segment. A segment opens when the
agent launches (`SandboxProvisioningService.launchAgent` reuses an active key when one
exists, else it mints a key and calls `startUsage`). Sealing a segment sets `revokedAt`
on the usage row. The execution totals are the sum of all segments. A resume opens a new
segment with a fresh key, because sleep seals the old one.

Sleep seals through `SandboxExecutionSuspendedEvent`, not through the complete event.
`sleepEnvironmentInternal` publishes it for the latest execution after the `SLEEPING`
commit. `suspendAfterLostRuntime` publishes it for each `IDLE` execution that stays
resumable (`RUNNING` executions seal through the complete event on the same path).
`SandboxExecutionService` handles the event `AFTER_COMMIT` on a worker thread. It seals
the segment first, waits `usageFinalizeDelay`, fetches final usage from LiteLLM, applies
the totals, publishes `ExecutionStatusChangedEvent`, and revokes the key. A fast resume
inside the delay mints a fresh key because the old segment is already sealed. The usage
refresher cancels its trailing refresh on suspend, so no refresh races the revoke.

The UI gets no `execution_complete` on sleep. The sleep transaction already sends
`TeamEntityChangedEvent(ENVIRONMENTS)` and `ExecutionStatusChangedEvent`, which refresh
the environment list and the chat executions. The async finalize sends a second
`ExecutionStatusChangedEvent` when the totals land.

## Cleanup and reconnect

On completion, termination, or reconnect-lease expiry the control plane:

1. Sends `env.terminate` (connector cancels and stops the agent, deliberately leaving the ACP
   session on disk so a later resume can re-attach).
   The resulting `env.complete` is marked `terminated: true`, so a deliberate stop (including
   the idle-sleeper's gentle pause) is not recorded as an agent failure; the execution settles to
   `IDLE`, not `COMPLETED`.
2. Revokes the active transient LiteLLM virtual key and seals its usage segment.
3. Calls `destroy` on the provider (or `terminateSandbox` to keep a suspend snapshot) —
   idempotent, even if an earlier step fails.

A user **halt** is the single terminate action: `POST /api/v1/chats/{chatId}/executions/{executionId}/halt`.
- Sandbox: marks the execution `COMPLETED`, sets the environment `TERMINATED`, then calls
  `destroy`. It needs no WebSocket session, so a sleeping or disconnected sandbox can always
  be purged. If `destroy` fails, the sweep retries.
- Workspace connector: marks the execution `COMPLETED` and best-effort sends `env.terminate`.
  The connector stays `CONNECTED`.

A transient `/ws/env` disconnect does not terminate the agent or discard its session; the
connector reconnects and re-registers within the lease. See
[`acp-lifecycle.md`](acp-lifecycle.md) for reconnect and lease behaviour.

## Environment registration

The connector registers over the environment WebSocket with `env.register`. A first-time
registration flips the environment to connected and publishes `EnvironmentRegisteredEvent`,
which relaunches the agent on a resumed sandbox. A reconnect (`isReconnect: true`) replaces
the session mapping and rebinds pending human-in-the-loop requests, but never publishes the
registered event, so no second relaunch fires.

## Suspend and resume

Suspend commits the runner container to a snapshot image and tears the runtime down;
resume restores that snapshot as a fresh container and starts a **new** connector process.
`SandboxProvisioningService.reprovisionEnvironment` reuses steps 1–2 (`resume` +
`initializeWorkspace`) of `provisionEnvironment`.

Because git credentials live only in connector memory, a resumed connector starts
without them. On every fresh `env.register` — spawn or resume — the control plane
re-deploys the git identity and auth for the environment's active execution (steps 4–5)
before dispatching any execution, so publishing works after a sandbox wakes. Transient
reconnects keep the original connector process and its credentials, so they are left
untouched.

A resume never re-dispatches `env.checkout`, so the connector's in-memory diff base
branch is gone. The connector persists the checked-out branch to
`<credentials-dir>/diff_base_branch` at checkout and reloads it on start; when it is
absent, `origin/HEAD` (git's own default branch) is used. A workspace with an origin
remote is therefore never treated as new and never reports the whole tree in the diff.
See [`publish-rebase-flow.md`](publish-rebase-flow.md).

When the latest execution for a resumed environment is `IDLE` (a paused sandbox), the fresh
`env.register` also **relaunches the agent**, after redeploying the git identity and auth (the new
connector holds no credentials, and the relaunched agent needs them). The connector reads the ACP session id it persisted
at `<credentials-dir>/acp_session_id` (inside the sandbox, so it survives the snapshot and is
never seen by the control plane) and re-attaches with `session/resume` when the agent advertises
`sessionCapabilities.resume`, else `session/load` when it advertises `loadSession`, else falls
back to `session/new`. On re-attach the connector reports `env.acp_initialized` with
`relaunch: true`, so the control plane does not re-dispatch the task prompt; the execution stays
`IDLE` and steerable.

A resume mints a **fresh** LiteLLM virtual key (the previous one was revoked on sleep) and opens
a new `sandbox_execution_usage` segment for it. The execution's reported totals are the sum of all
segments, so usage from every run lifetime aggregates rather than resetting. Each segment records
its own model name, which also leaves room for per-segment model changes later.

Execution duration is tracked as **active time**: `previous_active_ms` holds the total of closed
`RUNNING` segments and `active_since` marks the start of the open segment, so the live total is
`previous_active_ms + (now - active_since)`. Time spent `IDLE` (between turns, or paused while the
sandbox sleeps) is excluded, so the duration shown for a resumed execution reflects work actually
done rather than wall-clock elapsed time. Clients tick this count locally from the two fields (as
`previousActiveSeconds` + `activeSince` in the DTO); the server never republishes per second.
`completedAt` is no longer used as a duration anchor and is left unset on a pause.

## Lifecycle

`EnvironmentLifecycleService` changes environment status. `ExecutionLifecycleService`
changes execution status. Neither writes the other's rows.

### Environment states

| Status | Compute | Snapshot | Meaning |
|--------|---------|----------|---------|
| `PENDING_CONNECT` | Being created | Present only when resuming | Sidecar not yet registered |
| `CONNECTED` | Present | Present only after a prior suspend | Runtime live |
| `SLEEPING` | Absent | Present | Resumable |
| `TERMINATED` | Absent | Absent | Final |

The API returns the stored status unchanged. Connection is a separate `connected` flag: true when
the status is `CONNECTED` and the heartbeat is at most 60 seconds old. The UI shows it only while an
execution is `RUNNING` or `IDLE`.

```mermaid
stateDiagram-v2
    [*] --> PENDING_CONNECT : create / spawn
    PENDING_CONNECT --> CONNECTED : sidecar registers
    SLEEPING --> PENDING_CONNECT : user resume
    CONNECTED --> SLEEPING : idle 20m, quiet, no pending HITL
    SLEEPING --> TERMINATED : retention 30d
    PENDING_CONNECT --> SLEEPING : lost runtime, snapshot exists
    PENDING_CONNECT --> TERMINATED : lost runtime, no snapshot
    CONNECTED --> SLEEPING : lost runtime, snapshot exists
    CONNECTED --> TERMINATED : lost runtime, no snapshot
    TERMINATED --> [*]
```

### Clocks

Each transition sets `state_changed_at`. The sweep uses this column as its only clock:

- `CONNECTED`: idle time, against `idle-timeout` (20 minutes). The clock starts when the
  last `RUNNING` execution stops.
- `PENDING_CONNECT`: spawn or resume age, against `connect-timeout` (5 minutes).
- `SLEEPING`: time since suspend, against `retention-period` (30 days). Each resume
  restarts it.

### Execution states

- `RUNNING` → `IDLE`: the agent finishes a turn. A hung execution is stopped by the user
  (Terminate) or by the connector going away.
- Only a new user prompt returns `IDLE` to `RUNNING`. Sidecar activity does not.
- Sleep does not change execution status.
- `UNKNOWN`: the outcome was not observed, because the runtime was lost or the
  environment was terminated.

### Pending human answers

`PendingHitlRegistry` holds approval and question requests that wait for a user answer.
It answers one question: does an execution still wait for a human.
`ExecutionLifecycleService.hasPendingHitl` checks every `RUNNING` and `IDLE` execution
of an environment. The idle sweep skips an environment with a pending request, so sleep
never strands a turn that waits for the user.

### Lost runtime

A runtime is lost when a `PENDING_CONNECT` environment does not register within
`connect-timeout`, or a `CONNECTED` environment has no runner past `startup-grace`
(30 seconds). The sweep:

1. Calls `terminateSandbox`. It never commits a snapshot.
2. Sets `SLEEPING` if a snapshot exists, otherwise `TERMINATED`.
3. Sets `RUNNING` executions to `UNKNOWN`. `IDLE` executions stay `IDLE` for `SLEEPING`.

### Reaping

The sweep keys all cleanup on the environment row:

| Row | Compute | Snapshot |
|-----|---------|----------|
| `PENDING_CONNECT`, `CONNECTED` | Kept. Removed only by the lost runtime rule. | Kept |
| `SLEEPING` | Owned leftovers removed. Status unchanged. | Kept |
| `TERMINATED` | Owned resources removed | Owned snapshot removed |
| No row | Owned resources removed after `startup-grace` | Owned snapshot removed after `startup-grace` |

The sweep never deletes an environment row. Only the user delete action does, and it is allowed
only for a `TERMINATED` sandbox or for a connector that is not connected and has no active
execution. Any other row returns `409 Conflict`. A snapshot
without the `kratis.instance.id` label predates ownership tracking, so the sweep keeps it.
The sweep waits 2 minutes after boot so sidecars can reconnect.

## Current provider: Local Docker

[`LocalDockerSandboxProvider`](../../control-plane/src/main/java/com/kratisai/controlplane/service/LocalDockerSandboxProvider.java)
(`ExecutionProviderType.DOCKER`) manages sandboxes on the local Docker daemon. Per
sandbox it creates three labelled resources:

1. A Docker network (`kratis-net-<envId>`).
2. A rootless Docker-in-Docker sibling (`kratis-dind-<envId>`, `docker:dind-rootless`,
   `seccomp=unconfined`, `systempaths=unconfined`) with `/dev/net/tun` and unprivileged
   user namespaces.
3. The runner container (`kratis-sandbox-<envId>`) with
   `DOCKER_HOST=tcp://<dind>:2375` so the agent can run Docker/Testcontainers against the
   rootless daemon, isolated from the host.

Every resource carries `kratis.instance.id` and `kratis.sandbox.id` labels. Cleanup
checks the instance label before it removes anything. See [Reaping](#reaping).

**DinD security flags:** the sibling needs `systempaths=unconfined`. Docker's default
masked paths leave non-empty, writable child mounts under `/proc`; the kernel then refuses
a nested procfs mount over them (`fs_fully_visible`), runc inside rootlesskit fails with
`error mounting "proc" to rootfs at "/proc": operation not permitted`, and no inner
container can start. Removing the masks is the only relaxation: the sibling still runs as
uid 1000 with `CapEff: 0` in the host user namespace, sees no host devices, and holds no
host socket. It is never `--privileged` — privilege would expose host block devices to the
agent through the sibling's unauthenticated Docker API. Capabilities are also never
dropped, because rootlesskit's setuid `newuidmap` needs `SETUID`/`SETGID` left in the
bounding set. On kernels where AppArmor gates unprivileged user namespaces
(`kernel.apparmor_restrict_unprivileged_userns=1`) the sibling additionally gets
`apparmor=rootlesskit`.

**Image resolution:** `kratis-runner-base:latest` by default, or the team provider's
`dockerImage` (e.g. a DevContainer image) when set.

**Runner security flags:** `--cap-drop=ALL` re-grants only
`CHOWN`, `DAC_OVERRIDE`, `FOWNER`, `FSETID`, `KILL`, `SETGID`, `SETUID`, `AUDIT_WRITE`
(the package-management minimum) and runs as the non-root user (`--user=1000`). The host
Docker socket is never mounted. Testcontainers needs no capability in the runner: it is a
plain API client of the sibling daemon, so `CAP_SYS_ADMIN` there would buy nothing and
weaken the boundary.

**Testcontainers reachability:** inner containers live in rootlesskit's private network
namespace, so their own IPs are not routable from the runner. Published ports are:
rootlesskit's port driver forwards them into the sibling's namespace, which is why the
runner gets `TESTCONTAINERS_HOST_OVERRIDE=<dind>` and connects to `<dind>:<mappedPort>`.
The sibling's dockerd is also started with
`--host-gateway-ip=<gateway of the sandbox network>`, so `host.docker.internal` inside the
sibling's containers — including nested sandboxes — resolves to the machine running the
control plane, which is what the connector's `--server-url` depends on. The docker default
(gateway of the sibling's own bridge) points at the sibling's daemon instead, which nothing
outside the sibling listens on.

**Container-to-container addressing:** three consumers, three different addresses, published by
`PostgresTestInitializer` for the test suite.

- *Inner container → inner container* (LiteLLM reaching Postgres) resolves peers by alias on a
  fixed-name user network (`kratis-test-net`): `postgresql://test:test@postgres:5432/litellm`.
  Do not replace this with the bridge gateway plus published port (`172.17.0.1:<mapped>`): that
  listener exists only in the sibling's parent namespace, so from inside LiteLLM it refuses the
  connection and Prisma dies with `httpx.ConnectError`, which surfaces as
  `Container startup failed for image ghcr.io/berriai/litellm`, then a
  class-initialization error in `PostgresTestInitializer`, then cascading Spring context
  failures. The network name is fixed so the container reuse hash stays stable across runs.
- *Test JVM → LiteLLM* (`kratis.litellm.base-url`) must be reachable from the JVM's own
  namespace: `getHost()`, which honours `TESTCONTAINERS_HOST_OVERRIDE` (the sibling name in a
  sandbox, localhost on a developer host).
- *Container → the test JVM* (LiteLLM reaching a `WireMockLlmServer` in the runner) must use
  the **runner's own address**, not the inner gateway and not `host.docker.internal`.
  `kratis.litellm.container-host` carries it: the bridge gateway on a developer host, the
  JVM's own address when it runs inside a container. `WireMockLlmServer.getBaseUrl(...)` reads it.
- *Spawned sandbox agent → LiteLLM* (`kratis.litellm.sandbox-base-url`) must be reachable from
  inside a sandbox. Spawned sandboxes sit on the same network as the sibling, so they address
  LiteLLM through its published port on the sibling — the same value as `getHost()` when the
  control plane itself runs in a sandbox, and the gateway plus published port on a developer
  host. Do not use a container IP: Docker isolates traffic between different bridge networks,
  so a peer IP is unreachable from a container on another network.

**Testcontainer lifecycle:** `TESTCONTAINERS_RYUK_DISABLED=true` because Ryuk cannot reach
the sibling daemon. With no reaper, every test JVM would leave its Postgres and LiteLLM
containers behind inside the dind, so the runner also gets
`TESTCONTAINERS_REUSE_ENABLE=true`. That satisfies the `testcontainers.reuse.enable` gate
that `PostgresTestInitializer`'s `withReuse(true)` call requires — without it, reuse is
silently disabled (a log warning, not an error) and the containers accumulate.

**Registry cache:** the sibling is started with `--registry-mirror` from
`kratis.sandbox.docker.registry-mirror`. The dev compose stack publishes its cache on 5002 and deploy
on 5001, so each profile must point at the port its stack actually publishes — the provider
warns at spawn time when the mirror refuses connections, and dockerd otherwise silently falls
back to docker.io, so a stale port looks like slow pulls rather than an error.

**Volume cleanup:** `docker:dind-rootless` declares `VOLUME /home/rootless/.local/share/docker`
and `VOLUME /var/lib/docker`, so Docker creates two anonymous volumes per sibling holding
the dind image store (hundreds of MB to GB). Teardown must use `docker rm -f -v`; without
`-v` every provisioned sandbox leaks those volumes permanently.

**Image contract:** `/kratis/workspace` and `/kratis/logs` must exist and be writable by
the sandbox's non-root user at image build time; ownership is never fixed up at runtime
(see `build/Dockerfile.runner-base`).

**Connector injection:** the provider resolves the host binary
(`KRATIS_CONNECTOR_PATH` → `kratis.connector.path` → `sidecar/kratis-connector`),
`docker cp`s it into the runner, then starts it with `docker exec` (`--mode=sidecar`,
`--server-url`, `--token`, `--log-file /kratis/logs/sidecar.log`).

**Teardown:** copy the connector log only when the sidecar never streamed its output
(never connected, or crashed/disconnected) or sandbox debug mode is on. Teardown then
verifies that no owned element remains. If one remains, the call fails and the sweep retries.
