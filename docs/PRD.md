# Product Requirements Document

## Kratis: self-host friendly, multi-harness agent orchestration for software development

---

## 1. Overview

Kratis is a control plane for software-development agents. A team connects its repositories, gets a living wiki and code graph, plans work with an architect agent, then runs a pluggable ACP harness in a Docker sandbox. Humans stay in the loop: they approve the spec and privileged actions, steer a running agent, review the diff, and publish a branch or pull request.

Kratis does not lock the team to one vendor agent or one LLM. Harnesses are ACP processes. Models go through LiteLLM as session-scoped virtual keys. The production image is one native binary that serves the API and the web UI.

### Goals

1. Give a team a single place to plan, run, and review agent work across harnesses and models - across web and mobile.
2. Ground planning in the actual codebase (graph + wiki), not in guessed context - across multiple repos.
3. Keep credentials and provider keys off the sandbox. Deliver Git auth and a virtual LLM key over the connector WebSocket after handshake.
4. Keep a human in control of privileged actions and of what lands on the remote.
5. Stay self-host friendly: one image, schema migrations on startup, no required cloud control plane.

---

## 2. Capabilities

### Repository intelligence

Kratis periodically ingests connected Git repositories. It parses source with `codebase-memory-mcp` (Tree-sitter), loads nodes and edges into PostgreSQL, discovers dimensions, ranks files, detects architecture patterns, generates wiki pages, and writes pgvector embeddings.  Kratis doesn't rely on the LLM alone to write the wiki, but uses a structured graph and directed research to build ground-facts for both the wiki and the planning agent.

The planning agent uses Spring AI tools (`list_repositories`, `search_wiki`, `read_wiki_page`, `list_dimensions`, `get_dependencies`, and related tools).

### Ask and plan

The web UI is a mobile-capable SPA - topics are retained and the user can switch between devices. The planning agent streams text, telemetry, and writes Docs and Specs for human review. The user reviews the spec, then starts the harness.

### Execution environments

Runs execute in short-lived Docker sandboxes on the host (`ExecutionProviderType.DOCKER`). The control plane copies `kratis-connector` into the container and starts it as the sidecar. The sidecar speaks JSON-RPC 2.0 on `/ws/env`.

`SandboxProvider` is the extension point for later hosts. Only Docker is implemented.

### Pluggable ACP harnesses

Multiple ACP harnesses: Aider, Claude Code, Codex, Gemini CLI, Goose, Mistral Vibe, OpenCode, OpenHands, Pi, Qwen. The user selects harness and model per launch. Activity and Terminal-output are streamed from the harness to any device and continue in the background.

### Human in the loop

Agents pause for questions and for privileged operations. The UI shows live output and diffs while the run waits. Team members can approve, reject, answer, or steer. After the run, the user reviews the branch diff and can push or open a pull request.

---

## 3. Architecture

```
                 ┌──────────────────┐
                 │  Web frontend    │
                 └────────┬─────────┘
        REST + JSON-RPC 2.0 over /ws/client
                          |
                          ▼
┌────────────────────────────────────────────────────────┐     ┌─────────────┐
│  Control plane (Java 21, Spring Boot 4)                │────▶│  LiteLLM    │
│  Planning agent, wiki, ingestion, HITL, virtual keys   │     │  (models)   │
│  Production image embeds the Vite SPA                  │     └─────────────┘
└──────────────────────────┬─────────────────────────────┘
                  JSON-RPC 2.0 over /ws/env
                           ▼
                 ┌──────────────────────┐
                 │  Docker sandbox      │
                 │  kratis-connector    │
                 │          │ ACP       │
                 │          ▼           │
                 │  Isolated harness    │
                 └──────────────────────┘
```

- **Control plane:** Java 21, Spring Boot 4, virtual threads, PostgreSQL + pgvector, Liquibase. Production is a GraalVM native image that serves REST, WebSockets, and the embedded SPA.
- **Web UI:** Vite + React SPA, Tailwind CSS v4, shadcn/ui. TanStack Query holds server state. Zustand holds UI state and the WebSocket session. Notifications update the query cache after commit.
- **Connector:** one Go binary (`kratis-connector`) that runs inside the sandbox and supervises the ACP agent.
- **Protocol:** JSON-RPC 2.0. Canonical schemas live in `protocol/`. Java, Go, and TypeScript must match or the build fails.
- **WebSockets:** `/ws/client` (JWT `auth`) for the UI; `/ws/env` (environment token `env.register`) for the connector.

### Sandbox launch

```
Control plane
    │  WebSocket JSON-RPC 2.0 on /ws/env
    │  - env.registerGitAuth (PAT helper or ssh-agent)
    │  - LiteLLM virtual token + gateway URL
    │  - telemetry and HITL
    ▼
kratis-connector (inside the sandbox)
    │  1. Deploy Git auth in process memory
    │  2. env.checkout of the canvas repo, or git init
    │  3. Harness setup commands
    │  4. Spawn the ACP agent on stdio
    ├──► LiteLLM (inference, usage, spend)
    ├──► Public internet (packages, Git)
    └──► Control plane (telemetry, HITL)
```

Security properties:

- No Git or LLM credentials in the container environment at launch.
- Sensitive material arrives over the WebSocket after handshake.
- Git credentials stay in connector process memory (credential helper / ssh-agent).
- LLM keys are session-scoped virtual tokens. LiteLLM tracks spend without exposing provider keys.

---

## 4. Distribution

| Component | How it is consumed |
|-----------|-------------------|
| Control plane | `deploy/compose.yaml`: native `kratis` image, PostgreSQL (pgvector), LiteLLM. Schema migrations run on startup. |
| Updates | Pull a newer image. Liquibase applies schema changes. |

Module layout and commands live in the root [`README.md`](../README.md) and each module README. Roadmap: [`docs/plans/kratis-future-work.md`](plans/kratis-future-work.md).
