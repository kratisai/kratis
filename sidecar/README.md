# Connector (`kratis-connector`)

Go binary that runs inside a Docker sandbox. It authenticates on `/ws/env`, supervises one ACP agent over stdio, streams activity, and pauses for HITL.

See the [root README](../README.md) for product context.

Sandbox provisioning copies the host binary. After any `sidecar/` change, rebuild before you launch an execution:

```bash
go build -o kratis-connector main.go
```

## Prerequisites

- Go 1.22+
- golangci-lint **v2.13.1** (pinned in `.golangci.yml` and CI)
  ```bash
  sudo rm -rf /usr/local/go
  wget https://go.dev/dl/go1.22.4.linux-amd64.tar.gz
  sudo tar -C /usr/local -xzf go1.22.4.linux-amd64.tar.gz
  echo 'export PATH=$PATH:/usr/local/go/bin' >> ~/.bashrc
  source ~/.bashrc
  go version
  ```

## Commands

```bash
go run main.go --server-url=ws://localhost:8080/ws/env --token=...
go build -o kratis-connector main.go
go vet ./... && golangci-lint run ./... && go test -v -cover ./...
```

Docker fallback (no local Go):

```bash
docker run --rm -v "$(pwd)":/sidecar -w /sidecar golang:1.22 go test -v ./...
docker run --rm -v "$(pwd)":/app -w /app golangci/golangci-lint:v2.13.1 golangci-lint run ./...
```

## Flags

| Flag | Env | Default | Description |
|------|-----|---------|-------------|
| `--mode` | — | `sidecar` | Run mode |
| `--token` | `KRATIS_TOKEN` | required | Environment registration token |
| `--server-url` | `KRATIS_SERVER_URL` | required | Control plane WebSocket, e.g. `ws://localhost:8080/ws/env` |
| `--container-id` | `KRATIS_CONTAINER_ID` | — | Optional sandbox container id |
| `--log-file` | — | — | Redirect logs (used when stdout is not captured) |
| `--debug` | — | false | Relay connector diagnostics on `env.output` |

Workspace is the process current directory.

## Layout

```
sidecar/
├── main.go                 # flags and process lifecycle
├── rpc/                    # WebSocket JSON-RPC, git, env.* dispatch
├── runner/                 # process supervisor, exec, HITL interdiction, ssh-agent
└── acp/                    # ACP client over agent stdio
```

## See also

- Protocol: [`protocol/README.md`](../protocol/README.md)
- ACP lifecycle: [`docs/knowledge/acp-lifecycle.md`](../docs/knowledge/acp-lifecycle.md)
- Sandboxes: [`docs/knowledge/sandbox-provisioning.md`](../docs/knowledge/sandbox-provisioning.md)
