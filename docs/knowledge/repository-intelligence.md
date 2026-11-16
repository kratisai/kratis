# Repository Intelligence Platform Architecture

**Component:** Repository Intelligence & Deep Wiki Engine  
**Status:** Current Implementation  
**Last Updated:** 2026-11-15

---

## 1. Executive Summary

The Repository Intelligence Platform ingests polyglot source code repositories and outputs a multi-dimensional knowledge base. This knowledge base serves both human developers, via a hierarchical Deep Wiki, and autonomous Planning Agents, via Spring AI function tools.

The system strictly separates deterministic structural data from agent-researched semantic data. This separation prevents LLM hallucination and context-window exhaustion, ensuring that autonomous agents operate on a grounded, mathematically verified representation of the codebase.

---

## 2. Business Objectives

### 2.1 Context-Aware Agentic Planning
The Planning Agent must formulate comprehensive, safe implementation plans before code generation. By performing Change-May-Impact analysis, the agent anticipates downstream breakages and surfaces real-world implementation risks to the user upfront.

### 2.2 Automated Architectural Documentation
The system automatically generates and maintains high-level documentation that reflects the current state of the codebase. It groups raw files into understandable business modules and application tiers, generating accurate system-architecture diagrams without hallucinating connections.

### 2.3 Coding Pattern Adherence
The platform identifies the overarching system design and key coding patterns utilized in the repository. This ensures that proposed solutions respect established architectural guidelines rather than introducing anti-patterns.

---

## 3. Core Architecture

The system relies on a single PostgreSQL database leveraging four distinct data tiers. All data is isolated by a `team_id` for multi-tenancy and tracked by an atomic `batch_id` to prevent dirty reads during ingestion.

| Tier | Purpose | Underlying Technology | Key Tables (`ctx_` prefix) |
| --- | --- | --- | --- |
| **Graph** | Deterministic structural mapping. Tracks files, classes, methods, and explicit import/call relationships. | `codebase-memory` (Tree-Sitter ETL) + PostgreSQL | `ctx_nodes`, `ctx_edges` |
| **Document** | Human-and-agent readable architectural summaries. Defines boundaries, business domains, and rules. | Spring AI (LLM) + Hierarchical Markdown | `ctx_dimensions`, `ctx_node_dimensions`, `ctx_architecture_patterns`, `ctx_wiki_pages` |
| **Semantic** | Vague intent resolution. Allows agents to find codebase domains using natural language queries. | `pgvector` HNSW Similarity Search | `ctx_embeddings` |
| **Cross-Repository** | Boundary contracts between repositories and the team's relation graph. Deterministic extraction, LLM-assisted discovery, and model-led matching of surfaces. | `codebase-memory`, Syft, Spring AI (LLM) | `ctx_surfaces`, `ctx_surface_attributes`, `ctx_repo_synopses`, `ctx_repo_relations` |

---

## 4. The Ingestion Pipeline

The primary worker thread, [`IngestionWorker`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/IngestionWorker.java:44), executes the ingestion pipeline asynchronously. It uses phase-level transactions to ensure that if a failure occurs, all previously completed phases are retained in the database, and the batch is marked as `FAILED`. Phases that run an LLM loop deliberately execute outside a transaction so a model call never pins a JDBC connection.

### Pipeline Phases

