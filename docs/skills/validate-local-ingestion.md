---
name: validate-local-ingestion
description: "Validates ingestion and cross-repository discovery against the local dev server, dev PostgreSQL database, and dev-team repositories. Use when asked to run real ingestion end to end, inspect generated dimensions, surfaces, wiki pages, or repo links, diagnose failed batches, or verify changes against populated local team data."
---

# Validate ingestion on the local dev stack

## Preconditions

- Work from the repository root.
- `kratis-dev-postgres` and `kratis-dev-litellm` must be running (launched via `control-plane/compose.yaml`).
- Never reset, clean, or bulk-delete the dev database. Failed batches retain useful surfaces and logs.

## Stack and team

```bash
docker ps --format '{{.Names}} {{.Status}}' | grep -E 'kratis-dev-(postgres|litellm)'
curl -fsS http://localhost:8080/actuator/health
docker exec kratis-dev-postgres psql -U kratis -d kratis -At -F '|' -c \
  "SELECT t.id,t.name,count(r.id) FROM teams t LEFT JOIN repositories r ON r.team_id=t.id GROUP BY t.id,t.name ORDER BY t.name"
```

If the control plane is down or needs restarting to pick up code changes:
- Stop any existing process listening on port `8080` and verify `ss -ltnp '( sport = :8080 )'` is empty before starting a new instance so a stale JVM cannot remain bound to `8080`.
- Start it in `control-plane/` with `./mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.jvmArguments="-Dspring.devtools.restart.enabled=false"`.
- Never run `java -jar target/kratis-*.jar` directly from `control-plane/target/`, because `./mvnw package` or `./mvnw verify` overwrites that jar in place and corrupts lazy classloading.
- Do not restart PostgreSQL or LiteLLM.

Set `TEAM` to the dev team ID, then confirm the repositories and member account:

```bash
docker exec kratis-dev-postgres psql -U kratis -d kratis -At -F '|' -c \
  "SELECT id,name,url,branch FROM repositories WHERE team_id='$TEAM' ORDER BY name"
docker exec kratis-dev-postgres psql -U kratis -d kratis -At -F '|' -c \
  "SELECT tm.user_id,u.email,tm.role FROM team_members tm JOIN users u ON u.id=tm.user_id WHERE tm.team_id='$TEAM'"
```

## Authenticate, trigger, and poll

Mint a short-lived local JWT in memory from `kratis.auth.jwt.secret` in `control-plane/src/main/resources/application-local.properties` and queue one batch per repository. Never print the secret or token:

```bash
python3 - <<'PY'
import base64, hashlib, hmac, json, subprocess, time, urllib.request, uuid
from pathlib import Path

TEAM = "<team-id>"
USER = "<user-id>"
EMAIL = "<user-email>"

secret = next(
    line.split("=", 1)[1].strip()
    for line in Path("control-plane/src/main/resources/application-local.properties").read_text().splitlines()
    if line.startswith("kratis.auth.jwt.secret=")
)
key = secret.encode("utf-8")
alg, digest = ("HS512", hashlib.sha512) if len(key) >= 64 else (("HS384", hashlib.sha384) if len(key) >= 48 else ("HS256", hashlib.sha256))
b64u = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode("ascii")
now = int(time.time())
hdr = b64u(json.dumps({"alg": alg, "typ": "JWT"}, separators=(",", ":")).encode())
pld = b64u(json.dumps({"jti": str(uuid.uuid4()), "sub": USER, "email": EMAIL, "iat": now, "exp": now + 600}, separators=(",", ":")).encode())
token = f"{hdr}.{pld}.{b64u(hmac.new(key, f'{hdr}.{pld}'.encode(), digest).digest())}"

run_start = subprocess.check_output(["docker", "exec", "kratis-dev-postgres", "psql", "-U", "kratis", "-d", "kratis", "-At", "-c", "SELECT now()"], text=True).strip()
print(f"RUN_START={run_start}")
repos = subprocess.check_output(["docker", "exec", "kratis-dev-postgres", "psql", "-U", "kratis", "-d", "kratis", "-At", "-F", "|", "-c", f"SELECT id,name FROM repositories WHERE team_id='{TEAM}' ORDER BY name"], text=True).strip().splitlines()
for row in repos:
    rid, name = row.split("|", 1)
    req = urllib.request.Request(f"http://localhost:8080/api/v1/teams/{TEAM}/repositories/{rid}/ingest", data=b"{}", method="POST", headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        print(f"{name}|{resp.status}|{resp.read().decode('utf-8')}")
PY
```

Poll progress by `RUN_START` until zero batches remain `QUEUED` or `PROCESSING`:

```bash
docker exec kratis-dev-postgres psql -U kratis -d kratis -P pager=off -c "
SELECT r.name, b.status,
  round(EXTRACT(EPOCH FROM (coalesce(b.completed_at, now()) - (SELECT min(l.created_at) FROM ingestion_batch_logs l WHERE l.batch_id=b.id)))/60.0, 2) AS active_min,
  round(EXTRACT(EPOCH FROM (max(l.created_at) FILTER (WHERE l.step='ACTIVATE_BATCH') - min(l.created_at) FILTER (WHERE l.step='ACTIVATE_BATCH'))), 1) AS activate_sec,
  (SELECT l2.step FROM ingestion_batch_logs l2 WHERE l2.batch_id=b.id ORDER BY l2.created_at DESC LIMIT 1) AS latest_step,
  left(b.error_message, 90) AS error
FROM ingestion_batches b JOIN repositories r ON r.id=b.repository_id LEFT JOIN ingestion_batch_logs l ON l.batch_id=b.id
WHERE b.started_at >= TIMESTAMPTZ '<run-start>'
GROUP BY r.name, b.id, b.status, b.completed_at, b.error_message
ORDER BY b.completed_at NULLS LAST, active_min DESC NULLS LAST, r.name"
```

