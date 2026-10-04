---
name: native-image
description: Builds and smoke-tests the GraalVM native control-plane image. Use after changing resource loading / dependencies, or debugging native-image-only startup failures such as MissingReflectionRegistrationError, NoSuchMethodException, or ClassNotFoundException that do not occur on the JVM.
---

## When to run

Run both steps for any change that can affect startup or reachability: build/deploy wiring,
`AotHints`, annotations that name classes, Jackson-bound types, and anything touching startup beans.
The JVM test suite cannot see these failures.

## Build

Build with Docker from the repository root. The build needs roughly 3-4 GB of heap per
`NATIVE_PARALLELISM` thread; keep `NATIVE_HEAP` at four times the parallelism.

```bash
docker build -f build/Dockerfile.control-plane \
  -t ghcr.io/kratisai/kratis:local \
  --build-arg NATIVE_PARALLELISM=4 --build-arg NATIVE_HEAP=24g .
```

For a host GraalVM toolchain, `cd control-plane && ./mvnw -Pnative native:compile` produces
`target/kratis`; use `./mvnw spring-boot:process-aot` as a cheaper AOT-only check.

## Smoke test

Boot the built image against a throwaway pgvector database and assert it reports healthy:

```bash
./build/smoke-test-image.sh ghcr.io/kratisai/kratis:local 240
```

The script starts `pgvector/pgvector:pg16` and the image, waits for the container healthcheck, and
dumps `docker logs` on failure. A healthy container means the Spring context and Liquibase booted,
which is the minimum bar before publishing. It does not exercise requests, so feature-level
regressions still need the JVM suite.

The release workflow (`main-release.yml`) builds with `load: true`, runs this script, and only
pushes to GHCR when it passes.

## Fixing native-only startup failures

GraalVM's closed-world analysis strips classes reached only through reflection or strings. A stack
trace pointing at `Class.getDeclaredConstructor`, `Class.forName`, or Spring AOT's
`MissingReflectionRegistrationError` identifies the class. Register it in
`config/AotHints` and lock it with a case in `AotHintsTest`.

Common culprits:

| Referenced by | Needs |
|---------------|-------|
| `@Constraint(validatedBy = X.class)` | `X` no-arg constructor |
| `@Tool(resultConverter = X.class)` | `X` no-arg constructor |
| Jackson-bound records / DTOs | constructor and component accessors |
| Classpath resources read at runtime | `RuntimeHints.resources().registerPattern(...)` |

Do not paper over a failure by initializing the class at build time; add the specific hint.
