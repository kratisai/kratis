---
name: websocket-method
description: Adds a new JSON-RPC 2.0 WebSocket method to the Kratis WebSocket handler. Use when adding real-time streaming capabilities, bidirectional communication, or extending the WebSocket API with new methods.
---

## Steps

1. **Protocol Specification & Examples** — Register the method in the canonical contract specification:
   - For sidecar/environment methods, add the method definition to [`protocol/environment/openrpc.json`](protocol/environment/openrpc.json) and create JSON Schemas under `protocol/environment/schemas/requests/` (and `schemas/results/` if the method returns a result).
   - For web client methods, add the method definition to [`protocol/client/openrpc.json`](protocol/client/openrpc.json) and create JSON Schemas under `protocol/client/schemas/requests/`. Declare the `result` schema (single `$ref`, or a `oneOf` list with `"x-kratis-streaming": true` for streaming methods). Server-push payloads go in the top-level `x-kratis-notifications` array.
   - Create full-envelope valid and invalid JSON examples under `protocol/*/examples/`. Every protocol method MUST have at least one valid example fixture. Every result/notification payload MUST have a valid fixture under `examples/results/valid/`.

2. **Closed Payload Collection (mandatory)** — Add the message to the sealed payload hierarchy in [`com.kratisai.controlplane.api.wsdto`](control-plane/src/main/java/com/kratisai/controlplane/api/wsdto):
   - Web client request → a record in `ClientRpcPayload` (record declares a `METHOD` constant; `JsonRpcRequest<P>` derives the wire method from the payload, so method/params can never disagree).
   - Connector method → a record in `EnvironmentRpcPayload`, implementing the matching sealed branch: `InboundRequestPayload` / `InboundNotificationPayload` (sidecar → cp), `OutboundRequestPayload` / `OutboundNotificationPayload` (cp → sidecar). Kind and direction are defaults on the branch.
   - Web-facing result/notification → a record in `ClientPayload` with a new `ClientPayloadType` enum constant; payloads emitted on a chat stream additionally implement `ChatStreamPayload`/`ChatHistoryPayload`.
   - Connector result (cp → sidecar) → `EnvironmentResponsePayload`; connector result (sidecar → cp) → `EnvironmentConnectorResult`. Both are sealed — a new payload requires a record + a protocol schema, or the build fails.
   - Method name strings must NEVER be written directly in production code — always reference the record (`ClientRpcPayload.Auth.METHOD`).

3. **Synchronize Web & Sidecar Registries**:
   - **Web**: Add the method to `CLIENT_METHODS` in [`web/src/protocol/client-methods.ts`](web/src/protocol/client-methods.ts) and update the matching params interface in [`web/src/types/websocket-types.ts`](web/src/types/websocket-types.ts). A compile-time assertion (`_assertMethodsMatch`) fails the build if the runtime registry keys drift from the TypeScript type map. New result/notification payloads get a zod schema in [`web/src/protocol/client-results.ts`](web/src/protocol/client-results.ts) keyed by the `type` discriminator.
   - **Sidecar**: Add the method to `EnvironmentMethods` in [`sidecar/rpc/protocol_methods.go`](sidecar/rpc/protocol_methods.go) (params and result struct pointers), update the matching structs in [`sidecar/rpc/protocol.go`](sidecar/rpc/protocol.go), and ensure inbound (`control-plane-to-connector`) methods have a `case` in [`sidecar/rpc/client.go`](sidecar/rpc/client.go). A conformance test fails the build if the dispatch switch is incomplete.

4. **Typed Parameter DTO (Option-1 Validation)** — Create a Java Record for parameter deserialization in [`com.kratisai.controlplane.api.wsdto`](control-plane/src/main/java/com/kratisai/controlplane/api/wsdto):
   ```java
   public record YourMethodParams(
           @JsonProperty("requiredField") String requiredField,
           @JsonProperty("optionalField") String optionalField) {
       public YourMethodParams {
           Objects.requireNonNull(requiredField, "requiredField is required");
       }
   }
   ```
   *CRITICAL:* Use compact constructors (`Objects.requireNonNull(...)`) for DTO validation to preserve reflection-free GraalVM AOT compilation. Do NOT use Jakarta Hibernate Validator annotations (`@NotNull`, `@Valid`) on WebSocket DTOs, as they require dynamic reflection hints and break AOT builds. The record components MUST exactly match the JSON Schema `properties`, every schema `required` property MUST be enforced in the compact constructor, and every schema `enum` MUST be validated — `ProtocolConformanceTest` fails the build otherwise.

5. **Handler Bean Implementation** — Create a Spring `@Component` implementing either `EnvironmentRpcHandler<P, R>` or `ClientRpcHandler<P, R>`:
   ```java
   @Component
   public class YourMethodRpcHandler
           implements ClientRpcHandler<ClientRpcPayload.YourMethod, ClientPayload.YourResult> {

       @Override
       public String getMethodName() {
           return ClientRpcPayload.YourMethod.METHOD;
       }

       @Override
       public Class<ClientRpcPayload.YourMethod> getPayloadType() {
           return ClientRpcPayload.YourMethod.class;
       }

       @Override
       public Flux<ClientPayload.YourResult> handle(
               String sessionId, Object requestId, ClientRpcPayload.YourMethod params) {
           return Flux.just(new ClientPayload.YourResult(...));
       }
   }
   ```
   Handlers never touch `WebSocketSession` or `WebSocketDispatch`. Return a `Flux` of result payloads — the transport subscribes after `handle` returns (after any `@Transactional` work commits) and sends each item as a JSON-RPC response with the request id. Sync errors are signaled by throwing `RpcErrorException`; async stream failures by `Flux.error`. `Flux.empty()` means no response. Spring automatically detects and registers all `EnvironmentRpcHandler` and `ClientRpcHandler` beans in `EnvironmentWebSocketHandler` and `ClientWebSocketHandler` respectively.