1. **`CLONE`**: Clones the repository from the remote location using [`GitCloneService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/GitCloneService.java:21). Supports GitHub App tokens, SSH keys, and standard authentication. Cleans up existing directories to prevent disk leakage.
2. **`PREPARE_AST`**: Parses repository structure using [`CodebaseMemoryParserService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/parse/CodebaseMemoryParserService.java:1). Uses `codebase-memory` to rapidly parse the AST of 60+ languages and output a SQLite database, which is then ETL'd into PostgreSQL `ctx_nodes` and `ctx_edges`.
3. **`EXTRACT_SURFACES`**: Persists deterministic boundary surfaces via [`SurfaceExtractionService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/surfaces/SurfaceExtractionService.java:17). Runs before the parser database is removed.
4. **`PREPARE_DIMENSIONS`**: Discovers unified dimensions (Domains, Archetypes, Cross-Cutting) via LLM using [`DimensionDiscoveryService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/parse/DimensionDiscoveryService.java:33).
5. **`RESEARCH_GRAPH`**: Calculates subgraph PageRank for dimension nodes using [`SubgraphRankingService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/research/SubgraphRankingService.java:17).
6. **`ASSIST_SURFACES`**: Runs after graph ranking and enriches surfaces the deterministic adapters cannot fully describe, using the LLM-driven [`AssistedSurfaceService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/surfaces/AssistedSurfaceService.java:63). Executed outside a phase transaction.
7. **`RESEARCH_DIMENSIONS`**: Researches dimensions and generates synopses concurrently using virtual threads via [`DimensionResearchService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/research/DimensionResearchService.java:19).
8. **`RESEARCH_PATTERNS`**: Deduces system-wide architecture patterns from dimension hubs using [`PatternResearchService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/research/PatternResearchService.java:45).
9. **`GENERATE_WIKI`**: Generates repository Wiki documentation using an agentic ReAct loop via [`WikiGenerationService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/write/WikiGenerationService.java:49).
10. **`GENERATE_INDEX`**: Generates vector embeddings for wiki pages using [`SemanticIndexingService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/write/SemanticIndexingService.java:25).
11. **`SYNTHESIZE_SYNOPSIS`**: Generates the batch repository synopsis with [`SynopsisService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/relations/SynopsisService.java:51), informed by the batch's surfaces and research output, retaining citations to its inputs.
12. **`ACTIVATE_BATCH`**: Under the team-wide lock, [`BatchActivator`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/relations/BatchActivator.java:33) snapshots the team's active batches, runs model-led surface matching, writes relation replacement, and activates the new batch in one transaction.
13. **`SUCCESS`**: Terminal marker written by the worker once activation has committed. Repository API responses expose the latest batch's `ingestionStatus` directly.

---

## 5. Surface Discovery

Surfaces are the cross-repository boundary facts a repository presents: interfaces it serves or consumes, messages it sends or receives, persistent stores it shares, and infrastructure it provisions or runs on. Every observation is persisted as a [`CtxSurface`](control-plane/src/main/java/com/kratisai/controlplane/model/CtxSurface.java:10) carrying a role (`PROVIDES`, `REQUIRES`, `BIDIRECTIONAL`, `UNKNOWN`), a description, and detection provenance (`CMM`, `SYFT`, `MODEL`). Facts about a surface are [`CtxSurfaceAttribute`](control-plane/src/main/java/com/kratisai/controlplane/model/CtxSurfaceAttribute.java:21) rows typed as `ENDPOINT`, `IDENTIFIER`, `METHOD`, `TECHNOLOGY`, or `FILE`, each with its own provenance. All rows are team- and batch-scoped.

### 5.1 Deterministic Extraction (`EXTRACT_SURFACES`)

[`SurfaceExtractionService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/surfaces/SurfaceExtractionService.java:17) runs two adapters over the clone and persists their output through a single path.

- **Parser graph (`CMM`)**: [`ParserGraphReader`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/surfaces/ParserGraphReader.java:18) reads the parser SQLite database for the batch's project, plus the project's `::missed` rows, which become the unparsed-file diagnostic. [`GraphSurfaceTranslator`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/surfaces/GraphSurfaceTranslator.java:28) then translates a fixed table of node and edge types: specification `Route` nodes (OpenAPI, proto), `HANDLES` and `route_registration` calls as HTTP providers, outbound `HTTP_CALLS` as HTTP requires, `EMITS`/`LISTENS_ON` as bidirectional messaging, `Resource` and `BuildDefinition` nodes, and image dependencies. Observations are grouped into one surface per contract family, then normalised: attribute kinds are deduplicated by specificity, named endpoint placeholders collapse onto their named form, and repeated same-file anchors collapse to the file path. Test-anchored observations are skipped and unclassified edges are ignored.
- **SBOM (`SYFT`)**: [`SyftSurfaceAdapter`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/surfaces/SyftSurfaceAdapter.java:29) runs Syft against the clone and emits one `REQUIRES` surface per manifest plus a `PROVIDES` surface for the repository's own published package identity. Every component with a package URL becomes an `IDENTIFIER`; test-scope components are dropped.

