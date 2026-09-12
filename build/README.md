# Build

Production Docker assets for Kratis. Parser binaries used by the control plane live in `bin/`.

See the [root README](../README.md) for product context. Deploy: [`deploy/README.md`](../deploy/README.md).

## Contents

| Path | Role |
|------|------|
| [`Dockerfile.control-plane`](Dockerfile.control-plane) | Multi-stage: Vite SPA, Go connector, GraalVM native image, Alpine runtime |
| [`Dockerfile.runner-base`](Dockerfile.runner-base) | Base image for agent sandboxes |
| `bin/codebase-memory-mcp` | Tree-sitter parser (DeusData v0.8.1). Path: `KRATIS_PARSER_BINARY_PATH` |
| `bin/scc` | Line-count metrics for wiki generation. Path: `KRATIS_SCC_BINARY_PATH` |

`codebase-memory-mcp` is a parser binary, not an MCP server. The control plane shells out to it during ingestion.

The Alpine runtime image uses the fully-static `-portable` release asset. The standard
Linux build links glibc 2.38+ and exits 127 under musl/gcompat.

## Parser binaries (dev)

```bash
mkdir -p build/bin
# Linux x86_64 — pick the matching asset for your OS from GitHub Releases
wget https://github.com/DeusData/codebase-memory-mcp/releases/download/v0.8.1/codebase-memory-mcp-linux-amd64.tar.gz -O codebase-memory-mcp.tar.gz
tar xzf codebase-memory-mcp.tar.gz -C build/bin/
chmod +x build/bin/codebase-memory-mcp
rm codebase-memory-mcp.tar.gz

# scc
go install github.com/boyter/scc/v3@latest
# or place a scc binary at build/bin/scc
```

```bash
export KRATIS_PARSER_BINARY_PATH="./build/bin/codebase-memory-mcp"
export KRATIS_SCC_BINARY_PATH="./build/bin/scc"
```

## Dockerfile.control-plane

Four stages:

1. **web** — `npm run build`
2. **sidecar-builder** — `kratis-connector`
3. **builder** — GraalVM native image (`ghcr.io/graalvm/native-image-community:25.0.2-ol9`) with embedded SPA
4. **runtime** — Alpine 3.20, user `kratis`, port 8080, `git`, `scc`, `codebase-memory-mcp`, `kratis-connector`

```bash
docker build -f build/Dockerfile.control-plane -t kratis-api .
docker build -f build/Dockerfile.control-plane \
  --build-arg NATIVE_PARALLELISM=8 \
  --build-arg NATIVE_HEAP=32g \
  -t kratis-api .
```

| Build arg | Role |
|-----------|------|
| `NATIVE_PARALLELISM` | GraalVM thread count |
| `NATIVE_HEAP` | GraalVM max heap (for example `32g`) |

## Dockerfile.runner-base

Sandbox base: Debian trixie-slim, `bash`, `git`, `python3`, Node.js, Docker CLI, `curl`, `tar`, passwordless sudo. User `kratis` (UID 1000). Workdir `/kratis/workspace`. Also built by [`.github/workflows/build-runner-base.yml`](../.github/workflows/build-runner-base.yml).

```bash
docker build -f build/Dockerfile.runner-base -t kratis-runner-base .
```