6. **Service Layer** — Inject business services using Spring constructor injection. Keep handlers decoupled from core business logic.

7. **Sending Requests (control plane → sidecar)** — Never construct raw method strings or serialize payloads at call sites. Use the typed operations on `EnvironmentRpcClient`:
   ```java
    // Fire-and-forget notification (e.g. env.checkout — completion via env.checkout_complete)
    environmentRpcClient.send(environmentId, new EnvironmentRpcPayload.Checkout(url, branch, env, executionId));

    // Await a typed result (payload declares its result record)
    EnvironmentConnectorResult.Exec result = environmentRpcClient.request(environmentId, new EnvironmentRpcPayload.Exec(command, true, executionId));
    ```
    `send()` accepts only `OutboundNotificationPayload`; `request()` accepts `OutboundRequestPayload<R>` and returns `R`. Failures surface as `EnvironmentRpcException` / `TimeoutException` — do not fire-and-forget requests, or sidecar failures go silently undetected.

8. **Streaming / Async Pushes** — Streaming handlers return a `Flux` (`chat.send` returns `Flux<ChatStreamPayload>`; `chat.subscribe` returns `Flux.concat(history, live)`). The transport owns subscribe + dispatch. Fan-out uses `dispatch.broadcastNotificationToTeam` / `broadcastNotificationToUser` with a `ClientPayload` record (`id=null` envelope), invoked from `@TransactionalEventListener(AFTER_COMMIT)` listeners. Deferred replies (HITL) return `Flux.empty()` and are sent later by `EnvironmentRealtimeEventListeners`.

9. **Error Responses** — Invalid parameters automatically produce a standard JSON-RPC `-32602 Invalid params` response during Jackson deserialization failure. For domain errors, throw `RpcErrorException(JsonRpcError.…)` with codes from `JsonRpcErrorCodes` (registered in `protocol/common/error-codes.json` — an unregistered code fails the build):
   - `-32600`: Invalid Request
   - `-32601`: Method not found
   - `-32602`: Invalid params
   - `-32603`: Internal error
   - `-32000`: Not authenticated / `-32001`: Invalid token / `-32002`: Not authorised

10. **Sidecar & Web UI Synchronization**:
    - For Go sidecar methods, update `sidecar/rpc/protocol.go` structs and run `go test ./...`.
    - For Vite Web methods, update `web/src/types/websocket-types.ts`, `web/src/protocol/client-results.ts` and `web/src/store/websocket-store.ts`, then run `npm test`.

11. **Automated Verification & Contract Testing**:
    - **Control plane**: `ProtocolConformanceTest` validates the closed collections against `protocol/*/openrpc.json`: exact method-set equality (completeness in both directions), `x-kratis-direction`/`x-kratis-message-kind` parity, JSON Schema conformance of every params and result record (property sets, required fields, enums, `const` discriminators, unknown-property rejection), sealed-payload ↔ `schemas/results/` set-equality, openrpc reference coverage, envelope + error-code conformance, and valid/invalid fixture round-trips. Any deviation FAILS the build.
    - **Web**: `protocol-conformance.test.ts` validates `CLIENT_METHODS` and `CLIENT_RESULTS` against `protocol/client/` and checks zod-schema alignment, required fields, and fixture round-trips. Any deviation FAILS `npm test`.
    - **Sidecar**: `protocol_conformance_test.go` validates `EnvironmentMethods` against `protocol/environment/openrpc.json`, checks Go struct alignment (params and results), required fields, inbound dispatch-switch completeness, and fixture acceptance/rejection. Any deviation FAILS `go test ./...`.
    - Verify canonical JSON fixtures deserialize cleanly and match schemas in `ProtocolFixtureRoundTripTest.java`.
    - Verify ArchUnit protocol constraints pass via `ArchUnitProtocolTest.java` and `ArchitectureSanityTest.java` (single `session.sendMessage` call site, handlers never touch `WebSocketSession`, closed discriminator enums).
    - Add handler unit tests and integration tests in `control-plane/src/test/java/com/kratisai/controlplane/websocket/`.
    - Run `./mvnw test` to perform final task validation (use `./mvnw test -Pfast` for rapid iteration only).

## Key Patterns

- **Reflection-Free Compact Constructors**: Use `Objects.requireNonNull(...)` inside record compact constructors for lightweight, AOT-safe validation.
- **Closed Discriminator Enums**: Result/notification payload `type` fields are `ClientPayloadType` / `EnvironmentResultType` enums pinned by schema `const` — arbitrary type strings cannot compile.
- **Typed Dispatching**: `EnvironmentWebSocketHandler` and `ClientWebSocketHandler` enforce `FAIL_ON_UNKNOWN_PROPERTIES=true` and delegate to typed `getPayloadType()` handlers. All writes flow through `WebSocketDispatch`.
- **Constructor Injection**: All services and repositories must be injected via constructor.
- **Contract Parity**: Handlers, Go structs, and TypeScript types must remain in exact parity with `protocol/` OpenRPC schemas.