### 5.2 LLM-Assisted Discovery (`ASSIST_SURFACES`)

Deterministic adapters cannot resolve runtime-composed targets, handler wiring, or publishing behaviour, so [`AssistedSurfaceService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/surfaces/AssistedSurfaceService.java:63) runs a bounded ReAct loop (25 turns) after graph ranking.

`SurfaceIndex` builds a size-capped index of non-test files from `ctx_nodes`, annotated with boundary signals (routes, channels, resources, images, configuration usage). The model reads, searches, and lists files with [`ReadFileTool`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/ReadFileTool.java:19), `search_files`, and `list_files`, and returns observations with an exact repository-relative file anchor for every fact. Prompt guidance keeps discovery focused on boundaries that cross repository, process, deployment, or resource limits and separates concerns explicitly:

- Infrastructure that provisions hosting, registries, brokers, queues, or managed stores is an infrastructure resource (`PROVIDES`), while application build and workload manifests are consumers of hosting (`REQUIRES`).
- When runtime messaging is mediated by an imported library or SDK, the dependency and the runtime contract are reported as two separate observations, the second `BIDIRECTIONAL`.

Deterministic validation rejects observations with unresolvable anchors, no description, only `FILE` facts, or test-only anchors, and feeds the error back to the model for a structured retry. Surface values are open-ended: unfamiliar technologies and classifications are preserved because matching validates the response contract, not a technology catalog.

---

## 6. Cross-Repository Matching & Relations

### 6.1 Repository Synopsis (`SYNTHESIZE_SYNOPSIS`)

[`SynopsisService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/relations/SynopsisService.java:51) generates one synopsis per batch into `ctx_repo_synopses`, describing purpose, interfaces, and deployment from the batch's surfaces and research output. Every synopsis retains citations to its inputs, exposed to matching as fragments (`f1`, `f2`, …). The synopsis supplies context for matching; it never writes relations itself.

### 6.2 Match Planning and Execution

