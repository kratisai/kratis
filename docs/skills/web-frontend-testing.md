---
name: web-frontend-testing
description: "Writes tests for the React/TypeScript web frontend. Use when adding or modifying tests in the /web/ module. Covers testing hierarchy, shared support infrastructure, and concrete patterns for integration, store, and component tests."
---

## Test Hierarchy

| Level | Location | When to Use |
|-------|----------|-------------|
| **Integration tests** | `web/tests/integration/<feature>-flow.test.tsx` | User flows, multi-component interactions, hooks + stores + TanStack Query together |
| **Component tests** | `web/tests/components/<path>.test.tsx` | Simple, isolated rendering of a single component |
| **Lib / hook tests** | `web/tests/lib/`, `web/tests/hooks/` | Pure helpers and hooks with no UI |

Never colocate `*.test.ts(x)` next to production files in `src/`. Mirror `src/` under `tests/` instead (`src/hooks/use-is-mobile.ts` → `tests/hooks/use-is-mobile.test.ts`).

**Prefer integration tests over component tests.** Integration tests exercise real hooks, real stores, and real providers — catching closure bugs, stale state, and provider misconfiguration that isolated component tests miss.

> **Store tests (`tests/store/`) are a legacy location.** Do not add new store tests. Stores should be verified through their user-visible impact: render the view that exercises the store, interact with it via `user.*` events, and assert on what appears on screen. Direct store tests bypass the UI contract and can pass even when the real experience is broken.

---

## Shared Test Support (`tests/support/`)

All shared test infrastructure lives in `tests/support/`. Import directly from the module that owns the symbol — do **not** create a local re-export.

| Module | What it contains |
|--------|-----------------|
| `test-factories.ts` | `createMock*` typed domain-object builders |
| `test-fetch-mocks.ts` | `addFetchHandler`, `jsonResponse`, `setupFetchMock`, all `mock*` REST helpers |
| `test-render.tsx` | `renderWithRouter`, `renderIntegration`, `setAuthenticated`, `setUnauthenticated`, `screen`, `waitFor`, toast helpers |
| `test-websocket.ts` | `MockWebSocket`, `setupConnected`, `allInstances`, `getLatestInstance`, `trigger*` frame helpers |

### Import paths by test location

```
tests/integration/*.test.tsx    →  from '../support/test-*'
tests/store/*.test.ts           →  from '../support/test-*'
tests/components/**/*.test.tsx  →  from '../../support/test-*'
```

---

## Integration Tests (preferred pattern)

Mock only at the **network boundary** (`global.fetch` + `WebSocket`). Let React, Zustand, and TanStack Query run naturally.

```typescript
// web/tests/integration/my-feature-flow.test.tsx
import { beforeEach, describe, expect, it } from 'vitest'

import {
  createMockTeam,
  mockListItems,
} from '../support/test-factories'      // domain builders

import {
  mockCreateItem,
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'    // REST mocks + lifecycle

import {
  renderIntegration,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'         // render + auth

describe('My Feature Flow', () => {
  setupFetchMock()  // registers beforeEach / afterEach lifecycle globally

  beforeEach(() => {
    setUnauthenticated()
  })

  it('completes user flow', async () => {
    mockListTeams([createMockTeam()])
    mockListItems([{ id: '1', name: 'Item 1' }])
    mockCreateItem()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/my-feature'])

    await user.click(screen.getByRole('button', { name: /add/i }))
    await user.type(screen.getByLabelText('Name'), 'New Item')
    await user.click(screen.getByRole('button', { name: /submit/i }))

    await waitFor(() => {
      expect(screen.getByText('New Item')).toBeInTheDocument()
    })
  })

  it('shows error when API fails', async () => {
    mockListTeams([createMockTeam()])
    mockListItems([])
    mockCreateItem(true)  // shouldFail = true

    setAuthenticated({ teamId: 'team-1' })
    renderIntegration(['/my-feature'])

    // trigger action, assert error state...
  })
})
```

### WebSocket integration tests

For tests that exercise the WebSocket connection (telemetry, chat, execution), add
`setupConnected()` after rendering. It opens the connection and completes the auth
handshake in one call.

```typescript
import {
  setupConnected,
  triggerMockTelemetry,
  triggerMockComplete,
  triggerMockMessageEcho,
  triggerMockExecutionOutput,
  triggerMockExecutionComplete,
} from '../support/test-websocket'

it('streams telemetry during a task', async () => {
  mockListTeams([createMockTeam()])
  setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
  renderIntegration(['/session/test-session'])

  const ws = setupConnected()  // connect + auth handshake

  act(() => {
    triggerMockTelemetry(ws, 'Thinking…')
    triggerMockMessageEcho(ws, 'Hello!', 'session-1', 'assistant')
    triggerMockComplete(ws, 'session-1')
  })

  await waitFor(() => {
    expect(screen.getByText('Hello!')).toBeInTheDocument()
  })
})
```

