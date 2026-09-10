# Deploy

Production Docker Compose for Kratis: control plane, PostgreSQL with pgvector, LiteLLM, and an optional registry cache.

See the [root README](../README.md) for product context. Image build: [`build/README.md`](../build/README.md).

## Quick start

UI and API: `http://localhost:8080`. All options below produce the same stack.

Once the stack is up — account, model provider keys, repositories, plan, execute, publish: [`docs/getting-started.md`](../docs/getting-started.md).

### Option 1 — installer one-liner (recommended) - x86 only
Requires Docker, `curl`, and `openssl`.
```bash
mkdir kratis && cd kratis
curl -fsSL https://raw.githubusercontent.com/kratisai/kratis/main/deploy/install.sh | sh
# Review / edit the .env file
docker compose up -d
```

### Option 2 — from a repository checkout - x86 only

```bash
git clone https://github.com/kratisai/kratis && cd kratis/deploy
cp .env.example .env
# Set JWT_SECRET (32+ characters) and DB_PASSWORD
docker compose up -d
```

### Option 3 — build from source - x86 or arm64 (untested)

Build the control-plane image (SPA + Go connector + GraalVM native binary) from a checkout instead of using a pre-built docker image:

```bash
git clone https://github.com/kratisai/kratis && cd kratis
docker build -f build/Dockerfile.runner-base -t ghcr.io/kratisai/kratis-runner-base:latest .
docker build -f build/Dockerfile.control-plane -t ghcr.io/kratisai/kratis:latest .
cd deploy
cp .env.example .env   # set JWT_SECRET (32+ characters) and DB_PASSWORD
docker compose up -d   # uses the locally built image
```

The native-compile step needs ~16 GB of heap by default and can take 40+ minutes to build. Tune it on larger machines with `--build-arg NATIVE_PARALLELISM=8 --build-arg NATIVE_HEAP=32g`. Details: [`build/README.md`](../build/README.md).

To run from source without building a Docker image (JVM mode), use the dev quick start in the [root README](../README.md). That path is supported for development, not production.

## Launch options

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `DB_USERNAME` | `kratis` | no | PostgreSQL user |
| `DB_PASSWORD` | `kratis` | yes in production | PostgreSQL password |
| `DB_PORT` | `5432` | no | PostgreSQL host port (when port mapping is enabled in compose.yaml) |
| `API_PORT` | `8080` | no | Control plane host port |
| `DOCKER_GID` | `0` | no | Group id of the host `/var/run/docker.sock` (the installer detects it) |
| `JWT_SECRET` | empty | yes | JWT signing secret, 32+ characters |
| `LITELLM_MASTER_KEY` | see `.env.example` | yes | LiteLLM master key |
| `LITELLM_SALT_KEY` | see `.env.example` | yes | LiteLLM salt |
| `LITELLM_PORT` | `4000` | no | LiteLLM host port |
| `KRATIS_GITHUB_APP_ID` | empty | no | GitHub App id (all three GitHub vars required to enable) |
| `KRATIS_GITHUB_APP_NAME` | empty | no | GitHub App slug |
| `KRATIS_GITHUB_PRIVATE_KEY_PATH` | empty | no | PEM path inside the container |
| `KRATIS_TELEMETRY_DISABLED` | `0` | no | Set `1` to disable anonymous usage telemetry |
| `MEM_LIMIT` | `512m` | no | Memory cap for the kratis container |
| `LITELLM_MEM_LIMIT` | `2g` | no | Memory cap for the LiteLLM container |


## Commands

```bash
docker compose up -d
docker compose logs -f
docker compose down          # keep volumes
docker compose down -v       # drop data
```

## Stack

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│   kratis    │────▶│  kratis-db   │◀────│  litellm    │
│  port 8080  │     │  pgvector/   │     │  port 4000  │
│  native     │     │  pg16        │     │             │
└─────────────┘     └──────────────┘     └─────────────┘
```

- **kratis-db:** `pgvector/pgvector:pg16`
- **kratis:** GraalVM native control plane + embedded SPA (`ghcr.io/kratisai/kratis:latest`)
- **litellm:** `ghcr.io/berriai/litellm:main-stable`
- **registry-cache:** optional Docker Hub pull-through cache
**NOTE** - additional containers spun up as needed for agent-harness executions

Health: database `pg_isready`; API `/actuator/health`; LiteLLM `/health/readiness`. The API waits for the database and LiteLLM.

Parser binaries are baked into the control-plane image. For local parser setup, see [`build/README.md`](../build/README.md).