[`BatchActivator`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/relations/BatchActivator.java:73) snapshots the subject batch and every other active batch in the team, then [`CrossRepoMatchingService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/relations/CrossRepoMatchingService.java:44) compares the subject against each peer.

- **Deterministic candidates first**: direct package-coordinate matches are computed before any model call and used as relation proposals as well as suppression keys.
- **One model call per repository pair**: subject and peer surfaces, attributes, and synopses are rendered with short per-call aliases (`A`/`B`, `s*`/`o*`, `a*`/`b*`). Aliases are resolved back to canonical `UUID`s before validation.
- **Bounded work**: the prompt budget is derived from the selected ingestion model's context window. A pair is split into surface groups only when it exceeds that budget, and the split is recorded. Peers with no eligible surfaces are skipped and reported; surfaces trimmed as unmatched SBOM dependencies are noted at `DEBUG`.
- **Concurrency**: peers run on virtual threads bounded by `kratis.ingestion.matching.max-concurrency`, with per-pair retry bounded by `kratis.ingestion.matching.max-retries`. Results are joined in deterministic peer order and repeated evidence is collapsed.
- **Correction pass**: when proposals fail with recoverable reasons, one repair pass is sent that lists the already-accepted matches, the rejected candidates with their alias selections and rejection reasons, and an instruction to repair every rejected candidate and additionally report any relationship missed on the first pass. Matches accepted in either pass are merged; proposals that remain invalid are discarded with a `WARN` carrying the reason.

### 6.3 Relation Natures and Direction

Validation is deterministic and checks the response contract, not a technology catalog: both surfaces must have been supplied, belong to their named repositories and active batches, come from different repositories, and their cited attributes and synopsis fragments must exist and belong to the supplied surfaces.

| Nature | Meaning | Direction |
| --- | --- | --- |
| `CALLS` | One repository serves a synchronous REST/RPC/GraphQL/HTTP interface the other invokes | `A_TO_B` / `B_TO_A` |
| `BUILDS_AGAINST` | One repository publishes a package the other declares as a direct dependency | `A_TO_B` / `B_TO_A` |
| `HOSTS` | An artifact or workload from one repository runs in, is stored in, or is provisioned by infrastructure declared in the other | `A_TO_B` / `B_TO_A`, always infrastructure → workload |
| `MESSAGES` | Both repositories send and/or receive runtime messages, events, or stream frames | `BIDIRECTIONAL` |
| `SHARES_STATE` | Both repositories save or load data from the same persistent backing store | `BIDIRECTIONAL` |
| `PEER` | Repositories collaborate around a shared domain concept with no technical runtime, build, or hosting nature | `BIDIRECTIONAL` |

Direction for the three non-directional natures is normalised to `BIDIRECTIONAL` regardless of what the model returned. For directional natures the model is guided to resolve direction from both the surface evidence and the repository synopses; when the surfaces cannot establish it, validation preserves `Direction.UNKNOWN` rather than rejecting the match.

Direction names the upstream side: `A_TO_B` means repository A serves, publishes, or hosts repository B, and `B_TO_A` is the mirror image. `HOSTS` therefore reads directly as "A hosts B", while `CALLS` and `BUILDS_AGAINST` name an action performed by the downstream repository, so an `A_TO_B` row reads "B calls A" or "B builds against A". Planning tools render the relation with the acting repository first.

### 6.4 Persistence and Re-ingestion

[`CtxRepoRelation`](control-plane/src/main/java/com/kratisai/controlplane/model/CtxRepoRelation.java:25) is the sole relation entity. Each row is one `(batch pair, nature)` with the repository pair ordered by SQL UUID ordering, a `CURRENT`/`SUPERSEDED` state, `CONFIRMED`/`SUGGESTED` confidence (a single `SUGGESTED` support downgrades the relation), and a `citations` JSON document whose `matches` array records every supporting surface pair with both surface `UUID`s, confidence, rationale, direction, and the cited attribute and synopsis `UUID`s.

Re-ingesting a repository supersedes only relations involving that repository; unrelated pairs are untouched. Superseded rows remain until the batch is deleted.

---

## 7. Dimension Discovery & Subgraph Ranking

The platform uses a hybrid approach to identify codebase structure: the LLM defines semantic boundaries, and the graph is used for efficient label propagation and localized ranking.

### 7.1 Unified LLM Dimension Identification
[`DimensionDiscoveryService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/parse/DimensionDiscoveryService.java:72) builds an annotated file tree (file paths combined with single-line semantic footprints, such as class inheritance or primary function signatures) and prompts the LLM to identify dimensions in three categories:
1. **Business Domains (`DOMAIN`)**: Core capabilities (e.g., "Booking Management").
2. **Technical Archetypes (`ARCHETYPE`)**: Primary technical layers (e.g., "Controllers", "Repositories").
3. **Cross-Cutting/Support (`CROSS_CUTTING`)**: Infrastructure, CI/CD, logging, testing.

For each dimension, the LLM returns a name and 3-5 candidate glob patterns (e.g., `**/booking/**`, `*Booking*.java`).

### 7.2 Deterministic Tagging & Graph Propagation
Instead of prompting the LLM to tag thousands of files individually, the system employs a two-step process:
1. **Fast Glob Pass**: The system applies the LLM-generated globs first, instantly and deterministically tagging the majority of files for each dimension.
2. **Graph Propagation**: For untagged files, the system uses the graph to find which dimensions they are most strongly connected to. Breadth-First Search (BFS) traversal is constrained to a maximum of 2-3 hops to prevent label bleeding to unrelated modules.

