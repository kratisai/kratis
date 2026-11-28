# Sandbox reachability reference

Where a service runs and what can reach it.

## The rule

The runner has **no published port**. The user cannot reach a sandbox service from a browser
directly. To give the user a live URL, publish a preview through the control plane (see the
`sandbox-publish-to-user` skill): the control plane proxies a control-plane-origin URL to the
sandbox-local port.

## Matrix

| From | To | Address | Works |
|:---|:---|:---|:---|
| runner | own service | `127.0.0.1:<port>` | yes |
| runner | own service | sandbox-net IP `<port>` (from `hostname -I`) | yes |
| runner | control plane | `host.docker.internal:8080` | yes |
| runner | inner container | `$TESTCONTAINERS_HOST_OVERRIDE:<mappedPort>` | yes |
| runner | inner container | container IP `<port>` | no (timeout) |
| inner container | runner | runner sandbox-net IP `<port>` | yes |
| inner container | runner | `host.docker.internal:<port>` | no (points at control plane) |
| user browser | control-plane preview | `/preview/<environmentId>/<port>/**` | yes (proxied) |
| user browser | runner | any direct address | no (no published port) |

## Address facts

- The runner sits on `kratis-net-<envId>` in `10.210.0.0/16`. Get its IP with `hostname -I`.
- `host.docker.internal` is the network gateway, or the control plane address. It is not the
  runner.
- Inner containers live in the rootless DinD network namespace. Their own IPs are not
  routable. Use the published port on `$TESTCONTAINERS_HOST_OVERRIDE`.

## Evidence for the user

- **Live preview** — publish a preview manifest to `.kratis/publish` (see
  `sandbox-publish-to-user`). The control plane records the URL in the activity log and proxies
  it.
- **Transient artifacts** — write logs, HAR, and coverage under `.kratis/` (for example
  `.kratis/evidence/`). It is git-excluded, so they survive the suspend snapshot but never enter
  the diff. Only intent-add an artifact (`git add -N <path>`) when the user wants it as a
  deliverable. The diff uses `git diff <base>`, which hides untracked files; without `git add -N`
  an intent-added artifact never shows.
