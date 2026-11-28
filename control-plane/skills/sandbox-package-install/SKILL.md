---
name: sandbox-package-install
description: Install system packages, language toolchains, and project dependencies inside the Kratis sandbox with apt, pip, and npm. Use when a task needs a missing tool, compiler, runtime, or library, when setup fails on a missing command, or before building or testing a project.
---

# Install packages in the sandbox

The sandbox runs as a non-root user (`kratis`, uid 1000) with passwordless `sudo`. The base
image is Debian. A team image may differ, so probe first. Approval prompts are answered via
HITL. Always attempt the command. Never skip work to avoid a prompt.

## 1. Probe before you install

- `command -v apt-get || command -v apk || command -v dnf` - pick the package manager.
- `command -v <tool>` - confirm the tool is really missing.
- `sudo -n true && echo sudo-ok` - confirm sudo works.

If `apt-get` is absent, this is a non-Debian image. Translate each step to `apk` or `dnf`
and continue. Do not assume `apt`.

## 2. System packages (Debian)

- Refresh the list once: `sudo apt-get update`.
- Install with the noninteractive frontend so no prompt blocks the turn:

```
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends <package>
```

- Add `procps` when you need `ps`, `pgrep`, or `pkill`. The image does not ship them.
- After a batch of installs, free the cache so the sleep snapshot stays small:

```
sudo apt-get clean && sudo rm -rf /var/lib/apt/lists/*
```

## 3. Python dependencies

System `pip3` is blocked by PEP 668 (`error: externally-managed-environment`). Use a venv.

- Install venv support once (not preinstalled):

```
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3-venv python3.13-venv
```

- Create and use a venv:

```
python3 -m venv .venv && . .venv/bin/activate && pip install <package>
```

Do not use `--break-system-packages`.

## 4. Node dependencies

- Local installs need no sudo: `npm ci` or `npm install`.
- A global install needs sudo because the prefix is `/usr`: `sudo npm i -g <package>`.
- `pnpm` and `yarn` are not preinstalled. Install one only when the project needs it.

## 5. Toolchains without apt

Add a third-party APT repo as root, following the image pattern:

```
curl -fsSL <key-url> | sudo gpg --dearmor -o /etc/apt/keyrings/<name>.gpg
echo "deb [signed-by=/etc/apt/keyrings/<name>.gpg] <repo-url> <suite> main" | sudo tee /etc/apt/sources.list.d/<name>.list
sudo apt-get update
```

## 6. Failures you will see

- `E: Unable to locate package` - run `sudo apt-get update` first. The list cache may be empty.
- `Could not get lock /var/lib/dpkg/lock-frontend` - another install runs or a prior run died.
  Check with `ps aux`. Wait, then retry. Remove the lock only after you confirm no apt process
  lives.
- Offline or blocked egress - the install times out or DNS fails. Report it. Do not retry
  in a loop.
- Disk pressure - `df -h /home/kratis`. Clean caches before a large install.

## 7. Keep output bounded

Redirect install logs and read only the tail:

```
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends <pkg> > /tmp/install.log 2>&1; tail -n 40 /tmp/install.log
```

## Failure recipes

The full failure catalog (dpkg lock, missing list cache, PEP 668, ensurepip, global npm) and the
cross-distro probe are in `references/install-recipes.md`.