### 7.3 Subgraph Ranking
[`SubgraphRankingService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/research/SubgraphRankingService.java:17) extracts a subgraph for each identified dimension, containing only the files tagged with that dimension. It then runs PageRank exclusively on this subgraph, storing the `rankScore` in the [`CtxNodeDimension`](control-plane/src/main/java/com/kratisai/controlplane/model/CtxNodeDimension.java:8) join entity. This ensures that a global logging utility will not dominate the "Booking Management" hub list, as it was classified as "Cross-Cutting" and excluded from the business subgraph.

---

## 8. Dimension Research & Pattern Deduction

### 8.1 Dimension Research
[`DimensionResearchService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/research/DimensionResearchService.java:40) uses Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`) to concurrently research each dimension. A [`ResearchDimensionWorker`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/research/ResearchDimensionWorker.java:1) prompts the LLM to generate a rich synopsis (2-3 paragraphs) explaining the role, scope, and key patterns of that dimension, saving it to the [`CtxDimension`](control-plane/src/main/java/com/kratisai/controlplane/model/CtxDimension.java:23) entity.

### 8.2 Pattern Deduction
[`PatternResearchService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/research/PatternResearchService.java:71) analyzes `ARCHETYPE` dimensions and their top-ranked exemplar files. It uses an LLM with tool calling (specifically [`ReadFileTool`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/ReadFileTool.java:25)) to read the source code of these exemplar files and deduce system-wide architecture patterns (e.g., "Hexagonal Architecture in Booking", "CQRS in Identity"). The results are saved to the [`CtxArchitecturePattern`](control-plane/src/main/java/com/kratisai/controlplane/model/CtxArchitecturePattern.java:1) entity.

---

## 9. Wiki Generation & Semantic Indexing

### 9.1 Agentic Wiki Generation

[`WikiGenerationService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/write/WikiGenerationService.java:67) executes an agentic ReAct loop. Before starting the ReAct loop, the service uses the `scc` (Sloc, Cloc and Code) CLI tool to calculate repository complexity metrics (lines of code and file count). The system enforces a hard dependency on `scc` and will throw a fatal exception if the binary is missing or fails. These metrics are combined into a weighted composite score (60% lines of code, 40% file count) to dynamically determine the target wiki page count (ranging from 2 pages for simple codebases to 20 pages for highly complex repositories), guiding the LLM generation scope.

It is provided with pre-fetched high-level context (Architectural Patterns and Code Dimensions) and has access to the following tools:

- [`ReadFileTool`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/ReadFileTool.java:25): Reads source code.
- [`ReadWikiPageTool`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/ReadWikiPageTool.java:22): Reads existing wiki pages for context.
- [`ListWikiPagesTool`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/ListWikiPagesTool.java:13): Lists the wiki pages already written in the batch, in hierarchy order, so the model can discover valid `parentPageSlug` values.
- [`WriteWikiPageTool`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/WriteWikiPageTool.java:39): Creates or updates wiki pages with a unique `pageSlug`, title, and markdown content. Recoverable failures (blank fields, invalid Mermaid, unknown `parentPageSlug`) raise [`KratisToolException`](control-plane/src/main/java/com/kratisai/controlplane/agentloop/KratisToolException.java:1); the ReAct loop catches it, logs it, returns the error (including the available page slugs) to the model, and continues so the agent can fix and retry.

The LLM iteratively builds a hierarchical wiki, constrained by a maximum turn limit (circuit breaker) to prevent infinite loops. It must write the single top-level page (no parent) before any child page; child pages reference an already-written parent slug. After the loop, generation fails if no pages were produced, and warns if the wiki has more than one top-level page.

### 9.2 Semantic Indexing
[`SemanticIndexingService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/write/SemanticIndexingService.java:44) chunks the generated wiki pages using `TokenTextSplitter` (default ~800 tokens per chunk). It then generates vector embeddings using the team's configured embedding model and saves them to the [`CtxEmbedding`](control-plane/src/main/java/com/kratisai/controlplane/model/CtxEmbedding.java:1) table, enabling HNSW similarity search for vague intent resolution.

---

## 10. State Management, Concurrency & the Team-Wide Lock

