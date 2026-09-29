# Getting started

From a running stack to a published pull request. Install Kratis first: [`deploy/README.md`](../deploy/README.md).

## 1. Create your account

Open the UI (`http://localhost:8080` by default), choose **Register**, enter a name, email, and password, then **Sign In**. Registration creates *{Your name}'s Team* plus a **Default Docker Provider** for sandboxed runs.

There is no seeded account and no email verification: anyone who can reach your instance can register. Keep it off the public internet, or put your own authentication in front of it.

## 2. Add a model provider

Kratis ships no models; bring your own keys. **Settings → Models → Add Provider** is a four-step wizard:

| Step | What you do |
|------|-------------|
| Provider | Anthropic, Azure OpenAI, AWS Bedrock, DeepSeek, Google GenAI, Groq, Mistral AI, Ollama, OpenAI, or Other (OpenAI-compatible) |
| Configure | Name, API key, and base URL where required. **Next** verifies the connection and discovers models. Ollama needs no key. |
| Models | Select the chat models agents may use |
| Defaults | Set the team **ingestion model** and **embedding model** |

The Defaults step is not optional. Ingestion refuses to start until the team has both.

Your provider key stays in the control plane. Kratis registers the models with LiteLLM and mints short-lived virtual keys per chat, per ingestion batch, and per sandbox execution, so a sandboxed agent never sees your provider key and spend is attributed per run (**Usage**).

## 3. Connect a repository

**Repos → Add Repository** walks Provider → Authenticate → Repositories → Details.

| Host | Credential | What to supply |
|------|-----------|----------------|
| Public repository | None | Git URL, display name, default branch |
| Git with SSH key | Kratis generates a 4096-bit RSA key pair; the private key never leaves the server | Add the displayed public key to the host as a deploy key with write access. No PR API: publish pushes a branch, then download the patch or open the PR manually |
| GitHub | GitHub App (recommended) or fine-grained PAT | App: Installation ID. PAT: Contents Read & write, Metadata Read-only, Pull requests Read & write. Add Administration Read & write to create new repositories |
| GitLab | Group or personal access token, or service account token | `api` + `write_repository`; numeric Group ID for group scope; Developer role in the group to create new projects; optional self-hosted URL |
| Bitbucket | App password, API token, or workspace access token | Repositories Read & write, Pull requests Read & write. Add Repositories Admin to create new repositories; optional workspace scope |
| Azure DevOps | PAT | Code Read & write. Add Code Read, write, & manage to create new repositories; organization required, project required for repository creation; server base URL for self-hosted |

The GitHub App choice only appears when `KRATIS_GITHUB_APP_ID`, `KRATIS_GITHUB_APP_NAME`, and `KRATIS_GITHUB_PRIVATE_KEY_PATH` are all set in e.g. the compose `.env` file. Otherwise GitHub falls back to PAT.

### GitHub App setup

Create your own personal GitHub app under GitHub → Settings → Developer settings → GitHub Apps:

1. Permissions: Contents Read & write, Pull requests Read & write, Metadata Read-only, and Administration Read & write. Administration is required only for Kratis to create new repositories; omit it when you publish only to existing repositories. No webhook needed.
2. Generate a private key (.pem); note the App ID and the App slug from the URL.
3. Set `KRATIS_GITHUB_APP_ID`, `KRATIS_GITHUB_APP_NAME`, and `KRATIS_GITHUB_PRIVATE_KEY_PATH` (PEM path inside the container), then restart the stack.
4. Install the App on the target organization or repositories. Creating new repositories requires an organization installation. In **Repos → Add Repository**, follow **Install GitHub App** and copy the Installation ID from the resulting GitHub URL.

A GitLab service account must also be invited to the group as Reporter or Developer, or every request returns `404 Group Not Found`. Creating projects in a group needs the Developer role.

**Details** asks for Repository Git URL, Display Name, and Default Branch. Leave **Start ingesting immediately after onboarding** checked.

### Ingestion

