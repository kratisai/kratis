# Web

Vite + React SPA for Kratis. Tailwind CSS v4, shadcn/ui, TanStack Query, Zustand. Talks to the control plane over REST and JSON-RPC on `/ws/client`.

See the [root README](../README.md) for product context.

## Prerequisites

- Node.js 20+
- Control plane on `http://localhost:8080` (Vite proxies `/api` and `/ws`)

## Commands

```bash
npm install
npm run dev                 # http://localhost:5173
npm run typecheck && npm run lint:fix && npm run format && npm test
npm run build               # production assets (also run inside Dockerfile.control-plane)
```

TanStack Query holds server data. Zustand holds UI state and the WebSocket session, not API entities. Details: [`docs/skills/frontend-feature.md`](../docs/skills/frontend-feature.md) and [`docs/skills/web-frontend-testing.md`](../docs/skills/web-frontend-testing.md).

Tests live under `tests/`, not next to `src/` files.

## Layout

```
web/
├── src/
│   ├── components/         # views, chat, canvas, settings, session/diff
│   ├── hooks/              # TanStack Query hooks
│   ├── lib/                # REST clients
│   ├── protocol/           # CLIENT_METHODS and CLIENT_RESULTS
│   ├── store/              # Zustand
│   └── types/
└── tests/                  # integration, component, support
```

## See also

- Protocol: [`protocol/README.md`](../protocol/README.md)
- Chat stream: [`docs/knowledge/chat-interaction-design.md`](../docs/knowledge/chat-interaction-design.md)
- Cache invalidation: [`docs/knowledge/cache-invalidation-strategy.md`](../docs/knowledge/cache-invalidation-strategy.md)
