# Docs

Kratis documentation for humans and coding agents. Product intent lives in [`PRD.md`](PRD.md). Task how-tos live in [`skills/`](skills/). Architecture lives in [`knowledge/`](knowledge/). Open work lives in [`plans/`](plans/).

Load the matching skill before you change code. See [`AGENTS.md`](../AGENTS.md) for the session rules.

## Start here

| Doc | Use it for |
|-----|------------|
| [PRD.md](PRD.md) | What Kratis is, what a user can do, architecture |
| [../README.md](../README.md) | Clone, run, test, deploy |

## Skills

Skills are agent how-tos. Each file has YAML frontmatter (`name`, `description`) so an agent can pick the right one.

| Skill | When |
|-------|------|
| [control-plane.md](skills/control-plane.md) | Java conventions, AOT, Liquibase |
| [control-plane-testing.md](skills/control-plane-testing.md) | Control-plane tests |
| [websocket-method.md](skills/websocket-method.md) | New JSON-RPC method |
| [rest-api-endpoint.md](skills/rest-api-endpoint.md) | New REST endpoint |
| [new-entity.md](skills/new-entity.md) | New JPA entity |
| [external-http-clients.md](skills/external-http-clients.md) | Declarative HTTP clients |
| [frontend-feature.md](skills/frontend-feature.md) | Web vertical slice |
| [web-frontend-testing.md](skills/web-frontend-testing.md) | Web tests |
| [adding-a-skill.md](skills/adding-a-skill.md) | Add a skill |
| [ste-writing-skill.md](skills/ste-writing-skill.md) | STE prose |

## Knowledge

Design and runtime reference. Do not copy wire shapes here; `protocol/` is the contract.

| Doc | Topic |
|-----|-------|
| [websocket-api.md](knowledge/websocket-api.md) | `/ws/client` and `/ws/env` |
| [cache-invalidation-strategy.md](knowledge/cache-invalidation-strategy.md) | AFTER_COMMIT fan-out and TanStack Query |
| [chat-interaction-design.md](knowledge/chat-interaction-design.md) | Chat persistence, replay, payload types |
| [sandbox-provisioning.md](knowledge/sandbox-provisioning.md) | Docker sandbox contract |
| [acp-lifecycle.md](knowledge/acp-lifecycle.md) | Connector as ACP client |
| [e2e-agent-orchestration.md](knowledge/e2e-agent-orchestration.md) | E2E sequence and host Docker hygiene |
| [repository-intelligence.md](knowledge/repository-intelligence.md) | Ingestion, graph, wiki |
| [publish-rebase-flow.md](knowledge/publish-rebase-flow.md) | Diff bases, squash, publish |
| [Kratis Architect Persona.md](knowledge/Kratis%20Architect%20Persona.md) | Planning-agent persona |