Kratis clones the repository, parses it into a code graph, then writes wiki pages and embeddings. Watch the badge go **Queued → Processing → Success** and open the wiki from the repository card. The repository page also carries live logs, ingestion history, and statistics (duration, cost, tokens, nodes, edges, generated wiki pages). Re-run any time with **Ingest Now**.

### Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `401 Unauthorized` | Token expired, revoked, or mis-pasted | Generate a fresh token and update the credential |
| `403` account blocked | Host anti-abuse flagged a bot or service account, or the billing tier rejects that token type | Use a PAT from a normal user account |
| `403` on push or PR creation | Token lacks a write or pull-request permission from the table above | Grant the permission and update the credential |
| `403 Resource not accessible by integration` on repository creation | GitHub App lacks Administration Read & write, or the installation is on a personal account | Add Administration Read & write to the App and approve the new permissions on the installation, or install the App on an organization |
| `403` on repository creation (GitLab, Bitbucket, Azure DevOps) | Token lacks the provider's create-repository permission | GitLab: `api` plus the Developer role in the group. Bitbucket: Repositories Admin. Azure DevOps: Code Read, write, & manage |
| `404 Group Not Found` | Missing `read_api`, service account not a group member, or a path used instead of the numeric Group ID | Add the scope, invite the service account as Reporter (Developer or higher to create projects), enter the numeric ID |
| SSH authentication fails | Public key not registered for that repository | Add the exact public key Kratis displayed as a deploy key on the repository |

## 4. Planning - Ask Kratis

**Ask Kratis** starts a planning chat grounded in your wiki and code graph. Pick a template — **Free-form**, **Plan & Grill**, **Investigate Error**, **Architecture Audit** — choose a model, and send.

The agent can search the web when the team has a Tavily API key (**Settings → Integrations**) — A free Tavily account lets Kratis check 
live docs instead of guessing: real API signatures, library options / upgrades, known issues behind your error messages, and upstream code paths.

The agent streams its answer and writes canvas documents alongside the chat:

- **SPEC** — an implementation brief tied to a repository, and the only canvas type you can launch.
- **DOCUMENT** — research and notes; not launchable.

Canvases render as read-only markdown. Iterate by asking the agent to revise; it patches the document in place.

## 5. Run an agent

On a SPEC tab, press **Run** and complete **Launch Execution**:

| Field | Options |
|-------|---------|
| Where should this task run? | `Docker (Default Docker Provider)` — a fresh sandbox per run |
| Which agent harness? | Aider, Claude Code, Codex, Gemini, Goose, Mistral, OpenCode, OpenHands, PI, Qwen |
| Which model? | Any chat model selected in step 2 |

Harnesses are installed inside the sandbox when the run starts. Sandboxes need outbound internet for packages and Git.

While it runs you get an **Activity Log** (reasoning, tool calls, commands, inline diffs), a **Changes** tab with the working-tree diff in unified or split view, and a terminal drawer with sandbox console output. Runs continue in the background, so you can leave and resume on another device.

## 6. Approve, steer, publish

**Permission Required** cards show the exact command the agent wants to run. Remembered rules are available under **Settings → Permissions**, where DENY beats ALLOW and anything unmatched asks a human. **Agent Question** cards take a form answer with **Submit**, **Decline**, or **Cancel**.

Steer mid-run from the bar at the bottom of the screen: send guidance, or comment on diff hunks and send those comments together as review feedback.

When the agent has finished, those changes can be reviewed and **Published**  or **Download Patch (.patch)**. If the base branch moved, you can ask the agent to rebase and retest.

## Optional

| Where | Purpose |
|-------|---------|
| Settings → Permissions | Pre-approve or block commands team-wide |
| Settings → Integrations | Tavily web-search key, repository credentials |
| Settings → Runtime | Environment providers, base-images |
| Settings → Team | Members, team name, additional teams |
| Usage | Token spend and activity per run |

Ports, secrets, LiteLLM master keys, GitHub App credentials, and the telemetry opt-out are environment variables: [`deploy/README.md`](../deploy/README.md). Capability detail and intent: [`PRD.md`](PRD.md).