Failed batches are terminal and inactive. Concurrent batches are required, with a per-team lock for activation. Do not downgrade to serial ingestion, and stop if ingestion exceeds roughly 5–10 minutes of active time per repository.

## Analyse active data

Always join `is_active=true`. Never present a failed latest batch as current.

```bash
# 1) Per-repository active inventory: dimensions, surfaces, attributes, wiki pages, timings, and tokens
docker exec kratis-dev-postgres psql -U kratis -d kratis -P pager=off -c "
SELECT r.name,
  (SELECT count(*) FROM ctx_dimensions d WHERE d.batch_id=b.id) AS dimensions,
  (SELECT count(*) FROM ctx_surfaces s WHERE s.batch_id=b.id) AS surfaces,
  (SELECT count(*) FROM ctx_surface_attributes a JOIN ctx_surfaces s ON s.id=a.surface_id WHERE s.batch_id=b.id) AS attrs,
  (SELECT count(*) FROM ctx_wiki_pages w WHERE w.batch_id=b.id) AS wiki_pages,
  round(EXTRACT(EPOCH FROM (b.completed_at - (SELECT min(l.created_at) FROM ingestion_batch_logs l WHERE l.batch_id=b.id)))/60.0, 2) AS active_min,
  round(EXTRACT(EPOCH FROM (SELECT max(l.created_at) - min(l.created_at) FROM ingestion_batch_logs l WHERE l.batch_id=b.id AND l.step='ACTIVATE_BATCH')), 1) AS activate_sec,
  (SELECT sum(u.prompt_tokens) FROM ingestion_model_usage u WHERE u.batch_id=b.id AND u.model_kind='CHAT') AS prompt_tok
FROM repositories r JOIN ingestion_batches b ON b.repository_id=r.id AND b.is_active=true
WHERE r.team_id='$TEAM' ORDER BY r.name"

# 2) Team totals: dimensions by category, surfaces by source/role, wiki pages, and attribute hygiene
docker exec kratis-dev-postgres psql -U kratis -d kratis -P pager=off -c "
SELECT d.category, count(*) AS dimensions
FROM ctx_dimensions d JOIN ingestion_batches b ON b.id=d.batch_id AND b.is_active=true
JOIN repositories r ON r.id=b.repository_id WHERE r.team_id='$TEAM' GROUP BY 1 ORDER BY 1;

SELECT s.detected_by, s.role, count(*) AS surfaces
FROM ctx_surfaces s JOIN ingestion_batches b ON b.id=s.batch_id AND b.is_active=true
JOIN repositories r ON r.id=s.repository_id WHERE r.team_id='$TEAM' GROUP BY 1, 2 ORDER BY 1, 2;

SELECT (SELECT count(*) FROM ctx_wiki_pages w JOIN ingestion_batches b ON b.id=w.batch_id AND b.is_active=true JOIN repositories r ON r.id=b.repository_id WHERE r.team_id='$TEAM') AS active_wiki_pages,
  count(a.id) AS active_attrs,
  count(a.id) FILTER (WHERE a.value LIKE '%:null') AS null_suffix_attrs,
  count(a.id) FILTER (WHERE a.kind='FILE' AND a.value ~* 'cypress|_test\.go|/tests?/|\.spec\.|\.test\.') AS test_file_leaks
FROM ctx_surface_attributes a JOIN ctx_surfaces s ON s.id=a.surface_id
JOIN ingestion_batches b ON b.id=s.batch_id AND b.is_active=true JOIN repositories r ON r.id=s.repository_id
WHERE r.team_id='$TEAM'"

# 3) Current relations with both batches active, match counts, and sample rationale
docker exec kratis-dev-postgres psql -U kratis -d kratis -P pager=off -c "
SELECT a.name AS repo_a, b.name AS repo_b, r.nature, r.direction, r.confidence,
  jsonb_array_length(r.citations::jsonb->'matches') AS matches,
  left(coalesce(r.citations::jsonb->'matches'->0->>'rationale', r.rationale), 110) AS sample_rationale
FROM ctx_repo_relations r
JOIN repositories a ON a.id=r.repository_a_id JOIN repositories b ON b.id=r.repository_b_id
JOIN ingestion_batches ba ON ba.id=r.batch_a_id AND ba.is_active=true
JOIN ingestion_batches bb ON bb.id=r.batch_b_id AND bb.is_active=true
WHERE r.team_id='$TEAM' AND r.state='CURRENT' ORDER BY 1, 2, 3"
```

Inspect `citations->'matches'` and surface attributes for evidence. Report any `FILE` test-leakage rows (`cypress`, `_test.go`, `/tests/`, `.spec.`, `.test.`) explicitly; do not hide them.

## Failures and report

For each failed batch, read `error_message` and the latest log rows:

```bash
docker exec kratis-dev-postgres psql -U kratis -d kratis -P pager=off -c \
  "SELECT step,level,left(message,500) FROM ingestion_batch_logs WHERE batch_id='<batch-id>' ORDER BY created_at DESC LIMIT 20"
```

Report batch outcomes, active counts of dimensions, surfaces, attributes, and wiki pages, current relations with direction/confidence/evidence, test or parser leakage, likely false-positive relation natures, and failed repositories whose analysis used a stale active batch. State the control-plane and container state left running.
