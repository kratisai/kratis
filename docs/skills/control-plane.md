---
name: control-plane
description: Control-plane Java conventions. Use when changing control-plane Java, adding DTOs, Liquibase migrations, GraalVM AOT hints, or Spring beans.
---

## AOT

- Constructor injection only.
- Record compact constructors (`Objects.requireNonNull(...)`) for DTO and RPC validation. Do not use Jakarta validation annotations on WebSocket DTOs.
- Third-party reflection or classloading needs an explicit `RuntimeHintsRegistrar`.

## Liquibase

- Never edit historical changelogs. That changes MD5 checksums and breaks existing installs.
- Test-only database init (pgvector) belongs in `PostgresTestInitializer.java`.

## Style

- Import every type. No fully-qualified names in code except JVM descriptor strings (ArchUnit allow-lists).
- Comment only non-obvious why (protocol sequencing, thread-safety, commit-ordering). No one-line Javadoc that restates the method name.
- Enums and sealed records for closed sets. Cover every value in tests.

## Commands

See [`control-plane/README.md`](../../control-plane/README.md). Do not use `-Pfast` to mark a task complete. Tests: [`control-plane-testing.md`](control-plane-testing.md).

## Dev stack

- `compose.yaml` (Postgres + LiteLLM) is orchestrated by Spring, not started manually. `application.properties` sets `spring.docker.compose.lifecycle-management=start-only`: the stack starts if needed, survives app exit, and restarts reuse it. The integration is inert in prod/native, where no compose file ships.
