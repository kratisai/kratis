# E2E Transparent Download Cache (Squid Proxy TestContainer)

## 1. Problem Statement

Agent harness E2E tests (`com.kratisai.controlplane.e2e.*`) install tools at
runtime inside ephemeral Docker sandboxes. Setup commands in `AgentHarness`
download from the public internet on every run:

| Category | Examples | Harnesses |
|---|---|---|
| npm registry | `npm install …` | Aider, Claude Code, Codex, Gemini, Pi |
| curl install scripts / binaries | uv, Goose, Mistral Vibe, OpenCode, OpenHands, Qwen | Aider, Goose, Mistral, OpenCode, OpenHands, Qwen |
| git clone | `github.com/jorgejhms/aider-acp` | Aider |
| PyPI via uv | `uv tool install aider-chat` | Aider |

Failures observed in CI/local runs are almost entirely network-related:
connection resets on `curl`, 3-minute timeouts waiting for install completion,
flaky npm/git downloads. There is **no download cache** today.

### Constraints

1. **Do not bake agent tools into the sandbox image.** Runtime install must
   remain the source of truth (matches production provisioning).
2. **Cache lives outside the sandbox.** Something alongside the container,
   not inside it.
3. **Zero (or near-zero) changes to harness setup commands.** Do not rewrite
   every `curl`/`npm`/`git` line with custom cache URLs.
4. **Subsequent runs should not need the internet** once the cache is warm
   (modulo TTL / forced refresh).

These constraints point to a **transparent HTTP/HTTPS caching proxy** running
as a companion Testcontainer, with sandboxes directed at it via standard
proxy environment variables.

---

## 2. Why Squid (not nginx alone)

| Requirement | nginx | Squid |
|---|---|---|
| Forward HTTP proxy | possible with modules | native |
| Cache HTTPS via MITM (SSL bump) | not a first-class feature | native (`ssl_bump`) |
| Persistent disk cache | `proxy_cache` (reverse) | `cache_dir` (forward) |
| Well-trodden “CI download cache” pattern | uncommon | common |

nginx can reverse-proxy and cache known upstreams, but that requires
rewriting every download URL or maintaining an allowlist of hosts. Squid as a
**forward proxy with SSL bump** intercepts whatever the harness already
downloads, with no setup-command changes.

> Production sandboxes (see `../knowledge/sandbox-provisioning.md`) continue to use
> direct egress. This design is **test-only** unless we later promote the same
> pattern for air-gapped deployments.

---

## 3. Target Architecture

```
+---------------------------+         +------------------------------+
| JUnit / Control Plane     |         | Squid Testcontainer          |
|                           |         |  - listen 3128               |
|  AbstractAgentExecution   | starts  |  - SSL bump + disk cache     |
|  RealTest @BeforeAll  ----|-------->|  - volume: squid-cache       |
|                           |         +--------------+---------------+
|  LocalDockerSandboxProvider|                       |
|    docker run …            |                       | MITM HTTPS
|      -e HTTP_PROXY=…       |                       v
|      -e HTTPS_PROXY=…      |         +------------------------------+
|      -e NO_PROXY=…         |         | Internet (first miss only)   |
|      -e SSL_CERT_FILE=…    |         | registry.npmjs.org, GitHub,  |
+-------------+-------------+         | astral.sh, mistral.ai, …     |
              | docker run            +------------------------------+
              v
+---------------------------+
| Sandbox (kratis-e2e-test) |
|  trusts proxy CA          |
|  curl / npm / git / uv    |
|  → all go via Squid       |
+---------------------------+
```

### Traffic that must **not** go through the proxy

| Destination | Why |
|---|---|
| Control plane WebSocket (`host.docker.internal:<test-port>`) | Sidecar registration / RPC |
| LiteLLM Testcontainer | LLM API calls (already mocked/proxied separately) |
| WireMock LLM server | Same |
| `localhost` / `127.0.0.1` | Local-only |

These are excluded via `NO_PROXY` / `no_proxy`.

---

## 4. Design Decisions

### 4.1 Scope: E2E tests only (phase 1)

| In scope | Out of scope (phase 1) |
|---|---|
| `AbstractAgentExecutionRealTest` and subclasses | Production `LocalDockerSandboxProvider` default behaviour |
| Optional proxy injection when a test property/env is set | Mandatory proxy for all Docker sandboxes |
| Warm cache on host volume under `/tmp` or Testcontainers reuse | Guaranteed offline CI without a seed cache |

Phase 2 (optional): enable the same proxy for local dev sandboxes via
`kratis.sandbox.http-proxy-url`.

### 4.2 SSL bump and trust

