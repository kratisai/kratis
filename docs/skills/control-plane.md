---
name: control-plane
description: Control-plane Java conventions. Use when changing control-plane Java, adding DTOs, Liquibase migrations, GraalVM AOT hints, or Spring beans.
---

## AOT

- Constructor injection only.
- Record compact constructors (`Objects.requireNonNull(...)`) for DTO and RPC validation. Do not use Jakarta validation annotations on WebSocket DTOs.
- Third-party reflection or classloading needs an explicit `RuntimeHintsRegistrar`.
- GraalVM native images set Hibernate `BytecodeProvider` to `none`, so lazy to-one associations cannot use runtime `HibernateProxy` subclasses. Keep the `hibernate-maven-plugin` enhance execution (model package only). Do not add `JOIN FETCH` to derived queries to paper over that; use a separately named method only for an exceptional fetch graph.

## Liquibase

- Never edit historical changelogs. That changes MD5 checksums and breaks existing installs.
- Test-only database init (pgvector) belongs in `PostgresTestInitializer.java`.

## Style

- Import every type. No fully-qualified names in code except JVM descriptor strings (ArchUnit allow-lists).
- Comment only non-obvious why (protocol sequencing, thread-safety, commit-ordering). No one-line Javadoc that restates the method name.
- Enums and sealed records for closed sets. Cover every value in tests.

## Commands

See [`control-plane/README.md`](../../control-plane/README.md). Do not use `-Pfast` to mark a task complete. Tests: [`control-plane-testing.md`](control-plane-testing.md).

## Harness catalogue

Harness definitions are the `FILENAME.json` files (plus each harness's bundled files) in
`control-plane/harnesses/`, loaded at startup from `kratis.harnesses.directory` by
`HarnessCatalogInitializer` (`@Lazy(false)` so it still runs under test lazy-initialization). Change
a harness by editing that directory. Never reintroduce classpath scanning: a native image cannot
enumerate classpath directories, so discovery must go through the filesystem. `HarnessResource.source`
is relative to the harness directory; the container image copies the directory to `/opt/kratis/harnesses`.

## Dev stack

- `compose.yaml` (Postgres + LiteLLM) is orchestrated by Spring, not started manually. `application.properties` sets `spring.docker.compose.lifecycle-management=start-only`: the stack starts if needed, survives app exit, and restarts reuse it. The integration is inert in prod/native, where no compose file ships.
