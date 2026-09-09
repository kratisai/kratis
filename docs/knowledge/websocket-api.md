# WebSocket API Overview

Kratis uses JSON-RPC 2.0 over WebSocket for bidirectional communication. There are two
endpoints:

| Endpoint | Peer | Authenticated by |
|---|---|---|
| `/ws/client` | Web UI → control plane | JWT (`auth` method) |
| `/ws/env` | `kratis-connector` → control plane | environment token (`env.register`) |

## Canonical definition

The wire contract is defined in [`protocol/`](../../protocol/README.md) — the OpenRPC
documents, JSON Schemas, and example fixtures under `protocol/client/` and
`protocol/environment/`. That directory is the source of truth; the Java, Go, and
TypeScript implementations extract from and conform to it, and any deviation fails the
build (`ProtocolConformanceTest`, `protocol_conformance_test.go`,
`protocol-conformance.test.ts`). Do not hand-copy a method or payload shape into a doc.

## High-level conventions

These are the only invariants a reader needs here; the protocol and code enforce the
rest:

- **Single transport path.** `WebSocketDispatch` is the only class that calls
  `session.sendMessage`. Every write — responses, errors, notifications, fan-out, and
  control-plane→connector requests — flows through it.
- **Closed payload hierarchies.** Every method and every result is a member of a sealed
  set (`ClientRpcPayload` / `EnvironmentRpcPayload` for requests;
  `ClientPayload` / `EnvironmentResponsePayload` / `EnvironmentConnectorResult` for
  results). Method names are derived from the records, so a mismatched method/params
  pair does not compile.
- **Handler contract.** RPC handlers receive a session id and return a `Flux` of typed
  result payloads. They never touch `WebSocketSession` or `WebSocketDispatch`.
- **Fan-out after commit.** Client notifications are published from `@Transactional`
  methods and delivered after commit — see
  [`cache-invalidation-strategy.md`](cache-invalidation-strategy.md).

## Connection lifecycle

1. Open a WebSocket to the endpoint.
2. Authenticate (`auth` for `/ws/client`, `env.register` for `/ws/env`).
3. Send requests and receive typed JSON-RPC responses.

Reconnection and replay semantics for chat streams are covered in
[`chat-interaction-design.md`](chat-interaction-design.md); the connector side of the
protocol is covered in [`acp-lifecycle.md`](acp-lifecycle.md).
