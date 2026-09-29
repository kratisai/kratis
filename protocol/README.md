# Protocol

Canonical JSON-RPC 2.0 schemas and fixtures for Kratis WebSockets. Java, Go, and TypeScript extract from this directory. Drift fails the build.

See the [root README](../README.md) for product context.

## Endpoints

| Path | Peer | Auth |
|------|------|------|
| `/ws/client` | Web UI | JWT (`auth`) |
| `/ws/env` | `kratis-connector` | Environment token (`env.register`) |

Overview: [`docs/knowledge/websocket-api.md`](../docs/knowledge/websocket-api.md). New method: [`docs/skills/websocket-method.md`](../docs/skills/websocket-method.md).

## Layout

```
protocol/
├── common/                 # envelopes and error-codes.json
├── client/
│   ├── openrpc.json        # web → control plane
│   ├── schemas/
│   └── examples/
└── environment/
    ├── openrpc.json        # connector ↔ control plane
    ├── schemas/
    └── examples/
```

- `client/openrpc.json` — UI methods. `x-kratis-streaming: true` marks a streamed result. `x-kratis-notifications` lists server-push payloads (`id=null`).
- `environment/openrpc.json` — connector methods. `x-kratis-message-kind: notification` means no response (completion arrives on a later method).
- `schemas/results/*.schema.json` — one schema per result `type`, pinned with `const`.

## Conformance

Each implementation models methods and payloads as a closed set:

- **Control plane:** sealed records in `com.kratisai.controlplane.api.wsdto`. `ProtocolConformanceTest` (`./mvnw test`).
- **Web:** `CLIENT_METHODS` and `CLIENT_RESULTS` in `web/src/protocol/`. `protocol-conformance.test.ts` (`npm test`).
- **Sidecar:** `EnvironmentMethods` in `sidecar/rpc/protocol_methods.go`. `protocol_conformance_test.go` (`go test ./...`).

Java DTO validation uses record compact constructors (`Objects.requireNonNull`). That stays GraalVM AOT-safe without extra reflection hints.
