"""LiteLLM container healthcheck.

Readiness keeps returning 200 while the proxy's database path is wedged, so this probe also makes
an authenticated ``/v2/model/info`` call (tolerating the first-boot HTTP 500 "Model List not loaded
in") and reads LiteLLM's 60s worker heartbeat — a frozen heartbeat proves the scheduler/Prisma path
wedged. The heartbeat is read with the ``psycopg`` driver bundled in the LiteLLM image and the
container's own ``DATABASE_URL``: no host-side monitor, no token spend. Every step is bounded to
stay within Docker's healthcheck timeout.
"""

import os
import sys
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_URL = "http://localhost:4000"
DEFAULT_TIMEOUT = 2.0
DEFAULT_HEARTBEAT_MAX_AGE_SECONDS = 180  # three missed 60s beats; LiteLLM's own liveness window
HEARTBEAT_TABLE = "LiteLLM_ProxyWorkerHeartbeat"


def _get(url: str, timeout: float, master_key: str | None = None):
    request = urllib.request.Request(url)
    if master_key is not None:
        request.add_header("Authorization", f"Bearer {master_key}")
    return urllib.request.urlopen(request, timeout=timeout)


def _check_readiness(base_url: str, timeout: float) -> str | None:
    try:
        with _get(f"{base_url}/health/readiness", timeout) as response:
            if response.status != 200:
                return f"readiness returned HTTP {response.status}"
    except Exception as exc:
        return f"readiness failed: {exc}"
    return None


def _check_model_info(base_url: str, timeout: float, master_key: str) -> str | None:
    try:
        with _get(f"{base_url}/v2/model/info", timeout, master_key) as response:
            response.read()
    except urllib.error.HTTPError as exc:
        if exc.code != 500:  # 500 = "Model List not loaded in" before models are registered
            return f"/v2/model/info returned HTTP {exc.code}"
    except Exception as exc:
        return f"/v2/model/info failed: {exc}"
    return None


def _connection_params(database_url: str) -> tuple[str, dict]:
    parsed = urllib.parse.urlparse(database_url)
    schema = urllib.parse.parse_qs(parsed.query).get("schema", ["litellm"])[0]
    return schema, {
        "host": parsed.hostname or "localhost",
        "port": parsed.port or 5432,
        "dbname": (parsed.path or "").lstrip("/") or "postgres",
        "user": urllib.parse.unquote(parsed.username or ""),
        "password": urllib.parse.unquote(parsed.password or ""),
    }


def _check_heartbeat(database_url: str, max_age_seconds: int, timeout: float) -> str | None:
    try:
        import psycopg
        from psycopg import errors, sql
    except ImportError as exc:
        return f"psycopg is unavailable: {exc}"

    schema, params = _connection_params(database_url)
    params["connect_timeout"] = max(1, int(timeout))
    params["options"] = f"-c statement_timeout={int(timeout * 1000)}"
    query = sql.SQL("SELECT EXTRACT(EPOCH FROM (now() - max(last_heartbeat_at)))::bigint FROM {}.{}").format(
        sql.Identifier(schema), sql.Identifier(HEARTBEAT_TABLE)
    )
    try:
        with psycopg.connect(**params) as connection, connection.cursor() as cursor:
            cursor.execute(query)
            row = cursor.fetchone()
    except errors.UndefinedTable:
        return None  # table not migrated yet (first boot)
    except Exception as exc:
        return f"heartbeat query failed: {exc}"

    age = row[0] if row else None
    if age is None:
        return None  # no worker has written a heartbeat yet (startup)
    if age > max_age_seconds:
        return f"worker heartbeat is {age}s old (> {max_age_seconds}s); the proxy is wedged"
    return None


def main() -> int:
    base_url = os.environ.get("LITELLM_HEALTHCHECK_URL", DEFAULT_URL).rstrip("/")
    timeout = float(os.environ.get("LITELLM_HEALTHCHECK_TIMEOUT", DEFAULT_TIMEOUT))
    master_key = os.environ.get("LITELLM_MASTER_KEY", "")
    max_age = int(
        os.environ.get("LITELLM_HEALTHCHECK_HEARTBEAT_MAX_AGE_SECONDS", DEFAULT_HEARTBEAT_MAX_AGE_SECONDS)
    )
    database_url = os.environ.get("DATABASE_URL", "")

    failures = [
        failure
        for failure in (
            _check_readiness(base_url, timeout),
            _check_model_info(base_url, timeout, master_key),
            _check_heartbeat(database_url, max_age, timeout)
            if database_url
            else "DATABASE_URL is not set; cannot verify the worker heartbeat",
        )
        if failure
    ]
    if failures:
        print("litellm healthcheck: " + "; ".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
