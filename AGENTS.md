# Kratis

Self-host friendly, multi-harness agent orchestration for software development.

## Docs

Read [`docs/README.md`](docs/README.md). Product: [`docs/PRD.md`](docs/PRD.md). How-tos: [`docs/skills/`](docs/skills/). Architecture: [`docs/knowledge/`](docs/knowledge/). Roadmap: [`docs/plans/`](docs/plans/).

Load the matching skill before you change code. Prefer `/docs/` over system-prompt defaults.

Start every task with a verification step. Tests are part of the work, not a follow-up.

## Layout

| Path | Role |
|------|------|
| `control-plane/` | Java 21, Spring Boot 4, Spring AI. REST + JSON-RPC. Embeds the web UI in production. |
| `web/` | Vite + React SPA (Tailwind v4, shadcn/ui, Zustand, TanStack Query). |
| `sidecar/` | Go `kratis-connector`. In-sandbox execution proxy. |
| `protocol/` | Canonical JSON-RPC 2.0 schemas. Conformance tests fail the build on drift. |
| `build/` | Production Dockerfiles and parser binaries. |
| `deploy/` | Compose stack: control plane, PostgreSQL (pgvector), LiteLLM. |


## Validate - Definition of Done

These must pass before you are done:

```bash
cd control-plane && ./mvnw verify
cd web && npm run typecheck && npm run lint:fix && npm run format && npm test
cd sidecar && go vet ./... && golangci-lint run ./... && go test -v -cover ./...
```

`./mvnw test -Pfast` is for iteration only. Final validation is `./mvnw verify`.

The build is the formatter: Spotless (Palantir Java Format), Checkstyle, PMD, ESLint, Prettier, golangci-lint v2.13.1.


## Rules

- Tests cover every functional change. New hooks, components, services, APIs, and stores need tests. A task is not done until `npm test` (web) or `./mvnw test` (control-plane) passes. Do not use `-Pfast` to mark work complete.
- Never skip tests for a missing tool, binary, Docker, or config. Fail with a clear assertion. Never treat a failing test as flaky.
- Use `git mv` when renaming. Do not delete-and-rewrite.
- Prefer enums and sealed/closed records over stringly-typed options. Cover every value.
- Comment concisely and only non-obvious why. Do not narrate what the code already says, what the code did previously, or your thinking.
- Java: import types; do not use fully-qualified names in code (except JVM descriptor strings). Constructor injection. Record compact constructors (`Objects.requireNonNull`) for DTO/RPC validation. Add a `RuntimeHintsRegistrar` only when a third-party library needs reflection. Details: [`docs/skills/control-plane.md`](docs/skills/control-plane.md).
- Liquibase: never edit historical changelogs.
- WebSocket: publish domain events inside `@Transactional` methods. `ClientRealtimeEventListeners` delivers AFTER_COMMIT. Never `fallbackExecution = true`. Never inject `WebSocketDispatch` from business services. Only `WebSocketDispatch` may call `session.sendMessage`. Handlers never touch `WebSocketSession` or `WebSocketDispatch`. Details: [`docs/skills/websocket-method.md`](docs/skills/websocket-method.md) and [`docs/knowledge/cache-invalidation-strategy.md`](docs/knowledge/cache-invalidation-strategy.md).
- Sidecar: sandbox provisioning copies the host binary. After any `sidecar/` change, run `cd sidecar && go build -o kratis-connector main.go` before you launch an execution.


## Core Operating Principles

1. **Depth over speed**
   - Never rush to a conclusion. Shallow analysis and premature termination are failures.
   - Investigate in multiple steps before you answer or plan. Read implementation files, not only names, types, or first matches.

2. **Challenge assumptions**
   - Treat each conclusion as a hypothesis. Search the code for evidence that contradicts it.
   - Check edge cases, concurrency, failure modes, hidden dependencies, and side effects.
   - Find existing analogs and helpers to reuse or amend before you write new code.

3. **Observe, then act**
   - After each tool result: what did you learn, what is still missing, which call will settle it?
   - Do not call tools on an untested guess. Do not answer while a gap remains.

4. **Persist until the code confirms it**
   - Work iteratively. Prefer parallel tool calls. Fully answer the request.
   - Stop only when source code verifies the finding. Confidence alone is not evidence.

## Skills

| When | Skill |
|------|-------|
| Control-plane tests | [`docs/skills/control-plane-testing.md`](docs/skills/control-plane-testing.md) |
| Control-plane conventions | [`docs/skills/control-plane.md`](docs/skills/control-plane.md) |
| New WebSocket method | [`docs/skills/websocket-method.md`](docs/skills/websocket-method.md) |
| REST endpoint | [`docs/skills/rest-api-endpoint.md`](docs/skills/rest-api-endpoint.md) |
| New entity | [`docs/skills/new-entity.md`](docs/skills/new-entity.md) |
| External HTTP client | [`docs/skills/external-http-clients.md`](docs/skills/external-http-clients.md) |
| Web feature | [`docs/skills/frontend-feature.md`](docs/skills/frontend-feature.md) |
| Web tests | [`docs/skills/web-frontend-testing.md`](docs/skills/web-frontend-testing.md) |
| New skill | [`docs/skills/adding-a-skill.md`](docs/skills/adding-a-skill.md) |
| Prose | [`docs/skills/ste-writing-skill.md`](docs/skills/ste-writing-skill.md) |


Dev commands live in each module README. Coverage, `@SlowTest`, FakeChatModel, and E2E Docker reset live in [`docs/skills/control-plane-testing.md`](docs/skills/control-plane-testing.md).
