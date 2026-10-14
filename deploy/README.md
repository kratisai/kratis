# Deploy

Production Docker Compose for Kratis: control plane, PostgreSQL with pgvector, LiteLLM, and an optional registry cache.

See the [root README](../README.md) for product context. Image build: [`build/README.md`](../build/README.md).

## Quick start

UI and API: `http://localhost:8080`. All options below produce the same stack.

Images are published for `linux/amd64` and `linux/arm64`; Apple Silicon Macs pick the arm64 variant automatically. LiteLLM and pgvector are pinned to multi-arch digests.

Once the stack is up — account, model provider keys, repositories, plan, execute, publish: [`docs/getting-started.md`](../docs/getting-started.md).

### Option 1 — installer one-liner (recommended)
Requires Docker, `curl`, and `openssl`.
```bash
mkdir kratis && cd kratis
curl -fsSL https://raw.githubusercontent.com/kratisai/kratis/main/deploy/install.sh | sh
# Review / edit the .env file
docker compose up -d --wait
```

### Option 2 — from a repository checkout

```bash
git clone https://github.com/kratisai/kratis && cd kratis/deploy
cp .env.example .env
# Set JWT_SECRET (32+ characters) and DB_PASSWORD
docker compose up -d --wait
```

### Option 3 — build from source

Build the control-plane image (SPA + Go connector + GraalVM native binary) from a checkout instead of using a pre-built docker image:

```bash
git clone https://github.com/kratisai/kratis && cd kratis
docker build -f build/Dockerfile.runner-base -t ghcr.io/kratisai/kratis-runner-base:latest .
docker build -f build/Dockerfile.control-plane -t ghcr.io/kratisai/kratis:latest .
cd deploy
cp .env.example .env   # set JWT_SECRET (32+ characters) and DB_PASSWORD
docker compose up -d --wait   # uses the locally built image
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
| `KRATIS_GITHUB_APP_ID` | empty | no | GitHub App id (app id + name required to enable) |
| `KRATIS_GITHUB_APP_NAME` | empty | no | GitHub App slug |
| `KRATIS_GITHUB_PRIVATE_KEY_PATH` | `/data/config/gh-app.pem` | no | PEM path inside the container (supports PKCS#8 and PKCS#1). Place the file in the config volume, or override this to a path you mount. |
| `KRATIS_TELEMETRY_DISABLED` | `0` | no | Set `1` to disable anonymous usage telemetry |
| `KRATIS_HARNESSES_DIRECTORY` | `/opt/kratis/harnesses` | no | Directory of harness definitions inside the container. Set to `/data/config/harnesses` (with the catalogue in `KRATIS_CONFIG_DIR`) or another mounted directory to replace the built-in catalogue without rebuilding the image. |
| `MEM_LIMIT` | `512m` | no | Memory cap for the kratis container |
| `LITELLM_MEM_LIMIT` | `2g` | no | Memory cap for the LiteLLM container |
| `KRATIS_DB_DIR` | `kratis-data` (named volume) | no | Host directory for PostgreSQL data. Must be writable by uid/gid 1000. |
| `KRATIS_CONFIG_DIR` | `kratis-config` (named volume) | no | Host directory for `/data/config`: the credential-encryption key, plus optional `gh-app.pem` and `harnesses/`. Must be writable by uid/gid 1000. |
| `KRATIS_BLOBS_DIR` | `kratis-blobs` (named volume) | no | Host directory for blob storage. Must be writable by uid/gid 1000. |
| `KRATIS_REGISTRY_DIR` | `kratis-image-cache` (named volume) | no | Host directory for the registry pull-through cache. |

### Storage locations

Every volume defaults to a Docker-managed named volume, so a plain `docker compose up` needs no
configuration. Setting a `KRATIS_*_DIR` variable binds that volume to a host directory instead
(useful for placing data on a chosen disk or making backups a directory copy). The target
directories must be writable by uid/gid 1000 (the container user):

```bash
mkdir -p /srv/kratis/{postgres,config,blobs,registry}
chown -R 1000:1000 /srv/kratis
```

`KRATIS_CONFIG_DIR` is the one directory that carries files as well as state: the generated
`encryption.key`, an optional GitHub App `gh-app.pem` (see `KRATIS_GITHUB_PRIVATE_KEY_PATH`), and an
optional `harnesses/` catalogue (`KRATIS_HARNESSES_DIRECTORY=/data/config/harnesses`).


## Commands

```bash
docker compose up -d --wait
docker compose logs -f
docker compose pull          # upgrade the pre-built docker image
docker compose down          # keep volumes
docker compose down -v       # drop data
```

`--wait` blocks until every container reports healthy, so a control-plane that fails to boot
(including a failed Liquibase migration) fails the command instead of leaving a "started" container.
Plain `docker compose up` returns as soon as containers are created, before the healthcheck has run.
Use `--wait-timeout <seconds>` to bound the wait.

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
- **litellm:** `ghcr.io/berriai/litellm:v1.102.0` (pinned by digest)
- **registry-cache:** optional Docker Hub pull-through cache
**NOTE** - additional containers spun up as needed for agent-harness executions

## Health

LiteLLM health is checked by a Python script (deploy/litellm/healthcheck.py) that probes LiteLLM's /health/readiness 
and an authenticated /v2/model/info, and reads the litellm-worker-heartbeat directly from Postgres.

Parser binaries are baked into the control-plane image. For local parser setup, see [`build/README.md`](../build/README.md).

Harness definitions (`FILENAME.json` plus each harness's bundled files) are baked into the image at
`/opt/kratis/harnesses`. Point `KRATIS_HARNESSES_DIRECTORY` at a mounted directory to use your own
catalogue instead — the directory is the single source, so a mount replaces the built-in set.
