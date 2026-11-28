# Install recipes and failure reference

Long-form detail for `sandbox-package-install`. Probe the base image first, then follow the
recipe that matches its package manager; do not assume one manager or one registry layout.

## Package manager probe

```
command -v apt-get || command -v apk || command -v dnf
```

| Manager | Image | Install line |
|:---|:---|:---|
| apt-get | Debian/Ubuntu (default) | `sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends <pkg>` |
| apk | Alpine | `sudo apk add --no-cache <pkg>` |
| dnf | Fedora/RHEL | `sudo dnf install -y <pkg>` |

The team provider may set `dockerImage` to a DevContainer or other base. Always run the probe.

## apt cache hygiene

- Clean the package cache after a batch: `sudo apt-get clean`. The suspend snapshot is a
  `docker commit`, so a large cache grows it and slows resume.
- `/var/lib/apt/lists/` grows after `apt-get update`; `apt-get clean` reclaims it. If disk
  pressure appears mid-install, clean before retrying.

## Conffile and timezone prompt

Always pair `-y` with `DEBIAN_FRONTEND=noninteractive`. Without the frontend variable an image
can emit `debconf: ... the dialog based frontend cannot be used` and may block on a conffile or
timezone prompt. Keep the frontend variable even when a run appears not to need it, so the
install stays non-interactive on every base image.

## dpkg lock

```
E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 1121 (apt-get)
E: Unable to acquire the dpkg frontend lock (/var/lib/dpkg/lock-frontend), is another process using it?
```

Meaning: another apt/dpkg runs, or a prior run died. Steps:

1. `ps aux | grep -E 'apt|dpkg'` (install `procps` if `ps` is missing).
2. If a process lives, wait for it or stop it.
3. Only when no process lives, remove `/var/lib/dpkg/lock-frontend` and
   `/var/lib/dpkg/lock`.
4. `sudo dpkg --configure -a` to finish a half-configured install.

## Python

`pip3 install` fails with PEP 668:

```
error: externally-managed-environment
... See PEP 668 ... --break-system-packages
```

A venv fails until venv support matching the installed interpreter is available:

```
The virtual environment was not created successfully because ensurepip is not available.
```

Install the venv package for the interpreter you are using (for example `python3-venv` plus the
versioned `python3.X-venv` that matches `python3 --version`). The working sequence:

```
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3-venv
python3 -m venv .venv
. .venv/bin/activate
pip install requests
python -c "import requests; print(requests.__version__)"
```

## Node

- A global `npm i -g <pkg>` fails with `The operation was rejected by your operating system`
  when the prefix is root-owned and you are not root. Prefix it with `sudo`, or point npm at a
  user-writable prefix.
- Check where a global install lands with `npm config get prefix`; it varies by base image.
- Local `npm install` works unprivileged and reaches the public registry.
- The local registry mirror serves `docker pull` only. Do not assume it caches npm, pip, or apt;
  if a fetch fails, verify egress to the public registry instead of retrying the mirror.

## Third-party APT repo

```
curl -fsSL <key-url> | sudo gpg --dearmor -o /etc/apt/keyrings/<name>.gpg
echo "deb [signed-by=/etc/apt/keyrings/<name>.gpg] <repo-url> <suite> main" | sudo tee /etc/apt/sources.list.d/<name>.list
sudo apt-get update
```

Use this pattern for any third-party repo (for example NodeSource or Docker): write the key under
`/etc/apt/keyrings/` and reference it with `signed-by` in the source list, then `apt-get update`.
A base image that already ships these repos may differ, so probe before adding one.

## Failure catalog

| Message | Cause | Fix |
|:---|:---|:---|
| `E: Unable to locate package` | empty list cache or wrong name | `sudo apt-get update`, then retry |
| `Could not get lock ...lock-frontend` | concurrent apt or dead run | see the dpkg lock section |
| `externally-managed-environment` | PEP 668 | use a venv |
| `ensurepip is not available` | no `python3-venv` | install it, then recreate the venv |
| `The operation was rejected by your operating system` | global npm without sudo | prefix `sudo` |
| install times out, DNS fails | offline or blocked egress | report, do not loop |
| `No space left on device` | disk pressure | clean caches, check `df -h` on the affected mount |