HTTPS caching requires intercepting TLS. Squid terminates client TLS with a
**proxy CA**, opens a real TLS session upstream, and caches the response body.

Sandboxes must trust that CA:

1. Generate a long-lived test-only CA (checked into the repo under
   `control-plane/src/test/resources/squid/` — **not** a production secret).
2. Install the CA into `kratis-runner-base` **or** only into the E2E-derived
   image (`kratis-e2e-test:latest` built in `@BeforeAll`).

**Preference:** install only into the E2E image so production runner base
stays clean. If the E2E image is a thin layer on `kratis-runner-base`, add:

```dockerfile
COPY test-ca.crt /usr/local/share/ca-certificates/kratis-e2e-proxy.crt
RUN update-ca-certificates
```

Also set for Node/npm if needed:

```
NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
```

(usually unnecessary once `update-ca-certificates` runs).

### 4.3 Cache persistence

| Option | Behaviour |
|---|---|
| **A. Named Docker volume** `kratis-e2e-squid-cache` | Survives container restart; easy on CI agents |
| **B. Host bind** `/tmp/kratis-e2e-squid-cache` | Easy to inspect/wipe locally |
| **C. Ephemeral only** | No cross-run benefit |

**Choose A with optional bind override** via env
`KRATIS_E2E_SQUID_CACHE_DIR`. First suite run after wipe is cold (internet);
later runs are warm.

Squid must initialize the cache dir on start (`squid -z` if empty).

### 4.4 Tool compatibility checklist

| Client | Respects `HTTPS_PROXY`? | Notes |
|---|---|---|
| curl | Yes | Used by most install scripts |
| npm | Yes | Uses global agent / undici |
| git (https) | Yes if `http.proxy` or env | Ensure `GIT_SSL_CAINFO` or system CA trust |
| uv | Yes | Uses system proxy env |
| pip | Yes | If ever used |
| Go modules | N/A | Not used in harness setup |

Risk: install scripts that pin `curl --noproxy '*'` or use raw IP HTTPS.
Mitigation: document; if hit, patch only that harness command (exception).

### 4.5 Image choice for Squid

Prefer a maintained Squid image that supports SSL bump, e.g.:

- Custom Dockerfile based on `ubuntu:24.04` + `squid-openssl` (recommended for
  reproducible CA layout), **or**
- Community image known to support SSL bump (pin digest).

Do **not** use a reverse-proxy-only nginx image for this plan.

---

## 5. Implementation Plan

### Step 0 — Verification (define success)

Automated:

1. `./mvnw test -Dtest='com.kratisai.controlplane.e2e.CodexExecutionRealTest'`
   twice in a row with cache volume retained.
2. Second run: Squid access log shows majority `TCP_HIT` /
   `TCP_MEM_HIT` for npm/tarball hosts (not only `TCP_MISS`).
3. Optional offline proof: after warm cache, `iptables`/disconnect host egress
   (or Squid `never_direct allow all` after seeding) and re-run one harness —
   setup still completes.
4. Non-E2E suite still green:
   `./mvnw test -Dtest='!com.kratisai.controlplane.e2e.**'`.
5. `LocalDockerSandboxProviderTest` asserts proxy env vars appear **only**
   when proxy URL is configured.

Manual:

- Wipe cache volume → first run slower, still passes with network.
- Inspect `/var/spool/squid` size growth after full E2E class run.

### Step 1 — Proxy CA + Squid config assets

Add under `control-plane/src/test/resources/squid/`:

| File | Purpose |
|---|---|
| `ca.crt` / `ca.key` / `proxy.pem` | Test-only CA for SSL bump |
| `squid.conf` | Port 3128, ssl_bump, cache_dir, ACLs |
| `README.md` | How to regenerate CA (`openssl` one-liner) |

`squid.conf` essentials:

```conf
http_port 3128 ssl-bump \
  cert=/etc/squid/ssl_cert/proxy.pem \
  generate-host-certificates=on \
  dynamic_cert_mem_cache_size=16MB

sslcrtd_program /usr/lib/squid/security_file_certgen -s /var/lib/squid/ssl_db -M 16MB
ssl_bump peek all
ssl_bump bump all

cache_dir ufs /var/spool/squid 2048 16 256
maximum_object_size 512 MB
cache_mem 256 MB

# Allow all (test only)
http_access allow all

access_log stdio:/var/log/squid/access.log
cache_log /var/log/squid/cache.log
```

Generate SSL cert DB in container entrypoint if missing.

### Step 2 — Squid Testcontainer wrapper

New class, e.g.
`control-plane/src/test/java/com/kratisai/controlplane/e2e/SquidProxyContainer.java`:

