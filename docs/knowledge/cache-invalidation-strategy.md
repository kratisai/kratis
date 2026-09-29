# WebSocket Cache Invalidation & Client Realtime

## Overview
Kratis uses **Smart Invalidation** via WebSockets. The backend broadcasts lightweight signals, and TanStack Query fetches fresh data only when actively viewed.

This two-tier architecture includes:
1. **Scoped (Per-Team)**: Team-specific entities (Repositories, Credentials, Model Providers). Prevents over-fetching.
2. **Global (Per-User)**: Cross-workspace entities (Team Memberships, Profiles).

The same AFTER_COMMIT pipeline also pushes live execution/ingestion payloads (output lines, activity, permissions, complete) that are not cache invalidations.

Execution activities are persisted in the same transaction that publishes 
`SandboxExecutionActivityEvent` 

---

## Developer Guide

### 1. Single pattern (enforced)

1. **Always `@Transactional`** on any method that calls `ApplicationEventPublisher.publishEvent` for client fan-out domain events.
2. **Always publish via domain events** — never inject `WebSocketDispatch` or session registries from business services.
3. **Only `WebSocketDispatch.broadcastNotificationTo*`** owns team/user WebSocket fan-out (session lookup + send).
4. **`ClientRealtimeEventListeners`** maps domain events → JSON-RPC results after commit (`@TransactionalEventListener(AFTER_COMMIT)`).
5. **Never `fallbackExecution = true`** — ensure events are published from a transaction.

ArchUnit enforces no `fallbackExecution` and that only `ClientRealtimeEventListeners` may call `WebSocketDispatch.broadcastNotification*`.

### 2. Backend Events
Use existing generic events to trigger invalidations:
- `TeamEntityChangedEvent(teamId, TeamEntityType)` (e.g., `REPOSITORIES`, `CREDENTIALS`)
- `UserEntityChangedEvent(userId, UserEntityType)` (e.g., `TEAMS`, `PROFILE`)

When adding new entities, expand the corresponding enum instead of creating new event classes.

Sandbox executions use the dedicated `ExecutionStatusChangedEvent(teamId, chatId, executionId)` to provide the additional chatId param.

Live execution/ingestion events (output, activity, permission, complete, ingestion status) follow the same publish-inside-TX rule.

### 3. Event Publishing

**Usage:** Inject `ApplicationEventPublisher` in a `@Transactional` service/controller/handler method:

```java
@Transactional
public Repository create(...) {
    Repository saved = repositoryRepository.save(entity);
    eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.REPOSITORIES));
    return saved;
}
```

- **Team Events**: Sent to `teamId` subscribers via `SubscriptionRegistry`.
- **User Events**: Sent to all active sessions for `userId` via `ClientSessionRegistry`.

Sidecar notifications are handled by `EnvironmentRealtimeEventListeners` (separate channel), also AFTER_COMMIT — e.g. permission replies on `SandboxExecutionPermissionResolvedEvent`.

### 4. Frontend Routing
`useWebSocketStore` (`websocket-store.ts`) handles signals:
- **`team_entity_changed`**: If `result.teamId` matches `currentTeamId`, calls `queryClient.invalidateQueries` for specific keys.
- **`user_entity_changed`**: Instantly invalidates global keys (e.g., `['teams']`).
- **`execution_status_changed`**: If `result.teamId` matches `currentTeamId`, invalidates `['chat-executions', result.chatId]`

Update handlers in `websocket-store.ts` when introducing new frontend entities.

---

## Integration Testing (`@SpringIntegrationTest`)
Test holistic behavior, not internal methods. See `WebSocketIntegrationTest`.

1. **Scoped Invalidation**: Connect a mock `WebSocketSession`, subscribe to a team, invoke a service method (e.g., `createCredential`), and assert the `team_entity_changed` JSON payload arrives.
2. **Global Invalidation**: Connect and authenticate, invoke a global service method (e.g., `addTeamMember`), and assert the `user_entity_changed` payload arrives.
