---
name: sandbox-run-application
description: Start an application in the Kratis sandbox, keep it alive across agent turns, prove it is up, and publish a user-openable preview URL. Use when dev-testing a server, a web app, or any long-running process, when a task needs a running service, or when the user asks to run or preview the app.
---

# Run an application in the sandbox

The sandbox is a non-root container with no published ports. A user cannot open a sandbox
service in a browser directly. When the app is up, publish a preview through the control plane so
the user gets a working URL (see `sandbox-publish-to-user`). Read `references/reachability.md`
before you rely on any address.

## 1. Start so the process survives

A plain `cmd &` and `nohup cmd &` die between turns, because each command runs in its own
process group that is torn down. Use `setsid` to start a new session:

```
setsid <command> > /tmp/<app>.log 2>&1 < /dev/null
```

- Bind to `0.0.0.0` so the sandbox-net IP and inner containers can reach the service.
- Use a port of 1024 or higher. That is the portable rule even though this image allows
  lower ports.

## 2. Poll until it is up

`curl` has no wait flag. Poll with a bounded loop:

```
for i in 1 2 3 4 5 6 7 8 9 10; do curl -sf -m 2 http://127.0.0.1:3000/ >/dev/null && break; sleep 1; done
```

If the loop ends without a hit, read the log tail and stop with a clear message:

```
tail -n 40 /tmp/<app>.log
```

## 3. Verify from inside

- `curl -sS -m 3 http://127.0.0.1:3000/` - the runner loopback.
- `curl -sS -m 3 http://$(hostname -I | awk '{print $1}'):3000/` - the sandbox-net IP.
- `curl -sS http://host.docker.internal:8080/actuator/health` - the control plane is reachable.

## 4. Publish a preview URL to the user

Once the app answers, publish it so the user can open it. Drop a manifest into the publish
spool at `.kratis/publish` (inside the workspace so harnesses restricted to it can write there,
and git-excluded so it never touches the diff); the sidecar polls it and forwards it to the
control plane, which owns the address translation and records the URL in the task activity log:

```
mkdir -p .kratis/publish
cat > .kratis/publish/preview-1.json <<'EOF'
{"port": 3000, "label": "Web app", "path": "/health"}
EOF
```

The control plane returns and logs a URL like `/preview/<environmentId>/3000/health` on the
control-plane origin. Do not hand the user a raw sandbox IP or port — those are not reachable
from the user's browser. See `sandbox-publish-to-user` for the full contract.

## 5. Stop cleanly

Find and kill the process, then confirm the port is released:

```
pkill -f '<command>' ; sleep 1 ; curl -sS -m 2 http://127.0.0.1:3000/ ; echo "exit=$?"
```

`curl` exit 7 means the port is released. Do this between runs so no orphan holds the port.

## 6. Keep evidence out of the diff

Write transient artifacts (logs, HAR, coverage, test output) under `.kratis/`, for example
`.kratis/evidence/`. That directory is git-excluded, so the artifacts survive the suspend snapshot
but never enter the execution diff. Only intent-add an artifact (`git add -N <path>`) when the user
wants it as a deliverable in the change set. The sandbox suspends after 20 minutes idle and a
runtime server dies with the runtime; only files in the snapshot survive.

## Common failures

- `curl: (7) Failed to connect` after `cmd &` - the process died with the turn. Use `setsid`.
- `curl: (28)` timeout to a container IP - use `$TESTCONTAINERS_HOST_OVERRIDE` with the
  published port, never the container IP.
- An inner container cannot reach the runner by `host.docker.internal` - that points at the
  control plane. Use the runner's sandbox-net IP.
- `pkill` or `ps` missing - install `procps` first (see `sandbox-package-install`).

Address rules and the reachability matrix: `references/reachability.md`.