Available trigger helpers (all take a `MockWebSocket` as the first argument):

| Helper | Emits |
|--------|-------|
| `triggerMockTelemetry(ws, status, taskId?, toolName?)` | `telemetry` frame |
| `triggerMockMessageEcho(ws, content, sessionId, role?, messageId?)` | `message` frame |
| `triggerMockComplete(ws, sessionId, messageId?, messageCount?)` | `complete` frame |
| `triggerMockExecutionOutput(ws, line, stream?)` | `execution_output` frame |
| `triggerMockExecutionComplete(ws, exitCode?, status?)` | `execution_complete` frame |

---

## Adding New Mock Helpers

When a `mock*` helper doesn't exist for an endpoint yet, add it to `test-fetch-mocks.ts`
in the relevant domain section (auth / credential / environment / repository / team / wiki):

```typescript
// tests/support/test-fetch-mocks.ts

export function mockListItems(items: Array<ItemDto> = []) {
  addFetchHandler((url) => {
    const match = url.match(/\/api\/v1\/teams\/[^/]+\/items$/)
    if (match) return jsonResponse(items)
    return null
  })
}

export function mockCreateItem(shouldFail = false) {
  addFetchHandler((url, options) => {
    if (url.includes('/items') && options?.method === 'POST') {
      if (shouldFail) return jsonResponse({ message: 'Conflict' }, 409)
      return jsonResponse({ id: 'item-new', name: 'New Item' }, 201)
    }
    return null
  })
}
```

Similarly, add `createMockItem()` to `test-factories.ts` when you have a reusable
typed domain object:

```typescript
// tests/support/test-factories.ts

export function createMockItem(overrides?: Partial<ItemDto>): ItemDto {
  return {
    createdAt: '2024-01-01T00:00:00Z',
    id: 'item-1',
    name: 'Test Item',
    teamId: 'team-1',
    ...overrides,
  }
}
```

---

## Key Principles

1. **Mock only at the network boundary** — `global.fetch` (via `setupFetchMock`) and
   `WebSocket` (via `MockWebSocket`). Never mock React hooks, Zustand stores, or
   TanStack Query internals.
2. **Use real component trees** — `renderIntegration(['/path'])` renders the full
   application with real providers and a memory router.
3. **Test user activities, not implementation** — "user logs in and creates an item",
   not "useCreateItem returns the correct DTO".
4. **Prefer typed `mock*` helpers** — Use the helpers in `test-fetch-mocks.ts`. Fall
   back to inline `addFetchHandler` only when the helper doesn't exist yet, then
   extract it.
5. **Use `createMock*` factories for test data** — They produce valid, fully-typed
   objects with sensible defaults. Pass `overrides` for the fields that matter to the
   specific test.
6. **Keep `setupFetchMock()` at the `describe` level, not inside `it`** — It
   registers `beforeEach`/`afterEach` lifecycle hooks that reset state between tests.

---

## Anti-Patterns

| Anti-Pattern | Why It's Bad | Correct Approach |
|---|---|---|
| `import { ... } from './test-helpers'` | File doesn't exist (deleted) | Import from `../support/test-*` |
| Adding a new file to `tests/store/` | Stores should be verified through their user-visible impact | Write an integration test that renders the view and asserts on screen output |
| Calling `useMyStore.getState()` to assert state | Bypasses the UI contract; store internals can change without breaking UX | Assert on what the user sees (`screen.getByText`, etc.) |
| Mocking `@/lib/api` or hook modules | Bypasses TanStack Query, misses integration bugs | Mock `global.fetch` via `setupFetchMock` |
| Mocking Zustand stores in component tests | Tests diverge from real user experience | Use real stores; mock only external I/O |
| Calling `vi.stubGlobal('WebSocket', ...)` manually | Duplicates `setupFetchMock`'s responsibility | Call `setupFetchMock()` — it stubs both WebSocket and fetch |
| Defining a local `MockWebSocket` class | Creates fragmented, inconsistent mocks | Use `MockWebSocket` from `test-websocket.ts` |
| Inline `new Response(...)` in tests | Noise; varies across tests | Use `jsonResponse(data, status)` from `test-fetch-mocks.ts` |

---

## Running Tests

```bash
# Full suite
cd web && npm run test

# Single file
cd web && npm run test -- tests/integration/my-feature-flow.test.tsx

# Coverage report
cd web && npm run coverage
```