- **Deterministic Progression**: The core pipeline is strictly linear, with phase-level transactions ensuring partial progress is saved.
- **Concurrent Execution**: During the `RESEARCH_DIMENSIONS` phase, Java 21 Virtual Threads process every dimension's LLM prompt in parallel, bounded by a configurable semaphore (`ingestionProperties.getDimensionResearch().getMaxConcurrency()`), without deadlocking over shared state.
- **Concurrent Peer Matching**: During `ACTIVATE_BATCH`, one virtual task per peer repository evaluates one `(subject, peer)` pair, bounded by `kratis.ingestion.matching.max-concurrency`, with per-pair retries bounded by `kratis.ingestion.matching.max-retries`.
- **Team-Wide Activation Lock**: Multiple repositories in the same team may ingest concurrently, so activation is serialised per team by [`TeamActivationLock`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/relations/TeamActivationLock.java:15). It first waits in JVM memory on a per-team `ReentrantLock`, then takes a PostgreSQL session-level advisory lock (`pg_advisory_lock`) on a dedicated connection. Waiting in memory first bounds holding to one JDBC connection per team, preventing pool starvation when several ingestions race for the same team. The lock is held across snapshot, matching, and relation replacement, so the active-batch snapshot cannot drift mid-run and two activations cannot interleave their supersede/replace writes. Model calls run outside any transaction while the lock is held.
- **Queue Recovery**: [`IngestionQueueManager`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/IngestionQueueManager.java:29) listens for `ApplicationReadyEvent`. On startup, it clears data for orphaned `PROCESSING` batches, resets them to `QUEUED`, and resubmits them to the async worker, ensuring resilience against abrupt shutdowns.

---

## 11. Planning Agent Tools

The hydrated Context Layer is exposed to the planning agent as Spring AI `@Tool`
function beans, invoked by the ReAct loop. Agents never write SQL; every tool is
scoped to the requesting team.

| Tool class | Tools |
| --- | --- |
| `RepositoryTool` | `list_repositories`, `read_remote_file`, `get_dependencies`, `get_related_repos`, `search_files` |
| `DimensionTool` | `list_dimensions`, `get_dimension`, `list_architecture_patterns` |
| `WikiTool` | `list_wiki_pages`, `read_wiki_page`, `search_wiki` |
| `WebSearchTool` | `web_search` |
| `ScratchpadTool` | `save_to_scratchpad`, `delete_from_scratchpad` |
| `CanvasTool` | `list_canvas_documents`, `retrieve_from_canvas`, `write_canvas`, `patch_canvas` |

`list_repositories` returns each repository's ingestion status, last commit, a compact synopsis, and rendered cross-repository relation lines. `get_related_repos` groups the current repository's relations by peer repository, repeating the peer's synopsis and ingestion status and reporting each relation's file anchors, a readable relation sentence naming the acting repository, and the rationale. `get_dependencies` traverses the Graph tier to trace the blast radius of a proposed
change within the repository and appends any matching cross-repository surface links.

---

## 12. Domain Model Summary

| Entity | Description |
| --- | --- |
| `CtxNode` | Represents a file, class, or method in the codebase. |
| `CtxEdge` | Represents a relationship between nodes (e.g., `IMPORTS`, `CALLS`, `EXTENDS`). |
| `CtxDimension` | Represents a discovered dimension (`DOMAIN`, `ARCHETYPE`, `CROSS_CUTTING`) with a name, synopsis, and glob patterns. |
| `CtxNodeDimension` | Join entity mapping a `CtxNode` to a `CtxDimension`, including a `rankScore` from subgraph PageRank. |
| `CtxArchitecturePattern` | Represents a deduced system-wide architecture pattern with a description and exemplar file paths. |
| `CtxWikiPage` | Represents a generated wiki page with a `pageSlug`, title, content, and optional parent page. |
| `CtxSurface` | Represents a repository boundary interface, dependency, or deployment fact. |
| `CtxSurfaceAttribute` | Stores open-ended facts (`ENDPOINT`, `IDENTIFIER`, `METHOD`, `TECHNOLOGY`, `FILE`) and provenance for a `CtxSurface`. |
| `CtxRepoSynopsis` | Stores the batch-level repository synopsis and its supporting citations used during cross-repository matching and planning. |
| `CtxRepoRelation` | Team-level relation between two repositories' active batches for one `Nature` (`CALLS`, `MESSAGES`, `BUILDS_AGAINST`, `HOSTS`, `SHARES_STATE`, `PEER`) with embedded surface-pair evidence. |
| `CtxEmbedding` | Represents a vector embedding of a wiki page chunk for semantic search. |
