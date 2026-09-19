# Sandbox Provisioning

A sandbox is an isolated execution environment that runs a single `kratis-connector`
process. The control plane provisions the sandbox, then drives everything else over the
connector's `/ws/env` WebSocket using the `env.*` methods in
[`protocol/environment/openrpc.json`](../../protocol/environment/openrpc.json). The
connector, in turn, supervises one ACP agent (see [`acp-lifecycle.md`](acp-lifecycle.md)).

## Provider model

The control plane talks to sandboxes only through the
[`SandboxProvider`](../../control-plane/src/main/java/com/kratisai/controlplane/service/SandboxProvider.java)
interface — `spawnSandbox`, `initializeWorkspace`, `terminateSandbox`,
`getActiveSandboxIds` — plus a provider `type`. Beans implement the interface and are
resolved by type in
[`SandboxOrchestratorService`](../../control-plane/src/main/java/com/kratisai/controlplane/service/SandboxOrchestratorService.java).
`ExecutionProviderType` currently has one value, `DOCKER`. Teams name providers in the
`environment_providers` table (`EnvironmentProvider`), each with an optional
`dockerImage`.

This split is the extension point for new providers: a future provider is a new
`SandboxProvider` bean with a new `ExecutionProviderType`. Everything below under
"Provisioning contract" and "Security boundary" is provider-agnostic.

## Provisioning contract

[`SandboxProvisioningService`](../../control-plane/src/main/java/com/kratisai/controlplane/service/SandboxProvisioningService.java)
drives every sandbox the same way, independent of provider:

1. Resolve the provider by type; `spawnSandbox` returns a unique container/workspace id.
2. Persist the id, then `initializeWorkspace` (inject and start the connector).
3. The connector connects to `/ws/env` and registers (`env.register`).
4. Deploy Git auth (`env.registerGitAuth`) — PAT credential-helper or in-memory
   `ssh-agent`, exported process-wide; also pins `user.name`/`user.email`.
5. Prepare the workspace: `env.checkout` for an existing repository, or `env.exec`
   `git init` for a new repository; both verify `git rev-parse --verify HEAD`.
6. Run harness setup commands (`env.exec` ×N).
7. Launch the ACP agent (`env.launch_acp_agent`).

Steps 3–7 are connector-side behaviour; see [`acp-lifecycle.md`](acp-lifecycle.md) and
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

## Cleanup and reconnect

On completion, termination, or reconnect-lease expiry the control plane:

1. Sends `env.terminate` (connector cancels/closes the ACP session and stops the agent).
2. Revokes the transient LiteLLM virtual key.
3. Calls `terminateSandbox` on the provider — idempotent, even if an earlier step fails.

A transient `/ws/env` disconnect does not terminate the agent or discard its session; the
connector reconnects and re-registers within the lease. See
[`acp-lifecycle.md`](acp-lifecycle.md) for reconnect and lease behaviour.

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

Every resource carries `kratis.instance.id` and `kratis.sandbox.id` labels for
instance-scoped cleanup (`ZombieContainerCollector`).

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
The same applies to a URL the control plane builds from `getContainerInfo()`: the inner
bridge gateway (e.g. `172.17.0.1`) is not routable from the runner, so anything the control
plane itself must call (e.g. `kratis.litellm.base-url` in `PostgresTestInitializer`) has to
be derived from Testcontainers' `getHost()`, which honours `TESTCONTAINERS_HOST_OVERRIDE`.

**Container-to-container addressing:** three consumers, three different addresses, published by
`PostgresTestInitializer` for the test suite.

- *Inner container → inner container* (LiteLLM reaching Postgres) must use the **peer's own
  IP and internal port**. The bridge gateway plus published port (`172.17.0.1:<mapped>`)
  works on a developer host but that listener only exists in the sibling's parent namespace,
  so from inside LiteLLM it refuses the connection and Prisma dies with
  `httpx.ConnectError`. This is what makes `Container startup failed for image
  ghcr.io/berriai/litellm:main-stable` appear, which then surfaces as a class-initialization
  error in `PostgresTestInitializer` and cascading Spring context failures.
- *Test JVM → LiteLLM* (`kratis.litellm.base-url`) must be reachable from the JVM's own
  namespace: `getHost()`, which honours `TESTCONTAINERS_HOST_OVERRIDE` (the sibling name in a
  sandbox, localhost on a developer host).
- *Container → the test JVM* (LiteLLM reaching a `WireMockLlmServer` in the runner) must use
  the **runner's own address**, not the inner gateway and not `host.docker.internal`.
  `kratis.litellm.container-host` carries it: the bridge gateway on a developer host, the
  JVM's own address when it runs inside a container. `WireMockLlmServer.getBaseUrl(...)` reads it.
- *Spawned sandbox agent → LiteLLM* (`kratis.litellm.sandbox-base-url`) must be reachable from
  inside a sandbox. Set explicitly — never left to fall back to `base-url`, whose host is only
  valid for the JVM itself. On a developer host it is the gateway plus the published port;
  inside a sandbox it is LiteLLM's own container IP on the dind bridge with port 4000.

**Testcontainer lifecycle:** `TESTCONTAINERS_RYUK_DISABLED=true` because Ryuk cannot reach
the sibling daemon. With no reaper, every test JVM would leave its Postgres and LiteLLM
containers behind inside the dind, so the runner also gets
`TESTCONTAINERS_REUSE_ENABLE=true`. That satisfies the `testcontainers.reuse.enable` gate
that `PostgresTestInitializer`'s `withReuse(true)` call requires — without it, reuse is
silently disabled (a log warning, not an error) and the containers accumulate.

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
(never connected, or crashed/disconnected) or sandbox debug mode is on; then `docker rm -f`
the runner and DinD sibling and remove the network.
