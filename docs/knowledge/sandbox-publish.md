# Sandbox publish and reachability

How a sandbox result reaches the user, and what can reach what. The agent-facing instructions
live in the runtime skills `sandbox-run-application`, `sandbox-playwright-e2e`, and
`sandbox-publish-to-user` under `control-plane/skills/`. This file is the design and evidence
reference for humans.

## Publish path (implemented)

The user cannot reach the sandbox network, so publication goes through the control plane, which
owns the sandbox-to-user address translation:

1. The agent drops a manifest into the publish spool `.kratis/publish` (inside the workspace so
   harnesses restricted to it can write there; `.kratis/` is git-excluded, so it never reaches the
   execution diff).
2. The sidecar polls the spool and sends `env.preview` or `env.attachment` over the environment
   WebSocket.
3. The control plane translates the sandbox-local address, records a `PUBLISH` or `ATTACHMENT`
   activity with the control-plane-origin URL, broadcasts it, and the web UI renders a link
   (preview) or an expandable thumbnail (attachment).

- Preview URL: `/preview/<environmentId>/<port>/<path>`. `SandboxPreviewProxyController` proxies it
  to the sandbox-local port. `SandboxPreviewReachability` resolves the runner's address on
  `kratis-net-<envId>`; when the control plane runs in a container it joins that network
  (`docker network connect`) before proxying.
- Attachment: images and markdown only, under 6 MB decoded. Bytes are stored in blob storage under
  `attachments/<environmentId>/<attachmentId>` with a `.content-type` sidecar; the inline view URL
  `/attachments/<environmentId>/<attachmentId>` is a capability URL (unguessable environment and
  attachment ids) so `<img src>` works without an auth header.

Protocol contract: `protocol/environment/openrpc.yaml` (`env.preview`, `env.attachment`).

## Reachability matrix (measured)

| From | To | Address | Works |
|:---|:---|:---|:---|
| runner | own service | `127.0.0.1:<port>` | yes |
| runner | own service | sandbox-net IP `<port>` (`hostname -I`) | yes |
| runner | control plane | `host.docker.internal:8080` | yes |
| runner | inner container | `$TESTCONTAINERS_HOST_OVERRIDE:<mappedPort>` | yes |
| runner | inner container | container IP `<port>` | no (timeout) |
| inner container | runner | runner sandbox-net IP `<port>` | yes |
| inner container | runner | `host.docker.internal:<port>` | no (points at control plane) |
| user browser | control-plane preview | `/preview/<envId>/<port>/**` | yes (proxied) |
| user browser | runner | any direct address | no (no published port) |

Address facts:

- The runner sits on `kratis-net-<envId>` in `10.210.0.0/16`.
- `host.docker.internal` is the network gateway / control plane address, not the runner.
- Inner containers live in the rootless DinD network namespace; their IPs are not routable. Use
  `$TESTCONTAINERS_HOST_OVERRIDE` with the mapped port.

## Why the control plane proxies instead of publishing a port

The runner container is spawned with no `-p` and there is no `env.port_*` RPC, so the user cannot
open a sandbox port directly. Publishing the runner port on the host would leak a host port and
give a per-environment URL. The control-plane proxy keeps one origin, keeps the sandbox address
private, and is the only component that can translate the namespaces.

## Evidence artifacts and the diff

The execution diff is `git diff <base>` (`sidecar/rpc/git.go`), which hides untracked files. An
artifact in the workspace appears in the diff only after `git add -N <path>`. `.kratis/` is
git-excluded, so artifacts there never reach the diff. The publish spool lives at `.kratis/publish`
inside the workspace for harness access, but because `.kratis/` is git-excluded, publications
never touch the diff.