- Extends `GenericContainer`
- Mounts config + PEM
- Named volume or bind for `/var/spool/squid`
- Exposes `3128`
- Wait strategy: log line or TCP port
- Methods: `getProxyUrl()` → `http://host:mappedPort`
- Optional: `getAccessLogSnippet()` for assertions

Lifecycle: start once per JVM in `AbstractAgentExecutionRealTest` static
`@BeforeAll` (alongside existing image build), stop in `@AfterAll` only if
not reusing.

### Step 3 — Trust CA in E2E sandbox image

In `AbstractAgentExecutionRealTest` image build (where `kratis-e2e-test:latest`
is constructed from `kratis-runner-base`):

- `COPY` / `docker build` step that installs `ca.crt` via
  `update-ca-certificates`.

If build is shell-scripted Dockerfile generation, inject the CA copy there.


### Step 6 — NO_PROXY completeness

Build `NO_PROXY` at E2E setup time:

```
localhost,127.0.0.1,host.docker.internal,<docker-bridge-gateway>,<litellm-host>,<wiremock-host>
```

Verify sidecar still connects to control plane WS after proxy enablement
(regression already covered by any passing E2E).

### Step 7 — Warm-up / documentation

Optional `@BeforeAll` “cache warm” is **not** required if first real harness
run seeds the cache. Document in `docs/knowledge/e2e-agent-orchestration.md`:

- How to wipe cache
- How to read Squid HIT/MISS logs
- That first CI job on a clean agent is slower

### Step 8 — Hardening (phase 1.5)

- Pin Squid image digest.
- Fail E2E fast if Squid unhealthy.
- Metric/log: count of `TCP_MISS` vs `TCP_HIT` in test teardown (info log).
- Consider `cache deny` for hosts that must always be fresh (none expected for
  harness installs).

---

## 6. File / Touch List

| Path | Change |
|---|---|
| `control-plane/src/test/resources/squid/*` | **New** CA + squid.conf |
| `control-plane/src/test/java/.../e2e/SquidProxyContainer.java` | **New** |
| `control-plane/src/test/java/.../e2e/AbstractAgentExecutionRealTest.java` | Start Squid; set proxy on provider; install CA in E2E image |
| `control-plane/src/main/java/.../LocalDockerSandboxProvider.java` | Optional proxy env injection |
| `control-plane/src/test/java/.../LocalDockerSandboxProviderTest.java` | Cover proxy on/off |
| `docs/knowledge/e2e-agent-orchestration.md` | Document cache behaviour |
| `build/Dockerfile.runner-base` | **No change** (prefer E2E-only CA) |

No changes to `AgentHarness` setup command strings in phase 1.

---

## 7. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| SSL bump breaks a specific client | System CA install + `NODE_EXTRA_CA_CERTS`; isolate failing harness |
| Install script disables proxy | Rare; patch that single command or ACL exception |
| Cache serves stale broken tarball | Squid refresh patterns; wipe volume; pin package versions already in harness |
| Proxy intercepts LiteLLM and breaks LLM mocks | Strict `NO_PROXY` |
| Squid image lacks ssl_bump | Use custom Dockerfile with `squid-openssl` |
| Parallel E2E tests thrash one Squid | One Squid per JVM is fine; cache_dir is concurrent-safe enough for tests |
| CA key in git | Test-only CA; rotate if leaked; never reuse for prod |

---

## 8. Out of Scope

- Changing production default egress (see `../knowledge/sandbox-provisioning.md`).
- Baking npm global caches or agent binaries into `kratis-runner-base`.
- Per-host reverse-proxy URL rewriting.
- Caching Docker image pulls (`docker pull`) — separate concern (registry
  mirror).
- Making the full E2E suite mandatory offline on every PR without a seeded
  volume (nice-to-have later).

---

## 9. Delivery Sequence

1. **Assets + Squid container** — can be developed/tested with a manual
   `curl -x` smoke test.
2. **Provider proxy injection + unit tests** — no E2E required.
3. **E2E wiring + CA in test image** — validate one harness (Codex or Claude).
4. **Full E2E pass ×2** — confirm HIT rate and reduced wall time.
5. **Docs** — knowledge update.

Estimated effort: ~1 day focused implementation + flaky-network soak.

---

## 10. Success Criteria

- [ ] Squid Testcontainer starts reliably in E2E `@BeforeAll`.
- [ ] Sandboxes receive proxy env only when configured.
- [ ] Harness setup commands unchanged.
- [ ] Second full E2E run shows substantial cache hits and lower install time.
- [ ] Sidecar ↔ control plane and LiteLLM paths unaffected (`NO_PROXY`).
- [ ] Non-E2E test suite remains green.
- [ ] Cache wipe procedure documented and verified.
