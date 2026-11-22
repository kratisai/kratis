# Control plane

Java API core for Kratis. Spring Boot 4, Spring AI, virtual threads. REST + JSON-RPC over WebSockets. The production image embeds the Vite SPA and serves it from the same process.

See the [root README](../README.md) for product context.

## Prerequisites

- Java 21
- Docker (Postgres + LiteLLM via `compose.yaml` orchestrated by Springboot at launch)
- Parser binaries at `../build/bin/codebase-memory-mcp` and `../build/bin/scc` (see [`build/README.md`](../build/README.md))

## Commands

```bash
./mvnw spring-boot:run          # dev; starts compose.yaml (Postgres, LiteLLM)
./mvnw test -Pfast              # fast iteration first (~2m); skips @SlowTest & JaCoCo
./mvnw verify                   # full validation before done (~7m): style + all tests + E2E + SpotBugs + JaCoCo
./mvnw clean verify             # required after spring-boot:process-test-aot so SpotBugs ignores target/spring-aot/
./mvnw spotless:apply           # Palantir Java Format
./mvnw spring-boot:process-aot  # GraalVM AOT check
./mvnw -Pnative native:compile  # native binary
```

JaCoCo: `>90%` instruction and line, `>85%` branch. After `./mvnw verify`, read `target/site/jacoco/jacoco.csv` and `target/test-timing-summary.txt`.

Dev stack lifecycle: Spring orchestrates `compose.yaml` (`spring-boot-docker-compose`) with `lifecycle-management=start-only`: the stack is started if needed but never torn down on exit — restarts reuse the running containers. Inert in prod/native, where the runtime image ships no compose file. LiteLLM is pinned by digest (`v1.102.0`) instead of tracking `main-stable`, so `up` reuses the cached image and only a recreated container pays its ~20s health-wait.

Never use `@SpringBootTest`, `@MockitoBean`, `@TestPropertySource`, `@DirtiesContext`, or per-class `@Import` on `@SpringIntegrationTest` classes. Use `TestSpiesConfig` for shared spies, `FakeChatModel` + `PromptMatcher` for LLM mocks, `@UseRealNativeBinaries` for real binary tests (and `IngestionTestStubs` elsewhere), and `TestIsolationScope` for Docker/temp isolation. Details: [`docs/skills/control-plane-testing.md`](../docs/skills/control-plane-testing.md) and [`docs/skills/control-plane.md`](../docs/skills/control-plane.md).

## Layout

```
control-plane/
├── compose.yaml              # dev Postgres (pgvector/pg16) + LiteLLM
├── pom.xml
└── src/main/java/.../controlplane/
    ├── api/rest/             # REST controllers
    ├── api/wsdto/            # sealed JSON-RPC payloads
    ├── websocket/            # /ws/client and /ws/env handlers
    ├── planningagent/        # Ask & Plan ReAct loop and tools
    ├── ingestion/            # parse, research, wiki
    ├── service/              # orchestration, HITL, LiteLLM, dispatch
    └── model/                # JPA entities and enums
```

## See also

- Protocol: [`protocol/README.md`](../protocol/README.md)
- WebSockets: [`docs/knowledge/websocket-api.md`](../docs/knowledge/websocket-api.md)
- Sandboxes: [`docs/knowledge/sandbox-provisioning.md`](../docs/knowledge/sandbox-provisioning.md)
